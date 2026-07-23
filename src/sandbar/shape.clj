(ns sandbar.shape
  "SHACL-style abstract-interpreter walker for `:mm/Shape` validation.

   Stage C of the SHACL-deeply-incorporated arc (2026-05-23) — implements
   the validation walker as a family of first-class `:mm/Fn` instances per
   the Cousot-Cousot abstract-interpretation framing (see
   `memory/observations/shape_walker_is_abstract_interpreter.md`).

   ## Architecture

   Each check fn here is authored via `sandbar.db.fn/defdbfn` with
   `:dt.fn/installed-as :classpath-fn` — the actual implementation lives
   here in the peer JVM classpath; the `:mm/Fn` memorial entity carries
   the metadata (purpose / purity / cost-class / source-ns / source-var)
   so substrate consumers can discover + invoke the walker fns by ident.

   The check fns are composed by `walk-entity` (the top-level walker) and
   `conformance-report` (the batch aggregator).

   ## Abstract-interpretation framing (per the observation)

   - **Concrete domain**: dynamic substrate properties (actual entity
     graph; actual behavior under workload; actual retrieval semantics)
   - **Abstract domain**: the entity's slot values + ref-graph viewed
     statically (without running it through any workload)
   - **Shape constraints**: invariants over the abstract domain that
     soundly approximate properties we care about in the concrete domain
   - **Walker = interpreter**: evaluates each constraint against the
     abstract view per (entity, shape) pair
   - **Severity discipline**: `:violation` shapes should be SOUND (no
     false negatives — never says 'clean' when concrete domain is dirty);
     `:warning` shapes may admit false positives; `:info` is fully
     informational

   ## Conformance report shape

   Per-check fns return:
     `{:status :pass}` — constraint satisfied
     `{:status :fail :severity <s> ...}` — constraint violated; <s> from
                                            shape's `:mm.shape/severity`
                                            (default :violation)

   `walk-entity` aggregates per-check results:
     `{:status :pass :entity <eid> :shape <eid>}`
     `{:status :fail :entity <eid> :shape <eid> :failures [<check-result> ...]}`

   `conformance-report` produces batch summary:
     `{:class <ident> :instance-count <n> :shape-count <n>
       :total-checks <n> :passes <n> :failures <n>
       :failure-details [<walk-result> ...]}`"
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.fn :refer [defdbfn]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers (not memorialized; internal to the walker)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ->ident
  "Returns the :db/ident keyword for x.  Handles both raw keywords and
   Datomic entity wrappers transparently — Datomic's entity API auto-
   resolves refs to keyword idents when the target entity carries
   :db/ident, so a ref-slot retrieval may yield EITHER an EntityMap
   wrapper OR the keyword directly depending on the target's shape.
   This helper normalizes both cases."
  [x]
  (cond
    (keyword? x) x
    (map? x)     (:db/ident x)
    :else        nil))

(defn- ->entity
  "Resolve a ref-slot VALUE to its EntityMap.  Dual to ->ident: the Datomic
   entity API returns a ref whose target carries :db/ident as the ident KEYWORD
   rather than an EntityMap, so a constraint sub-entity that is schema-seeded
   with a stable :db/ident (e.g. the interval XOR seeds in schema/mm-temporal.edn)
   comes back as a keyword.  Iterating a constraint slot must re-resolve such
   keyword refs to their entity before reading the sub-entity's OWN slots
   (:mm.shape.xor/slot-a etc.).  EntityMaps + nil pass through unchanged."
  [db x]
  (if (keyword? x) (d/entity db x) x))

(defn- shape-severity
  "Resolve the shape's declared severity, defaulting to :violation."
  [shape]
  (or (:mm.shape/severity shape) :violation))

(defn- slot-value-count
  "Return the cardinality count of a slot value: 0 for nil, 1 for a
   scalar, n for a collection."
  [v]
  (cond
    (nil? v)    0
    (coll? v)   (count v)
    :else       1))

(defn- regex-match?
  "Test whether `value` matches `pattern` under optional `flags` (Java
   regex flag chars: 'i', 'm', 's', 'x')."
  [value pattern flags]
  (let [flag-mask (reduce
                    (fn [acc c]
                      (bit-or acc (case c
                                    \i java.util.regex.Pattern/CASE_INSENSITIVE
                                    \m java.util.regex.Pattern/MULTILINE
                                    \s java.util.regex.Pattern/DOTALL
                                    \x java.util.regex.Pattern/COMMENTS
                                    0)))
                    0
                    (or flags ""))
        re        (java.util.regex.Pattern/compile pattern flag-mask)]
    (boolean (re-find re (str value)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-constraint check fns — each a first-class :mm/Fn memorial
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defdbfn check-required-property [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Verify entity carries every property in shape's :mm.shape/required-property set."
   :dt.fn/version      "1.0.0"}
  (let [entity   (d/entity db entity-eid)
        shape    (d/entity db shape-eid)
        required (set (map ->ident (:mm.shape/required-property shape)))
        present  (set (filter (fn [k]
                                (and (contains? required k)
                                     (some? (get entity k))))
                              (keys entity)))
        missing  (set/difference required present)]
    (if (empty? missing)
      {:status :pass :check :required-property}
      {:status              :fail
       :check               :required-property
       :missing-properties  missing
       :severity            (shape-severity shape)})))


(defdbfn check-cardinality [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Verify entity respects all cardinality constraints in shape's :mm.shape/cardinality-constraints."
   :dt.fn/version      "1.0.0"}
  (let [entity      (d/entity db entity-eid)
        shape       (d/entity db shape-eid)
        constraints (map #(->entity db %) (:mm.shape/cardinality-constraints shape))
        violations  (for [c constraints
                          :let [prop    (->ident (:mm.shape.cardinality/property c))
                                min-v   (:mm.shape.cardinality/min c)
                                max-v   (:mm.shape.cardinality/max c)
                                value   (get entity prop)
                                n       (slot-value-count value)]
                          :when (or (and min-v (< n min-v))
                                    (and max-v (not= max-v -1) (> n max-v)))]
                      {:property prop :count n :min min-v :max max-v})]
    (if (empty? violations)
      {:status :pass :check :cardinality}
      {:status                 :fail
       :check                  :cardinality
       :cardinality-violations (vec violations)
       :severity               (shape-severity shape)})))


(defdbfn check-pattern [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :moderate
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Verify string-valued properties match regex patterns declared in shape's :mm.shape/pattern-constraints."
   :dt.fn/version      "1.0.0"}
  (let [entity      (d/entity db entity-eid)
        shape       (d/entity db shape-eid)
        constraints (map #(->entity db %) (:mm.shape/pattern-constraints shape))
        violations  (for [c constraints
                          :let [prop  (->ident (:mm.shape.pattern/property c))
                                regex (:mm.shape.pattern/regex c)
                                flags (:mm.shape.pattern/flags c)
                                value (get entity prop)
                                vals  (if (coll? value) value [value])
                                bad   (filter
                                        (fn [v]
                                          (and (some? v)
                                               (not (regex-match? v regex flags))))
                                        vals)]
                          :when (seq bad)]
                      {:property prop :regex regex :flags flags
                       :non-matching-values (vec bad)})]
    (if (empty? violations)
      {:status :pass :check :pattern}
      {:status             :fail
       :check              :pattern
       :pattern-violations (vec violations)
       :severity           (shape-severity shape)})))


(defdbfn check-datatype [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Verify property values match declared datatypes in shape's :mm.shape/datatype-constraints."
   :dt.fn/version      "1.0.0"}
  (let [entity      (d/entity db entity-eid)
        shape       (d/entity db shape-eid)
        constraints (map #(->entity db %) (:mm.shape/datatype-constraints shape))
        violations  (for [c constraints
                          :let [prop      (->ident (:mm.shape.datatype/property c))
                                expected  (->ident (:mm.shape.datatype/expected-datatype c))
                                value     (get entity prop)
                                vals      (if (coll? value) value [value])
                                ;; Best-effort type check; full Datomic value-type
                                ;; verification deferred to v2 (requires walking
                                ;; the schema for the slot's :db/valueType).
                                bad-types (filter
                                            (fn [v]
                                              (and (some? v)
                                                   (not (or
                                                          (and (= expected :db.type/string) (string? v))
                                                          (and (= expected :db.type/long)   (integer? v))
                                                          (and (= expected :db.type/boolean) (boolean? v))
                                                          (and (= expected :db.type/keyword) (keyword? v))
                                                          (and (= expected :db.type/ref)    (or (map? v) (integer? v) (keyword? v)))
                                                          (and (= expected :db.type/instant) (inst? v))
                                                          (and (= expected :db.type/uuid)   (uuid? v))
                                                          ;; For :dt/Class-shaped expectations (class ident as range),
                                                          ;; defer full coercion to v2; pass-through for now.
                                                          (keyword? expected)))))
                                            vals)]
                          :when (seq bad-types)]
                      {:property prop :expected-datatype expected
                       :bad-values (vec bad-types)})]
    (if (empty? violations)
      {:status :pass :check :datatype}
      {:status              :fail
       :check               :datatype
       :datatype-violations (vec violations)
       :severity            (shape-severity shape)})))


(defdbfn check-closed [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "If shape's :mm.shape/closed? is true, verify entity carries no slot beyond the shape's declared properties."
   :dt.fn/version      "1.0.0"}
  (let [entity (d/entity db entity-eid)
        shape  (d/entity db shape-eid)]
    (if (true? (:mm.shape/closed? shape))
      (let [declared (into #{}
                           (concat (map ->ident (:mm.shape/required-property shape))
                                   (map #(->ident (:mm.shape.cardinality/property (->entity db %)))
                                        (:mm.shape/cardinality-constraints shape))
                                   (map #(->ident (:mm.shape.pattern/property (->entity db %)))
                                        (:mm.shape/pattern-constraints shape))
                                   (map #(->ident (:mm.shape.datatype/property (->entity db %)))
                                        (:mm.shape/datatype-constraints shape))))
            ;; Always-allowed substrate slots:
            substrate-allowed #{:db/id :db/ident :dt/type :dt/context :dt/label}
            present  (set (filter keyword? (keys entity)))
            extra    (set/difference present declared substrate-allowed)]
        (if (empty? extra)
          {:status :pass :check :closed}
          {:status            :fail
           :check             :closed
           :extra-properties  extra
           :severity          (shape-severity shape)}))
      {:status :pass :check :closed :reason :shape-not-closed})))


(defdbfn check-validator-fn [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-partial
   :dt.fn/cost-class   :moderate
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "If shape carries a :mm.shape/validator-fn ref, resolve it via :dt.fn/source-ns + :dt.fn/source-var and invoke; capture the result."
   :dt.fn/version      "1.0.0"}
  (let [shape        (d/entity db shape-eid)
        validator-fn (:mm.shape/validator-fn shape)]
    (if (nil? validator-fn)
      {:status :pass :check :validator-fn :reason :no-validator-fn}
      (let [fn-ns  (:dt.fn/source-ns validator-fn)
            fn-var (:dt.fn/source-var validator-fn)]
        (try
          (let [resolved (requiring-resolve (symbol fn-ns fn-var))]
            (if resolved
              (let [result (resolved db entity-eid shape-eid)]
                (if (= :pass (:status result))
                  {:status :pass :check :validator-fn :delegate-result result}
                  (assoc result :check :validator-fn)))
              {:status :fail :check :validator-fn
               :reason :unresolved-symbol
               :ns fn-ns :var fn-var
               :severity (shape-severity shape)}))
          (catch Throwable t
            {:status :fail :check :validator-fn
             :error (.getMessage t)
             :severity (shape-severity shape)}))))))


(defdbfn check-xor-constraints [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Iterate the shape's :mm.shape/xor-constraints sub-entities; for each (slot-a, slot-b) pair, verify EXACTLY ONE is populated on the entity (rejects both-populated AND both-absent).  Substrate extension for Option ε Paired Property Pattern per pre-0.2.0 β.0.5 Decision ADR (eid 17592186101815)."
   :dt.fn/version      "1.0.0"}
  (let [entity      (d/entity db entity-eid)
        shape       (d/entity db shape-eid)
        constraints (map #(->entity db %) (:mm.shape/xor-constraints shape))
        violations  (vec
                      (keep
                        (fn [xc]
                          (let [slot-a       (->ident (:mm.shape.xor/slot-a xc))
                                slot-b       (->ident (:mm.shape.xor/slot-b xc))
                                a-populated? (some? (get entity slot-a))
                                b-populated? (some? (get entity slot-b))]
                            (cond
                              (and a-populated? b-populated?)
                              {:slot-a slot-a :slot-b slot-b :reason :both-populated}
                              (and (not a-populated?) (not b-populated?))
                              {:slot-a slot-a :slot-b slot-b :reason :both-absent}
                              :else nil)))
                        constraints))]
    (if (empty? violations)
      {:status :pass :check :xor-constraints}
      {:status     :fail
       :check      :xor-constraints
       :violations violations
       :severity   (shape-severity shape)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Top-level walker — composes the per-constraint check fns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defdbfn walk-entity [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-partial
   :dt.fn/cost-class   :moderate
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Top-level walker: invokes all applicable check fns for the given (entity, shape) pair; aggregates results into a conformance report."
   :dt.fn/version      "1.0.0"}
  (let [checks   [(check-required-property db entity-eid shape-eid)
                  (check-cardinality       db entity-eid shape-eid)
                  (check-pattern           db entity-eid shape-eid)
                  (check-datatype          db entity-eid shape-eid)
                  (check-closed            db entity-eid shape-eid)
                  (check-validator-fn      db entity-eid shape-eid)
                  (check-xor-constraints   db entity-eid shape-eid)]
        failures (filter (comp #{:fail} :status) checks)]
    (if (empty? failures)
      {:status :pass :entity entity-eid :shape shape-eid :checks-passed (count checks)}
      {:status :fail :entity entity-eid :shape shape-eid :failures (vec failures)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Batch aggregator — :dt.fn/purpose :derive (produces report; doesn't validate-in-place)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defdbfn conformance-report [db class-ident]
  {:dt.fn/purpose      :derive
   :dt.fn/purity       :pure-partial
   :dt.fn/cost-class   :expensive
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Batch conformance: walks all instances of class-ident against all applicable :mm/Shape instances; aggregates per-pair walk-entity results into a structured report."
   :dt.fn/version      "1.0.0"}
  (let [;; Shapes targeting this class:
        shape-eids    (d/q '[:find [?s ...]
                             :in $ ?cls
                             :where [?s :mm.shape/applies-to ?cls]]
                           db class-ident)
        ;; Instances of this class:
        instance-eids (d/q '[:find [?e ...]
                             :in $ ?cls
                             :where [?e :dt/type ?cls]]
                           db class-ident)
        results  (for [eid       instance-eids
                       shape-eid shape-eids]
                   (walk-entity db eid shape-eid))
        failures (filter (comp #{:fail} :status) results)
        warnings (filter (fn [r] (some #(= :warning (:severity %)) (:failures r))) failures)
        errors   (filter (fn [r] (some #(= :violation (:severity %)) (:failures r))) failures)]
    {:class           class-ident
     :instance-count  (count instance-eids)
     :shape-count     (count shape-eids)
     :total-checks    (count results)
     :passes          (- (count results) (count failures))
     :failures        (count failures)
     :error-count     (count errors)
     :warning-count   (count warnings)
     :failure-details (vec failures)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public peer-side entry points (consumer-facing wrappers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate
  "Public entry: validate an entity against the shapes applicable to its class.
   Returns a vector of walk-entity results (one per applicable shape).

   `mode` ∈ #{:strict :audit :disabled}:
     - :strict — throw ex-info on first :violation-severity failure
     - :audit  — return all results; let caller decide
     - :disabled — skip validation entirely (no-op; returns [])"
  ([db entity-eid] (validate db entity-eid :audit))
  ([db entity-eid mode]
   (if (= mode :disabled)
     []
     (let [entity        (d/entity db entity-eid)
           class-ident   (->ident (:dt/type entity))
           shape-eids    (when class-ident
                           (d/q '[:find [?s ...]
                                  :in $ ?cls
                                  :where [?s :mm.shape/applies-to ?cls]]
                                db class-ident))
           results       (when (seq shape-eids)
                           (mapv #(walk-entity db entity-eid %) shape-eids))
           violations    (filter
                           (fn [r]
                             (and (= :fail (:status r))
                                  (some #(= :violation (:severity %)) (:failures r))))
                           (or results []))]
       (when (and (= mode :strict) (seq violations))
         (throw (ex-info "Shape-validation failed (strict mode)"
                         {:entity      entity-eid
                          :class       class-ident
                          :violations  (vec violations)})))
       (or results [])))))
