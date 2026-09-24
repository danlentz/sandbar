(ns sandbar.scripts.issue-mcp-token
  "Create an MCP service account or rotate its key, then print its token.
   Usage:
     lein issue-mcp-token <service-name> [<api-key>] [--rotate]

   Connect to the selected database, initialize a fresh database if needed,
   and create an auth/ServiceAccount or rotate an existing account's key hash.
   Existing stores must already contain the required account schema. The
   printed token has shape <service-name>:<api-key>; handle stdout as a secret.

   This script assigns neither an authorization role nor memory clearance.
   Provision those separately as described in doc/auth.md before expecting
   authenticated tool calls or private-memory reads to succeed."
  (:require [clojure.string        :as str]
            [clojure.tools.logging :as log]
            [datomic.api           :as d]
            [sandbar.db.datatype   :as dt]
            [sandbar.db.datomic    :as db]
            [sandbar.util.auth     :as auth])
  (:import [java.security SecureRandom]
           [java.util Base64])
  (:gen-class))

(defn- generate-api-key
  "Cryptographically-strong API key — 32 random bytes encoded as
   URL-safe base64 (43 chars after stripping padding)."
  []
  (let [rng   (SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes rng bytes)
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString bytes))))

(defn- exit!
  ([code] (System/exit code))
  ([code msg]
   (binding [*out* *err*]
     (println msg))
   (System/exit code)))

(defn- parse-args
  "Split argv into {:positional [strings] :flags #{strings}}."
  [args]
  (reduce (fn [acc arg]
            (if (str/starts-with? arg "--")
              (update acc :flags conj arg)
              (update acc :positional conj arg)))
          {:positional [] :flags #{}}
          args))

(defn- print-token-banner
  [service-name token]
  (println)
  (println "=== MCP Bearer Token Issued ===")
  (println)
  (println (str "Service:  " service-name))
  (println (str "Token:    " token))
  (println)
  (println "Set in your shell to enable the MCP client to authenticate:")
  (println)
  (println (str "  export SANDBAR_TOKEN=\"" token "\""))
  (println)
  (println "Then any MCP client (e.g., the corpus-side bb mcp-client at")
  (println "~/claude/etc/lib/mcp_client.clj) will use this token when")
  (println "calling http://localhost:8080/mcp.")
  (println))

(defn -main
  "Lein-alias entry point.  See ns-docstring."
  [& args]
  (let [{:keys [positional flags]} (parse-args args)
        rotate?                    (contains? flags "--rotate")
        [service-name api-key-arg] positional]
    (when-not service-name
      (exit! 2 "Usage: lein issue-mcp-token <service-name> [<api-key>] [--rotate]"))
    (let [api-key    (or api-key-arg (generate-api-key))
          service-kw (keyword service-name)
          uri        (db/db-uri)]
      (log/info :BOOTSTRAP/INIT :service-name service-kw :uri uri :rotate? rotate?)
      (db/initialize-db! uri)
      (reset! db/**conn* (db/conn uri))
      (let [existing (auth/find-service-account service-kw)
            hash     (auth/hash-password api-key)]
        (cond
          (and existing (not rotate?))
          (exit! 1 (str "Service account already exists: " service-name
                        "\nUse --rotate to replace the API key."))

          existing
          (do
            @(d/transact (db/conn)
                         [{:db/id             (:db/id existing)
                           :auth/api-key-hash hash
                           :auth/active?      true}])
            (log/info :BOOTSTRAP/ROTATED :service-name service-kw))

          :else
          (do
            (dt/make :auth/ServiceAccount
                     {:auth/service-name   service-kw
                      :auth/principal-name (str "MCP Client: " service-name)
                      :auth/api-key-hash   hash
                      :auth/active?        true})
            (log/info :BOOTSTRAP/CREATED :service-name service-kw))))
      (print-token-banner service-name (str service-name ":" api-key))
      (exit! 0))))
