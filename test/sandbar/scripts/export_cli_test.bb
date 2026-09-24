#!/usr/bin/env bb
;; Offline tests for the `sandbar export` wrapper (bin/sandbar, W1.H first
;; build CLI slice, 2026-09-21).  The real wrapper runs against a FAKE curl
;; on PATH that captures the request body and answers a canned response per
;; scenario; a PID file stands in for the running server and SANDBAR_TOKEN for
;; the credential.  No network, no JVM, no live server, nothing under the
;; repository is created or modified.
;;
;;   bb test/sandbar/scripts/export_cli_test.bb
(ns sandbar.scripts.export-cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def repo (str (fs/canonicalize (fs/parent (fs/parent (fs/parent (fs/parent *file*)))))))
(def wrapper (str (fs/path repo "bin" "sandbar")))

(def fake-curl
  "A curl stand-in: records the -d body, honours -o and -w '%{http_code}'."
  (str "#!/usr/bin/env bash\n"
       "out=\"\"; data=\"\"\n"
       "while [ $# -gt 0 ]; do\n"
       "  case \"$1\" in\n"
       "    -o) out=\"$2\"; shift 2 ;;\n"
       "    -d) data=\"$2\"; shift 2 ;;\n"
       "    -w|-H|-X|--max-time) shift 2 ;;\n"
       "    *) shift ;;\n"
       "  esac\n"
       "done\n"
       "printf '%s' \"$data\" > \"$FAKE_CURL_CAPTURE\"\n"
       "if [ -n \"${FAKE_CURL_EXIT:-}\" ] && [ \"$FAKE_CURL_EXIT\" != \"0\" ]; then exit \"$FAKE_CURL_EXIT\"; fi\n"
       "printf '%s' \"$FAKE_CURL_BODY\" > \"$out\"\n"
       "printf '%s' \"${FAKE_CURL_HTTP:-200}\"\n"))

(defn tool-result
  "A JSON-RPC envelope carrying an MCP tool result whose text is `payload`."
  ([payload] (tool-result payload false))
  ([payload error?]
   (json/generate-string
     {:jsonrpc "2.0" :id 1
      :result (cond-> {:content [{:type "text" :text (json/generate-string payload)}]}
                error? (assoc :isError true))})))

(def preview-payload
  {:status "preview" :plan-token "tok-preview-123" :basis 4242 :included-count 3
   :held-count 2 :exported-count 0 :complete? false :audit-ref "aud-1"})

(defn run-export
  "Run `bin/sandbar export <args>` against the fake transport.  Returns
   {:exit :out :err :request :dir-exists?}."
  [{:keys [args body http curl-exit running? token]
    :or {http "200" running? true token "fake-token"}}]
  (let [root    (str (fs/create-temp-dir {:prefix "export-cli-"}))
        client  (fs/create-dirs (fs/path root "client" ".sandbar"))
        fakebin (fs/create-dirs (fs/path root "fakebin"))
        capture (str (fs/path root "request.json"))
        dir     (str (fs/path root "staging" "child-one"))
        curl    (fs/path fakebin "curl")]
    (spit (str curl) fake-curl)
    (fs/set-posix-file-permissions curl "rwxr-xr-x")
    (when running?
      (spit (str (fs/path client "sandbar.pid")) (str (.pid (java.lang.ProcessHandle/current)) "\n")))
    (let [env {"PATH" (str fakebin ":" (System/getenv "PATH"))
               "SANDBAR_HOME" repo
               "SANDBAR_CLIENT_DIR" (str (fs/path root "client"))
               "SANDBAR_PORT" "65010"
               ;; An empty value overrides any token in the inherited
               ;; environment (the wrapper tests -n, then the token file).
               "SANDBAR_TOKEN" (or token "")
               "FAKE_CURL_CAPTURE" capture
               "FAKE_CURL_BODY" (or body "")
               "FAKE_CURL_HTTP" http
               "FAKE_CURL_EXIT" (str (or curl-exit 0))}
          full-args (mapv #(str/replace % "<DIR>" dir) args)
          r (apply p/shell {:out :string :err :string :continue true :extra-env env}
                   "bash" wrapper "export" full-args)]
      {:exit (:exit r) :out (:out r) :err (:err r)
       :request (when (fs/exists? capture) (json/parse-string (slurp capture) true))
       :dir dir :dir-exists? (fs/exists? dir)})))

(defn args-of [r] (get-in r [:request :params :arguments]))

;; ---- previews -------------------------------------------------------------------------
(deftest preview-is-the-default-and-writes-nothing
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result preview-payload)})]
    (is (= 0 (:exit r)) (:err r))
    (is (= "sandbar_project_export" (get-in r [:request :params :name])))
    (is (= "tools/call" (get-in r [:request :method])))
    (is (= (:dir r) (:to (args-of r))) "to is <dir> unchanged — no /memory suffix")
    (is (true? (:dry-run (args-of r))) "preview sends dry-run true as a JSON boolean")
    (is (= ":memory.projects/pub" (:project (args-of r))))
    (is (= "public" (:destination (args-of r))))
    (is (not (contains? (args-of r) :expect-plan)))
    (is (not (contains? (args-of r) :provenance)))
    (is (false? (:dir-exists? r)) "the wrapper never creates the destination")
    (is (str/includes? (:out r) "status preview"))
    (is (str/includes? (:out r) "plan-token: tok-preview-123") "the preview token is preserved verbatim")
    (is (str/includes? (:out r) "included: 3"))
    (is (str/includes? (:out r) "held (see the private audit): 2"))
    (is (str/includes? (:out r) "a private audit was written") "the preview says the audit exists")
    (is (str/includes? (:out r) "nothing was written to the staging destination"))
    (is (not (str/includes? (:out r) "complete —")) "a preview never prints an export-complete count")))

(defn structured-result
  "A JSON-RPC envelope whose MCP result carries only structuredContent."
  ([payload] (structured-result payload false))
  ([payload error?]
   (json/generate-string
     {:jsonrpc "2.0" :id 1
      :result (cond-> {:content [] :structuredContent payload}
                error? (assoc :isError true))})))

(deftest structured-content-is-decoded-and-preferred
  (testing "a preview delivered as structuredContent only"
    (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                         :body (structured-result preview-payload)})]
      (is (= 0 (:exit r)) (:err r))
      (is (str/includes? (:out r) "plan-token: tok-preview-123"))))
  (testing "structuredContent wins over a stale text item"
    (let [body (json/generate-string
                 {:jsonrpc "2.0" :id 1
                  :result {:content [{:type "text" :text (json/generate-string (assoc preview-payload :plan-token "text-token"))}]
                           :structuredContent (assoc preview-payload :plan-token "structured-token")}})
          r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"] :body body})]
      (is (= 0 (:exit r)) (:err r))
      (is (str/includes? (:out r) "plan-token: structured-token"))))
  (testing "a refusal delivered as structuredContent keeps its reason"
    (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                         :body (structured-result {:message "Guarded export refused; inspect the operator configuration or private audit."
                                                   :details {:reason "unauthorized-destination"}} true)})]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "refused (unauthorized-destination)"))))
  (testing "an execute answered incomplete as structuredContent still fails"
    (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"
                                "--execute" "--expect-plan" "tok-preview-123"]
                         :body (structured-result {:status "incomplete" :complete? false :exported-count 2 :reason "prewrite-or-audit-failed"})})]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "NOT complete")))))

(deftest preview-with-provenance-passes-the-flag-and-reports-honestly
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public" "--provenance"]
                       :body (tool-result (assoc preview-payload :provenance-recorded? false))})]
    (is (= 0 (:exit r)) (:err r))
    (is (true? (:provenance (args-of r))))
    (is (str/includes? (:out r) "provenance recorded: false"))))

(deftest preview-that-comes-back-without-a-token-fails
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result (dissoc preview-payload :plan-token))})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "expected a preview with a plan token"))))

;; ---- execution ------------------------------------------------------------------------
(deftest execute-sends-dry-run-false-with-the-token-and-succeeds-only-on-complete
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"
                              "--execute" "--expect-plan" "tok-preview-123"]
                       :body (tool-result {:status "complete" :complete? true :plan-token "tok-preview-123"
                                           :basis 4242 :included-count 3 :held-count 2 :exported-count 3
                                           :audit-ref "aud-2" :provenance-recorded? false})})]
    (is (= 0 (:exit r)) (:err r))
    (is (false? (:dry-run (args-of r))) "execute sends dry-run false as a JSON boolean")
    (is (= "tok-preview-123" (:expect-plan (args-of r))))
    (is (str/includes? (:out r) "status complete"))
    (is (str/includes? (:out r) "complete — 3 documents written"))
    (is (false? (:dir-exists? r)) "the server, not the wrapper, creates the child")))

(deftest execute-incomplete-is-a-failure-and-never-claims-completion
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"
                              "--execute" "--expect-plan" "tok-preview-123"]
                       :body (tool-result {:status "incomplete" :complete? false :plan-token "tok-preview-123"
                                           :basis 4242 :included-count 3 :held-count 2 :exported-count 1
                                           :audit-ref "aud-3" :reason "write-or-audit-failed"})})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "NOT complete"))
    (is (str/includes? (:out r) "exported: 1") "the partial count is shown as a count, not as completion")
    (is (str/includes? (:out r) "reason: write-or-audit-failed"))
    (is (not (str/includes? (:out r) "complete —")))))

(deftest execute-answered-with-a-preview-is-a-failure
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"
                              "--execute" "--expect-plan" "tok-preview-123"]
                       :body (tool-result preview-payload)})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "NOT complete (status preview"))))

(deftest complete-status-without-the-complete-flag-is-a-failure
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"
                              "--execute" "--expect-plan" "tok-preview-123"]
                       :body (tool-result {:status "complete" :complete? false :exported-count 3})})]
    (is (= 1 (:exit r)))
    (is (not (str/includes? (:out r) "complete —")))))

;; ---- error envelopes decoded before data ---------------------------------------------
(deftest mcp-refusal-is-reported-with-its-reason-and-no-counts
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result {:message "Guarded export refused; inspect the operator configuration or private audit."
                                           :details {:reason "fresh-staging-child-required"}}
                                          true)})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "refused (fresh-staging-child-required)"))
    (is (not (str/includes? (:out r) "status")) "no data field is printed for a refusal")))

(deftest jsonrpc-error-object-is-decoded-before-data
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (json/generate-string {:jsonrpc "2.0" :id 1 :error {:code -32603 :message "Internal error"}})})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "JSON-RPC error -32603"))))

(deftest http-failure-reads-no-data
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result preview-payload) :http "500"})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "HTTP 500"))
    (is (not (str/includes? (:out r) "plan-token")))))

(deftest non-json-body-reads-no-data
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body "<html>not json</html>"})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "not JSON"))))

(deftest transport-failure-concludes-nothing
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result preview-payload) :curl-exit 7})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "transport failure (curl exit 7)"))))

(deftest result-without-decodable-tool-text-fails
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (json/generate-string {:jsonrpc "2.0" :id 1 :result {:content [{:type "text" :text "not json"}]}})})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "could not be decoded"))))

;; ---- argument and precondition checks (no request is sent) ------------------------
(deftest argument-errors-send-no-request
  (doseq [[label args] [["missing project" ["<DIR>" "--destination" "public"]]
                        ["missing destination" ["<DIR>" "--project" ":memory.projects/pub"]]
                        ["missing dir" ["--project" ":memory.projects/pub" "--destination" "public"]]
                        ["relative dir" ["relative/child" "--project" ":memory.projects/pub" "--destination" "public"]]
                        ["execute without token" ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public" "--execute"]]
                        ["token without execute" ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public" "--expect-plan" "t"]]
                        ["canonical removed" ["--canonical"]]
                        ["unknown option" ["<DIR>" "--project" "p" "--destination" "d" "--bogus"]]]]
    (let [r (run-export {:args args :body (tool-result preview-payload)})]
      (is (= 2 (:exit r)) label)
      (is (nil? (:request r)) (str label ": no request"))
      (is (false? (:dir-exists? r)) label))))

(deftest not-running-and-no-token-are-reported-before-any-request
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result preview-payload) :running? false})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "not running"))
    (is (nil? (:request r))))
  (let [r (run-export {:args ["<DIR>" "--project" ":memory.projects/pub" "--destination" "public"]
                       :body (tool-result preview-payload) :token nil})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "no MCP token"))
    (is (nil? (:request r)))))

(let [{:keys [fail error]} (run-tests 'sandbar.scripts.export-cli-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
