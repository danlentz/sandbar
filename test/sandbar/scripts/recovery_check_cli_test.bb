#!/usr/bin/env bb
(ns sandbar.scripts.recovery-check-cli-test
  "Black-box CLI requests to an ephemeral loopback stub, never live Sandbar.
   Run with bb test/sandbar/scripts/recovery_check_cli_test.bb."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [org.httpkit.server :as server])
  (:import [java.security MessageDigest]))

(def repo (str (fs/canonicalize (nth (iterate fs/parent *file*) 4))))
(def wire (atom []))
(def response (atom {}))
(def running (atom nil))
(def receipts (atom []))
(def digest (apply str (repeat 64 "a")))
(def checked {:status "checked" :scope "snapshot-comparison"
              :state "same-store-same-basis" :file-count 2 :audience "public"
              :held-count 1 :manifest-sha256 digest :import-approved? false})
(def arguments ["/chosen export/with spaces" "--project" ":memory.projects/test"
                "--destination" "public"])

(defn envelope
  ([data] (envelope data false))
  ([data structured?]
   (json/generate-string
     {:jsonrpc "2.0" :id 1
      :result (if structured? {:structuredContent data :content []}
                {:content [{:type "text" :text (json/generate-string data)}]})})))

(defn sha [f]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                         (java.nio.file.Files/readAllBytes (fs/path f))))))
(defn tree [root]
  (into (sorted-map)
    (for [f (fs/glob root "**" {:hidden true})]
      [(str (fs/relativize root f)) (if (fs/regular-file? f) (sha f) :directory)])))

(defn run-cli
  [{:keys [args body status direct? token token-file? config-port? backend-port? raw-config
           extra-env]
    :or {args arguments status 200 token "operator:TEST-ONLY-SECRET"}}]
  (let [root (fs/create-temp-dir {:prefix "recovery-cli-"})]
    (fs/set-posix-file-permissions root "rwx------")
    (doseq [rel ["bin/sandbar" "bin/recovery-check.bb"]]
      (let [dest (fs/path root "backend" rel)]
        (fs/create-dirs (fs/parent dest))
        (fs/copy (fs/path repo rel) dest)))
    (let [client (str (fs/path root "client"))
          config (fs/path client ".sandbar/config.edn")
          port (server/server-port @running)]
      (when (or config-port? raw-config)
        (fs/create-dirs (fs/parent config))
        (spit (str config) (or raw-config (pr-str {:port port}))))
      (when backend-port?
        (let [cfg (fs/path root "backend/config/config.edn")]
          (fs/create-dirs (fs/parent cfg)) (spit (str cfg) (pr-str {:port port}))))
      (when token-file?
        (fs/create-dirs (fs/path client ".sandbar"))
        (spit (str (fs/path client ".sandbar/token")) "operator:TEST-FILE-SECRET\n"))
      (let [env (merge {"PATH" (System/getenv "PATH") "HOME" (str root)
                       "SANDBAR_HOME" (str (fs/path root "backend"))
                       "SANDBAR_CLIENT_DIR" client}
                      (when-not (or config-port? backend-port? raw-config)
                        {"SANDBAR_PORT" (str port)})
                      (when token {"SANDBAR_TOKEN" token})
                      extra-env)
            before (tree root)
            _ (reset! wire [])
            _ (reset! response {:status status
                                :headers {"content-type" "application/json"
                                          "location" "http://127.0.0.1:1/SECRET-REDIRECT"}
                                :body (or body (envelope checked))})
            command (if direct? ["bb" (str (fs/path root "backend/bin/recovery-check.bb"))]
                        ["bash" (str (fs/path root "backend/bin/sandbar")) "recovery-check"])
            result (apply p/shell {:dir (str root) :env env :out :string :err :string
                                   :continue true :shutdown nil}
                          (concat command args))
            record (assoc (select-keys result [:exit :out :err])
                          :requests @wire :unchanged? (= before (tree root)))]
        (is (:unchanged? record) "CLI changes no client, export or config files")
        (is (not (str/includes? (str (:out record) (:err record)) "SECRET"))
            "No credential or untrusted private prose leaves the CLI")
        (swap! receipts conj record)
        record))))

(deftest five-states-use-distinct-success-and-review-exits
  (doseq [direct? [false true]
          state ["same-store-same-basis" "store-advanced" "store-behind-export"
                 "different-store" "origin-unverified"]]
    (let [r (run-cli {:direct? direct? :body (envelope (assoc checked :state state))})
          data (edn/read-string (:out r))
          request (:request (first (:requests r)))]
      (is (= (if (= "same-store-same-basis" state) 0 3) (:exit r)))
      (is (= (keyword state) (:state data)))
      (is (false? (:import-approved? data)))
      (is (= "" (:err r)))
      (is (= 1 (count (:requests r))))
      (is (= "sandbar_project_recovery-check" (get-in request [:params :name])))
      (is (= {:from "/chosen export/with spaces" :project ":memory.projects/test"
              :destination "public"} (get-in request [:params :arguments])))
      (is (= :post (:method (first (:requests r)))))
      (is (= "/mcp" (:uri (first (:requests r))))))))

(deftest structured-content-and-pin-and-private-audience
  (let [data (assoc checked :audience "private" :held-count 0
                    :private-path "SECRET" :database-id "SECRET")
        r (run-cli {:args (concat arguments ["--expect-manifest" (str/upper-case digest)])
                    :body (json/generate-string
                            {:jsonrpc "2.0" :id 1 :result
                             {:structuredContent data
                              :content [{:type "text" :text "SECRET stale text"}]}})})]
    (is (= 0 (:exit r)))
    (is (= :private (:audience (edn/read-string (:out r)))))
    (is (= digest (get-in r [:requests 0 :request :params :arguments :expect-manifest])))
    (is (= (set (keys checked)) (set (keys (edn/read-string (:out r))))))))

(deftest error-envelopes-and-malformed-successes-never-pass
  (doseq [body (concat
               [(json/generate-string {:jsonrpc "2.0" :id 1 :error {:message "SECRET"}})
                (json/generate-string {:jsonrpc "2.0" :id 1 :error nil :result {:structuredContent checked}})
                (json/generate-string {:jsonrpc "2.0" :id 1 :error false :result {:structuredContent checked}})
                (json/generate-string {:jsonrpc "2.0" :id 1
                                       :result {:isError true :structuredContent checked}})
                (json/generate-string {:jsonrpc "2.0" :id 1
                                       :result {:isError "false" :structuredContent checked}})
                (json/generate-string {:jsonrpc "2.0" :id 1
                                       :result {:structuredContent nil
                                                :content [{:type "text" :text (json/generate-string checked)}]}})
                "SECRET not JSON" "{}"
                (json/generate-string {:jsonrpc "2.0" :id 2 :result {:structuredContent checked}})]
               (map #(envelope % true)
                 [(dissoc checked :state) (assoc checked :state "eligible")
                  (assoc checked :import-approved? true) (dissoc checked :import-approved?)
                  (assoc checked :status "complete") (assoc checked :file-count -1)
                  (assoc checked :held-count "0") (assoc checked :manifest-sha256 "SECRET")
                  (assoc checked :scope "full-backup") (assoc checked :isError true)]))]
    (let [r (run-cli {:body body})]
      (is (= 1 (:exit r)))
      (is (= "" (:out r)))
      (is (= 1 (count (:requests r)))))))

(deftest http-error-and-redirect-and-pin-mismatch
  (doseq [status [401 500 302]]
    (let [r (run-cli {:status status})]
      (is (= 1 (:exit r))) (is (= "" (:out r)))
      (is (= 1 (count (:requests r))) "Redirect not followed")))
  (let [r (run-cli {:args (concat arguments ["--expect-manifest" (apply str (repeat 64 "b"))])})]
    (is (= 1 (:exit r))) (is (= "" (:out r)))))

(deftest arguments-help-and-credentials-make-no-request
  (doseq [args [[] ["/somewhere"] ["relative" "--project" ":p/p" "--destination" "p"]
               (concat arguments ["--execute"])
               (concat arguments ["--project" ":p/duplicate"])
               (concat arguments ["--expect-manifest" "not-a-hash"])
               (concat arguments ["--destination"])
               ["--help" "--execute"]]]
    (let [r (run-cli {:args args})]
      (is (= 2 (:exit r))) (is (empty? (:requests r)))))
  (doseq [direct? [false true]]
    (let [r (run-cli {:args ["--help"] :direct? direct? :token nil})]
      (is (= 0 (:exit r)))
      (is (str/includes? (:out r) "No import is approved."))
      (is (empty? (:requests r)))))
  (doseq [token [nil "invalid" "operator:a\nb"]]
    (let [r (run-cli {:token token})]
      (is (= 2 (:exit r))) (is (empty? (:requests r))))))

(deftest existing-config-and-credential-file-are-read-without-effects
  (doseq [options [{:token nil :token-file? true :config-port? true}
                  {:backend-port? true}
                  {:config-port? true :token-file? true}
                  {:raw-config "{} {}"}
                  {:raw-config "{:port \"bad\"}"}
                  {:extra-env {"SANDBAR_PORT" "70000"}}]]
    (let [r (run-cli options)
          invalid? (or (:raw-config options) (:extra-env options))]
      (is (= (if invalid? 2 0) (:exit r)))
      (is (= (if invalid? 0 1) (count (:requests r))))
      (when-not invalid?
        (is (= (if (nil? (:token options)) ; absent key means default env token
                 (if (contains? options :token) "Bearer operator:TEST-FILE-SECRET"
                   "Bearer operator:TEST-ONLY-SECRET")
                 "Bearer operator:TEST-ONLY-SECRET")
               (:authorization (first (:requests r)))))))))

(deftest stopped-endpoint-is-a-transport-failure
  ;; The port belonged to our own temporary stub; never choose a fixed port
  ;; that might name live Sandbar.
  (let [temporary (server/run-server (fn [_] {:status 200 :body ""})
                    {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port (server/server-port temporary)]
    @(server/server-stop! temporary {:timeout 100})
    (let [r (run-cli {:extra-env {"SANDBAR_PORT" (str port)}})]
      (is (= 1 (:exit r)))
      (is (= "recovery-check: transport\n" (:err r)))
      (is (= "" (:out r)))
      (is (empty? (:requests r))))))

(deftest unverified-reason-is-finite-and-never-untrusted-prose
  (doseq [[state reason expected] [["origin-unverified" "audit-missing" :audit-missing]
                                  ["origin-unverified" "manifest-hash-mismatch" :manifest-hash-mismatch]
                                  ["origin-unverified" "SECRET-untrusted" nil]
                                  ["same-store-same-basis" "audit-missing" nil]]]
    (let [r (run-cli {:body (envelope (assoc checked :state state :reason reason))})]
      (is (= (if (= "origin-unverified" state) 3 0) (:exit r)))
      (is (= expected (:reason (edn/read-string (:out r))))))))

(defn -main []
  (reset! running
    (server/run-server
      (fn [r]
        (swap! wire conj {:method (:request-method r) :uri (:uri r)
                          :authorization (get-in r [:headers "authorization"])
                          :request (json/parse-string (slurp (:body r)) true)})
        @response)
      {:ip "127.0.0.1" :port 0 :legacy-return-value? false}))
  (try
    (let [summary (t/run-tests 'sandbar.scripts.recovery-check-cli-test)]
      (when-let [report (first *command-line-args*)]
        ;; Fixture credentials are synthetic; avoid retaining even those values.
        (spit report (pr-str {:summary summary
                             :receipts (mapv #(update % :requests
                                                (fn [rs] (mapv (fn [r] (dissoc r :authorization)) rs)))
                                             @receipts)})))
      (if (zero? (+ (:fail summary) (:error summary))) 0 1))
    (finally @(server/server-stop! @running {:timeout 100}))))

(let [exit (-main)] (flush) (binding [*out* *err*] (flush)) (System/exit exit))
