(ns sandbar.logging.config
  "EDN-resident config loader for sandbar's logging substrate.

   Reads `resources/logging.edn` (or env-var override
   `SANDBAR_LOGGING_CONFIG`); applies the parsed spec via Telemere
   setters.  No XML config.

   Per logging-arc plan §3.4 + §6.3 — this is the SINGLE source of
   runtime-filter truth for Telemere; the companion
   `sandbar.logging.init/start!` is the canonical reconfiguration site
   and calls `load!` from this namespace before installing handlers.

   Schema (see resources/logging.edn for the shipping default):

     :min-level    — global floor (:trace / :debug / :info / :warn / :error)
     :ns-filter    — {:allow #{ns-glob...} :deny #{ns-glob...}}
     :id-filter    — {:allow #{::event-id...} :deny #{::event-id...}}
     :kind-filter  — {:allow #{:log :event :trace ...}}
     :ctx          — base context map merged into every signal's :ctx"
  (:require [clojure.edn       :as edn]
            [clojure.java.io   :as io]
            [taoensso.telemere :as tel]))

(def ^:const +default-config-resource+ "logging.edn")
(def ^:const +env-override-var+        "SANDBAR_LOGGING_CONFIG")

(defn- read-config-source
  "Resolve + slurp the EDN config source.  Order:
   1. SANDBAR_LOGGING_CONFIG env-var → file path
   2. classpath resource `logging.edn`
   Returns nil if neither is present (caller must handle)."
  []
  (if-let [path (System/getenv +env-override-var+)]
    (slurp path)
    (some-> (io/resource +default-config-resource+) slurp)))

(defn parse-config
  "Read + edn-parse the config source.  Returns the parsed map or nil
   if no source is available."
  []
  (when-let [src (read-config-source)]
    (edn/read-string src)))

(defn apply-config!
  "Apply a parsed config spec to Telemere via setters.  Idempotent.
   Returns the spec."
  [{:keys [min-level ns-filter id-filter kind-filter ctx] :as spec}]
  (when min-level   (tel/set-min-level!   min-level))
  (when ns-filter   (tel/set-ns-filter!   ns-filter))
  (when id-filter   (tel/set-id-filter!   id-filter))
  (when kind-filter (tel/set-kind-filter! kind-filter))
  (when ctx         (tel/set-ctx!         ctx))
  spec)

(defn load!
  "Read + apply the EDN config.  Returns the applied spec, or nil if no
   config source is available (then Telemere stays at its defaults)."
  []
  (when-let [spec (parse-config)]
    (apply-config! spec)))
