(ns sandbar.scripts.drift-audit
  "The stopped-server drift audit: `lein drift-audit -- --from <corpus-root> [--out <file.json>]`.

   Runs `sandbar.audit.fs-substrate-drift/audit-all` in an admin JVM against
   the configured store while the server is STOPPED, so a maintenance import's
   result is audited before anything restarts and before any writer can move
   the store.  The wrapper `bin/sandbar drift-audit` refuses while the server
   runs; over the wire the same audit is `sandbar_audit_fs-substrate-drift`.
   Prints the summary; writes the full report as JSON when `--out` is given.
   Exit 0 when the audit ran; the report is the result, not the exit code —
   every remaining row should carry a named reason (D7b/D8, 2026-09-20)."
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
