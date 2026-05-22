#!/usr/bin/env bb
;; etc/apply_predicate_migration.bb
;;
;; Apply the full predicate-migration plan to schema/mm.edn: for each
;; :mm.memory/<slot> declaration matching the plan, rewrite its
;; `:dt/type :dt/Property` to `:dt/type :dt/<IntersectionClass>`.
;;
;; Plan source: etc/migration_plan_predicates.bb in the corpus repo
;; (run there; copy the resulting EDN map into the `migration-plan`
;; var below).  Hardcoded here for traceability in the sandbar commit.
;;
;; Idempotent: applying twice produces the same output.  Skips slots
;; whose :dt/type is already at the target intersection class.
;;
;; Per ADR sandbar_rdfs_entailment_via_datomic_native_datalog_rules_
;; with_swclos_style_metaclass_property_characteristics_2026_05_21.

(require '[clojure.string :as str])

(def schema-path "schema/mm.edn")

(def migration-plan
  "Map { :mm.memory/<slot> :dt/<IntersectionClass> } per the corpus
   characteristics audit."
  {:mm.memory/about                  :dt/DirectedBinaryRelationProperty
   :mm.memory/ancestor-of            :dt/StrictPartialOrderProperty
   :mm.memory/applies-in             :dt/DirectedBinaryRelationProperty
   :mm.memory/belongs-to             :dt/DirectedBinaryRelationProperty
   :mm.memory/bypasses               :dt/DirectedBinaryRelationProperty
   :mm.memory/cites                  :dt/DirectedBinaryRelationProperty
   :mm.memory/composes-with          :dt/IrreflexiveSymmetricProperty
   :mm.memory/conforms-to            :dt/DirectedBinaryRelationProperty
   :mm.memory/consumed-by            :dt/StrictPartialOrderProperty
   :mm.memory/consumes               :dt/StrictPartialOrderProperty
   :mm.memory/contradicts            :dt/DirectedBinaryRelationProperty
   :mm.memory/created-by             :dt/DirectedBinaryRelationProperty
   :mm.memory/created-with           :dt/AsymmetricFunctionalIrreflexiveProperty
   :mm.memory/demonstrated-by        :dt/DirectedBinaryRelationProperty
   :mm.memory/demonstrates           :dt/DirectedBinaryRelationProperty
   :mm.memory/derives-from           :dt/DirectedBinaryRelationProperty
   :mm.memory/descends-from          :dt/StrictPartialOrderProperty
   :mm.memory/disagrees-with         :dt/DirectedBinaryRelationProperty
   :mm.memory/documented-by          :dt/DirectedBinaryRelationProperty
   :mm.memory/documents              :dt/DirectedBinaryRelationProperty
   :mm.memory/evidenced-by           :dt/DirectedBinaryRelationProperty
   :mm.memory/evidences              :dt/DirectedBinaryRelationProperty
   :mm.memory/extends                :dt/AntisymmetricStrictOrderProperty
   :mm.memory/generalizes            :dt/AntisymmetricStrictOrderProperty
   :mm.memory/has-beginning          :dt/AsymmetricFunctionalIrreflexiveProperty
   :mm.memory/has-domain             :dt/DirectedBinaryRelationProperty
   :mm.memory/has-end                :dt/AsymmetricFunctionalIrreflexiveProperty
   :mm.memory/has-meta-type          :dt/DirectedBinaryRelationProperty
   :mm.memory/has-part               :dt/AntisymmetricStrictOrderProperty
   :mm.memory/has-participant        :dt/DirectedBinaryRelationProperty
   :mm.memory/has-range              :dt/DirectedBinaryRelationProperty
   :mm.memory/has-reply              :dt/DirectedBinaryRelationProperty
   :mm.memory/implemented-by         :dt/DirectedBinaryRelationProperty
   :mm.memory/implements             :dt/DirectedBinaryRelationProperty
   :mm.memory/informed-by            :dt/DirectedBinaryRelationProperty
   :mm.memory/informs                :dt/DirectedBinaryRelationProperty
   :mm.memory/inherits-from          :dt/StrictPartialOrderProperty
   :mm.memory/instance-of            :dt/FunctionalIrreflexiveProperty
   :mm.memory/is-about-of            :dt/DirectedBinaryRelationProperty
   :mm.memory/is-disagreed-with-by   :dt/DirectedBinaryRelationProperty
   :mm.memory/is-mentioned-by        :dt/DirectedBinaryRelationProperty
   :mm.memory/made                   :dt/DirectedBinaryRelationProperty
   :mm.memory/mentions               :dt/DirectedBinaryRelationProperty
   :mm.memory/motivated-by           :dt/DirectedBinaryRelationProperty
   :mm.memory/motivates              :dt/DirectedBinaryRelationProperty
   :mm.memory/next-by-date           :dt/DirectedBinaryRelationProperty
   :mm.memory/part-of                :dt/AntisymmetricStrictOrderProperty
   :mm.memory/previous-by-date       :dt/DirectedBinaryRelationProperty
   :mm.memory/provides-method-for    :dt/DirectedBinaryRelationProperty
   :mm.memory/realized-by            :dt/DirectedBinaryRelationProperty
   :mm.memory/realizes               :dt/DirectedBinaryRelationProperty
   :mm.memory/refines                :dt/AntisymmetricStrictOrderProperty
   :mm.memory/related                :dt/IrreflexiveSymmetricProperty
   :mm.memory/reply-of               :dt/DirectedBinaryRelationProperty
   :mm.memory/requires               :dt/StrictPartialOrderProperty
   :mm.memory/runs-in-context        :dt/DirectedBinaryRelationProperty
   :mm.memory/specializes            :dt/AntisymmetricStrictOrderProperty
   :mm.memory/superseded-by          :dt/StrictPartialOrderProperty
   :mm.memory/supersedes             :dt/StrictPartialOrderProperty
   :mm.memory/triggered-by           :dt/DirectedBinaryRelationProperty
   :mm.memory/uses-method-in         :dt/DirectedBinaryRelationProperty})

(def slot-name->target
  "Map slot-name (string, e.g. \"descends-from\") → target intersection class.
   Derived from migration-plan; lets a `:mm.actor/runs-in-context` substrate
   slot match the corpus predicate `runs-in-context.md` even though the plan
   key is `:mm.memory/runs-in-context`."
  (into {} (for [[k v] migration-plan] [(name k) v])))

(defn migrate-content
  "Apply the migration plan to file content; return [new-content stats].

   Line-by-line scan; matches `:mm.<any-namespace>/<slot-name>` against the
   slot-name→target map.  The same corpus predicate may be specialized into
   multiple substrate-side namespaces (e.g., `:mm.rule/applies-in` +
   `:mm.actor/belongs-to`); all matching variants get the same intersection
   class because the characteristic semantics apply uniformly per the
   corpus declaration."
  [content]
  (let [stats (atom {:migrated 0 :already-done 0 :not-found 0})
        found-slots (atom #{})
        lines (str/split-lines content)
        new-lines
        (loop [[line & rest] lines
               ;; current-slot-name + current-target track the MOST RECENTLY
               ;; SEEN :db/ident.  CRITICAL: when a new :db/ident appears, the
               ;; tracking RESETS to that ident's values — even if the new ident
               ;; isn't in the plan (in which case current-target becomes nil
               ;; and subsequent :dt/type lines won't trigger migration).
               ;; A previous bug where new identifiers OR'd the new + old
               ;; tracking caused entirely unrelated entities to be migrated.
               current-slot-name nil
               current-target nil
               acc []]
          (if-not line
            acc
            (let [ident-match (re-find #":db/ident\s+:mm\.[\w-]+/([\w-]+)" line)
                  new-slot-name (when ident-match (second ident-match))
                  new-target (when new-slot-name (get slot-name->target new-slot-name))
                  ;; When this line has a new ident, RESET tracking; otherwise propagate.
                  effective-name (if new-slot-name new-slot-name current-slot-name)
                  effective-target (if new-slot-name new-target current-target)
                  ;; Single-line shape: `{:db/ident :mm.x/y :dt/type :dt/Property ...}`
                  ;; on the same line.  Handle separately.
                  single-line? (and new-slot-name new-target
                                    (re-find #":dt/type\s+" line))
                  ;; Multi-line shape: `:dt/type :dt/...` appears on its own line.
                  is-dt-type-line? (re-find #"^\s+:dt/type\s+" line)]
              (cond
                ;; Single-line entity with the target's slot-name + :dt/type :dt/Property
                single-line?
                (cond
                  (re-find (re-pattern (str ":dt/type\\s+" (str new-target) "\\b")) line)
                  (do (swap! stats update :already-done inc)
                      (swap! found-slots conj new-slot-name)
                      (recur rest new-slot-name new-target (conj acc line)))
                  (re-find #":dt/type\s+:dt/Property\b" line)
                  (let [new-line (str/replace line
                                              #":dt/type(\s+):dt/Property"
                                              (str ":dt/type$1" (str new-target)))]
                    (swap! stats update :migrated inc)
                    (swap! found-slots conj new-slot-name)
                    (recur rest new-slot-name new-target (conj acc new-line)))
                  :else
                  (recur rest new-slot-name new-target (conj acc line)))

                ;; Multi-line: :dt/type on its own line for the current slot.
                ;; effective-target may be nil here if the current slot is not
                ;; in the migration plan — in which case skip silently.
                (and is-dt-type-line? effective-target)
                (cond
                  (re-find (re-pattern (str ":dt/type\\s+" (str effective-target) "\\b")) line)
                  (do (swap! stats update :already-done inc)
                      (swap! found-slots conj effective-name)
                      (recur rest effective-name effective-target (conj acc line)))
                  (re-find #":dt/type\s+:dt/Property\b" line)
                  (let [new-line (str/replace line
                                              #":dt/type(\s+):dt/Property"
                                              (str ":dt/type$1" (str effective-target)))]
                    (swap! stats update :migrated inc)
                    (swap! found-slots conj effective-name)
                    (recur rest effective-name effective-target (conj acc new-line)))
                  :else
                  (recur rest effective-name effective-target (conj acc line)))

                :else
                (recur rest effective-name effective-target (conj acc line))))))]
    (doseq [slot-name (sort (keys slot-name->target))]
      (when-not (contains? @found-slots slot-name)
        (swap! stats update :not-found inc)
        (println "WARN: slot not found:" slot-name)))
    [(str/join "\n" new-lines) @stats]))

(let [content (slurp schema-path)
      [new-content stats] (migrate-content content)]
  (spit schema-path new-content)
  (println "Migration complete.")
  (println "  Slots migrated:" (:migrated stats))
  (println "  Already at target:" (:already-done stats))
  (println "  Not found:" (:not-found stats)))
