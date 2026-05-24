(ns sandbar.config
  "Sandbar's 3-layer config loader — bundled defaults + client-project
   override + env vars.

   ## Layer 1 — bundled defaults
   Read from `config.edn` on the classpath (sandbar's `:resource-paths`
   include `config/`; the resource is bundled with the deployment artifact).
   Holds sentinel + minimal values; ships with sandbar.

   ## Layer 2 — client-project override
   Read from `<SANDBAR_CLIENT_DIR>/.sandbar/config.edn` if it exists.
   This is the PRIMARY configuration surface — project-specific bindings
   like `:sid`, `:port`, `:nrepl-port`, transactor URL, backup retention.

   `SANDBAR_CLIENT_DIR` discovery: env var → `sandbar.client-dir` JVM
   property → CWD fallback.  The `bin/sandbar` wrapper propagates the env
   value to the JVM via `-Dsandbar.client-dir=<path>`.

   ## Layer 3 — env vars
   Per-deployment ops overrides without editing config files.  Mapped
   from env-var names to config-map paths via `env-overrides-mapping`.

   ## Merge semantics
   Layer 3 wins over layer 2 wins over layer 1.  Deep merge — nested
   maps merge slot-by-slot.  Non-map values from a higher layer replace
   non-map values from a lower layer.

   ## Caching
   The resolved config is computed via `delay` — runs at most once per
   JVM, on first call to `(config)`.  In test environments where the
   layers should be re-evaluated (e.g., after `with-redefs` stubs the
   env), use `(reload!)`.

   ## Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
   This namespace implements D.B (3-layer merge) which finally executes
   D.3 of `sandbar_sid_reconciliation_*_2026_05_12.md` (late-bind via
   env-var / CLI / registry-driven config) — extended from `:sid`-only
   to the full config surface."
  (:require [clojure.edn        :as edn]
            [clojure.java.io    :as io]
            [clojure.string     :as str])
  (:import (java.io File)))

(def ^:const default-resource-name "config.edn")
(def ^:const client-config-relpath ".sandbar/config.edn")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Env-var → config-path mapping
;;
;; Each entry maps an env-var NAME to a [path, coercer] pair.  Path is
;; the get-in path into the config map; coercer parses the string env
;; value into the appropriate Clojure value (Long for ports, str for
;; URIs, etc.).  Extend this map as new env-var overrides are added.

(def env-overrides-mapping
  {"SANDBAR_PORT"           {:path [:port]              :coerce #(Long/parseLong %)}
   "SANDBAR_NREPL_PORT"     {:path [:nrepl :port]       :coerce #(Long/parseLong %)}
   "SANDBAR_DB_SID"         {:path [:db :sid]           :coerce str}
   "SANDBAR_DB_URL"         {:path [:db :url]           :coerce str}
   "SANDBAR_CLIENT_DIR"     {:path [:client-dir]        :coerce str}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Discovery seams (with-redefs-able for tests)

(defn getenv
  "Wrapper around `System/getenv` providing a redefinable seam."
  [k]
  (System/getenv k))

(defn getprop
  "Wrapper around `System/getProperty` providing a redefinable seam."
  [k]
  (System/getProperty k))

(defn client-dir
  "Resolve the SANDBAR_CLIENT_DIR — env var → JVM prop → CWD.
   The `bin/sandbar` wrapper propagates the env value to the JVM via
   `-Dsandbar.client-dir=<path>`; CWD is the development fallback."
  []
  (or (getenv "SANDBAR_CLIENT_DIR")
      (getprop "sandbar.client-dir")
      (getprop "user.dir")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layer readers

(defn- read-edn-stream [stream]
  (when stream
    (try (with-open [r (io/reader stream)]
           (edn/read (java.io.PushbackReader. r)))
         (catch Throwable _ nil))))

(defn read-bundled-defaults
  "Layer 1 — bundled defaults from the classpath `config.edn` resource."
  []
  (or (read-edn-stream (io/resource default-resource-name))
      {}))

(defn read-client-override
  "Layer 2 — client-project override from `<CLIENT_DIR>/.sandbar/config.edn`.
   Returns {} if the file doesn't exist or fails to parse."
  []
  (let [path (str (client-dir) "/" client-config-relpath)
        f    (io/file path)]
    (if (and (.exists f) (.isFile f))
      (or (read-edn-stream f) {})
      {})))

(defn read-env-overrides
  "Layer 3 — env-var overrides per `env-overrides-mapping`.
   Builds a nested map from env vars that are set."
  []
  (reduce-kv
   (fn [acc env-name {:keys [path coerce]}]
     (if-let [v (getenv env-name)]
       (assoc-in acc path (coerce v))
       acc))
   {} env-overrides-mapping))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deep merge

(defn deep-merge
  "Like `merge` but recurses into nested maps.  Non-map values from `b`
   replace values from `a`."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b)               b
    :else                   a))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resolved config (memoized; reloadable)

(def ^:private resolved (atom nil))

(defn- compute-resolved []
  (-> (read-bundled-defaults)
      (deep-merge (read-client-override))
      (deep-merge (read-env-overrides))))

(defn config
  "Return the resolved 3-layer config map.  Memoized — runs the layer
   reads + merge at most once per JVM (or until `(reload!)`)."
  []
  (or @resolved
      (reset! resolved (compute-resolved))))

(defn reload!
  "Force a re-resolution of the layered config.  Useful in tests after
   stubbing env vars + JVM props."
  []
  (reset! resolved nil)
  (config))

(defn get-in*
  "Convenience — `(get-in (config) path default)`."
  ([path]         (get-in (config) path))
  ([path default] (get-in (config) path default)))

(defn value
  "Convenience — return a single key or path from the resolved config."
  ([k]             (get-in* [k]))
  ([k & more-keys] (get-in* (cons k more-keys))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Provenance — useful for `bin/sandbar config show`

(defn provenance
  "Return a map describing where each layer's contribution came from.
   Useful for diagnosing 'why is :port X instead of Y?'."
  []
  {:client-dir         (client-dir)
   :defaults-resource  (str (io/resource default-resource-name))
   :client-override    (str (client-dir) "/" client-config-relpath)
   :client-exists?     (.exists (io/file (str (client-dir) "/" client-config-relpath)))
   :env-vars-set       (->> env-overrides-mapping
                            keys
                            (filter getenv)
                            vec)
   :resolved           (config)
   :layer-1-defaults   (read-bundled-defaults)
   :layer-2-override   (read-client-override)
   :layer-3-env        (read-env-overrides)})
