(ns sandbar.codec.rule-shadow-collision-test
  "Regression receipts for the :mm/Rule derived-projection-lag emit defect
   (bugs/mm_rule_derived_projection_lag_stale_frontmatter_emit_reimport_
   regression_risk_2026_07_03).

   THE DEFECT: a :mm/Rule instance persists BOTH the canonical inherited
   :mm.memory/{name,description,body-raw} AND a legacy shadow
   :mm.rule/{name,description,body-raw} (doubly-declared attributes whose
   codec position β.2.2 Phase I moved to :mm.memory/* but whose stored
   values were never dropped).  `entity.update` writes the canonical slots;
   the shadow slots lag.  On emit both families map to the SAME frontmatter
   key (`name:` / `description:` / `body-raw:`) via `slot->frontmatter-key`,
   and — because the shadow slots are absent from :mm/Rule's
   `:dt/codec-slot-order` — they append as emit `extras` AFTER the canonical
   slot and CLOBBER it with the STALE value.  :mm.rule/body-raw is not the
   body-slot, so it additionally leaks a whole stale `body-raw:` frontmatter
   block.  Fix (smallest correct): the emit method drops shadow slots that
   collide with a class-EFFECTIVE slot via `strip-shadow-collisions`
   (metamodel-introspective — NO hardcoded :mm.rule/* knowledge), so the
   authoritative :mm.memory/* value wins.  :mm/Protocol has no such shadow
   and is the no-regression control.

   HARD CONSTRAINT (mirrors fidelity / routing-fix / carrier-reuse fleets):
   the DB-touching tests run under `tu/make-test-db-fixture` — an isolated
   `datomic:mem://` conn.  NO test touches the shared dev transactor.  Emit
   is NOT pure (`dt/slots-of` / `dt/range-of` resolve through `(db/db)`), so
   the conn MUST be bound first."
  (:require [clojure.test           :refer :all]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.projection     :as pg]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — isolated in-mem DB + fresh codec registry per test.

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "rule-shadow-collision"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- fm-line-for
  "Return the emitted frontmatter line whose key is `k` (a leading
   `<k>:` at column 0), or nil.  Scans only the pre-fence frontmatter
   block so a body mention of the key never matches."
  [emitted k]
  (let [fence (str/index-of emitted "\n---\n")
        fm    (if fence (subs emitted 0 fence) emitted)]
    (->> (str/split-lines fm)
         (some (fn [line]
                 (when (str/starts-with? line (str k ":")) line))))))

(defn- emit-db-entity
  "Emit the DB entity at `ident` through the EXACT DB→FS path the reactive
   sink uses (`pg/realize-and-emit-entity` on the lazy datomic Entity — NOT
   `(into {} ent)`, which would drop `:db/id` and defeat realize-with's
   eid-dedup; per the fidelity-test idiom)."
  [ident]
  (pg/realize-and-emit-entity (db/entity ident)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The exact round-trip the bug breaks — new canonical values under stale
;; shadow slots.

(def ^:private new-name "Ground New Concepts — Run sandbar_ground (MCP-primary)")
(def ^:private old-name "Ground New Concepts — Run /memory-ground (RETIRED)")
(def ^:private new-desc "Canonical description: run sandbar_ground first (MCP-primary).")
(def ^:private old-desc "Stale shadow description: run /memory-ground first.")
(def ^:private new-body "## Body\n\nRun `mcp__sandbar__sandbar_ground` first.\n")
(def ^:private old-body "## Body\n\nRun `/memory-ground` first.\n")

(defn- seed-rule!
  "Seed a :mm/Rule with NEW canonical :mm.memory/* AND STALE shadow
   :mm.rule/* name/description/body-raw — the exact live shape (a direct tx;
   `parse-document` never mints the shadow slots, so the defect can only be
   reproduced by seeding them explicitly, matching the corpus's post-update
   entities).  No sections → emit takes the single-entity `codec/emit` path."
  [ident]
  @(d/transact (db/conn)
               [{:db/ident              ident
                 :dt/type               :mm/Rule
                 :mm.memory/rel-path    "rules/shadow_probe.md"
                 :mm.memory/name        new-name
                 :mm.memory/description new-desc
                 :mm.memory/body-raw    new-body
                 :mm.rule/rule-type     :rule
                 :mm.rule/event         :user-prompt-submit
                 :mm.rule/enforcement   :warn
                 ;; The STALE shadows — must NOT reach the wire form.
                 :mm.rule/name          old-name
                 :mm.rule/description   old-desc
                 :mm.rule/body-raw      old-body}])
  ident)

(deftest rule-emit-uses-canonical-not-stale-shadow
  (testing "emit serializes the NEW :mm.memory/* values, never the stale
            :mm.rule/* shadows (the derived-projection-lag defect)"
    (let [ident   :memory.rules/shadow_probe
          _       (seed-rule! ident)
          emitted (emit-db-entity ident)]
      (is (some? emitted) "realize-and-emit-entity returned nil for the rule")
      ;; name: / description: reflect the canonical NEW values.
      (let [name-line (fm-line-for emitted "name")
            desc-line (fm-line-for emitted "description")]
        (is (some? name-line) "emitted frontmatter has a name: line")
        (is (str/includes? name-line new-name)
            (str "name: must carry the canonical value, got: " (pr-str name-line)))
        (is (not (str/includes? (or name-line "") old-name))
            (str "name: leaked the STALE shadow value: " (pr-str name-line)))
        (is (some? desc-line) "emitted frontmatter has a description: line")
        (is (str/includes? desc-line new-desc)
            (str "description: must carry the canonical value, got: " (pr-str desc-line)))
        (is (not (str/includes? (or desc-line "") old-desc))
            (str "description: leaked the STALE shadow value: " (pr-str desc-line))))
      ;; The stale :mm.rule/body-raw must NOT leak a body-raw: frontmatter
      ;; block (it is not the body-slot; the canonical body rides post-fence).
      (is (nil? (fm-line-for emitted "body-raw"))
          (str "a stale body-raw: frontmatter block leaked:\n"
               (pr-str (fm-line-for emitted "body-raw"))))
      ;; The post-fence body is the canonical NEW body, never the stale one.
      (is (str/includes? emitted "mcp__sandbar__sandbar_ground")
          "post-fence body must carry the canonical body text")
      (is (not (str/includes? emitted "/memory-ground"))
          "post-fence body must NOT carry the stale shadow body text")
      ;; The rule's own dispatch slots (effective, no collision) still emit.
      (is (some? (fm-line-for emitted "event"))
          "rule dispatch slot event: must survive the shadow-strip")
      (is (some? (fm-line-for emitted "enforcement"))
          "rule dispatch slot enforcement: must survive the shadow-strip"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; No-regression control — :mm/Protocol has no shadow family and must still
;; emit its canonical name/description faithfully.

(deftest protocol-control-emits-canonical-name
  (testing ":mm/Protocol (no :mm.protocol/* shadow) still emits name: /
            description: from :mm.memory/* — no regression from the strip"
    (let [ident   :memory.protocol/shadow_control
          p-name  "Session Orientation Protocol"
          p-desc  "Pick up where the last session left off."]
      @(d/transact (db/conn)
                   [{:db/ident              ident
                     :dt/type               :mm/Protocol
                     :mm.memory/rel-path    "protocol/shadow_control.md"
                     :mm.memory/name        p-name
                     :mm.memory/description p-desc
                     :mm.memory/body-raw    "## Body\n\nProtocol body.\n"}])
      (let [emitted   (emit-db-entity ident)
            name-line (fm-line-for emitted "name")
            desc-line (fm-line-for emitted "description")]
        (is (some? emitted) "realize-and-emit-entity returned nil for the protocol")
        (is (and name-line (str/includes? name-line p-name))
            (str "protocol name: line wrong: " (pr-str name-line)))
        (is (and desc-line (str/includes? desc-line p-desc))
            (str "protocol description: line wrong: " (pr-str desc-line)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FS→DB direction — re-parsing the emitted rule file yields the UPDATED
;; :mm.memory/* (no re-import regression — the guard the bug flagged).

(deftest reparse-of-emitted-rule-yields-canonical-values
  (testing "re-parse of the emitted rule markdown lands the NEW canonical
            :mm.memory/{name,description,body-raw} — an FS→DB re-import no
            longer regresses the update"
    (let [ident    :memory.rules/shadow_probe
          _        (seed-rule! ident)
          emitted  (emit-db-entity ident)
          reparsed (codec/parse emitted {:format :markdown :class :mm/Rule})]
      (is (= new-name (:mm.memory/name reparsed))
          (str ":mm.memory/name after re-parse: " (pr-str (:mm.memory/name reparsed))))
      (is (= new-desc (:mm.memory/description reparsed))
          (str ":mm.memory/description after re-parse: "
               (pr-str (:mm.memory/description reparsed))))
      (is (str/includes? (or (:mm.memory/body-raw reparsed) "")
                         "mcp__sandbar__sandbar_ground")
          "re-parsed body-raw must be the canonical body")
      ;; The stale shadow strings must be ABSENT from every re-parsed slot.
      (is (not-any? #(and (string? %) (str/includes? % "/memory-ground"))
                    (vals reparsed))
          "no re-parsed slot may carry the stale shadow text"))))
