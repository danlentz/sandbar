(ns sandbar.gate.release-gate-test
  "clojure.test surface for the W1.J standing release gate, so CI runs the
   composed G1 precondition under `lein test` (the script
   `sandbar.scripts.w1-release-gate` is the CLI/exit-code surface over the
   SAME gate functions).

   No `make-test-db-fixture` here: the gate manages its own ephemeral
   datomic:mem databases via `sandbar.gate.db/with-fresh-db*` (save/restore
   the ambient conn), so these deftests just drive the gate + assert its
   plain-data result."
  (:require [clojure.test :refer [deftest testing is]]
            [sandbar.gate.roundtrip  :as rt]
            [sandbar.gate.scoreboard :as sb]
            [sandbar.gate.fixture    :as fx]
            [sandbar.scripts.w1-release-gate :as gate]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CHECK 1 — round-trip semantic equivalence (8-query §D.5 contract)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check1-second-project-round-trips
  (testing "the private second-project corpus round-trips to semantic equivalence"
    (let [r (rt/round-trip-corpus (fx/store-dir :second-project))]
      (is (:equivalent? r)
          (str "round-trip divergences: "
               (pr-str (get-in r [:contract :divergences]))))
      (doseq [chk (get-in r [:contract :checks])]
        (is (:equivalent? chk)
            (str (name (:query chk)) " diverged: " (pr-str (:detail chk))))))))

(deftest check1-public-corpus-round-trips
  (testing "the public corpus round-trips to semantic equivalence"
    (let [r (rt/round-trip-corpus (fx/store-dir :public-corpus))]
      (is (:equivalent? r)
          (str "round-trip divergences: "
               (pr-str (get-in r [:contract :divergences])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CHECK 2 — firewall attack scoreboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check2-directional-attacks-all-refused
  (testing "every mechanical directional attack is refused on the edge"
    (doseq [a (sb/run-directional-attacks!)]
      (is (:pass a) (str (name (:id a)) " — expected " (:expect a))))))

(deftest check2-absence-probes-with-negative-controls
  (testing "each absence probe: absent-in-uncleared AND present-in-cleared"
    (doseq [p (sb/run-absence-probes!)]
      (is (:absent-in-uncleared? p)
          (str (name (:id p)) " must be ABSENT in the uncleared session; got "
               (pr-str (:uncleared p))))
      (is (:present-in-cleared? p)
          (str (name (:id p)) " negative control must be PRESENT in cleared; got "
               (pr-str (:cleared p)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G1 — the composed gate is green (BOTH checks)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest g1-composed-gate-green
  (testing "GREEN requires BOTH round-trip equivalence AND the firewall scoreboard"
    (let [result (gate/run-gate)]
      (is (:pass (:check-1 result)) "CHECK 1 (round-trip) must pass")
      (is (:pass (:check-2 result)) "CHECK 2 (firewall scoreboard) must pass")
      (is (:green? result) "the composed G1 gate must be GREEN"))))
