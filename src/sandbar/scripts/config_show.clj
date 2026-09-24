(ns sandbar.scripts.config-show
  "Print the resolved layered config, optionally with each layer's values.
   Usage: bin/sandbar config-show [--provenance]
   Use this to diagnose which client directory, database, port, or environment
   override the process actually resolves."
  (:require [clojure.pprint :as pp]
            [sandbar.config :as cfg])
  (:gen-class))

(defn -main [& args]
  (let [provenance? (some #{"--provenance" "-p"} args)]
    (if provenance?
      (let [{:keys [client-dir
                    defaults-resource
                    client-override
                    client-exists?
                    env-vars-set
                    resolved
                    layer-1-defaults
                    layer-2-override
                    layer-3-env]} (cfg/provenance)]
        (println "=== sandbar config — layered provenance ===")
        (println)
        (println (str "CLIENT_DIR: " client-dir))
        (println)
        (println "LAYER 1 — bundled defaults")
        (println (str "  source: " defaults-resource))
        (pp/pprint layer-1-defaults)
        (println)
        (println "LAYER 2 — client-project override")
        (println (str "  source: " client-override))
        (println (str "  exists: " client-exists?))
        (pp/pprint layer-2-override)
        (println)
        (println "LAYER 3 — env-var overrides")
        (println (str "  env vars set: " env-vars-set))
        (pp/pprint layer-3-env)
        (println)
        (println "=== RESOLVED (merged) ===")
        (pp/pprint resolved))
      (do
        (println "=== sandbar resolved config ===")
        (println (str "(CLIENT_DIR: " (cfg/client-dir) ")"))
        (println)
        (pp/pprint (cfg/config))
        (println)
        (println "(re-run with --provenance for per-layer breakdown)")))
    (System/exit 0)))
