(ns sandbar.scripts.w1-release-gate
  "Run the composed fixture gate for database/Markdown round trips and
   project-firewall attack cases. Both checks must pass for a green result.
   Usage: lein w1-release-gate

   The gate uses ephemeral in-memory fixtures. Its report distinguishes
   implemented enforcement, deployment seams, and documented residuals; green
   is not proof of live project enrollment, a configured filesystem sandbox,
   or a portable backup/restore procedure. See doc/W1J-RELEASE-GATE.md."
  (:require [clojure.string :as str]
            [sandbar.gate.fixture   :as fx]
            [sandbar.gate.roundtrip :as rt]
            [sandbar.gate.scoreboard :as sb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; run
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-gate
  "Run both checks; return {:green? bool :check-1 {...} :check-2 {...}}.
   `opts` :corpus-roots (default the two fixture stores) + :allowed-drift
   (default empty — the fixture has zero expected drift)."
  ([] (run-gate {}))
  ([{:keys [corpus-roots allowed-drift]
     :or   {allowed-drift #{}}}]
   (let [roots  (or corpus-roots [(fx/store-dir :public-corpus)
                                  (fx/store-dir :second-project)])
         check1 (rt/run roots {:allowed-drift allowed-drift})
         check2 (sb/run)]
     {:green?  (and (:pass check1) (:pass check2))
      :check-1 check1
      :check-2 check2})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; human-readable report
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mark [pass?] (if pass? "PASS" "FAIL"))

(defn- live-tag [enforced-today]
  (case enforced-today
    true  "[live]"
    :seam "[seam:W1.deploy]"
    false "[documented]"
    "[?]"))

(defn print-report!
  [{:keys [green? check-1 check-2]}]
  (println "================================================================")
  (println "  W1.J RELEASE GATE  —  composed G1 precondition")
  (println "================================================================")
  (println)
  (println (str "CHECK 1 — round-trip semantic equivalence (8-query §D.5)   "
                (mark (:pass check-1))))
  (doseq [c (:corpora check-1)]
    (println (format "   %-30s %s" (last (str/split (:corpus c) #"/"))
                     (mark (:equivalent? c))))
    (doseq [chk (get-in c [:contract :checks])]
      (println (format "      %-24s %s" (name (:query chk))
                       (mark (:equivalent? chk)))))
    (when-let [divs (seq (get-in c [:contract :divergences]))]
      (println "      DIVERGENCES:")
      (doseq [d divs]
        (println (format "        %s  only-in=%s keys=%s"
                         (:rel-path d) (:only-in d) (:differing-keys d)))))
    (let [h (:import-health c)]
      (when (or (seq (get-in h [:reference :failed]))
                (seq (get-in h [:reconstruct :failed]))
                (seq (get-in h [:reference :refused]))
                (seq (get-in h [:reconstruct :refused])))
        (println (format "      import-health ref=%s recon=%s"
                         (dissoc (:reference h) :persisted)
                         (dissoc (:reconstruct h) :persisted))))))
  (println)
  (println (str "CHECK 2 — firewall attack scoreboard                       "
                (mark (:pass check-2))))
  (println "   A. directional attacks (mechanical):")
  (doseq [a (:directional-attacks check-2)]
    (println (format "      %-9s %-42s %-6s %s"
                     (name (:id a)) (:name a) (mark (:pass a)) (live-tag (:enforced-today a)))))
  (println "   B. absence probes (physical exclusion; w/ negative control):")
  (doseq [p (:absence-probes check-2)]
    (println (format "      %-9s %-42s %-6s %s"
                     (name (:id p)) (:name p) (mark (:pass p)) (live-tag (:enforced-today p))))
    (println (format "                uncleared=%s  cleared=%s"
                     (pr-str (:uncleared p)) (pr-str (:cleared p)))))
  (let [r (:disciplinary-residual check-2)]
    (println "   C. disciplinary residual (documented, not mechanical):")
    (println (format "      %-9s %-42s %-6s %s"
                     (name (:id r)) (:name r) (name (:verdict r)) (live-tag (:enforced-today r))))
    (println (format "                backstop: %s" (:backstop r))))
  (println)
  (println "----------------------------------------------------------------")
  (println (str "  GATE: " (if green? "GREEN — both checks pass" "RED — see failures above")))
  (println "----------------------------------------------------------------")
  green?)

(defn -main [& _args]
  (let [result (run-gate)]
    (print-report! result)
    (System/exit (if (:green? result) 0 1))))
