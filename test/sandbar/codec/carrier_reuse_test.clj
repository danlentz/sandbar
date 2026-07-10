(ns sandbar.codec.carrier-reuse-test
  "Carrier-reuse receipts for the identful-:mm/Frontmatter-carrier fix per
   scratchpad/carrier-reuse-2026-07-02/SPEC.md (follow-up to the landed
   emitter-fidelity fix, commit fc6ae69 + the Option-A ADR).

   The point (SPEC §Problem): extras carriers used to mint ANONYMOUS
   entities (tempid branch in `entity-specs->tx-data`), so every re-import
   of a carrier-bearing file minted a NEW carrier and repointed the host's
   `:mm.memory/frontmatter` ref — stranding the old carrier as an
   ident-less orphan.  The fix mints carriers IDENTFUL
   (`<host-ident>__frontmatter`) so re-import upserts in place: eid stable,
   `:mm.frontmatter/extra` updated, count stays 1.

   HARD CONSTRAINT (SPEC §2 / mirrors the emitter fleet): every test runs
   under `tu/make-test-db-fixture` — an isolated `datomic:mem://` conn
   seeded from the required schema.  NO test touches the shared dev
   transactor.  Real corpus files are READ-ONLY; the round-trips run on
   COMMITTED FIXTURE COPIES under test/resources/codec-fixtures/carrier-reuse/
   (originally authored at scratchpad/carrier-reuse-2026-07-02/scratch/)."
  (:require [clojure.test            :refer :all]
            [clojure.edn             :as edn]
            [clojure.java.io         :as io]
            [datomic.api             :as d]
            [sandbar.codec           :as codec]
            [sandbar.codec.markdown  :as md]
            [sandbar.db.datomic      :as db]
            [sandbar.test-util       :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — isolated in-mem DB + fresh codec registry per test (SPEC §2)

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "carrier-reuse"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Paths + helpers

(def scratch-dir
  "Committed fixture copies (formerly untracked
   scratchpad/carrier-reuse-2026-07-02/scratch/), so fresh
   clones/worktrees run green.  CWD-relative — `lein test` runs from the
   project root (same convention as test/resources/w1-fixtures via
   sandbar.gate.fixture)."
  "test/resources/codec-fixtures/carrier-reuse")

;; Two scratch copies of the SAME TIER-0 discipline file (ground_new_concepts).
;; They differ ONLY in the extras key `at-startup:` — import1 has `high`,
;; import2 has `keystone` — so re-import exercises the in-place UPDATE of
;; `:mm.frontmatter/extra` (SPEC §Tests T1).
(def import1-scratch (str scratch-dir "/ground_import1.md"))
(def import2-scratch (str scratch-dir "/ground_import2.md"))

;; The host rel-path both copies project AS (identity across imports).
;; memory-ident-from-rel-path → :memory.interaction/ground_new_concepts_at_introduction_boundaries
(def ground-rel-path
  "memory/interaction/ground_new_concepts_at_introduction_boundaries.md")

(def expected-host-ident
  :memory.interaction/ground_new_concepts_at_introduction_boundaries)

(def expected-carrier-ident
  :memory.interaction/ground_new_concepts_at_introduction_boundaries__frontmatter)

(defn slurp-scratch [p] (slurp (io/file p)))

(defn transact-doc!
  "Transact the parsed entity-specs for `src`/`rel-path` into the fixture
   DB via the codec's tx-boundary helper.  Returns the memory :db/ident.
   Mirrors the fidelity-test idiom: the fixture has bound
   sandbar.db.datomic/**conn* to the isolated conn, so `(db/conn)` is the
   fixture conn, NOT the shared dev transactor (SPEC §2)."
  [src rel-path]
  (let [specs (md/parse-document src rel-path)]
    @(d/transact (db/conn) (md/entity-specs->tx-data specs))
    (:db/ident (first specs))))

(defn frontmatter-instance-count
  "Count :mm/Frontmatter instances currently in the fixture DB."
  []
  (or (ffirst (d/q '[:find (count ?e)
                     :where [?e :dt/type :mm/Frontmatter]]
                   (db/db)))
      0))

(defn resolve-carrier-ent
  "Resolve the carrier ENTITY reachable from host `host-ident` via
   :mm.memory/frontmatter.

   IMPORTANT (Datomic ref-to-ident semantics, confirmed on the fixture):
   a non-component ref whose TARGET carries a `:db/ident` resolves — via
   `d/entity` attribute lookup — to the target's IDENT KEYWORD, not to an
   EntityMap.  Since identful carriers now carry
   `<host-ident>__frontmatter`, `(:mm.memory/frontmatter host)` yields
   that keyword; we re-resolve it through `db/entity` to reach the carrier
   entity.  For the anonymous-carrier fallback (T3) the ref value is an
   EntityMap already, so `db/entity` is an identity pass-through there."
  [host-ident]
  (some-> (:mm.memory/frontmatter (db/entity host-ident))
          db/entity))

(defn host-carrier-eid
  "Resolve the carrier EID reachable from host `host-ident` via
   :mm.memory/frontmatter (see `resolve-carrier-ent`)."
  [host-ident]
  (:db/id (resolve-carrier-ent host-ident)))

(defn carrier-extra-edn
  "Read + parse the carrier's :mm.frontmatter/extra EDN payload for the
   given host."
  [host-ident]
  (some-> (resolve-carrier-ent host-ident)
          :mm.frontmatter/extra
          edn/read-string))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T1 — THE POINT: re-import upserts the carrier in place (SPEC §Tests T1)

(deftest t1-reimport-upserts-carrier-in-place
  (testing "carrier is minted on first import (extras present → carrier)"
    (let [src1  (slurp-scratch import1-scratch)
          ident (transact-doc! src1 ground-rel-path)]
      (is (= expected-host-ident ident)
          "host ident derived from rel-path")
      (is (= 1 (frontmatter-instance-count))
          "exactly one :mm/Frontmatter instance after first import")
      (let [eid1 (host-carrier-eid ident)]
        (is (some? eid1) "host's :mm.memory/frontmatter carrier resolves to an eid")

        (testing "extras reflects the FIRST import (at-startup: high)"
          (is (= "high"
                 (get-in (carrier-extra-edn ident) [:extras "at-startup" :val]))
              "carrier :val for at-startup is 'high' after import1"))

        (testing "SECOND import (at-startup: keystone) upserts the SAME carrier"
          (let [src2   (slurp-scratch import2-scratch)
                ident2 (transact-doc! src2 ground-rel-path)
                eid2   (host-carrier-eid ident2)]
            (is (= expected-host-ident ident2)
                "same host ident on re-import")

            (testing "count stays 1 — NO orphan minted"
              (is (= 1 (frontmatter-instance-count))
                  "still exactly one :mm/Frontmatter instance after re-import"))

            (testing "carrier EID is STABLE across imports"
              (is (= eid1 eid2)
                  (str "carrier eid changed across re-import: " eid1 " -> " eid2)))

            (testing ":mm.frontmatter/extra reflects the SECOND import"
              (is (= "keystone"
                     (get-in (carrier-extra-edn ident2) [:extras "at-startup" :val]))
                  "carrier updated in place — :val for at-startup is now 'keystone'"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T2 — carrier ident equals <host-ident>__frontmatter exactly (SPEC §Tests T2)

(deftest t2-carrier-ident-is-derived-from-host
  (testing "the pure derivation helper"
    (is (= expected-carrier-ident
           (md/carrier-ident-for-host expected-host-ident))
        "carrier-ident-for-host appends __frontmatter to the host ident")
    (is (= :memory.git/no_commit_signing__frontmatter
           (md/carrier-ident-for-host :memory.git/no_commit_signing))
        "matches the SPEC worked example"))

  (testing "the minted carrier in the parse output carries that exact ident"
    (let [src   (slurp-scratch import1-scratch)
          specs (md/parse-document src ground-rel-path)
          host  (first specs)
          carrier (:mm.memory/frontmatter host)]
      (is (= expected-carrier-ident (:db/ident carrier))
          "parsed carrier :db/ident equals <host-ident>__frontmatter")))

  (testing "after transact the carrier is addressable by that ident"
    (let [src   (slurp-scratch import1-scratch)
          _     (transact-doc! src ground-rel-path)
          ent   (db/entity expected-carrier-ident)]
      (is (some? (:db/id ent)) "carrier resolvable by its derived ident")
      ;; :dt/type is a ref to the :mm/Frontmatter class entity, which
      ;; itself carries :db/ident :mm/Frontmatter — so Datomic's ref-to-
      ;; ident resolution returns the keyword :mm/Frontmatter directly.
      (is (= :mm/Frontmatter (:dt/type ent))
          "the ident-addressed entity is a :mm/Frontmatter"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T3 — identless-host fallback still works (tempid path; no crash) (SPEC §Tests T3)

(deftest t3-identless-host-falls-back-to-anonymous-carrier
  (testing "carrier-ident-for-host returns nil for a nil/identless host"
    (is (nil? (md/carrier-ident-for-host nil))
        "nil host → no derived ident")
    (is (nil? (md/carrier-ident-for-host "not-a-keyword"))
        "non-keyword host → no derived ident"))

  (testing "frontmatter->slots with NO host-ident mints an ANONYMOUS carrier"
    (let [src   (slurp-scratch import1-scratch)
          ;; Direct codec parse WITHOUT :host-ident — the identless-host path.
          entity (codec/parse src {:class :mm/Memory})
          carrier (:mm.memory/frontmatter entity)]
      (is (some? carrier) "extras present → carrier still attached")
      (is (not (contains? carrier :db/ident))
          "anonymous carrier carries NO :db/ident (fallback path)")))

  (testing "entity-specs->tx-data assigns a tempid to the anonymous carrier (no crash)"
    (let [src   (slurp-scratch import1-scratch)
          ;; Build a host-spec with an anonymous carrier (no :db/ident on it),
          ;; simulating an identless host: strip the carrier's ident.
          entity (codec/parse src {:class :mm/Memory})
          host   (-> entity
                     (assoc :db/ident :memory.scratch/anon-host)
                     (assoc :mm.memory/rel-path ground-rel-path)
                     (update :mm.memory/frontmatter dissoc :db/ident))
          tx     (md/entity-specs->tx-data [host])
          carrier-in-tx (:mm.memory/frontmatter (first tx))]
      (is (map? carrier-in-tx) "carrier still a nested map in tx-data")
      (is (contains? carrier-in-tx :db/id)
          "anonymous carrier got a stable string tempid (fallback branch)")
      (is (string? (:db/id carrier-in-tx))
          "the fallback :db/id is a string tempid")
      (testing "the fallback tx transacts cleanly (no :db.error/invalid-nested-entity)"
        (is (some? @(d/transact (db/conn) tx))
            "anonymous-carrier tx-data commits without throwing")))))
