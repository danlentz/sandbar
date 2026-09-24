(ns sandbar.scripts.drift-audit
  "Compare a configured store with a corpus after maintenance and before
   restarting writers.
   Usage: lein drift-audit -- --from <corpus-root> [--out <file.json>]

   The wrapper command checks that the server is stopped; direct Leiningen
   invocation relies on the operator's stopped-writer procedure. Print the
   audit summary and optionally write the complete JSON report. Exit 0 means
   the audit ran, not that every discrepancy is resolved. Give each retained
   discrepancy a reason and preserve ambiguous versions; see doc/operations.md."
  (:require [cheshire.core :as json]
            [sandbar.audit.fs-substrate-drift :as audit]
            [sandbar.codec.markdown :as md]))

(defn- arg
  "The value following `flag` in `args`, else `default`."
  [args flag default]
  (let [i (.indexOf ^java.util.List (vec args) flag)]
    (if (or (neg? i) (>= (inc i) (count args))) default (nth args (inc i)))))

(defn -main [& args]
  (let [from (arg args "--from" (str (System/getProperty "user.home") "/claude"))
        out  (arg args "--out" nil)]
    (md/register!)
    (let [report (audit/audit-all {:from from})]
      (when out
        (spit out (json/generate-string report))
        (println "drift audit report written:" out))
      (println "drift audit summary:" (pr-str (:summary report)))
      (shutdown-agents)
      (System/exit 0))))
