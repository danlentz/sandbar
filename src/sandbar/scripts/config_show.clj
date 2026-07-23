(ns sandbar.scripts.config-show
  "Print the resolved 3-layer config + provenance.

   Usage:

       bin/sandbar config-show              ;; pretty-printed full config
       bin/sandbar config-show --provenance ;; ALSO show per-layer contributions

   The output answers 'what config does sandbar actually see right now?'
   — useful for diagnosing 'why is :port X instead of Y?' situations.
   Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md D.G."
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
