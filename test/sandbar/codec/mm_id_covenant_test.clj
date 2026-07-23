(ns sandbar.codec.mm-id-covenant-test
  "D1 `:mm/id` covenant receipts (W1 Phase-0): the `id:` line emitted to
   frontmatter must be READ BACK into the `:mm/id` federation-anchor slot on
   reload, never re-derived.

   `:mm/id` is `:db.unique/identity` and `store.clj/create-memory!` derives it
   ABSENT-ONLY, so an explicitly-parsed id upserts the SAME entity and survives
   a Tempo-C rebuild.  Before this fix the codec dropped the `id:` string on
   parse (A.6 uuid-string guard → extras), so a reconstruct re-derived (or, on
   the direct entity-specs→transact reload path, LOST) the identity.  These
   tests prove the round-trip is now emit-from-slot → read-into-slot.

   HARD CONSTRAINT (mirrors fidelity_test §1.2): every test runs under
   `tu/make-test-db-fixture` — an isolated `datomic:mem://` conn.  parse/emit
   are NOT pure (`dt/range-of` resolves through `(db/db)`), so the fixture MUST
   bind `sandbar.db.datomic/**conn*` before any codec call.  No live store, no
   shared dev transactor."
  (:require [clojure.test           :refer :all]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.identifier     :as ident]
            [sandbar.test-util      :as tu])
  (:import [java.util UUID]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "mm-id-covenant"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

(def rel-path "decisions/mm_id_covenant_fixture.md")

(defn- doc-with-id [^UUID u]
  (str "---\n"
       "name: Covenant Fixture\n"
       "type: decision\n"
       "id: '" u "'\n"
       "---\n"
       "## Context\n\nBody.\n"))

(defn- doc-no-id []
  (str "---\n"
       "name: Covenant Fixture\n"
       "type: decision\n"
       "---\n"
       "## Context\n\nBody.\n"))

(defn- transact-doc!
  "Transact the parsed entity-specs for `src`/`rel-path` into the fixture DB
   via the codec tx-boundary helper (mirrors fidelity_test).  Returns the
   memory :db/ident."
  [src rel]
  (let [specs (md/parse-document src rel)]
    @(d/transact (db/conn) (md/entity-specs->tx-data specs))
    (:db/ident (first specs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parse side — the `id:` line reads back into :mm/id.

(deftest parse-reads-id-into-mm-id
  (testing "parse READS the id: line into :mm/id (not dropped to extras)"
    (let [u   (UUID/randomUUID)
          mem (first (md/parse-document (doc-with-id u) rel-path))]
      (is (= u (:mm/id mem))
          "the emitted id: must round-trip back into the :mm/id slot"))))

(deftest parse-without-id-yields-no-mm-id
  (testing "with no id: line, parse yields no :mm/id — the id is sourced from the file"
    (let [mem (first (md/parse-document (doc-no-id) rel-path))]
      (is (nil? (:mm/id mem))
          "parse must not fabricate a :mm/id when the file carries none"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reload side (THE killer) — a Tempo-C rebuild READS the stored id, does NOT
;; re-derive it.  The chosen id is deliberately a random UUID that differs from
;; what v5 derivation would produce, so equality proves READ, not derive.

(deftest reload-reads-not-rederives
  (testing "reloaded entity carries the FILE's id, never the re-derived one"
    (let [u       (UUID/randomUUID)
          derived (ident/ident-uuid
                    (:db/ident (first (md/parse-document (doc-no-id) rel-path))))]
      (is (not= u derived)
          "test premise: the chosen file id differs from the derived id")
      (let [ident (transact-doc! (doc-with-id u) rel-path)
            ent   (db/entity ident)]
        (is (= u (:mm/id ent))
            "reload must READ the stored :mm/id (never re-derive/lose it)")
        (is (not= derived (:mm/id ent))
            "reloaded id must NOT be the v5-re-derived value")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Full parse→emit→parse round-trip preserves the id in the slot.

(deftest round-trip-preserves-id
  (testing "id survives parse→emit→parse in the :mm/id slot"
    (let [u        (UUID/randomUUID)
          emitted  (md/emit-document (md/parse-document (doc-with-id u) rel-path))
          mem2     (first (md/parse-document emitted rel-path))]
      (is (str/includes? emitted (str "id: '" u "'"))
          "emit renders the id as a plain single-quoted line")
      (is (= u (:mm/id mem2))
          "id reads back into :mm/id after a full round-trip"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The engineered regression case: id + OTHER extras (carrier present).  The
;; id must ride the slot (trailing emit) WITHOUT being dropped and WITHOUT
;; double-emitting — this is exactly what the emit-guard change protects.

(deftest id-with-extras-round-trips-exactly-once
  (testing "id + an undeclared long-tail key: exactly one id: line, extras preserved, id read back"
    (let [u   (UUID/randomUUID)
          src (str "---\n"
                   "name: Covenant + extras\n"
                   "type: decision\n"
                   "at-startup: true\n"          ;; undeclared long-tail → extras carrier
                   "id: '" u "'\n"
                   "---\n"
                   "## Context\n\nBody.\n")
          emitted (md/emit-document (md/parse-document src rel-path))
          id-lines (re-seq (re-pattern (str "(?m)^id: '" u "'$")) emitted)]
      (is (= 1 (count id-lines))
          (str "exactly one id: line expected (no drop, no double-emit); emitted:\n" emitted))
      (is (str/includes? emitted "at-startup: true")
          "the undeclared extras key must survive the round-trip")
      (is (= u (:mm/id (first (md/parse-document emitted rel-path))))
          "id still reads back into :mm/id when a carrier is present"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A non-UUID / legacy-mangled id: value must NOT be coerced into :mm/id; it
;; falls through to the extras carrier and round-trips byte-faithfully (policy
;; (c)) — no crash, no fabricated identity.

(deftest non-uuid-id-falls-to-extras
  (testing "a non-UUID id: value is not coerced; it rides extras byte-faithfully"
    (let [src (str "---\n"
                   "name: Legacy mangle\n"
                   "type: decision\n"
                   "id: not-a-uuid\n"
                   "---\n"
                   "## Context\n\nBody.\n")
          mem (first (md/parse-document src rel-path))]
      (is (nil? (:mm/id mem))
          "a non-UUID id: must not become a :mm/id")
      (is (some? (:mm.memory/frontmatter mem))
          "a non-UUID id: is captured in the extras carrier")
      (let [emitted (md/emit-document (md/parse-document src rel-path))]
        (is (str/includes? emitted "id: not-a-uuid")
            "non-UUID id: round-trips byte-faithfully via extras")))))
