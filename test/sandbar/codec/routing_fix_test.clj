(ns sandbar.codec.routing-fix-test
  "D2 routing-fix receipts — canonical ident derivation + unprefixed
   `:mm.memory/rel-path` at parse.  Per
   scratchpad/routing-fix-2026-07-02/SPEC.md (§The change 1-3, §Tests T1-T4)
   + D2 of decisions/c8_ratification_batch_d1_d9_plus_defaults_all_approved_2026_07_02.md
   (Dan-ratified 2026-07-02) + the drafted-but-unapplied fix from
   scratchpad/reconcile-2026-07-02/import-fix-and-loading.md §1.4.

   THE CHANGE (markdown.clj `parse-document`):
   1. Host ident derived via the CANONICAL `rel-path->memory-ident` (was the
      bare `memory-ident-from-rel-path`), so a `:from` anchored at ANY root —
      including `.../memory` — mints a canonical `memory.`-prefixed ident.
      Kills the bare-ident duplicate class
      (bugs/project_import_drops_memory_namespace_prefix_bare_ident_2026_07_01).
   2. Stored `:mm.memory/rel-path` normalized to the UNPREFIXED form
      (`^memory/` stripped), so the reactive sink routes to the REAL corpus
      path (kills the `memory/memory/` junk-twin mis-route for re-imports).
   3. Section + carrier idents stay consistent with the canonical host ident
      under BOTH anchoring shapes (they derive from the passed memory-ident).

   HARD CONSTRAINT (SPEC §HARD CONSTRAINTS 2; mirrors the fidelity + carrier
   fleets): the DB-touching test (T2) runs under `tu/make-test-db-fixture` —
   an isolated `datomic:mem://` conn seeded from required schema.  NO test
   touches the shared dev transactor.  Pure-parse tests (T1/T3/T4) still
   need the fixture because `dt/range-of` etc. resolve through `(db/db)`."
  (:require [clojure.test           :refer :all]
            [datomic.api            :as d]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — isolated in-mem DB + fresh codec registry per test
;; (mirrors fidelity-test / carrier-reuse-test; parse/emit are NOT pure —
;; dt/range-of resolves through (db/db), so the conn MUST be bound first).

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "routing-fix"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(def ^:private simple-src
  "A minimal :mm/Memory (decision) source with one section, reused across
   the anchoring-convergence tests."
  (str "---\n"
       "name: X Decision\n"
       "type: decision\n"
       "---\n"
       "## Context\n\nBody.\n"))

(defn ^:private transact-doc!
  "Parse + transact `src`/`rel-path` into the fixture DB via the codec's
   tx-boundary helper.  Returns the host memory :db/ident.  Mirrors the
   fidelity/carrier idiom: the fixture bound `db/**conn*` to the isolated
   conn, so `(db/conn)` is the fixture conn — NOT the shared transactor."
  [src rel-path]
  (let [specs (md/parse-document src rel-path)]
    @(d/transact (db/conn) (md/entity-specs->tx-data specs))
    (:db/ident (first specs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T1 — the two `:from` anchoring shapes converge on canonical ident +
;;       unprefixed stored rel-path.

(deftest t1-anchoring-shapes-converge
  (testing "prefixed root (memory/git/x.md) and unprefixed root (git/x.md)
            both yield the canonical :memory.git/x ident + unprefixed rel-path"
    (let [src        "---\nname: X\n---\n"
          prefixed   (first (md/parse-document src "memory/git/x.md"))
          unprefixed (first (md/parse-document src "git/x.md"))]
      ;; Canonical `memory.`-prefixed ident from BOTH anchoring shapes.
      (is (= :memory.git/x (:db/ident prefixed))
          "memory/git/x.md derives the canonical :memory.git/x ident")
      (is (= :memory.git/x (:db/ident unprefixed))
          "git/x.md derives the SAME canonical :memory.git/x ident")
      (is (= (:db/ident prefixed) (:db/ident unprefixed))
          "the two anchoring shapes converge on one ident")
      ;; Stored rel-path is the UNPREFIXED form for both.
      (is (= "git/x.md" (:mm.memory/rel-path prefixed))
          "prefixed input has its `memory/` prefix stripped for storage")
      (is (= "git/x.md" (:mm.memory/rel-path unprefixed))
          "unprefixed input stored as-is")
      (is (= (:mm.memory/rel-path prefixed) (:mm.memory/rel-path unprefixed))
          "the two anchoring shapes converge on one stored rel-path"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T2 — import-upsert continuity: an entity pre-seeded with the PREFIXED
;;       rel-path form + canonical ident keeps its eid on re-parse/transact
;;       through the new code, and its stored rel-path becomes unprefixed
;;       (the migration semantics, one entity at a time).

(deftest t2-import-upsert-continuity
  (testing "pre-seed with canonical ident + PREFIXED stored rel-path, then
            re-parse/transact through the new code: eid stable (ident upsert)
            + rel-path normalized to unprefixed"
    ;; Seed: canonical ident but the OLD prefixed rel-path form (what the
    ;; pre-fix code stored).  A direct tx, NOT via parse-document.
    (let [host-ident :memory.git/x]
      @(d/transact (db/conn)
                   [{:db/ident host-ident
                     :dt/type  :mm/Memory
                     :mm.memory/name "X"
                     :mm.memory/rel-path "memory/git/x.md"}])
      (let [eid-before (:db/id (db/entity host-ident))
            rp-before  (:mm.memory/rel-path (db/entity host-ident))]
        (is (some? eid-before) "seed entity resolves to an eid")
        (is (= "memory/git/x.md" rp-before)
            "seed carries the OLD prefixed rel-path")
        ;; Re-import the SAME file through the new parse-document.  Ident
        ;; upsert must keep the eid; rel-path must normalize to unprefixed.
        (let [ident-after (transact-doc! "---\nname: X\n---\n" "memory/git/x.md")
              eid-after   (:db/id (db/entity host-ident))
              rp-after    (:mm.memory/rel-path (db/entity host-ident))]
          (is (= host-ident ident-after)
              "re-import derives the same canonical ident")
          (is (= eid-before eid-after)
              (str "eid must be STABLE across re-import (ident upsert): "
                   eid-before " -> " eid-after))
          (is (= "git/x.md" rp-after)
              "stored rel-path migrated to the UNPREFIXED form on re-import"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T3 — digit-dodge: a date-leading filename derives the dodged canonical
;;       ident (consistent with rel-path->memory-ident), and the emit path
;;       inverse-un-dodges (existing fidelity behavior — the full fidelity
;;       suite is the T6 guard; here we prove parse-document routes through
;;       the dodge consistently under both anchoring shapes).

(deftest t3-digit-dodge-consistent-under-both-anchors
  (testing "date-leading filename dodges to the SAME canonical ident that
            rel-path->memory-ident produces, from both anchoring shapes"
    (let [rp-un  "sessions/2026-05-29T0713_x.md"
          rp-pre "memory/sessions/2026-05-29T0713_x.md"
          ;; The canonical converter is the source of truth for the dodge.
          expected (md/rel-path->memory-ident rp-un)]
      ;; Sanity: the dodge fires (name no longer starts with a digit).
      (is (= :memory.sessions/session-2026-05-29T0713_x expected)
          "rel-path->memory-ident applies the P6 digit-dodge")
      (let [from-un  (first (md/parse-document "---\nname: X\n---\n" rp-un))
            from-pre (first (md/parse-document "---\nname: X\n---\n" rp-pre))]
        (is (= expected (:db/ident from-un))
            "unprefixed anchor derives the dodged canonical ident")
        (is (= expected (:db/ident from-pre))
            "prefixed anchor derives the SAME dodged canonical ident")))
    (testing "emit-document inverse-un-dodges: the emitted markdown round-trips
              a digit-leading document (fidelity behavior preserved)"
      (let [src      "---\nname: X\n---\n## Body\n\nText.\n"
            parsed   (md/parse-document src "sessions/2026-05-29T0713_x.md")
            emitted  (md/emit-document parsed)
            reparsed (md/parse-document emitted "sessions/2026-05-29T0713_x.md")]
        (is (= (mapv :db/ident parsed) (mapv :db/ident reparsed))
            "idents stable across the dodge round-trip")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T4 — sections + carrier ident derivation stay consistent with the
;;       canonical host ident under BOTH anchoring shapes.

(deftest t4-sections-and-carrier-derive-from-canonical-ident
  (testing "section idents are canonical-host-anchored under both shapes"
    (let [from-un  (md/parse-document simple-src "decisions/y.md")
          from-pre (md/parse-document simple-src "memory/decisions/y.md")
          host-un  (first from-un)
          sec-un   (second from-un)
          host-pre (first from-pre)
          sec-pre  (second from-pre)]
      (is (= :memory.decisions/y (:db/ident host-un)))
      (is (= :memory.decisions/y (:db/ident host-pre)))
      ;; Section ident = <canonical-host>__<slug>, identical across anchors.
      (is (= :memory.decisions/y__context (:db/ident sec-un))
          "section ident derives from the canonical host (unprefixed anchor)")
      (is (= :memory.decisions/y__context (:db/ident sec-pre))
          "section ident derives from the canonical host (prefixed anchor)")
      (is (= (:db/ident sec-un) (:db/ident sec-pre))
          "section idents converge across anchoring shapes")
      ;; The section's parent + the host's first-section point back to the
      ;; canonical host ident.
      (is (= :memory.decisions/y (:mm.section/parent sec-un)))
      (is (= :memory.decisions/y__context (:mm.memory/first-section host-un)))))
  (testing "carrier ident (<host>__frontmatter) is canonical-host-anchored
            under both shapes when extras are present"
    ;; `extra-key:` is undeclared on :mm/Memory → forces an extras carrier.
    ;; The carrier is attached as a NESTED value under the host's
    ;; `:mm.memory/frontmatter` slot (frontmatter->slots §898-910), not as a
    ;; separate top-level entity-spec — read it off the host entity.
    (let [src (str "---\n"
                   "name: Z\n"
                   "extra-key: some-value\n"
                   "---\n"
                   "Body.\n")
          carrier-of (fn [specs]
                       (:mm.memory/frontmatter (first specs)))
          from-un  (carrier-of (md/parse-document src "decisions/z.md"))
          from-pre (carrier-of (md/parse-document src "memory/decisions/z.md"))]
      (is (some? from-un)  "undeclared key mints an extras carrier (unprefixed)")
      (is (some? from-pre) "undeclared key mints an extras carrier (prefixed)")
      (is (= :memory.decisions/z__frontmatter (:db/ident from-un))
          "carrier ident = <canonical-host>__frontmatter (unprefixed anchor)")
      (is (= :memory.decisions/z__frontmatter (:db/ident from-pre))
          "carrier ident = <canonical-host>__frontmatter (prefixed anchor)")
      (is (= (:db/ident from-un) (:db/ident from-pre))
          "carrier idents converge across anchoring shapes"))))
