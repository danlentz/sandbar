(ns sandbar.db.datomic
  (:require [clojure.string       :as string]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [datomic.api          :as d]
            [sandbar.db.fn      :as fn]
            [sandbar.db.rules   :as rules]
            [sandbar.util.common       :as util]
            [sandbar.util.edn        :as dedn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Connectivity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (def ^:dynamic *db*   nil)

(def ^:dynamic **conn*   (atom nil))

(defn db-spec []
  (dedn/config-value :db))

(defn db-uri
  ([] (db-uri (db-spec)))
  ([spec] (apply str  ((juxt :url :sid) spec))))

(defn conn
   ([]     (or @**conn* (conn (db-uri))))
   ([uri] (d/connect uri)))

(defn ensure-db! [uri]
  (d/create-database uri))

(defn db
  ([] (d/db (conn)))
  ([c] (d/db c)))

;; (defmacro with-db [db-instance & body]
;;   `(binding [*db* ~db-instance]
;;      ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema-value [designator]
  (dedn/resource-value designator nil))

(defn required-schema []
  (dedn/config-value :required-schema))

;; Class-level cardinality-many meta-slots whose re-load from schema EDN
;; should have SET-REPLACE semantics (not the default additive upsert).
;;
;; Without this set-replace pre-pass, edits to schema/*.edn that REMOVE
;; tuples from these slots (e.g., trimming :dt/slots after a Phase I
;; canonicalization, rewriting :dt/codec-slot-order with new positions)
;; leave the prior values behind in the DB.  Each new load adds; nothing
;; retracts.  Cardinality-many ref-typed :dt/slots is hit hardest:
;; observed accumulation to 19 entries when EDN declared 5.
;;
;; Tuple-typed :dt/codec-slot-order + :dt/bm25f-weights are partially
;; mitigated by Datomic's tuple value-equality (identical tuples don't
;; duplicate) but unique-by-position tuples can still collide (e.g.,
;; [:mm.context/name 0] + [:mm.memory/name 0] both present at position 0).
;;
;; Per `observations/schema_edn_reload_is_additive_not_set_replace_for_cardinality_many_class_meta_slots_2026_05_26.md`
;; + `interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.md`.
;;
;; Mechanism: before transacting each schema stmt, walk its entity-maps
;; for the set-replace meta-slots.  For each, compute the symmetric
;; difference (current DB values not present in new EDN values) and
;; prepend [:db/retract ...] operations.  Retract-then-add semantics
;; in a single tx means same-value-redeclarations are no-ops (Datomic
;; idempotency); strictly-removed values get retracted; strictly-new
;; values get added.

(def class-set-replace-meta-slots
  "Cardinality-many class-level meta-slots that the schema-EDN-load
  mechanism treats with set-replace semantics.  Adding to this set
  extends the discipline to other class meta-slots that should be
  fully-declared per EDN (versus accumulated across loads).

  Public for testability + extensibility — consumers can rebind in
  scoped contexts via `(with-redefs ...)` to exercise alternative sets."
  #{:dt/slots :dt/codec-slot-order :dt/bm25f-weights})

(defn normalize-slot-value-for-set
  "Normalize a DB-side slot value for set-membership comparison with an
  EDN-side value.  Ref-typed slots come back as Entity instances; we
  compare by :db/ident.  Tuple- + scalar-typed values compare directly.

  Public for testability."
  [v]
  (if (and (associative? v) (contains? v :db/ident))
    (:db/ident v)
    v))

(defn class-meta-slot-retracts
  "Given current DB + an entity-map being asserted, return [:db/retract ...]
  operations for any class-set-replace meta-slot whose current DB value
  contains entries not present in the new EDN declaration.  Returns nil
  when no entity exists yet (first-time load) or no relevant slot is
  being asserted.

  Public for testability."
  [db ent-map]
  (when-let [ident (:db/ident ent-map)]
    (when-let [current (d/entity db ident)]
      (mapcat
       (fn [slot]
         (when (contains? ent-map slot)
           (let [new-values (set (map normalize-slot-value-for-set
                                      (get ent-map slot)))
                 cur-values (get current slot)]
             (for [v cur-values
                   :let [v-norm (normalize-slot-value-for-set v)]
                   :when (not (contains? new-values v-norm))]
               [:db/retract ident slot
                ;; For ref-typed slots, retract by :db/id (Datomic
                ;; accepts :db/ident lookup-ref too but :db/id is the
                ;; canonical form post-resolution).
                (if (and (associative? v) (contains? v :db/id))
                  (:db/id v)
                  v)]))))
       class-set-replace-meta-slots))))

(defn with-class-meta-slot-set-replace
  "Augment a schema-load stmt with retract operations for class-set-replace
  meta-slots that are being redeclared.  Retracts are prepended to the
  stmt so they execute before the new asserts in the same tx.  Per the
  set-replace mechanism documented above class-set-replace-meta-slots.

  Public for testability."
  [db stmt]
  (let [retracts (vec (mapcat #(class-meta-slot-retracts db %) stmt))]
    (if (seq retracts)
      (vec (concat retracts stmt))
      stmt)))

(defn load-schema
  ([schema-designator]
     (load-schema (db-uri) schema-designator))
  ([uri schema-designator]
   (log/info :DB/SCHEMA (str "Load " schema-designator))
   (doseq [stmt (schema-value schema-designator)]
     (let [c (conn uri)
           tx (with-class-meta-slot-set-replace (d/db c) stmt)]
       (log/info :DB/STMT stmt)
       (when (not= tx stmt)
         (log/info :DB/SET-REPLACE-RETRACTS
                   {:retract-count (- (count tx) (count stmt))}))
       @(d/transact c tx)))))

;(schema-value :literal)
;(schema-value :resource)

;; Stage 5 Phase A.5 — post-schema-reload callback registry.
;;
;; Higher-level namespaces (e.g., sandbar.db.datatype) hold caches that
;; depend on schema state.  When `load-all-schema!` runs, those caches
;; need to invalidate.  Direct deps would create a cycle (datatype already
;; requires datomic for `db/db` etc.), so the higher-level ns REGISTERS
;; a clear-fn at namespace-load time + this ns invokes the registered
;; handlers after each schema reload.  Set-valued for idempotent
;; registration across REPL reloads.
;;
;; Per `interaction/dont_use_requiring_resolve_for_namespace_dep_avoidance_2026_05_22.md`
;; + `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`.

(defonce post-schema-reload-handlers
  (atom #{}))

(defn register-post-schema-reload-handler!
  "Register a no-arg function to run after each `load-all-schema!`.
  Set-valued: re-registration is idempotent.  Typical usage: clear a
  cache whose validity depends on schema state."
  [f]
  (swap! post-schema-reload-handlers conj f))

(defn fire-post-schema-reload-handlers!
  "Invoke every registered post-schema-reload handler, swallowing per-
  handler failures so one bad handler can't block the others.  Public so
  alternative schema-load entry points (e.g. `sandbar.test-util`) can
  preserve the cache-invalidation invariant that `load-all-schema!`
  guarantees."
  []
  (doseq [handler @post-schema-reload-handlers]
    (try (handler)
         (catch Throwable t
           (log/warn t :DB/POST-SCHEMA-RELOAD-HANDLER-FAILED
                     {:handler (str handler)})))))

(defn load-all-schema! [uri]
  (doseq [sd (required-schema)]
    (load-schema uri sd))
  (fire-post-schema-reload-handlers!))

; (load-all-schema! (db-uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema-derived cache invalidator registrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per the precedent of `sandbar.db.datatype` (which registers its own
;; type-relation-cache invalidator) + the symmetric-handler-fire pattern of
;; decisions/zorp_test_cross_fixture_cache_survival_fix_option_2_2026_05_23.md,
;; register schema-derived cache invalidators here in db.datomic — the one ns
;; that already requires the consumer namespaces (inverse would cycle).
;;
;; NOT registered:
;;  - `fn/clear-fnbase!` / `fn/clear-mm-fn-memorial-base!` — populated at
;;    namespace-load (ONCE per JVM) by `defdbfn` macro; consumed by
;;    `fn/load-all-dbfn` which runs AFTER `load-all-schema!` in
;;    `initialize-db!` (line ~145 above).  Clearing post-schema-reload would
;;    wipe the bases BEFORE dbfn install, breaking production startup.
;;  - `rules/clear-rulebase!` — same shape; populated at namespace-load via
;;    `defrule` macros, consumed by test fixtures via `(d/transact conn (all-rules))`.
;;    Clearing wipes rules needed by subsequent fixtures.
;;
;; Wave 0 W.0.4 of the metamodel-unification arc.

;; (No additional registrations needed beyond dt.datatype's auto-registration of
;; clear-type-relation-cache! + search.clj's auto-registration of clear-bm25f-cache!)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema-seeded constraint sub-entity self-heal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; The 2 Option-ε interval XOR seed shapes (schema/mm-temporal.edn) each declare
;; ONE :mm.shape/XorConstraint sub-entity.  Before those sub-entities carried a
;; stable :db/ident, they were seeded tempid-only; because :mm.shape/xor-
;; constraints is :db.type/ref cardinality-many WITHOUT :db/isComponent, every
;; schema retransaction (initialize-db! reloads schema on every boot) minted a
;; NEW sub-entity eid + APPENDED it to the slot.  :mm.shape/XorConstraint
;; entities proliferated (~40/shape observed; aggregate.count = 80 vs 2) and
;; each seed shape's slot accumulated duplicate refs, never retracting.
;;
;; Stable :db/idents on the seed sub-entities prevent RECURRENCE (upsert → same
;; eid each reload → idempotent slot assertion).  This migration HEALS DBs that
;; already accumulated duplicates: for each schema-seeded shape, retract + GC
;; every constraint ref in the slot that is NOT its canonical idented sub-
;; entity.  Idempotent (post-heal each slot holds only its canonical sub-entity,
;; so subsequent runs find nothing) + surgical (only touches the named seed
;; shapes — never client-authored constraint sub-entities, which legitimately
;; lack :db/idents).
;;
;; Parallels the set-replace additive-accumulation fix for cardinality-many
;; class meta-slots (see class-set-replace-meta-slots above); differs in that
;; xor-constraints values are ref sub-ENTITIES, so healing must GC the detached
;; entity (retractEntity), not merely retract the slot value.
;;
;; Per observations/schema_seeded_ref_subentities_without_db_ident_proliferate_on_reload
;; + interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.

(def seed-shape-canonical-constraints
  "Schema-seeded :mm/Shape ident → [constraint-slot canonical-constraint-ident].
  Names the ONE constraint sub-entity each seed shape must carry.  Extend this
  when new seed shapes are declared in schema EDN (e.g. if the Cardinality /
  Pattern / Datatype constraint families ever gain seed instances — they carry
  the same tempid-vs-:db/ident hazard).

  Public for testability + extensibility."
  {:memory.shapes/interval-begins-at-xor
   [:mm.shape/xor-constraints :memory.shapes.xor/interval-begins-at]
   :memory.shapes/interval-ends-at-xor
   [:mm.shape/xor-constraints :memory.shapes.xor/interval-ends-at]})

(defn prune-duplicate-seed-constraint-subentities!
  "Retract + GC duplicate constraint sub-entities accumulated on schema-seeded
  :mm/Shapes across pre-:db/ident schema reloads.  For each entry in
  `seed-shape-canonical-constraints`, retract every value of the shape's
  constraint slot that is NOT the canonical idented sub-entity, and
  :db.fn/retractEntity the detached duplicate.  Returns the total number of
  duplicate sub-entities pruned.  Idempotent + surgical — safe to run on every
  boot.  See the comment block above.

  Public for testability."
  [uri]
  (let [c (conn uri)
        ;; The Datomic Entity API renders a ref to an idented entity (the
        ;; canonical sub-entity, post-fix) as the :db/ident KEYWORD, and a ref
        ;; to a non-idented entity (the accumulated duplicates) as an EntityMap.
        ;; Normalize both to an eid before comparing against the canonical.
        ref->eid (fn [db v] (if (keyword? v) (:db/id (d/entity db v)) (:db/id v)))]
    (reduce
     (fn [total [shape-ident [slot canonical-ident]]]
       (let [db    (d/db c)
             shape (d/entity db shape-ident)
             canon (d/entity db canonical-ident)]
         (if (and shape canon)
           (let [canon-eid (:db/id canon)
                 dupes     (->> (get shape slot)
                                (map #(ref->eid db %))
                                (remove #(= % canon-eid))
                                distinct)]
             (if (seq dupes)
               (do
                 ;; Retract the slot ref AND GC the now-detached sub-entity.
                 ;; :db.fn/retractEntity alone also retracts inbound refs, but
                 ;; the explicit :db/retract makes the slot cleanup obvious.
                 @(d/transact c
                    (into (mapv (fn [d] [:db/retract (:db/id shape) slot d]) dupes)
                          (map (fn [d] [:db.fn/retractEntity d]) dupes)))
                 (log/info :DB/SEED-CONSTRAINT-PRUNE
                           {:shape shape-ident :slot slot :pruned (count dupes)})
                 (+ total (count dupes)))
               total))
           total)))
     0
     seed-shape-canonical-constraints)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Duplicate :mm/Schedule self-heal (W3.B proliferation fix)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; :mm/Schedule is a :mm/Spec (NOT a :mm/Memory), so before this arc the runtime
;; create path (sandbar.store/create-memory!) derived NO :db/ident for it and
;; dt/make minted a fresh tempid → brand-new eid on every create.  Repeated
;; UNTARGETED creates (the MCP `entity.create :class :mm/Schedule` path + the
;; equivalent `dt/make :mm/Schedule` in tests / dev-loops) therefore APPENDED a
;; new target-less row each time — the target-less :mm/Schedule proliferation
;; this arc fixes.  create-memory! now derives a stable content-key :db/ident
;; (sandbar.store/derive-schedule-ident) so RECURRENCE is prevented (upsert →
;; same eid).  This migration HEALS DBs that already accumulated duplicates.
;;
;; CONSERVATISM (provable-duplicates-only): schedules are grouped by the SAME
;; logical-identity key the create path idents on — the FULL semantic slot set
;; (target, recurrence, timezone, until, count, exdates, rdates, misfire-policy,
;; concurrency; see `schedule-content-key-string`), deliberately EXCLUDING ONLY
;; :mm.schedule/dtstart (callers re-anchor it at (now) on every create, so it
;; diverges across otherwise-identical dupes; folding it in would UNDER-collapse
;; them).  Because EVERY distinguishing slot is now IN the key, two schedules
;; that differ on ANY of them (a different :until, a different :concurrency, …)
;; never share a group — only rows PROVABLY identical on every semantic axis
;; merge.  This closes the original data-loss flaw where a widened-key-absent
;; group silently collapsed genuinely-distinct schedules onto one survivor.
;;
;; GATING (W3.B REVISE): this is NO LONGER wired into initialize-db! and no
;; longer runs on every boot.  It is a standalone, deliberately-invoked
;; migration: DRY-RUN/REPORT by default (lists, per group, the survivor and the
;; per-dupe slot values that would be dropped, WITHOUT mutating); pass
;; `:apply? true` to perform the retraction.  Run once out-of-band AFTER a DB
;; backup; dry-run FIRST and inspect the report before applying.
;;
;; Non-destructive to graph edges: before retracting a duplicate, EVERY inbound
;; ref pointing at it (e.g. :mm.run/triggered-by, :mm.schedule-event/schedule) is
;; RE-POINTED onto the survivor, so no :mm/Run / :mm.event/ScheduleEvent loses
;; its edge.  Survivor selection prefers a schedule carrying a :db/ident (the
;; system schedules + any content-key-idented rows created post-fix) over an
;; identless legacy row, and breaks ties on lowest eid for determinism.
;;
;; Idempotent: post-heal each key holds exactly one schedule, so a re-run finds
;; no group with >1 member.  Safe to run on every boot.
;;
;; Per the W3.B schedule-idempotency arc + the XorConstraint seed-idempotency
;; precedent (prune-duplicate-seed-constraint-subentities! above; commit 1bc426b)
;; + interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.

;; ── Shared content-key builder (byte-identical on BOTH sides) ────────────────
;;
;; W3.B REVISE (data-loss fix): the original key was ONLY (target, recurrence,
;; timezone) — it EXCLUDED six first-class, dispatcher-read slots (:until,
;; :count, :exdates, :rdates, :misfire-policy, :concurrency), so two schedules
;; differing only on one of those (e.g. :until 2027 vs 2099) collapsed onto one
;; eid at both create (upsert) AND prune (retract) — silently deleting a
;; legitimately-distinct schedule and overwriting terminator/policy slots.
;;
;; The key now spans the FULL semantically-distinguishing slot set.  The
;; create-path (sandbar.store/derive-schedule-ident, pre-coercion entity-spec
;; map) and the prune-path (schedule-content-key, post-transact EntityMap) MUST
;; produce byte-identical strings, so the canonical string is built HERE by ONE
;; shared fn that both sides call after normalizing their differently-shaped
;; inputs to the same primitives.  Placed in this (lowest) ns so store — which
;; requires datatype→datomic — can call it WITHOUT a require cycle.

(defn- key-token
  "Normalize a scalar slot value to a stable string token for the content-key.
   nil → \"\".  Instant-typed values → their epoch-millis: a java.util.Date (the
   post-transact wire shape) and a java.time.Instant (a create-path caller may
   pass either for a :db.type/instant slot) both canonicalize to the same
   millis, so :until keys identically on both sides regardless of which the
   caller supplied.  Avoids toString locale/format drift.  Everything else →
   (str v)."
  [v]
  (cond
    (nil? v)                     ""
    (instance? java.util.Date v) (str (.getTime ^java.util.Date v))
    (instance? java.time.Instant v) (str (.toEpochMilli ^java.time.Instant v))
    :else                        (str v)))

(defn instant->millis
  "Epoch-millis of an instant-typed value — java.util.Date (post-transact wire
   shape) or java.time.Instant (a create-path caller may pass either).  Public so
   the create-path (sandbar.store) shares the exact same member-canonicalization
   for :exdates/:rdates, guaranteeing byte-identical date-set tokens on both
   sides."
  ^long [v]
  (cond
    (instance? java.util.Date v)    (.getTime ^java.util.Date v)
    (instance? java.time.Instant v) (.toEpochMilli ^java.time.Instant v)
    :else (throw (ex-info "not an instant" {:v v :class (class v)}))))

(defn date-set-token
  "Normalize a cardinality-many instant slot (:exdates / :rdates) to an
   ORDER-INDEPENDENT canonical token: epoch-millis of every member, sorted,
   comma-joined.  A set/vector/nil of java.util.Date (or java.time.Instant, or
   nil) all canonicalize identically, so member order and collection type can
   never perturb the key.  Public so the create-path shares the exact builder."
  [coll]
  (->> (or coll [])
       (map instant->millis)
       sort
       (clojure.string/join ",")))

(defn schedule-content-key-string
  "THE canonical :mm/Schedule content-key string — the single source of truth
   both the create-path ident derivation and the prune-path grouping key are
   built from, so the two can never drift byte-for-byte.

   Keyed slot set (the FULL semantically-distinguishing identity — enumerated
   so any future slot addition forces a conscious keep/exclude decision):

     :mm.schedule/target          (canonical `target-token`; see callers)
     :mm.schedule/recurrence      (RFC-5545 RRULE string — already encodes
                                    FREQ / INTERVAL / every BY-* rule, so it is
                                    the COMPLETE recurrence-defining form)
     :mm.schedule/timezone        (IANA tz name)
     :mm.schedule/until           (RFC-5545 UNTIL terminator instant)
     :mm.schedule/count           (RFC-5545 COUNT terminator)
     :mm.schedule/exdates         (order-independent set of exclusions)
     :mm.schedule/rdates          (order-independent set of extra instants)
     :mm.schedule/misfire-policy  (FOLDED IN — LEAD RULING (a): two schedules
                                    firing the same recurrence with different
                                    misfire behavior are operationally distinct)
     :mm.schedule/concurrency     (FOLDED IN — same rationale)

   DELIBERATELY EXCLUDED: :mm.schedule/dtstart — the recurrence anchor callers
   re-anchor to (now) on every create, which is the proliferation driver
   itself; folding it in would defeat upsert for logically-identical schedules.
   Accepted tradeoff: an idempotent re-create UPSERTS onto the survivor and thus
   overwrites its dtstart — a phase shift of the anchor — but never changes WHICH
   distinct schedules exist.  Also excluded: derived/observational slots that
   are NOT part of logical identity (:next-fire-at, :source-nl, :source-cron).

   Args are ALREADY-NORMALIZED primitives (strings/nil), so this fn is a pure
   deterministic join with no knowledge of Entity-vs-spec-map shape."
  [{:keys [target-token recurrence timezone until count exdates rdates
           misfire-policy concurrency]}]
  (clojure.string/join
   "|"
   [(key-token target-token)
    (key-token recurrence)
    (key-token timezone)
    (key-token until)
    (key-token count)
    (key-token exdates)              ; already the date-set canonical token
    (key-token rdates)               ; already the date-set canonical token
    (key-token misfire-policy)
    (key-token concurrency)]))

(defn- schedule-content-key
  "Prune-path logical-identity key for a :mm/Schedule EntityMap — normalizes the
  post-transact read shape, then delegates to `schedule-content-key-string` so
  it is byte-identical to the create-path key (sandbar.store/derive-schedule-
  ident).  See `schedule-content-key-string` for the keyed slot set + the
  dtstart exclusion rationale.

  Target normalization (must-fix #2): the Datomic entity API renders an
  IDENT-BEARING ref target as its :db/ident KEYWORD (not an EntityMap), so
  `target` may be a keyword, an EntityMap, or nil.  We resolve it to a CANONICAL
  token — :db/ident when present (stable), else the numeric eid — exactly as the
  create-path does for a raw eid vs a :db/ident keyword, so an idented target
  passed either way keys identically."
  [sched]
  (let [target (:mm.schedule/target sched)
        target-token (cond
                       (nil? target)     nil
                       (keyword? target) target
                       :else             (or (:db/ident target) (:db/id target)))]
    (schedule-content-key-string
     {:target-token   target-token
      :recurrence     (:mm.schedule/recurrence sched)
      :timezone       (:mm.schedule/timezone sched)
      :until          (:mm.schedule/until sched)
      :count          (:mm.schedule/count sched)
      :exdates        (date-set-token (:mm.schedule/exdates sched))
      :rdates         (date-set-token (:mm.schedule/rdates sched))
      :misfire-policy (:mm.schedule/misfire-policy sched)
      :concurrency    (:mm.schedule/concurrency sched)})))

(defn- inbound-ref-datoms
  "All [e a] pairs where datom [e a target-eid] exists AND `a` is a :db.type/ref
  attribute — i.e. every inbound ref pointing at `target-eid`.  Used to re-point
  a pruned duplicate schedule's inbound edges onto the survivor before GC."
  [db target-eid]
  (->> (d/datoms db :vaet target-eid)
       (map (fn [dtm] [(.e dtm) (.a dtm)]))))

(def ^:private schedule-identity-slots
  "The full slot set the content-key spans — echoed into the dry-run report so an
  operator sees exactly which values are shared by a collapsing group (and, per
  dupe, which of the survivor's would be kept).  dtstart is intentionally ABSENT."
  [:mm.schedule/target :mm.schedule/recurrence :mm.schedule/timezone
   :mm.schedule/until :mm.schedule/count :mm.schedule/exdates :mm.schedule/rdates
   :mm.schedule/misfire-policy :mm.schedule/concurrency])

(defn- schedule-slot-snapshot
  "Read-only snapshot of an EntityMap's identity slots + dtstart (the one
  excluded axis) + :db/id/:db/ident, for the dry-run report."
  [sched]
  (into {:db/id           (:db/id sched)
         :db/ident        (:db/ident sched)
         :mm.schedule/dtstart (:mm.schedule/dtstart sched)}
        (map (fn [slot] [slot (get sched slot)]) schedule-identity-slots)))

(defn- schedule-prune-plan
  "PURE (read-only) prune plan.  Groups every :mm/Schedule by the widened
  content-key; for each group of >1 provable duplicates, picks a DETERMINISTIC
  survivor and enumerates the dupes.  Because the key now spans every
  semantically-distinguishing slot, all members of a group are PROVABLY identical
  on every logical axis, so survivor choice loses no data — we therefore pick the
  LOWEST EID unconditionally (fully deterministic; an idented row, when present,
  is the one create-path-minted row and already sorts stably by its eid).

  Returns a vector of per-group plan maps:
    {:content-key <str> :survivor <snapshot> :dupes [<snapshot> …]
     :dropped-dtstarts [<Date> …]}  ;; dtstart values the phase-shift discards."
  [db]
  (let [sched-es (map first
                      (d/q '[:find ?e :where [?e :dt/type :mm/Schedule]] db))
        groups   (->> sched-es
                      (map #(d/entity db %))
                      (group-by schedule-content-key))]
    (->> groups
         (keep
          (fn [[k members]]
            (when (> (count members) 1)
              (let [;; Deterministic survivor: lowest eid.  With the widened key
                    ;; every member is identical on all semantic axes, so this is
                    ;; a data-loss-free tiebreak, not a value choice.
                    sorted   (sort-by :db/id members)
                    survivor (first sorted)
                    dupes    (rest sorted)]
                {:content-key      k
                 :survivor         (schedule-slot-snapshot survivor)
                 :dupes            (mapv schedule-slot-snapshot dupes)
                 :dropped-dtstarts (vec (keep :mm.schedule/dtstart dupes))}))))
         vec)))

(defn prune-duplicate-schedules!
  "Standalone, deliberately-invoked migration that collapses PROVABLE duplicate
  :mm/Schedule rows accumulated by pre-:db/ident runtime creates.  Groups by the
  WIDENED logical-identity key (every semantically-distinguishing slot — target,
  recurrence, timezone, until, count, exdates, rdates, misfire-policy,
  concurrency; dtstart excluded — see `schedule-content-key-string`).

  MODES (must-fix #3 — this is NO LONGER wired into initialize-db!):
    - DRY-RUN / REPORT (default): returns
        {:mode :dry-run :groups [<plan> …] :would-prune <n>}
      WITHOUT mutating.  Each plan lists the survivor, the dupes that would
      collapse onto it, and the per-dupe dtstart values that would be dropped
      (the accepted phase-shift).  Inspect this before applying.
    - APPLY (`:apply? true`): for each group RE-POINTS every inbound ref from
      each duplicate onto the deterministic (lowest-eid) survivor, then
      :db.fn/retractEntity the duplicate; returns
        {:mode :apply :groups [<plan> …] :pruned <n>}.

  OPERATIONAL DISCIPLINE: run once out-of-band AFTER a DB backup; dry-run FIRST
  and read the report, THEN re-invoke with :apply? true.  Idempotent — post-heal
  each key holds exactly one schedule, so a re-run's dry-run reports zero groups.
  Conservative: with the widened key, only rows identical on EVERY semantic axis
  merge — genuinely-distinct schedules (any differing slot) are never collapsed.

  Public for testability + out-of-band invocation."
  [uri & {:keys [apply?] :or {apply? false}}]
  (let [c    (conn uri)
        db   (d/db c)
        plan (schedule-prune-plan db)]
    (if-not apply?
      {:mode       :dry-run
       :groups     plan
       :would-prune (reduce + 0 (map (comp count :dupes) plan))}
      (let [pruned
            (reduce
             (fn [total {:keys [survivor dupes]}]
               (let [surv-eid (:db/id survivor)
                     db'      (d/db c)
                     tx       (vec
                               (mapcat
                                (fn [{dupe-eid :db/id}]
                                  (concat
                                   ;; Re-point every inbound ref from the dupe
                                   ;; onto the survivor (retract old, assert new),
                                   ;; THEN GC the now-detached duplicate.
                                   (mapcat
                                    (fn [[e a]]
                                      [[:db/retract e a dupe-eid]
                                       [:db/add     e a surv-eid]])
                                    (inbound-ref-datoms db' dupe-eid))
                                   [[:db.fn/retractEntity dupe-eid]]))
                                dupes))]
                 @(d/transact c tx)
                 (log/info :DB/SCHEDULE-DUPLICATE-PRUNE
                           {:survivor surv-eid :pruned (count dupes)})
                 (+ total (count dupes))))
             0
             plan)]
        {:mode :apply :groups plan :pruned pruned}))))

(defn initialize-db! [uri & schema]
  ;; Stage 5 Phase B (2026-05-22): always reload schema + dbfns at start,
  ;; regardless of whether the DB needed to be created.  Datomic's
  ;; :db/ident upsert makes load-all-schema! idempotent — re-running
  ;; against an existing DB is safe + applies any schema edits made
  ;; since last start.  Without this, editing schema/mm.edn requires
  ;; full DB wipe + re-init to take effect, which is hostile to
  ;; substrate evolution.  Per Dan-directive 2026-05-22:
  ;;   "sandbar start always retransacting schema seems convenient for now."
  ;; Composes with `ideas/sandbar_reflective_schema_from_type_memorials_-
  ;; for_dynamic_client_type_evolution_2026_05_22.md` as the substrate-
  ;; evolution discipline.
  ;;
  ;; β.2.0 pre-work (2026-05-26): after schema-load, validate the
  ;; :dt/subclass-of + :dt/subproperty-of entailment graphs are acyclic
  ;; (S.8 mitigation).  Q.B.0.b ratification: loud-fail on cycle detect.
  ;; Per the β.2 plan (`/Users/dan/.claude/plans/wise-splashing-stardust.md`)
  ;; + master pre-0.2.0 plan §3.β.2 + metamodel-unification arc §10.B.0.
  ;; Resolved late-binding to avoid load-time cyclic dependency between
  ;; sandbar.db.datomic and sandbar.db.entailment.quality (which requires
  ;; this ns for db/conn + db/db-uri).
  (let [created? (ensure-db! uri)]
    (apply load-all-schema! uri schema)
    ;; Self-heal schema-seeded constraint sub-entities that proliferated on
    ;; pre-:db/ident reloads (idempotent no-op once healed).  Must run after
    ;; load-all-schema! so the canonical idented sub-entities exist.
    (prune-duplicate-seed-constraint-subentities! uri)
    ;; NB: prune-duplicate-schedules! is DELIBERATELY NOT called here.  W3.B REVISE
    ;; (data-loss fix): the boot-time prune could silently collapse genuinely-
    ;; distinct schedules whenever the identity key was too narrow.  The key is now
    ;; widened AND the prune is a standalone, out-of-band migration (dry-run first,
    ;; then :apply? true, after a DB backup) — NOT an unconditional every-boot
    ;; mutation.  See prune-duplicate-schedules! + the comment block above it.
    ;; The create-path content-key :db/ident (sandbar.store/derive-schedule-ident)
    ;; is the standing recurrence-prevention mechanism; healing pre-existing dupes
    ;; is an operator decision, not a boot side-effect.
    ((requiring-resolve 'sandbar.db.entailment.quality/validate-entailment-graph!) uri)
    (fn/load-all-dbfn uri)
    (fn/load-all-mm-fn-memorials uri)
    (when created?
      (log/info :DB/INIT :uri uri))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Peer Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord DatomicPeer [spec uri c]
  component/Lifecycle

  (start [self]
    (initialize-db! uri)
    (let [t-con (conn uri)]
      (reset! **conn* t-con)
      (log/info "Datomic Peer started @" uri)
      (assoc self :c t-con)))

  (stop [self]
    (when c
      (reset! **conn* nil)
      (log/info "Datomic Peer stopped")
      (assoc self :c nil))))

(defn make-datomic-peer [spec]
  (map->DatomicPeer {:spec spec :uri (db-uri spec)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn entity [e]
  (if (associative? e)
    e
    (d/entity (db) e)))

(defn describe
  "Returns the Concise Bounded Description (CBD) of an entity 'e'"
  [e]
  (if (map? e)
    e
    (d/touch (entity e))))

(defn entity-id [e]
  (cond
    (number? e)      (long e)
    (associative? e) (:db/id e)
    (keyword? e)     (entity-id (entity e))
    true             nil))

(defn excise-entity [e-or-eid]
  @(d/transact (conn)
     [{:db/id #db/id[db.part/user]
       :db/excise (entity-id e-or-eid)}]))

(defn retract-entity [e-or-eid]
  @(d/transact (conn)
     [[:db.fn/retractEntity (entity-id e-or-eid)]]))

(defn retract-entities [& e-or-eids]
  @(d/transact (conn)
     (map #(vec [:db.fn/retractEntity (entity-id %)]) e-or-eids)))


(defn delete-db [uri]
  (log/warn :DB/DELETE :uri uri)
  (d/delete-database uri))

;; TODO: clean up

(defn delete! []
  (delete-db (db-uri)))

(defn refresh! []
  (delete!)
  (initialize-db! (db-uri))
  (reset! **conn* (conn (db-uri))))


(comment

  (refresh!)

  (log/enabled? :info)


  (describe :dt/Resource)

  (describe :dt/domain)

  (describe :User)




  ;; {:db/id 17592186045419,
  ;;  :db/ident :dt/Resource,
  ;;  :db/doc "Resource is the abstract superclass of all classes",
  ;;  :dt/type :dt/Class,
  ;;  :dt/context "system",
  ;;  :dt/name "Resource",
  ;;  :dt/list :dt/Resource*,
  ;;  :dt/slots #{:dt/type}}

  (describe :dt/Literal)

  ;; {:db/id 17592186045439,
  ;;  :db/ident :dt/Literal,
  ;;  :db/doc "Abstract superclass of literal/scalar types",
  ;;  :dt/type :dt/Class,
  ;;  :dt/context "system",
  ;;  :dt/name "Literal",
  ;;  :dt/subclass-of #{:dt/Resource}}

  (sandbar.core/stop)
  (delete!)
  (sandbar.core/go)


;;  (ensure-db! (db-uri))
  (initialize-db! (db-uri))

  (load-schema :meta)
  (load-schema :resource)
  (load-schema :literal)
  (load-schema :ref)
  (load-schema :fn)
  (load-schema :any)
  (load-schema :user)

  (defn tx [x]
    @(d/transact (conn) x))


(delete!)


  )
