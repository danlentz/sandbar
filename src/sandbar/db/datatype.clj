(ns sandbar.db.datatype
  "Datatype Metamodel API

  This namespace provides functions for working with the RDFS-like metamodel
  built on top of Datomic. The metamodel supports:

  - Classes (:dt/Class) with inheritance via :dt/subclass-of
  - Properties (:dt/Property) with domain/range constraints
  - Typed instances via :dt/type
  - Validation including required slots, type checking, and custom validators

  Key concepts:
  - Class: A type definition (like rdfs:Class)
  - Property: An attribute definition with domain and range (like rdf:Property)
  - Resource: The root class of all things (like rdfs:Resource)
  - Slots: Properties that belong to a class

  Main entry points:
  - make/make*: Create typed instances
  - validate/valid?: Validate entities against their class
  - class-of, slots-of, ancestors-of: Introspection
  - instance-of?, subclass-of?: Type predicates"
  (:refer-clojure :exclude [cat])
  (:require [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.rules :refer [defrule clear-rulebase! all-rules] :as rule]
            [sandbar.db.fn :refer [defdbfn dbfn clear-fnbase! all-dbfn] :as fn]
            [sandbar.db.datomic :refer [entity describe] :as db]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.enforce :as fw-enforce]
            [sandbar.reactive :as reactive]
            [sandbar.security.query :as secq]))

(defn all-datatypes
  "Returns a sequence of all class idents in the database.
  These are entities where :dt/type is :dt/Class."
  []
  (map first
    (d/q '[:find ?dt :in $ :where
           [?e :dt/type :dt/Class]
           [?e :db/ident ?dt]]
      (db/db))))

(defrule direct-instance-of [?dt ?e]
  [?e :dt/type ?dt])

(defrule instance-of [?dt ?e]
  [?e :dt/type ?dt])

(defrule instance-of [?dt ?e]
  [?i  :dt/subclass-of ?dt]
  [?i  :db/ident  ?p]
  (instance-of ?p ?e))

(defn direct-instances-of
  "Returns all entities that are direct instances of class dt.
  Direct instances have :dt/type exactly equal to dt, not a subclass.
  Returns entity maps."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?e :in $ % ?dt :where
              (direct-instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn all-instances-of
  "Returns all entities that are instances of class dt or any of its subclasses.
  Uses the instance-of Datalog rule for recursive subclass traversal.
  Returns entity maps."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?e :in $ % ?dt :where
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn named-idents-of
  "Return the :db/ident keywords of named instances of class dt, including subclasses.
  Use named-entities-of when the caller needs entity values rather than idents.
  Replaces the deprecated all-named-instances-of alias."
  [dt]
  (map first
       (d/q '[:find ?ident :in $ % ?dt :where
              [?e :db/ident ?ident]
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn named-entities-of
  "Return entity views for named instances of class dt, including subclasses.
  Use named-idents-of when only the identifiers are needed."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?ident :in $ % ?dt :where
              [?e :db/ident ?ident]
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn ^{:deprecated "0.1.0"} all-named-instances-of
  "Deprecated alias for named-idents-of. Returns ident keywords, not entities.
  Use named-entities-of when entity values are required."
  [dt]
  (named-idents-of dt))

(defn all-classes
  "Returns the :db/ident keywords of all classes in the metamodel.
  Equivalent to (named-idents-of :dt/Class)."
  []
  (named-idents-of :dt/Class))

(defn all-properties
  "Returns the :db/ident keywords of all properties in the metamodel.
  Equivalent to (named-idents-of :dt/Property)."
  []
  (named-idents-of :dt/Property))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S7 EP-1 — THE UNCONDITIONAL FIREWALL FLOOR (BU-4 / CA-1)
;;
;; The firewall floor is a SECURITY boundary, NOT a schema check.  It fires on
;; EVERY interactive commit path INDEPENDENT of `:validate?` (which now gates
;; SCHEMA required/type/cardinality checks ONLY).  The check is a PURE predicate
;; over the edge's src/tgt LABELS — principal-INDEPENDENT (rejects on the EDGE,
;; never the caller).  Delegated wholesale to `sandbar.firewall.enforce`, which
;; NEVER requires this ns back (R14 acyclicity).  Per S7-PLAN §4 / CA-1.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- firewall-guard!
  "Run the EP-1 author-time flow check for entity-spec `props` of class `dt`
  and THROW an ex-info `\"Firewall violation\"` carrying the `{:errors [...]}`
  envelope when any governed edge is FORBIDDEN — else return nil (the write
  proceeds).  `:skipped` (unresolved governed targets) are WARN-logged, never
  fatal (the best-effort carrier / stub case, §4.4).

  UNCONDITIONAL: called on every interactive commit path (make*, make,
  update-entity!) regardless of `:validate?`.  Reads the CURRENT db as the
  resolver's snapshot.  `spec-index` (optional) threads same-batch forward-refs
  for the batch floor (CA-6)."
  ([dt props] (firewall-guard! dt props nil))
  ([dt props spec-index]
   (let [{:keys [violations skipped]}
         (fw-enforce/check-entity-flow (db/db) dt props spec-index)]
     (fw-enforce/warn-skipped! skipped)
     (when-let [envelope (fw-enforce/verdicts->error-envelope violations)]
       (log/debug :DT/FIREWALL-VIOLATION {:class dt :violations (count violations)})
       (throw (ex-info "Firewall violation" envelope))))))

(defn- firewall-batch-guard!
  "Run the EP-1 batch flow floor over `entity-specs` (each carrying `:dt/type`)
  and THROW an ex-info `\"Firewall violation in batch\"` when any governed edge
  is FORBIDDEN — else return nil.  Firewall-ONLY (the trust-caller bulk contract
  keeps schema checks the caller's job, R5).  `spec-index` (optional) is the
  pre-built intra-batch index so a same-batch forward-ref resolves against its
  sibling (CA-6).  Per S7-PLAN §4.3."
  ([entity-specs]
   (firewall-batch-guard! entity-specs (fw-enforce/index-specs-by-ident entity-specs)))
  ([entity-specs spec-index]
   (let [{:keys [violations skipped]}
         (fw-enforce/check-batch (db/db) entity-specs spec-index)]
     (fw-enforce/warn-skipped! skipped)
     (when (seq violations)
       (log/debug :DT/FIREWALL-VIOLATION-BATCH {:violations (count violations)})
       (throw (ex-info "Firewall violation in batch"
                       (fw-enforce/verdicts->error-envelope violations)))))))

(defdbfn assert-basis [db expected-t]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :db-fn
   :dt.fn/status       :validated
   :dt.fn/description  "Abort the transaction unless the database it is applied to has basis-t expected-t — the transactor-side half of a preflight-then-commit strict write, so nothing accepted between the preflight and the commit can invalidate what the preflight checked (RT-01, D6 2026-09-19)."
   :dt.fn/version      "1.0.0"}
  (let [actual (datomic.api/basis-t db)]
    (if (= expected-t actual)
      []
      (throw (ex-info (str "basis moved between preflight and commit: expected " expected-t ", found " actual)
                      {:type :basis-moved :expected expected-t :actual actual})))))

(defn basis-moved?
  "True when `ex`, or any cause beneath it, is the `:assert-basis` guard's
   refusal — the transactor wraps a transaction function's throw, so the
   marker is looked for down the whole chain, by ex-data and by message."
  [^Throwable ex]
  (loop [e ex]
    (cond
      (nil? e) false
      (or (= :basis-moved (:type (ex-data e)))
          (some-> (.getMessage e) (.contains "basis moved between preflight and commit"))) true
      :else (recur (.getCause e)))))

(defn transact-with-preflight!
  "Transact `tx-data`, but only after `pre-commit` has accepted it on a
   SPECULATIVE database (RT-01, D6 2026-09-19).  `pre-commit` is a fn of
   the `d/with` result's db and the eid that `main-tid` (a string tempid,
   or an existing eid) resolves to in it; if it throws, NOTHING is
   transacted, so a strict write refused by its shapes leaves no committed
   facts, enqueues no projection and notifies nobody.  The real
   transaction carries `[:assert-basis t]`, the transactor-side guard
   that aborts the commit if any transaction landed after the basis `t`
   the preflight examined — a concurrent change to the facts or shapes it
   checked cannot slip between check and commit.  On that abort the
   preflight runs again at the moved basis, up to `max-attempts` (3),
   after which the guard's refusal propagates.  Without `pre-commit` this
   is a plain transact.  Returns the transaction result map."
  ([tx-data main-tid pre-commit]
   (transact-with-preflight! tx-data main-tid pre-commit 3))
  ([tx-data main-tid pre-commit max-attempts]
   (if-not pre-commit
     @(d/transact (db/conn) tx-data)
     (loop [attempt 1]
       (let [db    (d/db (db/conn))
             basis (d/basis-t db)
             with  (d/with db tx-data)
             eid   (get (:tempids with) main-tid main-tid)]
         (pre-commit (:db-after with) eid)
         (let [outcome (try
                         @(d/transact (db/conn) (into [[:assert-basis basis]] tx-data))
                         (catch Throwable ex
                           (if (basis-moved? ex) ::basis-moved (throw ex))))]
           (if (not= ::basis-moved outcome)
             outcome
             (if (< attempt max-attempts)
               (do (log/info :DT/PREFLIGHT-RETRY {:attempt attempt :basis basis})
                   (recur (inc attempt)))
               (throw (ex-info (str "the database moved under a strict write " attempt
                                    " times between preflight and commit; refusing")
                               {:type :basis-moved :attempts attempt}))))))))))

(defn make*
  "Creates a typed instance without validation.

  Arguments:
    dt    - The class ident (keyword) for the new instance
    props - Optional map of property values

  Returns the newly created entity map.

  Example:
    (make* :User {:user/login \"dan\" :user/secret \"hash\"})

  Note: Use `make` instead for validated instance creation.

  S7 CA-1: an UNCONDITIONAL firewall guard fires immediately before the
  raw `d/transact` — `make*` is the unvalidated primitive `make`/`make-all`
  bottom out in, so guarding it here closes the `:validate? false` bypass at
  the transactor boundary (no interactive write reaches Datomic un-firewalled).

  Bug C10 fix (2026-05-22): the entity is identified by a NAMED
  string tempid so the post-transact eid lookup is deterministic.
  The prior implementation used `(-> result :tempids vals first
  entity)`, which is unsound when the transact contains MORE THAN
  ONE tempid — e.g., when props carries cardinality-many ref slots
  whose values are `:db.unique/identity` upsert-maps (each generates
  its own tempid).  `(first (vals ...))` over an unordered tempids
  map then non-deterministically returns the wrong entity.

  Named-tempid lookup ensures we always recover the MAIN entity
  regardless of how many secondary tempids the upsert resolution
  produces.  If `props` already declares `:db/id`, that takes
  precedence (caller-explicit identity wins).

  `opts` `:pre-commit` (RT-01, D6 2026-09-19): a fn of the speculative db
  and the new eid, run BEFORE the transaction via `transact-with-preflight!`;
  when it throws, nothing is transacted."
  ([dt] (make* dt {}))
  ([dt props] (make* dt props {}))
  ([dt props {:keys [pre-commit]}]
   ;; S7 CA-1: unconditional firewall floor BEFORE the raw transact.  Throws
   ;; on a forbidden governed edge whether or not the caller ran validation.
   (firewall-guard! dt props)
   (let [main-tid    (or (:db/id props) "main")
         row         (assoc props :dt/type dt :db/id main-tid)
         result      (transact-with-preflight! [row] main-tid pre-commit)
         new-eid     (get (:tempids result) main-tid main-tid)
         new-entity  (entity new-eid)]
     (log/debug :DT/MAKE {:class dt :entity-id (:db/id new-entity)})
     new-entity)))

(declare slots-of)  ; forward reference; defined later in this ns

(defn unique-of
  "Returns the `:db/unique` value of a slot (`:db.unique/identity` /
   `:db.unique/value` / nil) — looks up the Property entity by ident
   via the live Datomic connection.

   Added 2026-05-20 for the bootstrap-memory-substrate sub-arc — needed
   by `sandbar.codec.markdown/frontmatter->slots` to resolve string
   values at ref-typed slots as unique-identity upsert maps without
   hardcoding consumer-class knowledge."
  [slot]
  (when slot
    (some-> slot entity :db/unique)))

(defn unique-identity-slot-of
  "Returns the FIRST `:db.unique/identity` slot declared on `class-ident`,
   or nil if none exists.  Used by `sandbar.codec.markdown` to wrap
   string values at ref-typed slots as upsert maps without hardcoding
   `{:mm/Tag :mm.tag/value}` consumer-class knowledge."
  [class-ident]
  (some (fn [slot]
          (when (= :db.unique/identity (unique-of slot))
            slot))
        (slots-of class-ident)))

(defn make-all*
  "Create typed entity-specs in one Datomic transaction without class validation.
  Each spec carries :dt/type and its entity properties. Returns the Datomic
  transaction result, not a vector of entities. Cross-spec ident references
  resolve in the same transaction.

  The non-optional batch firewall checks governed edges before transacting;
  required slots, modeled ranges, cardinality checks and shape evaluation
  remain the caller's responsibility. Use make-all for class data validation.
  The second arity accepts an existing intra-batch spec-index for reference
  resolution. Single-entity counterparts are make and make*."
  ([entity-specs]
   (make-all* entity-specs (fw-enforce/index-specs-by-ident entity-specs)))
  ([entity-specs spec-index]
   (firewall-batch-guard! entity-specs spec-index)
   (let [result @(d/transact (db/conn) entity-specs)]
     (log/debug :DT/MAKE-ALL* {:count (count entity-specs)})
     result)))

(defn make-all-with-retractions*
  "`make-all*` plus explicit retraction ops in the SAME transaction — the
   replacement import's per-file unit (REP-03, D7 2026-09-20): the batch
   firewall floor runs over `entity-specs` (the asserted half, maps with
   `:dt/type`), and `ops` (`[:db/retract …]` / `[:db.fn/retractEntity …]`
   vectors the planner derived from the store) transact with them, so a
   file's re-import replaces its source-owned representation atomically.
   Firewall-only, like `make-all*`.  Returns the transaction result map."
  ([entity-specs ops] (make-all-with-retractions* entity-specs ops nil))
  ([entity-specs ops expected-basis]
   ;; D7-R3 (Astra, 2026-09-20): the plan was computed against a database
   ;; value; `[:assert-basis t]` in the SAME transaction aborts the commit if
   ;; anything landed since, so a citation added between planning and apply
   ;; can never be erased with the section it cites.  Nil = unguarded.
   (firewall-batch-guard! entity-specs (fw-enforce/index-specs-by-ident entity-specs))
   (let [guard  (when expected-basis [[:assert-basis expected-basis]])
         result @(d/transact (db/conn) (-> (vec guard) (into ops) (into entity-specs)))]
     (log/debug :DT/MAKE-ALL-WITH-RETRACTIONS {:entities (count entity-specs) :retractions (count ops)
                                               :expected-basis expected-basis})
     result)))

(declare validate-data)          ;; forward declaration
(declare type-isa?)              ;; forward reference; defined later in this ns
(declare coerce-ref-slot-values) ;; forward reference; defined with ref->eid

(def ^:dynamic *default-actor*
  "Ident (keyword) or eid of the actor on whose behalf substrate writes
   are performed — or nil.  When bound (e.g. by an MCP / orchestrator
   boundary that knows the calling actor), `make` defaults
   `:mm.memory/created-by` to it for :mm/Memory subclasses.  nil ⇒ no
   created-by default (provenance is left unset, never fabricated)."
  nil)

(defn- apply-memory-defaults
  "For :mm/Memory subclasses, supply provenance/temporal slots the caller
   omitted: `:mm.memory/created` + `:mm.memory/last-touched` ⇒ now;
   `:mm.memory/created-by` ⇒ [*default-actor*] when that var is bound.
   Absent-only — explicit slots AND codec-parsed frontmatter both win
   (this runs AFTER the codec merge in `make`).  No-op for non-:mm/Memory
   classes.  Root fix for MCP-/programmatically-created memorials that
   lacked these slots and therefore dropped out of `:mm.memory/last-touched`
   recency views (e.g. arcs created via `entity.create`)."
  [dt props]
  (if (type-isa? :mm/Memory dt)
    (let [now (java.util.Date.)]
      (cond-> props
        (not (contains? props :mm.memory/created))
        (assoc :mm.memory/created now)
        (not (contains? props :mm.memory/last-touched))
        (assoc :mm.memory/last-touched now)
        (and *default-actor* (not (contains? props :mm.memory/created-by)))
        (assoc :mm.memory/created-by [*default-actor*])))
    props))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Future-timestamp guard (reliability sprint 2026-09-18, item 2.4)
;;
;; `:mm.memory/created` and `:mm.memory/last-touched` ORDER the record: the
;; session-open banner, the recall re-ranking and every recency view sort on
;; them, and under the files-canonical ruling they are part of the record
;; itself.  A hand-stamped value that runs ahead of real time outranks
;; genuinely newer work and misattributes sequence between two collaborators
;; writing the same morning (observed 2026-09-18: one session one to three
;; hours ahead of UTC, the next up to forty minutes ahead).  Per
;; observations/manually_stamped_timestamps_run_ahead_of_utc_stamp_from_-
;; clock_read_before_write_mechanize_future_timestamp_guard_2026_09_18.
;;
;; The guard is a FLOOR like the firewall guard: it fires in `make` and
;; `update-entity!` regardless of `:validate?`, because a `{:validate? false}`
;; caller is skipping SCHEMA checks, not opting out of the clock.  It covers
;; the codec path for free — `make` merges codec-parsed frontmatter BEFORE
;; the guard runs, so a `created:` line in the future is refused exactly like
;; an explicit slot.  Only the two slots above are guarded; other instant
;; slots (`:mm.memory/last-reviewed`, `:mm.schedule/until`, ...) legitimately
;; live in the future and are untouched.  The bulk paths (`make-all` /
;; `make-all*` — project.import and the corpus-ingest scripts) do NOT run it:
;; the file is the record in that direction (sprint item 2.3 reports at
;; import rather than throwing).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const default-future-timestamp-skew-seconds
  "Allowed skew AHEAD of server time, in seconds, when neither the dynamic
   override nor the config key supplies one."
  60)

(def ^:const future-timestamp-skew-config-key
  "Top-level config key (config/config.edn or the client .sandbar/config.edn)
   holding the allowed skew in SECONDS.  Absent, negative or non-numeric ⇒
   `default-future-timestamp-skew-seconds`."
  :future-timestamp-skew-seconds)

(def ^:dynamic *future-timestamp-skew-ms*
  "Per-call override of the allowed skew, in MILLISECONDS.  nil (the default)
   ⇒ resolve from the config key, then the 60 s default.  Bind it where a
   tighter or looser window is wanted (tests).  There is deliberately no
   'off' value — bind a large skew instead."
  nil)

(def future-timestamp-guarded-slots
  "The two temporal slots the guard covers — nothing else."
  [:mm.memory/created :mm.memory/last-touched])

(defn future-timestamp-skew-ms
  "Resolve the allowed skew in ms: `*future-timestamp-skew-ms*` > config key
   `:future-timestamp-skew-seconds` > 60 s.  A config read failure or a
   negative / non-numeric value falls through to the default — the guard
   never blocks a write because config is unreadable."
  []
  (or *future-timestamp-skew-ms*
      (let [secs (try ((requiring-resolve 'sandbar.util.edn/config-value)
                       future-timestamp-skew-config-key)
                      (catch Throwable _ nil))]
        (when (and (number? secs) (not (neg? secs)))
          (long (* 1000 secs))))
      (* 1000 default-future-timestamp-skew-seconds)))

(defn- instant->epoch-ms
  "Epoch millis of a java.util.Date / java.time.Instant; nil for any other
   shape (a string or a collection is left to schema type validation)."
  [v]
  (cond
    (instance? java.util.Date v)    (.getTime ^java.util.Date v)
    (instance? java.time.Instant v) (.toEpochMilli ^java.time.Instant v)
    :else                           nil))

(defn- epoch-ms->iso [ms]
  (str (java.time.Instant/ofEpochMilli ms)))

(defn future-timestamp-errors
  "Error maps — one per guarded slot in `props` whose value runs more than the
   allowed skew AHEAD of `now` — in the entry shape `validate-data` produces
   (`:type` / `:slot` / `:message`) plus the guard's own `:value`,
   `:server-time`, `:ahead-ms` and `:allowed-skew-ms`.  Empty vector when
   nothing is ahead.  `now` is a java.util.Date and defaults to the server
   clock.  Shared by the commit floor (`make` / `update-entity!`) and the
   read-only advisory arm (`entity.validate`), so the two never disagree."
  ([props] (future-timestamp-errors props (java.util.Date.)))
  ([props ^java.util.Date now]
   (let [skew   (future-timestamp-skew-ms)
         now-ms (.getTime now)]
     (into []
           (keep (fn [slot]
                   (let [v (get props slot)]
                     (when-some [v-ms (instant->epoch-ms v)]
                       (let [ahead (- v-ms now-ms)]
                         (when (> ahead skew)
                           {:type            :future-timestamp
                            :slot            slot
                            :value           v
                            :server-time     now
                            :ahead-ms        ahead
                            :allowed-skew-ms skew
                            :message (str "Slot " slot " is " (epoch-ms->iso v-ms)
                                          ", " ahead " ms ahead of server time "
                                          (epoch-ms->iso now-ms)
                                          " (allowed skew " skew " ms). Stamp from"
                                          " a clock read at or before the write;"
                                          " never estimate forward.")}))))))
           future-timestamp-guarded-slots))))

(defn- future-timestamp-guard!
  "Throw the validation-failure envelope when any guarded slot in `props` is
   ahead of server time by more than the allowed skew; else nil.  `phase` is
   :create or :update and selects the message its schema-failure sibling uses
   (\"Validation failed\" / \"Validation failed on update\") so callers that
   match on the message keep working; `:sandbar/error :future-timestamp` is
   the machine-readable discriminator."
  [dt props phase]
  (when-let [errs (seq (future-timestamp-errors props))]
    (log/debug :DT/FUTURE-TIMESTAMP-REJECTED
               {:class dt :phase phase :slots (mapv :slot errs)})
    (throw (ex-info (if (= :update phase)
                      "Validation failed on update"
                      "Validation failed")
                    {:errors        (vec errs)
                     :sandbar/error :future-timestamp
                     :class         dt
                     :phase         phase
                     :hint (str "Read the clock (date -u) immediately before the"
                                " write and stamp "
                                (apply str (interpose " / " (map str future-timestamp-guarded-slots)))
                                " with that value — or omit them on create to"
                                " receive server time.")}))))

(defn make
  "Creates a typed instance with pre-transaction validation.

  Arguments:
    dt    - The class ident (keyword) for the new instance
    props - Optional map of property values
    opts  - Optional options map:
            :validate? - if false, skips validation (default true)
            :format    - codec format keyword (e.g., :markdown / :json)
                         When provided with :source, parses source via
                         the codec mediator and uses the resulting
                         entity-spec as the props base; explicit `props`
                         keys override parsed slots
            :source    - raw native-representation string to parse via
                         :format codec (e.g., markdown text for :markdown)
            :strict?   - when true with :source, the codec refuses unknown
                         front-matter keys, guard-dropped values and wrong-typed
                         values with a verdict instead of carrying or dropping
                         them (D6, 2026-09-19)
            :pre-commit - a fn of the SPECULATIVE db and the new eid, run
                         before the transaction (`transact-with-preflight!`);
                         when it throws, nothing is transacted — the strict
                         shape-validation boundary (RT-01, D6 2026-09-19)

  Returns the newly created entity map.

  Throws ex-info with {:errors [...]} if validation fails.

  Validation includes:
    - Class is not abstract
    - Required slots are present
    - Slot values match their declared range types
    - Cardinality constraints are satisfied

  Example:
    (make :User {:user/login \"dan\"})
    (make :User {:user/login \"dan\"} {:validate? false})

    ;; Parse markdown via codec.markdown; transact result
    (make :mm/Memory {} {:format :markdown
                          :source \"---\\nname: Foo\\n---\\n# Body\\n\"})"
  ([dt] (make dt {} {}))
  ([dt props] (make dt props {}))
  ([dt props {:keys [validate? format source project? strict? pre-commit] :or {validate? true}}]
   ;; F.1 codec arc Stage F per
   ;; plans/sandbar_codec_layer_arc_2026-05-12.md — when :format +
   ;; :source supplied, parse via the codec mediator first; explicit
   ;; props override parsed slots.
   ;;
   ;; Signal 3 (Stage G analysis) — when :source is provided WITHOUT
   ;; explicit :format, fall back to the class's :dt/native-codec
   ;; attribute (the mediator's class-default resolution semantics).
   ;; Symmetric with codec/parse's class-aware default.
   ;;
   ;; Stage A.5 of SSE-reactive-projection arc (decision eid
   ;; 17592186094347 + plan eid 17592186094359): on successful create,
   ;; invoke `reactive/on-entity-changed!` to fire the reactive-
   ;; projection hook.  `:project?` kwarg participates in three-layer
   ;; opt-out resolution (per-call kwarg > dynamic binding > class-
   ;; level skip-list).  Hook is a no-op when no callbacks registered.
   (let [resolved-format (or format
                             (when source
                               (:dt/native-codec (entity dt))))
         props (if (and resolved-format source)
                 (let [parse-fn (requiring-resolve 'sandbar.codec/parse)
                       ;; :strict? (D6, 2026-09-19): the interactive create passes
                       ;; true so an unknown key, a dropped value or a wrong-typed
                       ;; value is refused with a verdict BEFORE anything is built;
                       ;; absent (the bulk-import path) keeps the lenient carrier.
                       parsed   (parse-fn source (cond-> {:format resolved-format :class dt}
                                                   strict? (assoc :strict? true)))]
                   (merge (dissoc parsed :dt/type) props))
                 props)
         ;; Memorial-defaults: AFTER the codec merge (so explicit slots +
         ;; parsed frontmatter both win), absent-only.  entity.create-
         ;; defaults fix — MCP/programmatic :mm/Memory creates were missing
         ;; created/last-touched/created-by and fell out of recency views.
         props (apply-memory-defaults dt props)
         ;; Future-timestamp FLOOR (sprint 2.4, 2026-09-18) — AFTER the codec
         ;; merge (so a frontmatter `created:` is checked) and AFTER the
         ;; defaults (read from this same clock, so they cannot trip it),
         ;; BEFORE ref-coercion / schema validation; unconditional.
         _     (future-timestamp-guard! dt props :create)
         ;; Canonicalize `:db.type/ref` slot values to plain eids BEFORE both
         ;; validation and transact so the two agree — an eid / EntityMap /
         ;; {:db/id} / {:db/ident} at a ref slot all reduce to the eid Datomic
         ;; attaches, curing the reject-or-silently-drop split.  Per
         ;; bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.
         props (coerce-ref-slot-values props)
         new-entity (if-not validate?
                      (make* dt props {:pre-commit pre-commit})
                      (if-let [errors (validate-data dt props)]
                        (do
                          (log/debug :DT/VALIDATION-FAILED {:class dt :errors errors})
                          (throw (ex-info "Validation failed" errors)))
                        (make* dt props {:pre-commit pre-commit})))]
     (reactive/on-entity-changed! dt new-entity project?)
     new-entity)))

(defn make-all
  "Validate each typed entity-spec with validate-data, then transact the batch
  atomically. Returns the Datomic transaction result. A class data failure
  throws ExceptionInfo with {:errors [{:errors [...] :index n :class ident} ...]
  :total n} before any member is transacted. The batch firewall also applies.

  This validates class data; it does not run shape or custom class validators.
  Cross-spec references use the same transaction as make-all*. Optional
  :project? is forwarded to reactive notification after commit for resolvable
  entities. Notification failures are logged and do not undo accepted data.
  Use make-all* when the caller owns class data validation."
  ([entity-specs] (make-all entity-specs {}))
  ([entity-specs {:keys [project?]}]
   ;; S7 CA-6: build the intra-batch spec-index ONCE and thread it into the
   ;; firewall floor (via make-all* below) so a same-batch forward-ref (a
   ;; sibling `:db/ident` declared later in the batch, both public) resolves
   ;; against its sibling rather than over-refusing on tx-ordering.  The
   ;; schema validate-data pass is unchanged — it already admits the codec's
   ;; upsert-map cross-ref shape without a live-DB resolve.
   (let [spec-index (fw-enforce/index-specs-by-ident entity-specs)
         failures (keep-indexed
                    (fn [i spec]
                      (let [dt        (:dt/type spec)
                            spec-only (dissoc spec :dt/type)]
                        (when-let [errs (validate-data dt spec-only)]
                          (assoc errs :index i :class dt))))
                    entity-specs)]
     (if (seq failures)
       (do
         (log/debug :DT/MAKE-ALL-VALIDATION-FAILED
                    {:total (count entity-specs) :failures (count failures)})
         (throw (ex-info "Validation failed for one or more entities"
                         {:errors (vec failures)
                          :total  (count entity-specs)})))
       (let [tx-result (make-all* entity-specs spec-index)]
         ;; Per-entity reactive-projection hook fire
         (doseq [spec entity-specs
                 :let [class-ident  (:dt/type spec)
                       ident-or-eid (or (:db/ident spec) (:db/id spec))]
                 :when (and class-ident ident-or-eid)]
           (try
             (when-let [ent (entity ident-or-eid)]
               (reactive/on-entity-changed! class-ident ent project?))
             (catch Throwable t
               (log/warn t :REACTIVE/make-all-hook-skipped
                         {:spec-class class-ident
                          :spec-ident ident-or-eid}))))
         tx-result)))))

(defn realize-with
  "Realize a seed and the entities selected by walk-fn as a vector of entity
  specification maps, in breadth-first order including the seed.

  The seed and each related item may be a Datomic entity, ident keyword or
  numeric eid. Each is resolved before traversal and deduplicated by eid.
  walk-fn receives an entity and returns its related items; it defines the
  representation boundary, so this is not a dump of every reachable reference."
  [entity walk-fn]
  (let [->entity (fn [x]
                   (if (or (keyword? x) (number? x))
                     (db/entity x)
                     x))
        seed     (->entity entity)]
    (loop [acc      []
           visited  #{}
           frontier [seed]]
      (if (empty? frontier)
        acc
        (let [next-frontier (atom [])
              new-acc (reduce
                        (fn [a e]
                          (let [eid (:db/id e)]
                            (if (or (nil? eid) (contains? visited eid))
                              a
                              (let [related (or (walk-fn e) [])
                                    e-map   (into {:dt/type (:dt/type e)} e)]
                                (doseq [r0 related
                                        :let [r     (->entity r0)
                                              r-eid (:db/id r)]]
                                  (when (and r-eid (not (contains? visited r-eid)))
                                    (swap! next-frontier conj r)))
                                (conj a e-map)))))
                        acc
                        frontier)
              new-visited (into visited (keep :db/id frontier))]
          (recur new-acc new-visited @next-frontier))))))

(defn emit-entity
  "Emit an entity through the codec mediator and return the representation.
  Accepts an entity view, plain entity map or numeric eid. Resolve an ident
  with find-by-ident before calling. Options include :format; other options
  are forwarded to the selected codec. The default format comes from the
  class's :dt/native-codec declaration.

  Parsing and emitting form a supported codec round trip, with preservation
  determined by the selected codec rather than by this wrapper."
  ([entity] (emit-entity entity {}))
  ([entity opts]
   (let [emit-fn (requiring-resolve 'sandbar.codec/emit)
         ;; Realize Datomic entity → plain map (codecs operate on
         ;; entity-spec maps, not Entity records).
         entity-map (cond
                      (map? entity) entity
                      (number? entity) (into {} (db/entity entity))
                      :else (into {} entity))]
     (emit-fn entity-map opts))))

;; --- Cardinality-many REPLACE semantics (W0.found 2026-06-30) ---
;; Per decisions/entity_update_card_many_replace_by_default_opt_in_additive_2026_06_30:
;; update-entity! REPLACES a card-many slot's set by default (retract the
;; prior members absent from the supplied set, then assert the supplied
;; set); callers opt into the legacy additive UNION via {:additive? true}.
;; Reuses the set-replace diff shape proven in sandbar.db.datomic for class
;; meta-slots (normalize refs by :db/ident; retract refs by :db/id).

(defn- ref->eid
  "Resolve any ref-typed slot value to the :db/id of the live entity it names,
   or nil when it resolves to no live entity.

   A 1-line forwarder into the substrate canon `sandbar.db.ref/ref->eid`
   (S7 BU-0 / ruling R13), supplying the CURRENT db.  Accepts every shape a
   caller can hand a `:db.type/ref` slot — a Datomic Entity map, a `{:db/id eid}`
   map, an eid Long, an ident keyword (resolved THROUGH the db, curing the
   `(:db/id keyword)→nil` sentinel collapse, S6-review #1), a Datomic lookup-ref
   vector `[:unique-attr v]`, and a single-key upsert map `{:db/ident kw}` /
   `{<unique-identity-attr> v}` (the codec's ref shape).

   The single ref→eid canon shared by callers that MUST agree: the card-many
   replace diff (stable set-membership comparison), `make`'s pre-transact ref
   coercion, and `value-matches-range?`'s existence check.  nil ⇒ unresolvable
   — treated as a non-matching member by the diff, and (in `make`) left
   uncoerced so validation rejects it loudly rather than silently dropping it.

   Delegates to the true-leaf `sandbar.db.ref` so `sandbar.firewall.*` and
   `sandbar.mcp.clearance` share the identical normalization without pulling
   this ns (acyclicity, ruling R14).

   Per bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md
   (validation-and-transaction disagreed on ref shapes; upsert maps validated
   then silently dropped on the single-tx create path)."
  [v]
  (ref/ref->eid (db/db) v))

(defn- ref-valued-slot?
  "True if `slot-ident`'s property is a `:db.type/ref` slot (its values name
   other entities rather than carrying literals)."
  [slot-ident]
  (= :db.type/ref (:db/valueType (entity slot-ident))))

(defn- coerce-ref-slot-values
  "Canonicalizes every `:db.type/ref` slot value in `props` to a plain eid via
   `ref->eid`, returning the rewritten props map.  Card-one slots coerce the
   lone value; card-many slots coerce each member.  A value `ref->eid` cannot
   resolve is left UNCHANGED so downstream validation rejects it loudly —
   coercion never fabricates or silently drops.

   Why this exists: the single-entity `make`/`make*` create path transacted
   ref values verbatim, so an eid or a Datomic EntityMap failed `:dt/Ref`
   validation while a `{:db/id eid}` / `{:db/ident kw}` map validated and then
   silently dropped (Datomic does not attach a bare nested map at a card-one
   ref).  Coercing to the canonical eid — the same shape the update path's
   card-many diff already normalizes to — makes validation and transaction
   agree on every accepted ref shape.

   Per bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md."
  [props]
  (reduce-kv
   (fn [acc slot v]
     (if (and (some? v) (ref-valued-slot? slot))
       (let [coerce-one (fn [x] (or (ref->eid x) x))]
         (assoc acc slot
                (cond
                  (set? v)        (into #{} (map coerce-one) v)
                  (sequential? v) (into (empty v) (map coerce-one) v)
                  :else           (coerce-one v))))
       (assoc acc slot v)))
   {}
   props))

(defn- card-many-replace-retracts
  "For each cardinality-many slot present in `slot-updates`, return the
   [:db/retract eid slot v] ops removing prior members NOT in the supplied
   desired set — the retract half of replace-by-diff.  Card-one slots
   produce no retracts (Datomic auto-retracts the prior single value on
   assert).  Ref slots canonicalize BOTH prior and desired to :db/id so an
   unchanged member is never retracted-and-re-added in the same tx (which
   Datomic would resolve ambiguously); scalar slots compare by value."
  [ent eid slot-updates]
  (mapcat
   (fn [[slot new-val]]
     (let [prop (entity slot)]
       (when (= :db.cardinality/many (:db/cardinality prop))
         (let [ref?    (= :db.type/ref (:db/valueType prop))
               canon   (if ref? ref->eid identity)
               new-vec (if (sequential? new-val) new-val [new-val])
               desired (set (map canon new-vec))
               prior   (get ent slot)]
           (for [v prior
                 :when (not (contains? desired (canon v)))]
             [:db/retract eid slot (if ref? (ref->eid v) v)])))))
   slot-updates))

(defn update-entity!
  "Update an entity view, ident or eid and return the refreshed entity.
  With :validate? true (the default), validate the merged class data before
  transacting. This does not by itself invoke custom class or shape validators.

  Cardinality-many values replace the supplied slot's prior set atomically;
  :additive? true requests union instead. Cardinality-one follows Datomic's
  replacement behavior. :project? controls reactive projection participation.
  A :pre-commit callback receives the speculative database and eid;
  throwing refuses the proposal before commit.
  For a Memory whose native body is :mm.memory/body-raw, a whole-body edit
  reconciles its section tree in the same transaction. Removed sections with
  outside references refuse the edit. Sectioned edits require a stable ident.
  A body plan is valid at one database basis only: a moved basis refuses the
  edit with :body-update/basis-moved, for the caller to reread and retry.
  Other strict updates retain transact-with-preflight!'s bounded retries.
  Accepted updates notify the reactive mechanism after the transaction."
  ([entity slot-updates] (update-entity! entity slot-updates {}))
  ([entity slot-updates {:keys [validate? project? additive? pre-commit] :or {validate? true}}]
   (when-not (map? slot-updates)
     (throw (ex-info "update-entity! requires slot-updates to be a map"
                     {:received slot-updates})))
   (let [resolved (cond
                    (associative? entity) entity
                    :else (db/entity entity))
         eid     (or (:db/id resolved)
                     (throw (ex-info "update-entity! could not resolve :db/id"
                                     {:entity entity})))
         ;; S7 (adjudication must-fix #4): build the firewall SOURCE label —
         ;; and the card-many retracts + validation — from the CANONICAL DB
         ;; row read by eid, NEVER the caller-supplied map.  A caller passing a
         ;; SPARSE map (e.g. {:db/id .. :dt/type ..}) would otherwise omit
         ;; :mm.memory/visibility, defeating intrinsic-visibility-label's
         ;; declassification guard (T-12) and letting a public row be re-owned
         ;; into a private project.  The trust model is "labels are on the
         ;; data", not on the caller's shape — so read the data.
         ent     (or (db/entity eid) resolved)
         ;; Inline class-ident lookup (class-ident-of is defined below
         ;; in this file; avoid forward-reference for compile order)
         class-ident (:dt/type ent)
         ;; Merged shape — existing + updates (updates win).
         merged  (merge (into {} ent) slot-updates)]
     (when-not class-ident
       (throw (ex-info "update-entity! requires entity to have :dt/type"
                       {:entity entity :merged merged})))
     ;; S7 CA-1: UNCONDITIONAL firewall floor — OUTSIDE the `validate?` block so
     ;; an update ADDING a forbidden governed edge is refused whether or not the
     ;; caller ran schema validation.  Checks the MERGED shape (existing slots +
     ;; updates) so the src label reflects the entity's real visibility /
     ;; owning-project, and every governed edge on the post-update entity is
     ;; evaluated (an update introducing a public→private `cites` throws).
     (firewall-guard! class-ident (dissoc merged :db/id))
     ;; Future-timestamp FLOOR (sprint 2.4, 2026-09-18) — checks the SUPPLIED
     ;; updates only: an inherited future stamp on the stored row must not
     ;; block an unrelated update (it is corrected by re-stamping through this
     ;; very verb).  Unconditional, like the firewall floor above.
     (future-timestamp-guard! class-ident slot-updates :update)
     ;; Physical destinations are operator authority. An ordinary entity edit
     ;; is not a file migration; reject target changes before accepting them.
     ((requiring-resolve 'sandbar.project.destination/assert-stable-update!)
      (db/db) ent slot-updates
      ((requiring-resolve 'sandbar.project.destination/global-root)))
     (when validate?
       (when-let [errors (validate-data class-ident (dissoc merged :db/id :dt/type))]
         (log/debug :DT/UPDATE-VALIDATION-FAILED {:class class-ident :errors errors})
         (throw (ex-info "Validation failed on update" errors))))
     ;; Transact: assert the supplied slot values.  For cardinality-many
     ;; slots this REPLACES the prior set (retract prior members absent
     ;; from the supplied set, prepended so they execute before the assert
     ;; in the same tx) unless the caller opts into additive UNION via
     ;; :additive? true.  Card-one slots are unaffected (Datomic
     ;; auto-retracts the prior value on assert).  Per
     ;; decisions/entity_update_card_many_replace_by_default_opt_in_additive_2026_06_30.
     (let [retracts (when-not additive?
                      (card-many-replace-retracts ent eid slot-updates))
           ;; Whole-body edits replace their derived section representation in
           ;; the SAME transaction. Otherwise reads/search show the new body
           ;; while the filesystem/export emits old sections. Load the codec
           ;; planner lazily: it depends on datatype introspection.
           body-plan (when (and (contains? slot-updates :mm.memory/body-raw)
                                (type-isa? :mm/Memory class-ident))
                       ((requiring-resolve 'sandbar.import/plan-body-update)
                        (db/db) eid slot-updates))
           sections (:sections body-plan)
           _ (when (seq sections)
               (firewall-batch-guard! sections (fw-enforce/index-specs-by-ident sections)))
           tx (-> (cond-> [] body-plan (conj [:assert-basis (:basis body-plan)]))
                  (into retracts)
                  (into (:ops body-plan))
                  (into sections)
                  (conj (merge slot-updates (:host-updates body-plan) {:db/id eid})))]
       (when (seq retracts)
         (log/info :DT/UPDATE-CARD-MANY-REPLACE
                   {:eid eid :class class-ident :retract-count (count retracts)}))
       (if body-plan
         ;; A section diff contains removals justified at ONE basis. Reusing
         ;; that diff in the generic strict-write retry loop cannot replan it.
         ;; Check once and commit once under the plan's existing guard; any
         ;; intervening transaction is an actionable refusal, never an outage.
         (try
           (when pre-commit
             (pre-commit (:db-after (d/with (db/db) tx)) eid))
           @(d/transact (db/conn) tx)
           (catch Throwable ex
             (if (basis-moved? ex)
               (throw (ex-info "The database changed while this body edit was being planned; read the memory again and retry"
                               {:type :body-update/basis-moved
                                :retryable? true
                                :expected-basis (:basis body-plan)}
                               ex))
               (throw ex))))
         (transact-with-preflight! tx eid pre-commit)))
     ;; Stage A.5 of SSE-reactive-projection arc (decision eid
     ;; 17592186094347 + plan eid 17592186094359): on successful update,
     ;; fire the reactive-projection hook.  `:project?` participates in
     ;; the three-layer opt-out priority resolution.  No-op when no
     ;; callbacks registered.
     (let [updated-entity (db/entity eid)]
       (reactive/on-entity-changed! class-ident updated-entity project?)
       updated-entity))))

(defn class-ident-of
  "Return an entity's declared class ident from :dt/type.
  An instance yields its class; a class entity yields its metaclass.
  Use class-entity-of on the returned ident to inspect class metadata."
  [e]
  (-> e entity :dt/type))

(defn class-entity-of
  "Resolve a class ident to its entity view for reading class metadata.
  This looks up the class itself; it does not follow an instance's :dt/type.
  For an instance, compose class-ident-of with class-entity-of."
  [class-ident]
  (db/entity class-ident))

(defn ^{:deprecated "0.1.0"} class-of
  "Deprecated alias for class-ident-of. Returns a class ident, not its entity.
  Use class-entity-of to inspect a known class's metadata."
  [e]
  (class-ident-of e))

(defn find-by-ident
  "Return the entity view for an ident, or nil when absent.
  Use to resolve names returned by named-idents-of before reading their values."
  [ident]
  (db/entity ident))

(defn native-codec-of-class
  "Return the class's directly declared :dt/native-codec keyword, or nil.
  Reads the class entity itself rather than following its :dt/type."
  [class-ident]
  (:dt/native-codec (db/entity class-ident)))

(defn codec-aliases-of
  "Return the class's directly declared codec alias map, or {}.
  The schema stores cardinality-many [short-key slot-ident] keyword tuples.
  Aliases are names used in a representation, not identity equivalence such
  as owl:sameAs. The model property keeps its qualified identity. Codecs use
  these declarations instead of hardcoding a domain's property names."
  [class-ident]
  (into {} (or (:dt/codec-aliases (db/entity class-ident)) [])))

(defn codec-slot-order-of
  "Return directly declared codec slot idents in emission order, or [].
  The schema stores [slot-ident position] tuples; this function sorts by
  position. effective-codec-slot-order-of includes inherited declarations.
  Canonical emission follows model order, not arbitrary source text order."
  [class-ident]
  (->> (db/entity class-ident)
       :dt/codec-slot-order
       (sort-by second)
       (mapv first)))

(defn codec-type-keywords-of
  "Return the set of directly declared :dt/codec-type-keyword values, or #{}.
  A document's type keyword selects the class that declares it. The property
  is cardinality-many so one class can support several representation labels."
  [class-ident]
  (or (:dt/codec-type-keyword (db/entity class-ident)) #{}))

(defn class-for-codec-type-keyword
  "Return the class ident selected by type-kw, or nil when no class claims it.
  Resolves the unique-identity :dt/codec-type-keyword attribute with a Datomic
  lookup ref. Used for model-driven document class selection."
  [type-kw]
  (when type-kw
    (when-let [e (d/entity (db/db) [:dt/codec-type-keyword type-kw])]
      (:db/ident e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fulltext primitives — Stage 2 of fulltext arc
;; (plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md)
;;
;; Three primitives at the dt/* substrate layer:
;;   bm25f-weights-of  — class-attribute getter for :dt/bm25f-weights
;;                       (sibling of codec-aliases-of)
;;   fulltext-indexed? — predicate over a slot's :db/fulltext flag
;;   search-fulltext   — single-attribute Datomic+Lucene query wrapper
;;
;; Higher-level multi-field BM25F composition lives at sandbar.search/*
;; (Stage 4); the dt/* layer exposes per-field primitives only.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bm25f-weights-of
  "Return directly declared BM25F field weights as {slot-ident weight}, or {}.
  The schema stores [slot-ident weight-double] tuples. Use
  effective-bm25f-weights-of for inheritance. The declaration selects fields
  and their weights for Sandbar's own analyzer and multi-field scoring."
  [class-ident]
  (into {} (or (:dt/bm25f-weights (db/entity class-ident)) [])))

(defn memorial-policy-of
  "Return the class's directly declared :dt/memorial-policy keyword, or nil.
  Values are :first-class, :db-only and :inline. This does not walk ancestors;
  effective-memorial-policy-of supplies inherited policy. A policy declaration
  alone does not establish a complete file representation or active sink."
  [class-ident]
  (:dt/memorial-policy (db/entity class-ident)))

(defn superseded-when-of
  "Returns the `:dt/superseded-when` declaration on `class-ident` as a set
   of `[slot-ident value]` pairs, or `#{}` if none.  A pair's value `:some`
   means the slot marks the instance superseded whenever it holds any value
   (an edge such as a superseded-by ref); any other value must match.

   Does NOT walk ancestors — call `effective-superseded-when-of`.  Consumed
   by `sandbar.search/search-bm25f` for its default ordering (D4c,
   2026-09-19).  Sister to `bm25f-weights-of` — same tuple-valued
   class-declaration shape, different attribute."
  [class-ident]
  (into #{} (map vec) (or (:dt/superseded-when (db/entity class-ident)) [])))

(defn recency-slot-of
  "Returns the `:dt/recency-slot` keyword declared directly on
   `class-ident`, or nil.  The instant-valued slot the search surface
   breaks relevance ties on (the more recent first).  Does NOT walk
   ancestors — call `effective-recency-slot-of`."
  [class-ident]
  (:dt/recency-slot (db/entity class-ident)))

(defn fulltext-indexed?
  "Return true when the property's Datomic :db/fulltext flag is enabled.
  This tests the native single-attribute index, independently of participation
  in Sandbar's class-declared BM25F field analysis."
  [attribute]
  (boolean (:db/fulltext (db/entity attribute))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregation primitives — Stage 13 of fulltext arc
;; (plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md)
;;
;; Six substrate primitives at the dt/* layer:
;;   count-of            — entity count for class (optional where-clauses)
;;   group-by-of         — group-by-count {value count} map
;;   degree-of           — outbound + inbound ref-attribute count
;;   backlink-density-of — inbound-only ref-attribute count
;;   recency-rank-of     — entities ordered by temporal slot descending
;;   freshness-rank-of   — entities ordered by temporal slot ascending
;;
;; Higher-level composition lives at sandbar.aggregate namespace.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn count-of
  "Count instances of class-ident, including subclasses, optionally restricted
  by where-clauses using ?e as the entity variable. Returns a non-negative
  integer. Clauses pass through the supported query sanitizer."
  ([class-ident]
   (count-of class-ident nil))
  ([class-ident where-clauses]
   (let [base    '[:find (count ?e) .
                   :in $ % ?class
                   :where (instance-of ?class ?e)]
         ;; SECURITY (read-plane query-layer, AP-S3-6 vector A): sanitize the
         ;; caller-supplied clauses BEFORE the splice — throws loud ex-info on
         ;; any non-allowlisted operator, so no unsafe symbol reaches d/q.
         merged  (if (seq where-clauses)
                   (apply conj base (secq/sanitize-where where-clauses))
                   base)
         result  (d/q merged (db/db) (all-rules) class-ident)]
     (or result 0))))

(defn group-by-of
  "Group subclass-inclusive instances by group-slot; return {value count}.
  Optional where-clauses restrict the population before grouping. Missing
  values produce no bucket, and a many-valued slot can contribute to several
  buckets. Clauses pass through the supported query sanitizer."
  ([class-ident group-slot]
   (group-by-of class-ident group-slot nil))
  ([class-ident group-slot where-clauses]
   (let [base    '[:find ?v (count ?e)
                   :in $ % ?class ?slot
                   :where
                   (instance-of ?class ?e)
                   [?e ?slot ?v]]
         ;; SECURITY (read-plane query-layer, AP-S3-6 vector A): sanitize before
         ;; the splice — see count-of.  Same shared gate, same fail-closed path.
         merged  (if (seq where-clauses)
                   (apply conj base (secq/sanitize-where where-clauses))
                   base)
         rows    (d/q merged (db/db) (all-rules) class-ident group-slot)]
     (into {} rows))))

(defn assert-where-eids-allowed!
  "Check numeric entity references in where-clauses against the read-plane
  namespace guard. Recursively resolves integer values to idents and rejects
  protected attributes or classes with ExceptionInfo. This is the database-
  aware companion to secq/assert-where-namespaces!, which checks keywords.
  Returns the clauses when no forbidden reference is found."
  [where-clauses]
  (when (seq where-clauses)
    (letfn [(walk [form]
              (cond
                (integer? form)
                (when-let [id (:db/ident (db/entity form))]
                  (secq/assert-ident-allowed! id))
                (map? form)  (doseq [[k v] form] (walk k) (walk v))
                (coll? form) (doseq [x form] (walk x))
                :else nil))]
      (doseq [c where-clauses] (walk c))))
  where-clauses)

(defn read-plane-group-key-firewalled?
  "Filter identity buckets by their resolved target, not its ident spelling.
   Ref slots and db/ident carry identities; other slots carry scalar data and
   are not resolved (even keywords or numbers that happen to name entities).
   Ordinary targets require an allowed actual class and caller readability.
   Metamodel definitions retain the namespace guard on the definition's own
   ident: an allowed class bucket is useful, a protected class bucket leaks
   instance counts. Untyped named targets retain the existing ident allowlist
   (including Datomic enums); unnamed or unresolved identity keys fail closed.

   The one-arity form assumes identity keys and unrestricted in-process reads;
   read adapters must supply both the slot and the caller's readability check.
   This checks targets, not the aggregate's source population; S-2 stays open."
  ([k] (read-plane-group-key-firewalled? nil k (constantly true)))
  ([group-slot k readable?]
   (let [slot-type (:db/valueType (when group-slot (db/entity group-slot)))
         identity-slot? (or (nil? group-slot) (= :db/ident group-slot)
                            (= :db.type/ref
                               (if (keyword? slot-type) slot-type (:db/ident slot-type))))]
     (if-not identity-slot?
       false
       (let [target (some->> (ref/ref->eid (db/db) k) (db/entity))
             id     (:db/ident target)
             type   (:dt/type target)
             cls    (if (keyword? type) type (:db/ident type))
             definition? (or (:db/valueType target)
                             (= "dt" (some-> cls namespace (.split "\\.") first)))]
         (boolean
           (cond
             (and cls (not (secq/read-plane-namespace-allowed? cls))) true
             definition? (not (and id (secq/read-plane-ident-allowed? id)))
             cls (not (readable? target))
             id (not (secq/read-plane-ident-allowed? id))
             :else true)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-plane identity guard over :where clauses (readable identity filters,
;; 2026-09-21).  The two older guards judge every keyword by the attribute
;; namespace list and every integer by the ident list, so a filter could never
;; name a memorial, actor, project or tag by its keyword identity while the
;; numeric spelling of the same identity passed without any readability
;; decision.  This guard tells identity POSITIONS apart from everything else:
;; an identity constant is judged by its resolved target under the revision-3
;; group-key policy; every other position keeps the older checks unchanged.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private where-identity-placeholder
  "The symbol that stands in for an identity constant when the older checks
   run over a clause: a plain logic variable, which those walks ignore."
  '?read-plane-identity)

(defn- refuse-where-identity!
  "The one non-echoing refusal for an identity constant that resolves to no
   entity, to an entity the caller may not read, or to an instance of a
   firewalled class.  Reason, message and details are identical in the three
   cases so the filter surface answers the not-found parity of the entity
   reads: nothing here names the constant, the clause or what it resolved to."
  []
  (throw (ex-info "Rejected :where identity — the value names no entity this principal may filter by."
                  {:sanitizer 'sandbar.db.datatype/assert-where-identities-allowed!
                   :reason    :filter-identity-unavailable
                   :kind      :where-identity})))

(defn- ref-attribute?
  "True when `a` is a keyword naming an installed attribute of value type ref."
  [a]
  (and (keyword? a)
       (let [vt (:db/valueType (entity a))]
         (= :db.type/ref (if (keyword? vt) vt (:db/ident vt))))))

(defn- identity-constant?
  "A keyword, an integer, or a two-element lookup ref led by a keyword."
  [x]
  (or (keyword? x) (integer? x)
      (and (vector? x) (= 2 (count x)) (keyword? (first x)))))

(defn- data-pattern-positions
  "For a data-pattern clause, `{:e :a :v}` of its entity, attribute and value
   elements after an optional leading `$` source; nil for any other clause
   shape (predicate, binding, rule invocation)."
  [clause]
  (when (and (vector? clause) (seq clause) (not (seq? (first clause))))
    (let [[e a v] (if (= '$ (first clause)) (rest clause) clause)]
      {:e e :a a :v v})))

(defn- identity-target-kind
  "Resolve one identity constant and say what it names: `:definition` (a
   class, an attribute, or an untyped named entity such as a Datomic enum —
   judged by its spelling exactly as today), `:instance` (a typed entity) or
   `:absent` (nothing).  A lookup ref's key is an attribute and passes the
   attribute policy before anything is resolved."
  [value]
  (when (vector? value)
    (secq/assert-attribute-allowed! (first value)))
  (let [target (some->> (ref/ref->eid (db/db) value) (entity))
        type   (:dt/type target)
        cls    (if (keyword? type) type (:db/ident type))]
    (cond
      (nil? target) :absent
      (or (:db/valueType target)
          (= "dt" (some-> cls namespace (.split "\\.") first))
          (and (nil? cls) (:db/ident target))) :definition
      cls :instance
      :else :absent)))

(defn- authorize-where-identity!
  "Judge an instance-or-absent identity constant in `slot` (an attribute
   keyword, or nil for the entity position) by its resolved target under the
   revision-3 policy: an allowed actual class and caller readability.  Absent,
   hidden and firewalled instances all receive the one non-echoing refusal."
  [slot value readable?]
  (when (read-plane-group-key-firewalled? slot value readable?)
    (refuse-where-identity!)))

(defn assert-where-identities-allowed!
  "Position-aware read-plane guard over parsed `:where` clauses; returns them
   unchanged.  In a data pattern, a constant in entity position, a constant
   value of a ref-typed attribute (keyword ident, numeric eid or lookup ref —
   three spellings of one identity) and a keyword value of `:db/ident` are
   identity constants.  One that names an INSTANCE, or nothing, is resolved
   and judged by the revision-3 target policy with the caller-supplied
   `readable?` (the same decision the group-by keys and the search hits use)
   and is hidden from the older checks behind a logic variable.  One that
   names a DEFINITION (a class, an attribute, an enum) is left in place, so
   the older keyword and numeric checks judge its spelling exactly as today
   and a firewalled definition keeps the refusal that names it.  Every other
   position — attributes, scalar values, values under a variable attribute,
   predicate and rule arguments, integers and lookup refs under `:db/ident` —
   keeps the older checks unchanged; the call-form sanitizer inside the
   splice sites is untouched.  Counting and grouping populations are still
   not clearance-filtered (S-2): this guard makes the SELECTOR readable, not
   the population."
  [where-clauses readable?]
  (when (seq where-clauses)
    (doseq [clause where-clauses]
      (let [{:keys [e a v]} (data-pattern-positions clause)
            shift      (if (and (vector? clause) (= '$ (first clause))) 1 0)
            e-kind     (when (and (some? e) (not (symbol? e)) (identity-constant? e))
                         (identity-target-kind e))
            v-kind     (when (and (some? v) (identity-constant? v)
                                  (or (ref-attribute? a)
                                      (and (= :db/ident a) (keyword? v))))
                         (identity-target-kind v))
            judge?     #(contains? #{:instance :absent} %)
            masked     (cond-> clause
                         (judge? e-kind) (assoc shift where-identity-placeholder)
                         (judge? v-kind) (assoc (+ shift 2) where-identity-placeholder))]
        (secq/assert-where-namespaces! [masked])
        (assert-where-eids-allowed! [masked])
        (when (judge? e-kind) (authorize-where-identity! nil e readable?))
        (when (judge? v-kind) (authorize-where-identity! a v readable?)))))
  where-clauses)

(defn degree-of
  "Total ref-attribute count for `entity-ident` — number of (attribute,
  ref-target) outbound pairs plus inbound pairs.  Counts ALL ref-typed
  attributes by default; pass `:predicates` opt to restrict to a
  predicate set.

  Direction options:
    :forward       — outbound only
    :inverse       — inbound only
    :bidirectional — sum of both (default)"
  ([entity-ident]
   (degree-of entity-ident {:direction :bidirectional}))
  ([entity-ident {:keys [direction predicates]
                  :or   {direction :bidirectional}}]
   (let [eid       (:db/id (db/entity entity-ident))
         out-rows  (when (#{:forward :bidirectional} direction)
                     (d/q '[:find ?a ?v
                            :in $ ?e
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         ;; F-MF-2 fix (Phase R Stage R-3): inverse rows project
         ;; ?a (attribute) in position 0 to match the out-rows shape;
         ;; the `match?` predicate destructures `[a _]` (attribute
         ;; first), so both row shapes must align.  Pre-fix:
         ;; `:find ?s ?a` placed the source in position 0 and the
         ;; attribute in position 1; `match?` then read the SOURCE
         ;; as the attribute and the predicate filter silently
         ;; missed every inverse row (returned 0 for any
         ;; :predicates-filtered :inverse / :bidirectional call).
         ;; Aligning to `:find ?a ?s` restores per-direction
         ;; symmetry without per-direction match functions.
         in-rows   (when (#{:inverse :bidirectional} direction)
                     (d/q '[:find ?a ?s
                            :in $ ?e
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         match?    (if (seq predicates)
                     (let [predicate-set (set predicates)]
                       (fn [[a _]]
                         (predicate-set
                           (or (:db/ident (db/entity a)) a))))
                     (constantly true))]
     (+ (count (filter match? out-rows))
        (count (filter match? in-rows))))))

(defn backlink-density-of
  "Count incoming reference edges, optionally restricted to predicates.
  Equivalent to degree-of with :direction :inverse. Several predicates from
  one entity count as several edges; this is not a distinct-source count."
  ([entity-ident]
   (backlink-density-of entity-ident nil))
  ([entity-ident predicates]
   (degree-of entity-ident {:direction :inverse :predicates predicates})))

(defn recency-rank-of
  "Return instances of `class-ident` ordered by `temporal-slot` value
  DESCENDING (most-recent first).  Returns a vec of `[entity-map
  temporal-value]` pairs; consumers may project to entities-only via
  `(map first ...)`.

  Caller supplies `temporal-slot` (e.g., `:mm.memory/last-touched`) —
  substrate does not hardcode class-specific temporal axes."
  [class-ident temporal-slot]
  (->> (d/q '[:find ?e ?t
              :in $ % ?class ?slot
              :where
              (instance-of ?class ?e)
              [?e ?slot ?t]]
            (db/db) (all-rules) class-ident temporal-slot)
       (sort-by second #(compare %2 %1))
       (mapv (fn [[eid t]] [(db/entity eid) t]))))

(defn freshness-rank-of
  "Return instances of `class-ident` ordered by `temporal-slot` value
  ASCENDING (oldest / stalest first — the freshness axis surfaces
  candidates whose temporal marker is most-in-the-past, meriting
  attention or review).  Returns a vec of `[entity-map temporal-value]`
  pairs.

  Caller supplies `temporal-slot` (typically a `:last-reviewed`-style
  attribute) — substrate does not hardcode class-specific axes."
  [class-ident temporal-slot]
  (->> (d/q '[:find ?e ?t
              :in $ % ?class ?slot
              :where
              (instance-of ?class ?e)
              [?e ?slot ?t]]
            (db/db) (all-rules) class-ident temporal-slot)
       (sort-by second)
       (mapv (fn [[eid t]] [(db/entity eid) t]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation primitives — Stage 16 (fulltext arc Phase N)
;;
;; Edge = a (predicate-attribute, entity) pair where predicate-attribute is
;; a `:db.type/ref`-typed attribute.  Outbound = edges originating FROM the
;; subject; inbound = edges pointing AT the subject.  Substrate-quality
;; discipline: no hardcoded class/predicate knowledge in primitives.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn outbound-edges-of
  "Outbound typed-edges from `entity-ident` — `:db.type/ref` attribute
  pairs originating FROM the entity.  Returns a vec of maps:

    [{:predicate <pred-ident> :target <entity-map>} ...]

  Optional opts:
    :predicate   — keyword OR collection of keywords; restricts results
                   to edges whose attribute-ident is in the set
    :target-type — class ident; restricts results to edges whose target
                   is an instance-of the class (via `instance-of` rule)

  Substrate-quality: class-agnostic; predicate-set + target-type are
  caller-supplied."
  ([entity-ident]
   (outbound-edges-of entity-ident nil))
  ([entity-ident {:keys [predicate target-type]}]
   (let [eid       (:db/id (db/entity entity-ident))
         rows      (if target-type
                     (d/q '[:find ?a ?v
                            :in $ % ?e ?target-type
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]
                            (instance-of ?target-type ?v)]
                          (db/db) (all-rules) eid target-type)
                     (d/q '[:find ?a ?v
                            :in $ ?e
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         pred-set  (when predicate
                     (set (if (sequential? predicate) predicate [predicate])))
         db-now    (db/db)
         ;; S7 EP-3 (BU-5 / R16): a governed edge whose firewall verdict
         ;; REFUSES is REWRITTEN — {:predicate :blocked true :reason ...} with
         ;; NO :target key (key ABSENCE, not a sentinel, so a client feeding
         ;; :target onward gets nil, never a fake entity).  The forbidden hop
         ;; is src=eid → slot → tgt=v; principal-INDEPENDENT.  A PERMITTED /
         ;; exempt edge projects its :target normally.
         project   (fn [[a v]]
                     (let [slot (or (:db/ident (db/entity a)) a)]
                       (if (fw-enforce/hop-forbidden? db-now eid slot v)
                         {:predicate slot :blocked true
                          :reason :firewall/flow-forbidden}
                         {:predicate slot :target (db/entity v)})))
         match?    (if pred-set
                     (fn [edge] (pred-set (:predicate edge)))
                     (constantly true))]
     (->> rows
          (map project)
          (filter match?)
          vec))))

(defn inbound-edges-of
  "Inbound typed-edges to `entity-ident` — `:db.type/ref` attribute
  pairs pointing AT the entity.  Returns a vec of maps:

    [{:predicate <pred-ident> :source <entity-map>} ...]

  Optional opts:
    :predicate   — keyword OR collection of keywords; restricts results
                   to edges whose attribute-ident is in the set
    :source-type — class ident; restricts results to edges whose source
                   is an instance-of the class (via `instance-of` rule)

  Substrate-quality: class-agnostic; predicate-set + source-type are
  caller-supplied."
  ([entity-ident]
   (inbound-edges-of entity-ident nil))
  ([entity-ident {:keys [predicate source-type]}]
   (let [eid       (:db/id (db/entity entity-ident))
         rows      (if source-type
                     (d/q '[:find ?s ?a
                            :in $ % ?e ?source-type
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]
                            (instance-of ?source-type ?s)]
                          (db/db) (all-rules) eid source-type)
                     (d/q '[:find ?s ?a
                            :in $ ?e
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         pred-set  (when predicate
                     (set (if (sequential? predicate) predicate [predicate])))
         db-now    (db/db)
         ;; S7 EP-3 (BU-5 / R16): an inbound edge is the WRITTEN edge
         ;; s → slot → eid (s is the source that authored it; this entity is
         ;; the target).  Its firewall verdict is the per-hop verdict on that
         ;; written direction; a FORBIDDEN inbound edge is listed-as-broken
         ;; (no :source key) from this end too — symmetric with outbound.
         project   (fn [[s a]]
                     (let [slot (or (:db/ident (db/entity a)) a)]
                       (if (fw-enforce/hop-forbidden? db-now s slot eid)
                         {:predicate slot :blocked true
                          :reason :firewall/flow-forbidden}
                         {:predicate slot :source (db/entity s)})))
         match?    (if pred-set
                     (fn [edge] (pred-set (:predicate edge)))
                     (constantly true))]
     (->> rows
          (map project)
          (filter match?)
          vec))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph-walk BFS — Stage 17 (fulltext arc Phase N)
;;
;; Implemented as Clojure-side BFS rather than recursive Datomic rule
;; because (a) hop-cap semantics aren't naturally expressed in Datalog
;; recursive rules, (b) per-hop projection + path tracking is cleaner
;; in iteration, (c) :include [:paths] is trivial to attach when we
;; control the traversal step.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-card-of
  "Return {:entity entity :axes {axis-name [edge ...]}} for caller-defined axes.
  Each axis has :name, :direction (:forward or :inverse), optional :predicates,
  :target-type or :source-type, and :limit (default 0, no cap).
  Forward edges carry :predicate and :target; inverse edges carry :predicate
  and :source. Axes express the caller's vocabulary without domain-specific
  navigation code."
  [entity-ident axis-specs]
  (let [entity (db/entity entity-ident)]
    {:entity entity
     :axes (reduce (fn [acc {:keys [name direction predicates target-type source-type limit]
                             :or   {limit 0}}]
                     (let [opts (cond-> {}
                                  predicates (assoc :predicate predicates)
                                  target-type (assoc :target-type target-type)
                                  source-type (assoc :source-type source-type))
                           edges (case direction
                                   :forward
                                   (outbound-edges-of entity-ident
                                                       (dissoc opts :source-type))
                                   :inverse
                                   (inbound-edges-of entity-ident
                                                      (dissoc opts :target-type))
                                   ;; Default to :forward if unspecified
                                   (outbound-edges-of entity-ident
                                                       (dissoc opts :source-type)))
                           limited (if (zero? limit) edges (take limit edges))]
                       (assoc acc name (vec limited))))
                   {}
                   axis-specs)}))

(defn siblings-of
  "Return entities sharing the seed's directory prefix in path-slot.
  Paths in the same directory are siblings; a deeper subdirectory is not.
  path-slot is the caller-selected property containing each relative path."
  [entity-ident path-slot]
  (let [entity     (db/entity entity-ident)
        rel-path   (get entity path-slot)]
    (if (or (nil? rel-path) (not (string? rel-path)))
      []
      (let [self-eid   (:db/id entity)
            sep-idx    (clojure.string/last-index-of rel-path "/")
            dir-prefix (if sep-idx
                         (subs rel-path 0 (inc sep-idx))
                         "")
            candidates (d/q '[:find ?e ?p
                              :in $ ?slot
                              :where
                              [?e ?slot ?p]]
                            (db/db) path-slot)
            same-dir?  (fn [path]
                         (and (clojure.string/starts-with? path dir-prefix)
                              (let [tail (subs path (count dir-prefix))]
                                (not (clojure.string/includes? tail "/")))))]
        (->> candidates
             (filter (fn [[eid path]]
                       (and (not= eid self-eid)
                            (same-dir? path))))
             ;; S7 EP-3 (adjudication must-fix #1; S7-PLAN §5/R15 names
             ;; siblings-of an EP-3-inherited surface): siblings-of is a
             ;; rel-path prefix ROW-READ enumeration channel — withhold any
             ;; sibling the anchor's compartment may not see, so a public
             ;; anchor cannot discover a private sibling's ident/content.  The
             ;; per-endpoint firewall verdict (fail-closed, principal-
             ;; independent) is the same predicate the coarse path-via fallback
             ;; uses.  Defense-in-depth ahead of S9 physical exclusion.
             (filter (fn [[eid _]]
                       (fw-enforce/endpoint-permitted? (db/db) self-eid eid)))
             (mapv (fn [[eid _]] (db/entity eid))))))))

(defn graph-walk-from
  "Walk the typed-edge graph outward from `seed-ident` up to `hops`
  levels of distance.  Returns a vec of result maps for every entity
  reachable WITHIN `hops` (excluding the seed itself):

    [{:entity <entity-map> :hop <int>} ...]

  With `:include #{:paths}`, each map also carries `:path` — a vec of
  `{:predicate <pred-ident> :direction :forward|:inverse}` steps from
  seed to the result entity (shortest-path; BFS guarantees the first
  arrival is via a shortest path).

  Opts:
    :hops       — max distance to walk (default 4)
    :predicates — keyword OR coll restricting edges to a predicate set
    :direction  — :forward (outbound edges only) / :inverse (inbound only)
                  / :bidirectional (union).  Default :forward.
    :include    — coll-of opts; `:paths` attaches step sequence.

  The operation uses caller-supplied predicates and class filters."
  ([seed-ident]
   (graph-walk-from seed-ident nil))
  ([seed-ident {:keys [hops predicates direction include]
                :or   {hops 4 direction :forward}}]
   (let [seed-eid       (:db/id (db/entity seed-ident))
         pred-set       (when predicates
                          (set (if (sequential? predicates)
                                 predicates
                                 [predicates])))
         include-paths? (contains? (set include) :paths)
         forward?       (#{:forward :bidirectional} direction)
         inverse?       (#{:inverse :bidirectional} direction)

         pred-match?
         (fn [a]
           (or (nil? pred-set)
               (pred-set (or (:db/ident (db/entity a)) a))))

         db-now         (db/db)

         ;; S7 EP-3 (BU-5 / CA-2): the blocked-hop verdict for one walk-frontier
         ;; ref-datom row, applied BEFORE the target enters the next frontier.
         ;; A :forward row is the written edge from-frontier → slot → to-new; an
         ;; :inverse row `[?n ?a ?f]` binds ?f=from-frontier (object) / ?n=to-new
         ;; (subject), so the written edge is to-new → slot → from-frontier.
         ;; Governs whichever physical direction the datom was authored in;
         ;; principal-INDEPENDENT.  Without this, navigate.walk fully bypasses
         ;; EP-3 (walk.clj → graph-walk-from's direct d/q, not edges-of).
         hop-blocked?
         (fn [{:keys [from-frontier to-new attr direction]}]
           (let [slot (or (:db/ident (db/entity attr)) attr)]
             (boolean
               (if (= :inverse direction)
                 (fw-enforce/hop-forbidden? db-now to-new slot from-frontier)
                 (fw-enforce/hop-forbidden? db-now from-frontier slot to-new)))))

         step-edges
         (fn [frontier-eids]
           (let [forward-rows (when forward?
                                (d/q '[:find ?f ?a ?n
                                       :in $ [?f ...]
                                       :where
                                       [?f ?a ?n]
                                       [?a :db/valueType :db.type/ref]]
                                     (db/db) frontier-eids))
                 inverse-rows (when inverse?
                                (d/q '[:find ?f ?a ?n
                                       :in $ [?f ...]
                                       :where
                                       [?n ?a ?f]
                                       [?a :db/valueType :db.type/ref]]
                                     (db/db) frontier-eids))]
             (concat
               (keep (fn [[f a n]]
                       (when (pred-match? a)
                         {:from-frontier f :to-new n
                          :attr a :direction :forward}))
                     forward-rows)
               (keep (fn [[f a n]]
                       (when (pred-match? a)
                         {:from-frontier f :to-new n
                          :attr a :direction :inverse}))
                     inverse-rows))))]

     (loop [hop      0
            visited  #{seed-eid}
            frontier {seed-eid (when include-paths? [])}
            results  (transient [])]
       (cond
         (zero? (count frontier)) (persistent! results)
         (>= hop hops)            (persistent! results)
         :else
         (let [;; S7 EP-3 (CA-2): partition the step-edges into FORBIDDEN
               ;; (dropped from the frontier, surfaced as {:blocked true} rows
               ;; with NO :entity — the audit signal) and PERMITTED (traversed
               ;; normally).  A forbidden target NEVER enters `visited`/frontier,
               ;; so navigate.walk cannot reach a private target from a public
               ;; source.
               all-edges  (step-edges (keys frontier))
               blocked    (filterv hop-blocked? all-edges)
               allowed    (remove hop-blocked? all-edges)
               discovered
               (reduce
                 (fn [acc edge]
                   (let [{:keys [from-frontier to-new attr direction]} edge]
                     (cond
                       (contains? visited to-new) acc
                       (contains? acc to-new)     acc  ; first match wins
                       :else
                       (let [parent-path (get frontier from-frontier [])
                             step        {:predicate (or (:db/ident (db/entity attr))
                                                          attr)
                                          :direction direction}]
                         (assoc acc to-new
                                {:entity (db/entity to-new)
                                 :hop    (inc hop)
                                 :path   (when include-paths?
                                           (conj parent-path step))})))))
                 {}
                 allowed)
               ;; Blocked rows carry the predicate + reason but NO :entity/:target
               ;; and NO :path advance — one per forbidden ref-datom.  Dedup is
               ;; keyed on [predicate to-new] (NOT the emitted row): a single
               ;; forbidden target reached via the same predicate twice in one
               ;; hop collapses to one row, but TWO distinct forbidden targets on
               ;; the SAME card-many predicate each yield their own row so the
               ;; S10 audit signal counts one row per forbidden ref-datom (R16).
               ;; `to-new` is the DEDUP KEY only — it never enters the wire row
               ;; (no :target eid/ident leaks; key ABSENCE, not a sentinel).  A
               ;; global seen-set (not `distinct`/`dedupe`) is required because
               ;; forbidden edges are NOT sorted by key within a hop.
               blocked-rows
               (:rows
                 (reduce
                   (fn [{:keys [seen rows] :as acc} {:keys [attr to-new]}]
                     (let [pred (or (:db/ident (db/entity attr)) attr)
                           k    [pred to-new]]
                       (if (contains? seen k)
                         acc
                         {:seen (conj seen k)
                          :rows (conj rows
                                      {:predicate pred
                                       :blocked   true
                                       :reason    :firewall/flow-forbidden
                                       :hop       (inc hop)})})))
                   {:seen #{} :rows []}
                   blocked))]
           (recur (inc hop)
                  (into visited (keys discovered))
                  (into {} (map (fn [[eid r]] [eid (:path r)])) discovered)
                  (as-> results $
                    (reduce
                      (fn [acc [_ r]]
                        (conj! acc (if include-paths? r (dissoc r :path))))
                      $
                      discovered)
                    (reduce conj! $ blocked-rows)))))))))

(defn search-fulltext
  "Query Datomic's native single-attribute fulltext index and return [eid score]
  tuples. The score is Datomic/Lucene relevance, not Sandbar's BM25F score.
  attribute must have :db/fulltext enabled; use fulltext-indexed? first.
  Query syntax follows Datomic's native fulltext query support.
  Projection, limits and multi-field scoring belong to sandbar.search."
  [attribute query]
  (d/q '[:find ?e ?score
         :in $ ?attr ?q
         :where [(fulltext $ ?attr ?q) [[?e ?value ?tx ?score]]]]
       (db/db) attribute query))

(defn parents-of
  "Returns the direct parent classes of class dt.
  These are the immediate values of :dt/subclass-of."
  [dt]
  (:dt/subclass-of (entity dt)))

(defn ancestors-of
  "Returns all ancestor classes of class dt (parents, grandparents, etc.).
  Recursively traverses the :dt/subclass-of hierarchy."
  [dt]
  (let [direct-parents (parents-of dt)]
    (distinct
      (concat direct-parents
              (mapcat ancestors-of direct-parents)))))

(defn effective-codec-aliases-of
  "Merge codec alias maps over the class and its ancestors. The leaf class
  overrides inherited values for shared keys. Empty when no aliases exist."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (reduce (fn [acc c] (merge acc (codec-aliases-of c)))
            {}
            (reverse chain))))

(defn effective-codec-slot-order-of
  "Return codec slot order across the class and its ancestors: the leaf's
  declared order first, then ancestors in walked order. Repeated slots retain
  their first occurrence. Empty when no ordering is declared."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (vec (distinct (mapcat codec-slot-order-of chain)))))

(defn effective-bm25f-weights-of
  "Merge BM25F field weights across the class and its ancestors. The leaf
  overrides inherited weights for shared slot keys. This is the default
  field selection used by search-bm25f when no override is supplied."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (reduce (fn [acc c] (merge acc (bm25f-weights-of c)))
            {}
            (reverse chain))))

(defn effective-superseded-when-of
  "Return the union of [slot-ident value] supersession declarations from the
  class and all ancestors, or #{}. Search uses these declarations for default
  status-aware ordering; no domain-specific status vocabulary is hardcoded."
  [class-ident]
  (into #{} (mapcat superseded-when-of) (cons class-ident (ancestors-of class-ident))))

(defn effective-recency-slot-of
  "Returns the `:dt/recency-slot` in effect for `class-ident`: the leaf's
   own declaration, else the nearest ancestor's, else nil.  Sister to
   `effective-memorial-policy-of` (nearest-wins keyword walk)."
  [class-ident]
  (some recency-slot-of (cons class-ident (ancestors-of class-ident))))

(defn effective-memorial-policy-of
  "Return the first declared memorial policy in the class/ancestor walk, or nil.
  This scalar policy is selected rather than merged. Values are :first-class,
  :db-only and :inline. With no declaration, the caller chooses its default;
  the filesystem sink conservatively skips undeclared classes."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (some memorial-policy-of chain)))

(def ^{:private true
       :doc "The three roots of the unified :mm/* runtime lattice (Spec /
            Activity / Event).  Their subclasses INHERIT :mm/Memory's
            :first-class memorial-policy but never correspond to a corpus
            markdown FILE — they carry no :mm.memory/rel-path by design
            (Schedule/Workflow content-key idents; Run/Process/EventLog
            telemetry; Event bus primitives).  Per
            decisions/unified_mm_type_lattice_workflow_process_activity_run_-
            job_schedule_event_phase_2_2026_05_24."}
  +runtime-behavioral-roots+
  [:mm/Spec :mm/Activity :mm/Event])

(def ^{:private true
       :doc "Runtime-root descendants that ARE corpus documents (project to a
            corpus markdown FILE and MUST carry a :mm.memory/rel-path) despite
            living under a runtime-behavioral root — so the root-ancestry
            exclusion must NOT drop them:
              :mm/Log      (:mm/Activity) — session-handoff chronicle → memory/logs/
              :mm/Fn       (:mm/Spec)     — first-class function memorial → memory/fns/
              :mm/Workflow (:mm/Spec)     — user-visible behavioral spec → memory/workflows/
            (and their descendants, e.g. :mm/Rule under :mm/Fn — also a corpus
            document at memory/rules/.)  Per it7 FF-2 (it6 BOARD-MINUTE Lane-B
            fast-follow #1): the it6 root-ancestry gate SILENTLY EXCLUDED these
            three (:mm/Log was re-parented under :mm/Activity; :mm/Fn / :mm/Workflow
            live under :mm/Spec), leaving them at BASE behavior (silent skip / no
            create-time loud-reject).  Listing them here as an explicit CLASS-LEVEL
            inclusion restores derive-or-reject + WARN coverage.  This override is
            MONOTONE — it can only ADD coverage; :mm/Schedule / :mm/EventLog /
            :mm/Run / :mm/Job / :mm/Event / :mm/Process and every other runtime
            class stay rel-path-less (regression-pinned)."}
  +corpus-document-runtime-classes+
  [:mm/Log :mm/Fn :mm/Workflow])

(defn corpus-document-class?
  "True when `class-ident` is a FIRST-CLASS memorial that the reactive fs sink
   projects to a corpus markdown FILE — i.e. its effective memorial-policy is
   `:first-class` AND EITHER it is an explicit corpus-document class under a
   runtime root (`+corpus-document-runtime-classes+`: :mm/Log / :mm/Fn /
   :mm/Workflow + descendants) OR it is NOT under any runtime-behavioral root
   (`:mm/Spec` / `:mm/Activity` / `:mm/Event`).

   This is the precise 'must carry a `:mm.memory/rel-path`' set: the corpus
   document types (Decision / Plan / Bug / Observation / Interaction / Feedback /
   Shape / … PLUS Log / Fn / Workflow).  It EXCLUDES the classes that are
   `:first-class` only by inheritance from `:mm/Memory` yet legitimately have no
   rel-path — Schedule (content-key ident), EventLog (telemetry, created via a
   bare `dt/make`), Run (:db-only), Event/Process/Job kin.

   it7 FF-2 (it6 BOARD-MINUTE Lane-B fast-follow #1) moved this from a pure
   root-ancestry exclusion to CLASS-LEVEL gating: the coarse root exclusion
   wrongly dropped :mm/Log (re-parented under :mm/Activity) and :mm/Fn /
   :mm/Workflow (under :mm/Spec), which ARE corpus documents.  The explicit
   inclusion is MONOTONE-SAFE — it only ADDS those three (+ descendants) to
   coverage; it removes nothing, so no rel-path-less runtime create regresses.

   THE single shared predicate behind two defenses (so they can never diverge):
   `sandbar.store/create-memory!`'s create-time loud-fail and
   `sandbar.reactive.sinks/fs-projection-sink`'s WARN-on-skip.  Non-throwing —
   any lookup failure returns false (never reject/alarm on uncertainty).  Per
   bugs/entity_create_codec_path_mints_identless_relpathless_entities_fs_-
   projection_silently_skipped_2026_07_08."
  [class-ident]
  (boolean
   (try (and (= :first-class (effective-memorial-policy-of class-ident))
             (or (some #(type-isa? % class-ident) +corpus-document-runtime-classes+)
                 (not (some #(type-isa? % class-ident) +runtime-behavioral-roots+))))
        (catch Throwable _ false))))

(defn direct-subclasses-of
  "Returns the idents of classes that directly extend class dt.
  Only returns immediate children, not transitive descendants."
  [dt]
  (map first
       (d/q '[:find ?t :in $ ?dt :where
              [?e :dt/subclass-of ?dt]
              [?e :db/ident ?t]]
            (db/db) dt)))

(defrule subclass-of [?dt ?s]
  [?e  :dt/subclass-of ?dt]
  [?e  :db/ident ?s])

(defrule subclass-of [?dt ?c]
  [?e :dt/subclass-of ?dt]
  (subclass-of ?e ?s)
  [?s :db/ident ?c])

(defn subclasses-of
  "Returns idents of all transitive subclasses of class dt.
  Uses Datalog rules for recursive traversal of :dt/subclass-of."
  [dt]
  (mapv first
        (d/q '[:find ?c :in $ % ?dt :where
               [?t :db/ident  ?c]
               (subclass-of ?dt ?c)]
             (db/db) (all-rules) dt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type-relation cache (Stage 5 Phase A.5 — Dan-directive 2026-05-22)
;;
;; Memoizes type-relation queries so substrate operations that iterate
;; type-isa? in tight loops (projection filter, audit invariants, codec
;; routing, BM25F :where filter resolution) don't re-run Datalog queries
;; per entity.
;;
;; Bounded memory: stores ~50 class-idents × sets of ~10-50 idents each
;; (a few KB total).  Independent of corpus size.  Type relations are
;; STRUCTURE, not CONTENT.
;;
;; Invalidation: cleared by `sandbar.db.datomic/load-all-schema!` after
;; each schema reload (schema additions can change subclass closures).
;; Future class-creating MCP verbs invalidate similarly via
;; `clear-type-relation-cache!`.
;;
;; Per `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private type-relation-cache
  ;; {class-ident #{direct-and-transitive-subclass-idents}}  — subclasses, NOT including the class itself
  (atom {}))

(defn clear-type-relation-cache!
  "Clear the memoized type-relation cache.  Called by
  `sandbar.db.datomic/load-all-schema!` after each schema reload (via
  the post-schema-reload-handlers registry), since schema additions can
  change the subclass closure.  Also callable from tests + ad-hoc to
  force a rebuild.  Returns the prior cache value."
  []
  (let [prior @type-relation-cache]
    (reset! type-relation-cache {})
    prior))

;; Register the cache-clear with datomic's post-schema-reload registry
;; at namespace load time.  Set-valued registry dedupes on REPL reload.
;; Per `interaction/dont_use_requiring_resolve_for_namespace_dep_avoidance_2026_05_22.md`.
(db/register-post-schema-reload-handler! clear-type-relation-cache!)

(defn subclasses-of-cached
  "Return the cached set of transitive subclass idents, excluding class-ident.
  Populates a missing entry from the recursive subclasses-of query. Full schema
  loading clears the cache through its registered callback; single-file schema
  loading and ordinary class mutations do not reliably invalidate it."
  [class-ident]
  (or (get @type-relation-cache class-ident)
      (let [computed (set (subclasses-of class-ident))]
        (swap! type-relation-cache assoc class-ident computed)
        computed)))

(defn descendants-of
  "Return the cached set containing class-ident and its transitive subclasses.
  Suitable for repeated membership checks. Has the same model-change freshness
  boundary as subclasses-of-cached; this is not a fresh query on every call."
  [class-ident]
  (conj (subclasses-of-cached class-ident) class-ident))

(defn subclass-of?
  "Return whether c is a transitive subclass of dt, using the cached class set.
  Exact identity is handled by type-isa?. See subclasses-of-cached for freshness."
  [dt c]
  (contains? (subclasses-of-cached dt) c))

(defn type-isa?
  "Return whether entity-type is dt or a transitive subclass of dt.
  Both arguments are class idents, so it also applies to an untransacted map's
  :dt/type value. Uses cached subclass relations and returns false if that
  lookup throws; see subclasses-of-cached for the model-change limitation."
  [dt entity-type]
  (or (= dt entity-type)
      (try (subclass-of? dt entity-type)
           (catch Exception _ false))))

(defn instance-of?
  "Returns true if entity e is an instance of class dt.
  True when e's :dt/type is dt or a subclass of dt.

  Robust to BOTH shapes of `:dt/type` value:
  - keyword form (entity-spec map; pre-transact; in-memory data)
  - Datomic Entity form (post-DB-read; ref-slot resolution returns
    the target entity rather than its ident)

  Normalize a resolved type entity to its ident before comparison, so
  validating stored reference values uses the same representation as new
  entity maps. Subclass membership uses the process-local hierarchy cache;
  ordinary hot model edits do not yet invalidate every cached relation."
  [dt e]
  (let [t-val   (-> e entity :dt/type)
        t-ident (cond
                  (keyword? t-val)      t-val
                  (associative? t-val)  (:db/ident t-val)
                  :else                 nil)]
    (or (= dt t-ident)
        (and t-ident (subclass-of? dt t-ident)))))

(defn abstract?
  "Returns true if class dt is marked as abstract.
  Abstract classes should not be directly instantiated."
  [dt]
  (:dt/abstract? (entity dt)))

(defrule direct-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defn direct-slots-of
  "Returns the properties directly declared on class dt.
  Does not include slots inherited from parent classes."
  [dt]
  (:dt/slots (entity dt)))

(defrule effective-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defrule effective-slot [?dt ?s]
  [?dt  :dt/subclass-of ?p]
  (effective-slot ?p ?s))

(defn slots-of
  "Returns all effective slots for class dt, including inherited.
  Uses Datalog rules to traverse the class hierarchy."
  [dt]
  (set
    (map first
      (d/q '[:find ?s :in $ % ?dt :where
             (effective-slot ?dt ?s)]
           (db/db) (all-rules) dt))))


;; (defn map-slots [f e]
;;   (map (partial f dt) (datatype-slots dt)))

;; (defn slotwise [f dt]
;;   (let [slots (datatype-slots dt)
;;         vals  (map-datatype-slots f dt)]
;;   (zipmap slots vals)))


;; (defn about [dt]
;;   ;; TODO: do
;;   )


;; (defn entity-datatype [e]
;;   (:dt/type (entity e)))

;; (defn entity-slots [e]
;;   (datatype-slots (entity-datatype e)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn domain-of
  "Returns the domain class of property prop.
  The domain specifies which class instances may have this property."
  [prop]
  (:dt/domain (entity prop)))

(defn range-of
  "Returns the range type of property prop.
  The range specifies the allowed type of the property's values."
  [prop]
  (:dt/range (entity prop)))

(defn properties-with-domain
  "Returns idents of all properties whose domain is dt or an ancestor of dt.
  Useful for finding all properties applicable to instances of a class."
  [dt]
  (let [dt-and-ancestors (cons dt (ancestors-of dt))]
    (mapv first
          (d/q '[:find ?p :in $ [?domains ...] :where
                 [?e :dt/domain ?d]
                 [?d :db/ident ?domains]
                 [?e :db/ident ?p]]
               (db/db) dt-and-ancestors))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- literal-type?
  "Check if dt is a Datomic literal type (db.type/*)"
  [dt]
  (and (keyword? dt)
       (= "db.type" (namespace dt))))

(defn- upsert-map-for?
  "True if `v` is a single-key map whose key is either `:db/ident` OR
  the unique-identity slot of `target-class`.  Datomic transact resolves
  such maps to refs via the :db.unique/identity index — they are
  semantically valid ref-slot values pre-transact even though they are
  not Datomic Entity instances yet.

  Codec produces this shape for cardinality-many ref slots (per the
  .md-canonical principle — frontmatter strings like `tags: [observation,
  test]` become `[{:mm.tag/value \"observation\"} ...]` upsert maps that
  resolve to :mm/Tag refs at transact time).  Pre-transact validation
  must accept this shape; otherwise the codec ↔ validation contract
  breaks at entity.create-with-tags + similar cases.

  Per substrate-stabilization arc C6 / Coupling 1
  (codec ↔ validation contract gap) — added 2026-05-22."
  [v target-class]
  (and (map? v)
       (= 1 (count v))
       (let [k (first (keys v))]
         (or (= :db/ident k)
             (when target-class
               (= k (unique-identity-slot-of target-class)))))))

(defn- value-matches-range?
  "Returns true if `value` is admissible for a slot whose declared range is
   `range-type`.  Literal ranges check the Clojure/Java type; class ranges
   accept an instance of the target class, a `:db.unique/identity` upsert map,
   an untyped stub entity, or — for the universal `:dt/Ref` marker — any value
   resolving to a live entity.  nil range ⇒ anything admissible."
  [value range-type]
  (cond
    ;; No range specified - anything goes
    (nil? range-type)
    true

    ;; Literal types - check Clojure/Java type
    (literal-type? range-type)
    (case range-type
      :db.type/string  (string? value)
      :db.type/boolean (boolean? value)
      :db.type/long    (int? value)
      :db.type/keyword (keyword? value)
      :db.type/uuid    (uuid? value)
      :db.type/instant (inst? value)
      :db.type/uri     (instance? java.net.URI value)
      :db.type/double  (double? value)
      :db.type/float   (float? value)
      :db.type/bigint  (instance? java.math.BigInteger value)
      :db.type/bigdec  (decimal? value)
      :db.type/bytes   (bytes? value)
      :db.type/ref     true  ;; ref type accepts any entity
      :db.type/symbol  (symbol? value)
      :db.type/fn      (fn? value)
      :db.type/tuple   (vector? value)
      true)  ;; unknown literal type - pass

    ;; Reference to a class — accept any of:
    ;; (a) a Datomic Entity that is instance-of the target class
    ;; (b) an upsert map `{<unique-attr> <value>}` that Datomic transact
    ;;     will resolve to a ref via :db.unique/identity (codec produces
    ;;     this shape for tags + other ref slots; per C6 of substrate-
    ;;     stabilization arc — codec ↔ validation contract gap)
    ;; (c) Gap 20 — an untyped stub entity (resolved entity with :db/id
    ;;     but NO :dt/type).  Stubs are created via :db/ident upsert when
    ;;     a citation target doesn't exist yet (per the stub-then-fill
    ;;     pattern in decisions/mm_memory_typed_edge_migration_string_to_-
    ;;     ref_2026_05_21.md).  Subsequent ingest of the actual memorial
    ;;     upserts via the same ident, filling in the slots.  Permissive
    ;;     validation here honors that intentional design — refusing
    ;;     untyped stubs would break entity.update on any memorial that
    ;;     cites a not-yet-ingested target (common during MCP cutover).
    ;; (d) When the range is the UNIVERSAL ref marker `:dt/Ref` (not a
    ;;     concrete class), any value that resolves to a live entity — a
    ;;     raw eid, a Datomic EntityMap, or a `{:db/id eid}` map — is a
    ;;     valid reference; `instance-of?` fails here because ref-target
    ;;     classes descend from `:dt/Resource`, a sibling of `:dt/Ref`, not
    ;;     from `:dt/Ref` itself.  Concrete-class ranges keep the stricter
    ;;     `instance-of?` check.  Per
    ;;     bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.
    :else
    (or (instance-of? range-type value)
        (upsert-map-for? value range-type)
        ;; Universal `:dt/Ref` marker: any value naming a LIVE entity is a
        ;; valid reference.  Resolve through the ref->eid canon (so an eid /
        ;; EntityMap / {:db/id} all reduce identically), then confirm the
        ;; target actually exists — `d/entity` on a bogus eid yields a shell
        ;; with :db/id but no attributes, which must NOT pass.
        (and (= :dt/Ref range-type)
             (when-let [eid (ref->eid value)]
               (some? (seq (entity eid)))))
        ;; Gap 20 untyped-stub acceptance (see (c) above) — a resolved entity
        ;; with :db/id but no :dt/type, for the stub-then-fill citation path.
        (when-let [e (try (entity value) (catch Throwable _ nil))]
          (and (:db/id e)
               (nil? (:dt/type e)))))))

(defn required?
  "Return the property's declared :dt/required? value, or nil if absent."
  [prop]
  (:dt/required? (entity prop)))

(defn cardinality-of
  "Return the property's Datomic cardinality ident, or nil if absent."
  [prop]
  (:db/cardinality (entity prop)))

(defn cardinality-one?
  "Returns true if property prop has cardinality one (single-valued)."
  [prop]
  (= :db.cardinality/one (cardinality-of prop)))

(defn cardinality-many?
  "Returns true if property prop has cardinality many (multi-valued)."
  [prop]
  (= :db.cardinality/many (cardinality-of prop)))

(defn required-slots-of
  "Return a sequence of effective slot idents marked required, including inherited slots."
  [dt]
  (filter required? (slots-of dt)))

(defn validator-of
  "Resolve the class's :dt/validator symbol to a var; return nil if absent or unresolvable."
  [dt]
  (when-let [sym (:dt/validator (entity dt))]
    (try
      (requiring-resolve sym)
      (catch Exception _ nil))))

(defn- validate-slot-type
  "Validate a single slot value against its range. Returns nil if valid, error map if invalid.

   Multi-value handling: cardinality-many slots may arrive as
   - sets (Datomic peer reads return cardinality-many as sets)
   - vectors / lists / seqs (JSON arrays / EDN vectors at the MCP /
     codec boundaries)

   Both shapes iterate per-element through value-matches-range?.  Maps
   are single values (an upsert-map shape like {:mm.tag/value \"x\"}
   IS the value, not a collection); primitives wrap in a singleton set.

   Before C9 (2026-05-22): only sets iterated; vectors fell through
   to the singleton-set branch, passing the whole vector to
   value-matches-range? which (correctly) rejected it as a non-target-
   class collection.  Surfaced during C6 verification — cardinality-
   many ref slots with upsert-map values failed validation even
   though each individual upsert-map satisfies the range type."
  [slot-ident slot-value]
  (let [range-type (range-of slot-ident)
        values (cond
                 (set? slot-value)        slot-value
                 (sequential? slot-value) (set slot-value)
                 :else                    #{slot-value})]
    (when-let [invalid (seq (remove #(value-matches-range? % range-type) values))]
      {:type :invalid-type
       :slot slot-ident
       :expected range-type
       :actual (mapv class invalid)
       :values (vec invalid)
       :message (str "Slot " slot-ident " expects " range-type)})))

(defn- validate-slot-cardinality
  "Validate a slot value against its cardinality. Returns nil if valid, error map if invalid."
  [slot-ident slot-value]
  (when (and (cardinality-one? slot-ident)
             (set? slot-value)
             (> (count slot-value) 1))
    {:type :cardinality-violation
     :slot slot-ident
     :expected :db.cardinality/one
     :actual (count slot-value)
     :message (str "Slot " slot-ident " has cardinality one but got " (count slot-value) " values")}))

(defn- validate-required-slots
  "Check that all required slots have values. Returns seq of error maps."
  [ent dt]
  (let [required (required-slots-of dt)]
    (keep (fn [slot]
            (when (nil? (get ent slot))
              {:type :missing-required
               :slot slot
               :message (str "Required slot " slot " is missing")}))
          required)))

(defn- run-custom-validator
  "Run the custom validator for a class if one exists. Returns seq of error maps."
  [ent dt]
  (when-let [validator-fn (validator-of dt)]
    (try
      (when-let [result (validator-fn ent)]
        (if (map? result)
          [(assoc result :type :custom-validation)]
          [{:type :custom-validation
            :message (str result)}]))
      (catch Exception e
        [{:type :validator-error
          :message (str "Validator threw exception: " (.getMessage e))}]))))

(defn validate
  "Validate an entity against its class.
   Returns nil if valid, or a map of validation errors:
   {:entity e
    :errors [{:type :no-class | :abstract-class | :missing-required |
                    :invalid-type | :cardinality-violation | :custom-validation}]}"
  [e]
  (let [ent (entity e)
        dt (class-of e)]
    (cond
      ;; No class - not a typed entity
      (nil? dt)
      {:entity e
       :errors [{:type :no-class
                 :message "Entity has no :dt/type"}]}

      ;; Abstract class
      (abstract? dt)
      {:entity e
       :errors [{:type :abstract-class
                 :class dt
                 :message (str "Cannot instantiate abstract class " dt)}]}

      ;; Run all validations
      :else
      (let [slots (slots-of dt)

            ;; Check required slots
            required-errors (validate-required-slots ent dt)

            ;; Check slot types and cardinality
            slot-errors (->> slots
                             (keep (fn [slot]
                                     (when-let [v (get ent slot)]
                                       (or (validate-slot-type slot v)
                                           (validate-slot-cardinality slot v)))))
                             (into []))

            ;; Run custom validator
            custom-errors (run-custom-validator ent dt)

            ;; Combine all errors
            all-errors (concat required-errors slot-errors custom-errors)]

        (when (seq all-errors)
          {:entity e
           :errors (vec all-errors)})))))

(defn valid?
  "Returns true if entity passes validation"
  [e]
  (nil? (validate e)))

(defn validate-all-instances
  "Validate all instances of a class (including subclass instances).
   Returns a map with validation results:
   {:class dt
    :total N
    :valid N
    :invalid N
    :errors [{:entity e :errors [...]} ...]}"
  [dt]
  (let [instances (all-instances-of dt)
        results (map (fn [inst]
                       {:entity (:db/id inst)
                        :class (class-of inst)
                        :validation (validate inst)})
                     instances)
        invalid (filter #(some? (:validation %)) results)
        valid-count (- (count results) (count invalid))]
    {:class dt
     :total (count results)
     :valid valid-count
     :invalid (count invalid)
     :errors (mapv (fn [{:keys [entity class validation]}]
                     {:entity entity
                      :class class
                      :errors (:errors validation)})
                   invalid)}))

(defn validate-data
  "Validate data map before transaction (pre-transaction validation).
   Takes a class and a props map, returns nil if valid or error map."
  [dt props]
  (cond
    (abstract? dt)
    {:errors [{:type :abstract-class
               :class dt
               :message (str "Cannot instantiate abstract class " dt)}]}

    :else
    (let [slots (slots-of dt)
          ent (assoc props :dt/type dt)

          ;; Check required slots
          required-errors (validate-required-slots ent dt)

          ;; Check slot types and cardinality
          slot-errors (->> slots
                           (keep (fn [slot]
                                   (when-let [v (get ent slot)]
                                     (or (validate-slot-type slot v)
                                         (validate-slot-cardinality slot v)))))
                           (into []))

          all-errors (concat required-errors slot-errors)]

      (when (seq all-errors)
        {:errors (vec all-errors)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  ;; ============================================================
  ;; Validation Examples
  ;; ============================================================

  ;; Basic validation
  ;; (validate (make* :User {:user/login "dan"}))
  ;; => nil  ;; valid

  ;; Abstract class error
  ;; (validate (make* :dt/Resource {}))
  ;; => {:entity ..., :errors [{:type :abstract-class, :class :dt/Resource, ...}]}

  ;; Quick validity check
  ;; (valid? (make* :User {:user/login "dan"}))
  ;; => true

  ;; ============================================================
  ;; Pre-transaction Validation
  ;; ============================================================

  ;; Validated creation (throws on error)
  ;; (make :User {:user/login "dan"})
  ;; => entity

  ;; Skip validation
  ;; (make :User {:user/login "dan"} {:validate? false})
  ;; => entity

  ;; Pre-check data without transacting
  ;; (validate-data :User {:user/login "dan"})
  ;; => nil  ;; valid

  ;; ============================================================
  ;; Required Slots
  ;; ============================================================

  ;; Mark a property as required in schema:
  ;; {:db/ident :user/login :dt/required? true ...}

  ;; Then validation catches missing required slots:
  ;; (validate-data :User {})
  ;; => {:errors [{:type :missing-required, :slot :user/login, ...}]}

  ;; ============================================================
  ;; Cardinality Validation
  ;; ============================================================

  ;; Single-valued slot with multiple values:
  ;; (validate-slot-cardinality :user/login #{"a" "b"})
  ;; => {:type :cardinality-violation, :slot :user/login, ...}

  ;; ============================================================
  ;; Custom Validators
  ;; ============================================================

  ;; Add validator to class in schema:
  ;; {:db/ident :User :dt/validator 'myapp.validators/validate-user ...}

  ;; Validator fn signature: (fn [entity] -> nil | error-map)
  ;; (defn validate-user [ent]
  ;;   (when (< (count (:user/login ent)) 3)
  ;;     {:message "Login must be at least 3 characters"}))

  ;; ============================================================
  ;; Property Queries
  ;; ============================================================

  ;; (domain-of :user/login)
  ;; => :User

  ;; (range-of :user/login)
  ;; => :db.type/string

  ;; (required? :user/login)
  ;; => true/false

  ;; (cardinality-of :user/login)
  ;; => :db.cardinality/one

  ;; (properties-with-domain :User)
  ;; => [:user/uuid :user/login :user/secret :dt/type :db/doc ...]

  ;; (required-slots-of :User)
  ;; => (:user/login ...)

  (all-datatypes)
  (all-classes)
  (all-properties)

  (count (all-instances :dt/Resource))
  (map db/describe (all-instances :dt/Class))
  (map db/describe (all-instances :dt/Property))

  (describe :dt/Number)

  (d/touch (entity :dt/Number))

  ;; {:db/id 17592186045446, :db/ident :dt/Number,
  ;;  :db/doc "Numeric value type",
  ;;  :dt/type :dt/Class,
  ;;  :dt/context "system",
  ;;  :dt/label "Number",
  ;;  :dt/subclass-of #{:dt/Literal}}



  (all-named-instances-of-type :User)

  (datatype-parents :Twit)
  (datatype-ancestors :Twit)

  (:dt/type (make* :dt/Resource))

  ;; => :dt/Resource

  (describe (make* :User {:dt/label "test" :user/login "dan"}))
  (describe (make* :User {:dt/label "test" :user/login "jill"}))
  (describe (make* :User {:dt/label "test" :user/login "dexter"}))

  (all-instances-of :User)

  ;; => (#:db{:id 17592186045490} #:db{:id 17592186045492} #:db{:id 17592186045494} )

  (map :user/login (all-instances-of :User))

  ;; => ("dan" "dexter" jill)

  (class-of :dt/Property)

;; => :dt/Class



(datatype-subclasses :dt/Literal)

;; => [:db.type/instant :db.type/uri :db.type/keyword :db.type/bytes :db.type/fn :db.type/bigdec
;;     :db.type/long :db.type/uuid :db.type/bigint :db.type/float :db.type/tuple :db.type/symbol
;;     :db.type/boolean :dt/Number :db.type/string :db.type/double]

(datatype-subclasses :dt/Ref)

;; => [:dt/Fn :Twit :dt/Any :User]

(instance? :dt/Resource :dt/Literal)

;; =>true

  (subclass? :dt/Resource :dt/Literal)


  (datatype-slots :dt/Resource)
  ;; => #{:dt/label :dt/context :db/doc :db/ident :dt/type}

  (datatype-slots :dt/Class)

  ;; => #{:dt/list :dt/label :dt/context :dt/abstract? :db/doc :dt/slots :db/ident
  ;;      :dt/subclass-of :dt/type :dt/component}

  (datatype-slots :dt/Property)

  ;; => #{:db/unique :dt/label :dt/domain :dt/context :dt/range :db/fulltext :db/cardinality
  ;;       :db/doc :db/ident :dt/subproperty-of :dt/type}


  (describe :db/cardinality)

  ;; {:db/id 41,
  ;;  :db/ident :db/cardinality,
  ;;  :db/valueType :db.type/ref,
  ;;  :db/cardinality :db.cardinality/one,
  ;;  :db/doc "Property of an attribute. Two possible values: :db.cardinality/one for single-valued attributes, and :db.cardinality/many for many-valued attributes. Defaults to :db.cardinality/one.",
  ;;  :dt/type :dt/Property,
  ;;  :dt/domain :dt/Property,
  ;;  :dt/range :db.type/ref}

  (describe :dt/Property)

  (:dt/range (entity :dt/domain))


  (datatype-direct-subclasses :dt/Resource)

  ;; => #{[:dt/Ref] [:dt/Literal] [:dt/Resource**] [:dt/Property] [:dt/Resource*] [:dt/List] [:dt/Class]}

  )




;; TODO: change of semantics from metaclass to class?




;; (datatype-slots :user)
;; (entity-datatype :dt/dt)
;; (datatype-slots :any)



  ;; (def x (make* :List {:dt/first (entity (make* :User))}))

  ;;               :dt/rest (make* :List {:dt/first (make* :User)
  ;;                                      :dt/rest (make* :List
  ;;                                                      {:dt/first (make* :User)})})
