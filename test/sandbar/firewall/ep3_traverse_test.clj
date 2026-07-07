(ns sandbar.firewall.ep3-traverse-test
  "S7 BU-5 / BU-7 — EP-3 traverse-time refusal at the dt primitives.

   T-18  outbound-edges-of lists a legacy public→private edge as
         {:predicate :blocked true} with NO :target key.
   T-27  graph-walk-from / navigate.walk does NOT include the private target
         in the frontier and surfaces {:blocked true} — the CA-2 falsifier
         (walk bypassed the edges-of guard before the graph-walk-from hook).

   A legacy-bad edge is seeded via RAW transact (bypassing EP-1) so the
   traverse guard is what is under test."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.walk :as cwalk]
            [sandbar.db.datatype :as dt]
            [sandbar.firewall.support :as sup]
            [sandbar.navigate.path :as nav-path]
            [sandbar.navigate.walk :as walk]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-ep3" :auth? false}))

(defn- seed-legacy-bad-edge! []
  (sup/seed-context! :ctx/home :public-bottom)
  (sup/seed-context! :ctx/work :project-isolated)
  ;; :proj/pub gets firewall-class :public-bottom so its CA-4 4-way composition
  ;; is EFFECTIVELY :public — otherwise absent→:private makes :mem/pub-source
  ;; private, the seeded public→private edge is actually private→private-in-a-
  ;; different-context (still forbidden, but the permitted-edge leg would break)
  ;; and ep3-permitted-edge-lists-normally over-blocks a legit private→public.
  (sup/seed-project! :proj/pub  :public  :ctx/home :public-bottom)
  (sup/seed-project! :proj/priv :private :ctx/work)
  (sup/seed-memory!  :mem/priv-target :private :proj/priv)
  ;; RAW-transact a FORBIDDEN public→private cites edge (bypassing EP-1) so
  ;; EP-3 has a legacy-bad edge to list-as-broken.
  (sup/seed-memory!  :mem/pub-source :public :proj/pub
                     {:mm.memory/cites (sup/eid-of :mem/priv-target)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-18 — outbound-edges-of lists the forbidden hop as blocked (no :target)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep3-outbound-lists-blocked-hop
  (seed-legacy-bad-edge!)
  (let [edges (dt/outbound-edges-of :mem/pub-source {:predicate :mm.memory/cites})]
    (is (= 1 (count edges)))
    (let [edge (first edges)]
      (testing "the forbidden edge is REWRITTEN as blocked"
        (is (= :mm.memory/cites (:predicate edge)))
        (is (true? (:blocked edge)))
        (is (= :firewall/flow-forbidden (:reason edge))))
      (testing "NO :target key at all (absence, not a sentinel — a client
                feeding :target onward gets nil, never a fake entity)"
        (is (not (contains? edge :target)))))))

(deftest ep3-inbound-lists-blocked-hop
  (seed-legacy-bad-edge!)
  (testing "from the TARGET end, the forbidden inbound edge is also blocked
            (no :source key) — symmetric with outbound"
    (let [edges (dt/inbound-edges-of :mem/priv-target {:predicate :mm.memory/cites})]
      (is (= 1 (count edges)))
      (let [edge (first edges)]
        (is (true? (:blocked edge)))
        (is (not (contains? edge :source)))))))

(deftest ep3-permitted-edge-lists-normally
  (seed-legacy-bad-edge!)
  ;; a PERMITTED private→public edge (raw-seeded) lists its :target normally
  (sup/seed-memory! :mem/priv-source :private :proj/priv
                    {:mm.memory/cites (sup/eid-of :mem/pub-source)})
  (testing "a PERMITTED private→public edge is NOT blocked — :target present"
    (let [edges (dt/outbound-edges-of :mem/priv-source {:predicate :mm.memory/cites})]
      (is (= 1 (count edges)))
      (is (not (:blocked (first edges))))
      (is (contains? (first edges) :target)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-27 — graph-walk-from / navigate.walk does NOT reach the private target
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep3-walk-lists-blocked-hop
  (seed-legacy-bad-edge!)
  (let [priv-eid (sup/eid-of :mem/priv-target)]
    (testing "dt/graph-walk-from over the public source does NOT include the
              private target in the walk frontier (CA-2 — walk no longer
              bypasses the edges-of guard)"
      (let [results (dt/graph-walk-from :mem/pub-source
                                        {:direction :forward
                                         :predicates :mm.memory/cites})
            reached (into #{} (keep #(:db/id (:entity %))) results)]
        (is (not (contains? reached priv-eid))
            "the private target must NOT be reachable from the public source")
        (is (some :blocked results)
            "a {:blocked true} row surfaces the forbidden hop (audit signal)")
        (is (every? #(or (not (:blocked %)) (not (contains? % :target)))
                    results)
            "a blocked row carries NO :target")))

    (testing "navigate.walk inherits the guard (it wraps graph-walk-from)"
      (let [{:keys [reachable]} (walk/graph-walk {:from :mem/pub-source
                                                  :direction :forward
                                                  :predicates :mm.memory/cites})
            reached (into #{} (keep #(:db/id (:entity %))) reachable)]
        (is (not (contains? reached priv-eid))
            "navigate.walk must NOT reach the private target either")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-18 (path-via leg) — path-grammar traversal is EP-3-guarded too.
;;
;; The leak this closes (S7 leak-sweep 2026-07-06): path-via's DEFAULT
;; endpoint-only route ran raw recursive Datalog with NO firewall guard, so a
;; public seed's forbidden governed hop returned the private target.  Both the
;; guarded-evaluator route (Canonical-8) and the Tier-2 :NOT/:FILTER/:TEST
;; coarse-filter fallback must now withhold the forbidden endpoint + surface
;; the drop in :blocked (never silent, §5).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- reached-eids [result]
  (into #{} (keep #(or (:db/id %) (:db/id (:entity %)))) (:reachable result)))

(defn- eid-appears-anywhere?
  "True iff `eid` appears ANYWHERE in the path-via `result` (reachable entities,
   :blocked rows, nested) — the anti-leak invariant: a firewall-withheld eid
   must not surface through any channel (content OR audit)."
  [result eid]
  (let [seen (atom false)]
    (cwalk/postwalk (fn [x] (when (= x eid) (reset! seen true)) x) result)
    @seen))

(deftest ep3-path-via-atomic-forbidden-hop-withheld
  (seed-legacy-bad-edge!)
  (let [priv-eid (sup/eid-of :mem/priv-target)]
    (testing "path-via over the forbidden public→private :mm.memory/cites hop
              does NOT return the private target (guarded evaluator route)"
      (let [result (nav-path/path-via {:from :mem/pub-source :via :mm.memory/cites})]
        (is (not (contains? (reached-eids result) priv-eid))
            "the private target must NOT be reachable via path-via")
        (is (zero? (:total result)))))
    (testing "the dropped hop is SURFACED in :blocked as an eid-FREE R16 row
              (audit signal, not silent — and the private eid does NOT leak
              through the audit channel)"
      (let [result  (nav-path/path-via {:from :mem/pub-source :via :mm.memory/cites})
            blocked (:blocked result)]
        (is (seq blocked) "a blocked-hop row must surface the forbidden traverse")
        (is (some (fn [b] (and (:blocked b)
                               (= :firewall/flow-forbidden (:reason b))
                               (= :mm.memory/cites (:predicate b))))
                  blocked))
        (doseq [b blocked]
          (is (not (contains? b :to)) "R16: no endpoint eid on the blocked row")
          (is (not (contains? b :from)) "R16: no source eid on the blocked row"))
        (is (not (eid-appears-anywhere? result priv-eid))
            "the private eid must not leak through content OR the audit channel")))))

(deftest ep3-path-via-inv-over-permitted-edge-lists-normally
  ;; §8-R17: a PERMITTED private→public edge lists normally from EITHER end.
  ;; A public seed running [:INV :mm.memory/cites] reaches its private citer
  ;; because the WRITTEN edge (private→public) is permitted — the per-hop
  ;; verdict is symmetric.  Whether the public seed thereby learns a private
  ;; citer exists is the inbound-existence side-channel, ruled S9 physical-
  ;; exclusion territory (note-not-block), NOT an EP-3 leak.  This test PINS
  ;; that ratified behavior so a future "tighten INV" change is a conscious
  ;; divergence, not an accident.
  (sup/seed-context! :ctx/home :public-bottom)
  (sup/seed-context! :ctx/work :project-isolated)
  (sup/seed-project! :proj/pub  :public  :ctx/home :public-bottom)
  (sup/seed-project! :proj/priv :private :ctx/work)
  (sup/seed-memory!  :mem/pub-target :public :proj/pub)
  ;; a PERMITTED private→public citation (raw-seeded)
  (sup/seed-memory!  :mem/priv-citer :private :proj/priv
                     {:mm.memory/cites (sup/eid-of :mem/pub-target)})
  (testing "[:INV :mm.memory/cites] from the public target lists the private
            citer normally (permitted written edge, symmetric per-hop verdict)"
    (let [result (nav-path/path-via {:from :mem/pub-target :via [:INV :mm.memory/cites]})]
      (is (contains? (reached-eids result) (sup/eid-of :mem/priv-citer))
          "a permitted private→public edge must list from the inbound end (R17)")
      (is (empty? (:blocked result))
          "a permitted edge is not a blocked hop"))))

(deftest ep3-path-via-closure-does-not-escape-through-forbidden-hop
  (seed-legacy-bad-edge!)
  ;; priv-target cites a SECOND private memory in its own compartment; a
  ;; permitted intra-compartment hop that is only reachable by first crossing
  ;; the forbidden public→private hop must STILL be unreachable from the public
  ;; seed (per-hop frontier propagation — the forbidden hop terminates the
  ;; branch, so nothing downstream of it appears).
  (sup/seed-memory! :mem/priv-downstream :private :proj/priv)
  (sup/seed-memory! :mem/priv-target2 :private :proj/priv
                    {:mm.memory/cites (sup/eid-of :mem/priv-downstream)})
  ;; re-point priv-target's citation is not needed; add the hop off priv-target
  (dt/update-entity! :mem/priv-target
                     {:mm.memory/cites (sup/eid-of :mem/priv-downstream)})
  (let [reached (reached-eids
                  (nav-path/path-via {:from :mem/pub-source
                                      :via  [:REP+ :mm.memory/cites]}))]
    (testing "neither the forbidden target NOR anything downstream of it leaks"
      (is (not (contains? reached (sup/eid-of :mem/priv-target))))
      (is (not (contains? reached (sup/eid-of :mem/priv-downstream)))))))

(deftest ep3-path-via-permitted-direction-still-reachable
  (seed-legacy-bad-edge!)
  ;; a PERMITTED private→public citation must remain reachable (no over-block).
  (sup/seed-memory! :mem/priv-source :private :proj/priv
                    {:mm.memory/cites (sup/eid-of :mem/pub-source)})
  (let [result (nav-path/path-via {:from :mem/priv-source :via :mm.memory/cites})]
    (testing "the permitted private→public hop IS reachable via path-via"
      (is (contains? (reached-eids result) (sup/eid-of :mem/pub-source)))
      (is (empty? (:blocked result))))))

(deftest ep3-path-via-tier2-fallback-coarse-filter-withholds-forbidden-endpoint
  (seed-legacy-bad-edge!)
  ;; [:NOT :dt/subclass-of] is NOT evaluator-supported → the compiler fallback
  ;; under the coarse seed→endpoint firewall filter.  From the public seed it
  ;; reaches the private target via the complement predicate set (incl.
  ;; :mm.memory/cites); the coarse filter must drop it fail-closed.
  (let [priv-eid (sup/eid-of :mem/priv-target)
        result   (nav-path/path-via {:from :mem/pub-source :via [:NOT :dt/subclass-of]})]
    (testing "the Tier-2 endpoint-only fallback withholds the private endpoint"
      (is (not (contains? (reached-eids result) priv-eid))
          "coarse fail-closed filter must drop the public→private endpoint"))
    (testing "the coarse drop is surfaced in :blocked as an eid-FREE row"
      (is (some (fn [b] (and (:blocked b) (:coarse b))) (:blocked result))
          "a coarse blocked row must surface the withheld endpoint")
      (doseq [b (:blocked result)]
        (is (not (contains? b :to)) "R16: no endpoint eid on the coarse row"))
      (is (not (eid-appears-anywhere? result priv-eid))
          "the private eid must not leak through content OR the coarse audit channel"))))

(deftest ep3-path-via-tier2-fallback-preserves-non-governed-traversal
  ;; A metamodel seed (unassigned/private) doing [:NOT :dt/subclass-of] stays
  ;; within one UNASSIGNED compartment → the coarse filter drops nothing (no
  ;; over-refusal of legitimate non-governed traversal).
  (testing "non-governed metamodel traversal is NOT over-blocked by the filter"
    (let [result (nav-path/path-via {:from :dt/Property :via [:NOT :dt/subclass-of]})]
      (is (pos? (:total result))
          "the metamodel [:NOT :dt/subclass-of] walk still returns endpoints"))))
