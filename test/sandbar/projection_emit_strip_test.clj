(ns sandbar.projection-emit-strip-test
  "Regression tests for the DB->FS emit-path derived-attr leak fixed
   2026-07-10.

   Root cause (empirically verified, NOT the original walker-ancestry
   hypothesis): `sandbar.db.datatype/realize-with` coerced only its SEED
   (keyword/eid -> entity) but not the ref values a walk-fn returns.  A ref
   slot read off a LIVE `db/entity` whose target carries a `:db/ident`
   reads back as the IDENT KEYWORD (not an Entity), so `mm-walker`'s
   `[:section-ident]` was dropped by `(:db/id <keyword>) => nil` — a
   sectioned memory realized to `[memory]` only, `sections-present?` went
   false, and `realize-and-emit-entity` took the raw-`codec/emit` branch.
   That branch's exclusion list (the MarkdownCodec `emit` impl) drops
   `:db/*` + `:mm.memory/rel-path` but NOT `:mm.memory/first-section`, so
   `first-section:` leaked into emitted frontmatter.

   These tests exercise the LIVE-ENTITY path (`db/entity`) that the
   reactive fs-projection sink actually feeds — the pre-existing
   projection-test suite only ever hands `project-graph` plain entity-spec
   MAPS with keyword `:dt/type`, which is why it was blind to this.

   Two fixes are under regression here:
     (1) realize-with coerces walked ref-locators to Entities (dt layer),
         so sectioned memories route through `md/emit-document`.
     (2) belt-and-braces strip parity: `realize-and-emit-entity`'s two
         non-emit-document branches dissoc the derived attrs via the shared
         `md/strip-derived-memory-attrs` before `codec/emit`.

   Kept in a SEPARATE file from projection_test.clj (which is being edited
   on a sibling branch) to avoid a merge collision.

   Per observations/live_sink_emits_derived_first_section_for_subclass_-
   memorials_regenerating_debris_130_files_2026_07_10 +
   decisions/codec_14_rescope_zero_product_defects_test_and_fixture_fix_-
   plan_2026_07_10."
  (:require [clojure.test           :refer :all]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.projection     :as pg]
            [sandbar.codec.markdown :as md]
            [sandbar.test-util      :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "projection-emit-strip"
                                              :auth?     false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(def ^:private test-uuid #uuid "a1b2c3d4-0000-4000-8000-000000000001")

(defn- rel-path-for [ident]
  (str (namespace ident) "/" (name ident) ".md"))

(defn- transact-sectioned!
  "Transact a `class-ident` memory with TWO top-level sections + a `:mm/id`
   identity slot, using string tempids for the intra-tx cross-refs so both
   directions resolve in one tx.  Returns the LIVE `db/entity` for the
   memory — the exact shape the reactive sink hands `realize-and-emit-entity`."
  [class-ident mem-ident]
  (let [ctx-ident (keyword (namespace mem-ident) (str (name mem-ident) "__context"))
        dec-ident (keyword (namespace mem-ident) (str (name mem-ident) "__decision"))]
    @(d/transact (db/conn)
       [{:db/id "mem"
         :dt/type class-ident
         :db/ident mem-ident
         :mm/id test-uuid
         :mm.memory/rel-path (rel-path-for mem-ident)
         :mm.memory/name "Strip Regression Mem"
         :mm.memory/first-section "ctx"
         :mm.memory/body-raw ""}
        {:db/id "ctx"
         :dt/type :mm/Section
         :db/ident ctx-ident
         :mm.section/heading "Context"
         :mm.section/heading-level 2
         :mm.section/parent "mem"
         :mm.section/next-sibling "dec"
         :mm.section/body "Context body.\n"}
        {:db/id "dec"
         :dt/type :mm/Section
         :db/ident dec-ident
         :mm.section/heading "Decision"
         :mm.section/heading-level 2
         :mm.section/parent "mem"
         :mm.section/previous-sibling "ctx"
         :mm.section/body "Decision body.\n"}])
    (db/entity mem-ident)))

(defn- transact-section-less!
  "Transact a `class-ident` memory with NO sections + a `:mm/id`.  Returns
   the live entity."
  [class-ident mem-ident]
  @(d/transact (db/conn)
     [{:dt/type class-ident
       :db/ident mem-ident
       :mm/id test-uuid
       :mm.memory/rel-path (rel-path-for mem-ident)
       :mm.memory/name "Section-less Mem"
       :mm.memory/body-raw "Just a body paragraph.\n"}])
  (db/entity mem-ident))

(defn- frontmatter-block
  "Extract the YAML frontmatter block (text between the first two `---`
   delimiter lines) from emitted markdown, or \"\" if none."
  [out]
  (let [lines (str/split-lines out)]
    (if (= "---" (first lines))
      (let [tail (rest lines)
            end  (first (keep-indexed (fn [i l] (when (= "---" l) i)) tail))]
        (if end (str/join "\n" (take end tail)) ""))
      "")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (1) Sectioned :mm/Plan (SUBCLASS) — the headline regression

(deftest sectioned-plan-subclass-no-derived-leak-retains-id
  (md/register!)
  (let [ent (transact-sectioned! :mm/Plan :plans/strip_regression_plan)
        out (pg/realize-and-emit-entity ent)
        fm  (frontmatter-block out)]
    ;; The leak this whole arc is about:
    (is (not (re-find #"(?m)^first-section:" fm))
        (str "first-section: leaked into frontmatter:\n" fm))
    (is (not (re-find #"(?m)^rel-path:" fm))
        (str "rel-path: leaked into frontmatter:\n" fm))
    (is (not (re-find #"(?m)^ident:" fm))
        (str "db/ident leaked into frontmatter:\n" fm))
    ;; id: covenant — must SURVIVE the strip (NOT in derived-memory-attrs):
    (is (re-find #"(?m)^id: " fm)
        (str "id: line missing (covenant break):\n" fm))
    (is (str/includes? fm (str test-uuid))
        "id: line carries the entity's :mm/id UUID")
    ;; proof the sectioned SUBCLASS routed through emit-document (body has
    ;; the reconstructed section headings, not a leaked frontmatter slot):
    (is (str/includes? out "## Context") "Context section in body")
    (is (str/includes? out "## Decision") "Decision section in body")
    (is (str/includes? out "Context body.") "Context body reconstructed")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (2) Section-less memory — belt-and-braces section-less branch + id: covenant

(deftest section-less-memory-no-derived-leak-retains-id
  (md/register!)
  (let [ent (transact-section-less! :mm/Decision :decisions/strip_regression_dec)
        out (pg/realize-and-emit-entity ent)
        fm  (frontmatter-block out)]
    (is (not (re-find #"(?m)^first-section:" fm))
        (str "first-section: leaked:\n" fm))
    (is (not (re-find #"(?m)^rel-path:" fm))
        (str "rel-path: leaked:\n" fm))
    (is (not (re-find #"(?m)^ident:" fm))
        (str "db/ident leaked:\n" fm))
    (is (re-find #"(?m)^id: " fm)
        (str "id: line missing:\n" fm))
    (is (str/includes? out "Just a body paragraph.")
        "body-raw preserved on the section-less path")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (3) No-walker class.  :mm/Tag is :dt/subclass-of :dt/Resource ONLY (its
;;     full ancestry is `(:dt/Resource)` — NOT under :mm/Memory, unlike
;;     :mm/Actor/:mm/Context/:mm/Shape which reach :mm/Memory via :mm/Meta),
;;     so walker-for-class returns nil and realize-and-emit-entity takes the
;;     :else branch.  A synthetic stray :mm.memory/first-section proves the
;;     :else strip is load-bearing: pre-fix that branch called `codec/emit`
;;     on the UNstripped entity and leaked `first-section:` (the one derived
;;     attr the codec's own emit exclusion list does NOT drop).

(deftest no-walker-class-else-branch-strips-derived-attr
  (md/register!)
  ;; sanity: this class really does hit the :else branch
  (is (nil? (pg/walker-for-class :mm/Tag)) ":mm/Tag has no walker")
  (is (= :markdown (dt/native-codec-of-class :mm/Tag)) ":mm/Tag emits markdown")
  @(d/transact (db/conn)
     [{:db/id "sec"
       :dt/type :mm/Section
       :db/ident :tags/strip_tag__stray
       :mm.section/heading "Stray"
       :mm.section/heading-level 2
       :mm.section/body "stray.\n"}
      {:db/id "tag"
       :dt/type :mm/Tag
       :db/ident :tags/strip_tag
       :mm.tag/value "strip-regression-tag"
       ;; synthetic derived attr on a no-walker entity — exercises the
       ;; :else-branch strip (would leak `first-section:` pre-fix):
       :mm.memory/first-section "sec"}])
  (let [ent (db/entity :tags/strip_tag)
        out (pg/realize-and-emit-entity ent)
        fm  (frontmatter-block out)]
    (is (some? out) "no-walker class still emits")
    (is (not (re-find #"(?m)^first-section:" fm))
        (str "first-section: leaked on the :else branch:\n" fm))
    (is (str/includes? fm "value: strip-regression-tag")
        "tag's real slots still emit")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (4) Sectioned :mm/Memory (BASE class) still round-trips through
;;     emit-document — the fix must not privilege subclasses.

(deftest sectioned-base-memory-round-trips-through-emit-document
  (md/register!)
  (let [ent (transact-sectioned! :mm/Memory :notes/strip_regression_base)
        out (pg/realize-and-emit-entity ent)
        fm  (frontmatter-block out)]
    (is (not (re-find #"(?m)^first-section:" fm))
        (str "first-section: leaked for base :mm/Memory:\n" fm))
    (is (re-find #"(?m)^id: " fm)
        (str "id: line missing for base :mm/Memory:\n" fm))
    (is (str/includes? out "## Context") "base-class sections routed through emit-document")
    (is (str/includes? out "## Decision") "base-class sibling section walked")
    (is (str/includes? out "Decision body.") "base-class section body reconstructed")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (5) Direct unit test of the shared strip helper — removes every derived
;;     attr but RETAINS the identity slots (:mm/id covenant).

(deftest strip-derived-memory-attrs-removes-derived-keeps-identity
  (let [m {:dt/type :mm/Plan
           :db/id 12345
           :db/ident :plans/x
           :mm.memory/rel-path "plans/x.md"
           :mm.memory/first-section :plans/x__ctx
           :mm.memory/name "X"
           :mm/id test-uuid
           :mm.memory/body-raw "body"}
        stripped (md/strip-derived-memory-attrs m)]
    (is (not (contains? stripped :mm.memory/first-section)) "first-section stripped")
    (is (not (contains? stripped :mm.memory/rel-path)) "rel-path stripped")
    (is (not (contains? stripped :db/ident)) ":db/ident stripped")
    (is (not (contains? stripped :db/id)) ":db/id stripped")
    (is (= test-uuid (:mm/id stripped)) ":mm/id RETAINED (covenant)")
    (is (= :mm/Plan (:dt/type stripped)) ":dt/type retained (emit requires it)")
    (is (= "X" (:mm.memory/name stripped)) "regular slots retained")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (6) Root-cause pin at the dt layer: realize-with must WALK a live section
;;     chain whose refs read back as ident keywords (independent of the emit
;;     strip — fails if the realize-with coercion is reverted).

(deftest realize-with-follows-ident-keyword-refs-on-live-entity
  (md/register!)
  (let [ent (transact-sectioned! :mm/Plan :plans/realize_walk_plan)]
    ;; first-section reads back as a bare ident keyword off the live entity:
    (is (keyword? (:mm.memory/first-section ent))
        "precondition: ref reads back as ident keyword, not an Entity")
    (let [realized (dt/realize-with ent pg/mm-walker)]
      (is (= 3 (count realized))
          (str "expected memory + 2 sections walked, got types: "
               (pr-str (mapv :dt/type realized))))
      (is (= [:mm/Plan :mm/Section :mm/Section] (mapv :dt/type realized))
          "seed + both sections realized in BFS order"))))
