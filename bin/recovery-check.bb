#!/usr/bin/env bb
#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns sandbar.scripts.recovery-check
  "Read-only MCP origin comparison. Never creates client state or an export."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def usage
  (str "usage: sandbar recovery-check <dir> --project <ident> --destination <name>\n"
       "                              [--expect-manifest <sha256>]\n"
       "Read-only comparison with the configured private export audit and current store.\n"
       "No import is approved. Stop writers separately before any attended recovery.\n"
       "Exit: 0 same store/basis; 3 review required; 2 arguments/setup; 1 request failure.\n"
       "Review includes unverified origin; exit 3 does not imply the store moved.\n"
       "Uses SANDBAR_TOKEN or the existing client .sandbar/token. Port: SANDBAR_PORT,\n"
       "client .sandbar/config.edn, backend config/config.edn, then 8389. No mkdir.\n"))

(def states
  #{"same-store-same-basis" "store-advanced" "store-behind-export"
    "different-store" "origin-unverified"})

(def unverified-reasons
  #{"audit-ref-malformed" "audit-symlink" "audit-missing" "audit-not-private"
    "audit-malformed" "audit-phase-mismatch" "audit-ref-mismatch"
    "receipt-predates-origin-binding" "manifest-hash-malformed"
    "manifest-hash-mismatch" "store-id-malformed" "basis-malformed" "basis-mismatch"
    "destination-mismatch" "audience-mismatch" "project-mismatch" "file-list-mismatch"})

(defn fail! [exit reason]
  (throw (ex-info "recovery-check failed" {:exit exit :reason reason})))

(defn sha256? [s] (and (string? s) (boolean (re-matches #"[0-9a-fA-F]{64}" s))))

(defn arguments [args]
  (if (contains? #{["--help"] ["-h"]} (vec args))
    {:help true}
    (loop [xs args opts {}]
      (if-let [x (first xs)]
        (if-let [k ({"--project" :project "--destination" :destination
                     "--expect-manifest" :expect-manifest} x)]
          (let [v (second xs)]
            (when (or (contains? opts k) (str/blank? v) (str/starts-with? v "--"))
              (fail! 2 :arguments))
            (recur (nnext xs) (assoc opts k v)))
          (do
            (when (or (contains? opts :from) (str/starts-with? x "-"))
              (fail! 2 :arguments))
            (recur (next xs) (assoc opts :from x))))
        (do
          (when-not (and (string? (:from opts)) (.isAbsolute (io/file (:from opts)))
                        (string? (:project opts))
                        (re-matches #":[^\s:/]+/[^\s/]+" (:project opts))
                        (not (str/blank? (:destination opts)))
                        (or (nil? (:expect-manifest opts)) (sha256? (:expect-manifest opts))))
            (fail! 2 :arguments))
          (cond-> opts (:expect-manifest opts) (update :expect-manifest str/lower-case)))))))

(defn- nonblank [s] (when-not (str/blank? s) s))

(defn- config-port [path]
  ;; Only existing config files are read. No readers/eval or implicit creation.
  (when (.exists (io/file path))
    (try
      (with-open [r (java.io.PushbackReader. (io/reader path))]
        (let [eof (Object.) value (edn/read {:eof eof} r)]
          (when-not (and (map? value) (identical? eof (edn/read {:eof eof} r)))
            (fail! 2 :configuration))
          (:port value)))
      (catch Exception _ (fail! 2 :configuration)))))

(defn connection [env]
  (let [home (or (nonblank (get env "HOME")) (System/getProperty "user.home"))
        backend (or (nonblank (get env "SANDBAR_HOME")) (str home "/src/sandbar"))
        client (or (nonblank (get env "SANDBAR_CLIENT_DIR")) (str home "/claude"))
        port (or (nonblank (get env "SANDBAR_PORT"))
                 (config-port (str client "/.sandbar/config.edn"))
                 (config-port (str backend "/config/config.edn")) 8389)
        port (try (Long/parseLong (str port)) (catch Exception _ (fail! 2 :port)))
        _ (when-not (<= 1 port 65535) (fail! 2 :port))
        token (or (nonblank (get env "SANDBAR_TOKEN"))
                  (try
                    (let [f (io/file client ".sandbar" "token")]
                      (when (.isFile f) (slurp f)))
                    (catch Exception _ (fail! 2 :credential))))
        token (some-> token str/trim)]
    (when-not (and token (re-matches #"[^\s:]+:[^\s]+" token))
      (fail! 2 :credential))
    {:uri (str "http://127.0.0.1:" port "/mcp") :token token}))

(defn decode-response [response expected-sha]
  (when-not (= 200 (:status response)) (fail! 1 :http))
  (let [envelope (try (json/parse-string (:body response) true)
                      (catch Exception _ (fail! 1 :protocol)))
        result (:result envelope)]
    ;; Errors are checked before either payload representation.
    (when (contains? envelope :error) (fail! 1 :jsonrpc))
    (when-not (and (map? envelope) (= "2.0" (:jsonrpc envelope)) (= 1 (:id envelope))
                   (map? result)
                   (or (not (contains? result :isError)) (boolean? (:isError result))))
      (fail! 1 :protocol))
    (when (:isError result) (fail! 1 :server-refused))
    (let [data (if (contains? result :structuredContent)
                 (:structuredContent result)
                 (let [texts (filter #(= "text" (:type %)) (:content result))]
                   (when-not (= 1 (count texts)) (fail! 1 :protocol))
                   (try (json/parse-string (:text (first texts)) true)
                        (catch Exception _ (fail! 1 :protocol)))))]
      (when-not (and (map? data) (= "checked" (:status data))
                     (= "snapshot-comparison" (:scope data))
                     (contains? states (:state data))
                     (contains? #{"public" "private"} (:audience data))
                     (integer? (:file-count data)) (<= 0 (:file-count data))
                     (integer? (:held-count data)) (<= 0 (:held-count data))
                     (sha256? (:manifest-sha256 data))
                     (false? (:import-approved? data))
                     (not (:isError data)) (not (:error data))
                     (or (nil? expected-sha)
                         (= expected-sha (str/lower-case (:manifest-sha256 data)))))
        (fail! 1 :protocol))
      ;; Only finite contract fields leave the client; never echo raw errors,
      ;; unknown fields, private paths/IDs or server-supplied prose.
      (cond-> {:status :checked :scope :snapshot-comparison :state (keyword (:state data))
       :file-count (:file-count data) :audience (keyword (:audience data))
       :held-count (:held-count data) :manifest-sha256 (str/lower-case (:manifest-sha256 data))
       :import-approved? false}
        (and (= "origin-unverified" (:state data))
             (contains? unverified-reasons (:reason data)))
        (assoc :reason (keyword (:reason data)))))))

(defn request! [{:keys [uri token]} args]
  (try
    (http/post uri
      {:client (http/client {:follow-redirects :never :connect-timeout 3000})
       :timeout 30000 :throw false
       :headers {"Content-Type" "application/json" "Accept" "application/json"
                 "Authorization" (str "Bearer " token)}
       :body (json/generate-string
               {:jsonrpc "2.0" :id 1 :method "tools/call"
                :params {:name "sandbar_project_recovery-check" :arguments args}})})
    (catch Exception _ (fail! 1 :transport))))

(defn main [args]
  (try
    (let [opts (arguments args)]
      (if (:help opts)
        (do (print usage) 0)
        (let [response (request! (connection (into {} (System/getenv))) opts)
              result (decode-response response (:expect-manifest opts))]
          (prn result)
          (if (= :same-store-same-basis (:state result)) 0 3))))
    (catch Exception e
      (let [{:keys [exit reason]} (ex-data e)]
        (binding [*out* *err*]
          (println (str "recovery-check: " (name (or reason :failure))))
          (when (= :arguments reason) (print usage)))
        (or exit 1)))))

(when (= (System/getProperty "babashka.file") *file*)
  (let [exit (main *command-line-args*)]
    (flush)
    (binding [*out* *err*] (flush))
    (System/exit exit)))
