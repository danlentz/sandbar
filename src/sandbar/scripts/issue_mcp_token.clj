(ns sandbar.scripts.issue-mcp-token
  "Bootstrap a Sandbar MCP service-account + emit the corresponding
   Bearer-token value for the operator's `SANDBAR_TOKEN` env var.

   ## Usage

       lein issue-mcp-token <service-name>                    ; auto-generate API key
       lein issue-mcp-token <service-name> <api-key>          ; supply API key
       lein issue-mcp-token <service-name> --rotate           ; rotate existing
       lein issue-mcp-token <service-name> <api-key> --rotate

   The service-name should be a keyword-shape string (e.g. `corpus`,
   `claude`, `mcp-client`).  The script:

   1. Connects to the Datomic database per `config.edn`
   2. Ensures the database + required schema are present (idempotent
      for first-create; existing DBs must have schema loaded already
      — same behavior as `sandbar.db.datomic/initialize-db!`)
   3. Sets the dynamic `sandbar.db.datomic/**conn*` atom so downstream
      `dt/make` + `auth/find-service-account` calls use this conn
   4. Creates an `auth/ServiceAccount` entity OR rotates the API key
      hash on an existing account (with `--rotate`)
   5. Prints the Bearer-token shape (`<service-name>:<api-key>`) +
      a ready-to-paste shell-export line for `SANDBAR_TOKEN`

   ## Why this exists

   Before this script, the only documented path to provision an MCP
   client token was the Clojure recipe in `doc/auth.md` §'MCP Bearer
   Token Authentication' — eight lines of `dt/make` + `auth/hash-
   password` that the operator had to copy-paste into a REPL session.
   That was fine when Sandbar's only consumer was an interactive
   developer; the 0.1.1 memory-model co-evolution arc surfaced it as
   Friction Item #1 (`memory/plans/sandbar_0_1_1_coevolution_arc_-
   2026_05_20.md` §3): the corpus's MCP client cannot authenticate
   without `SANDBAR_TOKEN`, and no end-to-end script existed.

   Composes with:
   - `doc/auth.md` §'MCP Bearer Token Authentication' — the manual
     Clojure recipe this script automates
   - `sandbar.util.auth/hash-password` + `sandbar.util.auth/find-
     service-account` — the underlying primitives
   - `sandbar.db.datatype/make` — the canonical entity-creation
     primitive per the operational verb catalog

   ## Limitations

   - Assumes Datomic schema is already loaded for existing DBs
     (`initialize-db!` only loads schema on first-create).  If the
     DB exists but `:auth/ServiceAccount` schema isn't loaded, the
     `dt/make` call will fail; bring up `lein run` once to populate
     schema then re-run this script.
   - The token is printed to stdout — appropriate for dev-time
     bootstrap; not appropriate as a production secret-provisioning
     path.  Future work: emit to a file in `~/.config/sandbar/` or
     similar."
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
