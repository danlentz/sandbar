(ns sandbar.retract
  "Safety + report layer for first-class entity retraction.

   The substrate half already EXISTS in `sandbar.db.datomic`
   (`retract-entity` / `retract-entities`, both `:db.fn/retractEntity`
   wrappers).  This namespace builds the SAFETY LAYER that the MCP verb
   `sandbar.entity.retract` (registered in `sandbar.mcp.tools`) needs:

   - a PURE per-target dry-run report built against a `(db/db)` snapshot
     (`build-report`), enumerating resolved-eid / exists? / ident /
     dt-type / datom-count / dependents / protected? / protection-reason;
   - a protected-namespace / protected-class GUARD (`protected-namespaces`
     / `protected-classes` — plain data vars, extendable) that skips
     dangerous targets WITHOUT aborting the batch;
   - the 1..100 target-cap enforcement (loud error over the cap);
   - the dry-run-by-DEFAULT / `persist`-to-commit convention (mirrors
     `sandbar.project.import`);
   - dependents enumeration (the target's `:mm/Section` tree +
     `:mm.memory/frontmatter` carrier) — ALWAYS reported so the caller
     sees the blast radius, included in the retraction set ONLY when
     `cascade` is chosen;
   - one atomic `:db.fn/retractEntity` transaction over targets + cascade
     set;
   - one `:mm.event/EntityRetracted` audit event per persisted retraction
     via the Keystone Event Substrate (`sandbar.util.event/log-event!`).

   Per `decisions/mcp_retraction_verb_substrate_first_over_nrepl_toolchain_workaround_2026_07_02`
   + the ratified Fable safety-semantics decision-tokens (SPEC
   scratchpad/retract-verb-2026-07-02/SPEC.md).

   Design: the report is built pure-functionally against a db snapshot;
   retraction reuses the existing conn accessor (`db/conn`) — no new conn
   plumbing (the C1 bulk-retract redesign owns that)."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [datomic.api :as d]
   [sandbar.db.datomic :as db]
   [sandbar.db.datatype :as dt]
   [sandbar.entity-ref :as eref]
   [sandbar.util.event :as event]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Guard configuration — plain data, extendable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def max-targets
  "Upper bound on the number of explicit targets accepted in one call
   (Fable decision-token #1).  >max-targets ⇒ loud error naming the cap.
   No predicate/query-based mass retraction in v1."
  100)

(def protected-namespaces
  "Ident-namespace prefixes whose entities are NEVER retracted (Fable
   decision-token #5).  A target's ident namespace is protected iff it
   equals one of these OR begins with one followed by a `.` (so `dt`
   protects `dt`, `dt.type`, `dt.fn`, ...; `db` protects `db`,
   `db.type`, ...).  Plain data — extend by conj-ing prefixes."
  #{"dt" "workflow" "mm.event" "db"})

(def protected-classes
  "Classes whose INSTANCES are NEVER retracted (Fable decision-token #5).
   `:mm/Actor` instances (provenance actors) + `:mm/Workflow` DEFINITIONS
   (retracting a workflow definition orphans its processes).  Membership
   is tested transitively via `dt/type-isa?` so subclasses are covered.
   Plain data — extend by conj-ing class idents."
  #{:mm/Actor :mm/Workflow})

(defn- namespace-protected?
  "True when `ns-str` (an ident's namespace string, may be nil) matches a
   `protected-namespaces` prefix exactly or as a `prefix.` segment head."
  [ns-str]
  (boolean
   (when ns-str
     (some (fn [prefix]
             (or (= ns-str prefix)
                 (str/starts-with? ns-str (str prefix "."))))
           protected-namespaces))))

(defn- class-protected?
  "True when the entity's `:dt/type` is (transitively) one of
   `protected-classes`.  `dt-type-ident` may be nil (untyped entity)."
  [dt-type-ident]
  (boolean
   (when dt-type-ident
     (some #(dt/type-isa? % dt-type-ident) protected-classes))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Snapshot readers — all take a `db` value; pure w.r.t. the substrate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- dt-type-ident
  "The `:dt/type` of the entity `e` (a `d/entity` map) projected to a
   keyword ident — handles both the keyword form and the resolved-Entity
   form Datomic returns for a ref slot.  nil when the entity is untyped."
  [e]
  (let [t (:dt/type e)]
    (cond
      (keyword? t)     t
      (associative? t) (:db/ident t)
      :else            nil)))

(defn- datom-count
  "Number of datoms asserted on `eid` in `db` (the entity's blast size).
   Counts the EAVT slice for the entity — every attribute assertion the
   `:db.fn/retractEntity` will remove."
  [db eid]
  (count (seq (d/datoms db :eavt eid))))

(defn- section-subtree-eids
  "Recursively collect the eids of the `:mm/Section` subtree rooted at
   `section-eid` in `db`: the section itself, every `:mm.section/next-sibling`
   in its chain, and every child (reverse `:_mm.section/parent`) subtree.
   `seen` guards against malformed cyclic chains."
  ([db section-eid] (section-subtree-eids db section-eid #{}))
  ([db section-eid seen]
   (if (or (nil? section-eid) (contains? seen section-eid))
     seen
     (let [seen    (conj seen section-eid)
           e       (d/entity db section-eid)
           ;; children under this section (reverse component-shaped ref)
           kids    (map :db/id (:mm.section/_parent e))
           seen    (reduce (fn [acc k] (section-subtree-eids db k acc)) seen kids)
           ;; forward sibling chain at the same level
           sib     (:db/id (:mm.section/next-sibling e))]
       (section-subtree-eids db sib seen)))))

(defn- owned-section-eids
  "The eids of every `:mm/Section` directly parented at memory `mem-eid` in
   `db` — the roots of the owned section subtree.  Read via the reverse
   `:mm.section/parent` index rather than `:mm.memory/first-section` because
   Datomic projects a ref to an IDENTFUL target (every codec-ingested section
   carries a path-derived `:db/ident`) as the bare `:db/ident` KEYWORD, not an
   EntityMap — so `(:db/id (:mm.memory/first-section mem))` is nil and the
   first-section seed silently enumerates nothing.  Reverse-parent is robust to
   every projection form of the slot (keyword / eid / EntityMap / nil / dangling)
   and matches the 108-twin remediation's host→sections enumeration.

   Per bugs/retract_cascade_blind_to_first_section_dependents_bare_keyword_projection_2026_07_03.md."
  [db mem-eid]
  (mapv first
        (d/q '[:find ?s
               :in $ ?mem
               :where [?s :mm.section/parent ?mem]]
             db mem-eid)))

(defn- dependents-of
  "Enumerate the ident-less, component-shaped children reachable from the
   target entity `e` (a `d/entity` map) in `db`: its `:mm/Section` tree
   (every section subtree parented at `e`, walked via next-sibling + child
   recursion) and its `:mm.memory/frontmatter` carrier.  Returns a vec of
   `{:eid :dt-type :datom-count}` maps.  Refs are NOT `:db/isComponent`,
   so `:db.fn/retractEntity` will NOT auto-cascade to these — they survive
   as orphans unless `cascade` is chosen (said so in the report).

   The section tree is seeded from the reverse `:mm.section/parent` index
   (`owned-section-eids`), not `:mm.memory/first-section`, so identful
   sections — the corpus norm — are not lost to the bare-keyword ref
   projection (see `owned-section-eids`)."
  [db e]
  (let [section-eids  (reduce (fn [acc root] (section-subtree-eids db root acc))
                              #{}
                              (owned-section-eids db (:db/id e)))
        frontmatter   (:db/id (:mm.memory/frontmatter e))
        dep-eids      (cond-> (vec (sort section-eids))
                        frontmatter (conj frontmatter))]
    (mapv (fn [dep-eid]
            (let [de (d/entity db dep-eid)]
              {:eid         dep-eid
               :dt-type     (dt-type-ident de)
               :datom-count (datom-count db dep-eid)}))
          dep-eids)))

(defn- inbound-refs-of
  "Enumerate the FOREIGN inbound typed-edges pointing AT `eid` in `db`: every
   `:db.type/ref` datom `[?src ?attr eid]` whose source is NOT in `owned-eids`
   — the citation graph that would DANGLE if `eid` were retracted
   (`:mm.memory/cites`, `:mm.memory/motivated-by`, and any other ref attribute
   a SURVIVING entity uses to reference the target).  Returns a vec of
   `{:source-eid :predicate :source-ident :dt-type}` maps, one per edge,
   ordered by source eid then predicate.

   `owned-eids` is the target's own outbound-dependent set (its section
   subtree + frontmatter carrier — see `dependents-of`).  Those children hold
   BACK-edges at the target (`:mm.section/parent`, `:mm.memory/frontmatter`'s
   inverse) that surface in `:vaet` but are NOT dangle risks: they are already
   reported on the OUTBOUND axis and are cascade-retracted with the target.
   Excluding them keeps the two axes disjoint so a plainly-sectioned memory
   with no external citers reads `:inbound-count 0` rather than tripping the
   dangle guard on its own sections.

   These edges are the retract verb's SECOND blast-radius axis and are
   invisible to `dependents-of` (which walks only OUTBOUND component refs):
   `:db.fn/retractEntity` removes the target's own datoms but leaves every
   inbound reference asserted against a now-vanished eid.  Read via the VAET
   reverse-index (`:vaet` is Datomic's value→attribute→entity index over ref
   datoms) so the query is class-agnostic — no hardcoded predicate list.

   Per bugs/retract_dependents_blind_to_inbound_citation_edges_dangling_refs_2026_07_03.md."
  [db eid owned-eids]
  (->> (d/datoms db :vaet eid)
       (remove (fn [[src _attr _v _tx]] (contains? owned-eids src)))
       (map (fn [[src attr _v _tx]]
              (let [se (d/entity db src)]
                {:source-eid   src
                 :predicate    (:db/ident (d/entity db attr))
                 :source-ident (:db/ident se)
                 :dt-type      (dt-type-ident se)})))
       (sort-by (juxt :source-eid #(str (:predicate %))))
       vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-target report — PURE against a db snapshot
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn report-target
  "Build the dry-run report for ONE `target` (an ident keyword-string or a
   numeric eid) against `db`.  Never raises — a missing target yields
   `{:exists? false}`.  Shape:

     {:target            <original target arg>
      :resolved-eid      <eid | nil>
      :exists?           <bool>
      :ident             <keyword | nil>
      :dt-type           <keyword | nil>
      :datom-count       <int>
      :dependents        [<{:eid :dt-type :datom-count}> ...]
      :inbound-refs      [<{:source-eid :predicate :source-ident :dt-type}> ...]
      :inbound-count     <int>
      :protected?        <bool>
      :protection-reason <string | nil>}

   `dependents` (OUTBOUND owned children) and `inbound-refs` (INBOUND
   citation edges that would DANGLE) are BOTH always enumerated regardless
   of `:cascade` so the caller sees the full two-axis blast radius.  A
   nonzero `:inbound-count` is what gates un-acknowledged `:persist` in
   `retract!` (per the inbound-blind-spot bug)."
  [db target]
  (let [{:keys [valid? entity]} (eref/validate target)]
    (if-not valid?
      {:target            target
       :resolved-eid      nil
       :exists?           false
       :ident             nil
       :dt-type           nil
       :datom-count       0
       :dependents        []
       :inbound-refs      []
       :inbound-count     0
       :protected?        false
       :protection-reason nil}
      (let [eid        (:db/id entity)
            ident      (:db/ident entity)
            dt-type    (dt-type-ident entity)
            dependents (dependents-of db entity)
            owned-eids (into #{} (map :eid) dependents)
            inbound    (inbound-refs-of db eid owned-eids)
            ns-prot?   (namespace-protected? (some-> ident namespace))
            cls-prot?  (class-protected? dt-type)
            protected? (or ns-prot? cls-prot?)
            reason     (cond
                         ns-prot?  (str "protected namespace: " (namespace ident))
                         cls-prot? (str "protected class: " dt-type)
                         :else     nil)]
        {:target            target
         :resolved-eid      eid
         :exists?           true
         :ident             ident
         :dt-type           dt-type
         :datom-count       (datom-count db eid)
         :dependents        dependents
         :inbound-refs      inbound
         :inbound-count     (count inbound)
         :protected?        protected?
         :protection-reason reason}))))

(defn build-report
  "Build the full dry-run report over `targets` against `db`.  Returns
   `{:targets [<report-target>...] :cascade <bool> :dependents-note <string>}`.
   PURE — transacts nothing.  `targets` must already be cap-checked by the
   caller (`retract!`)."
  [db targets cascade]
  {:targets         (mapv #(report-target db %) targets)
   :cascade         (boolean cascade)
   :dependents-note (if cascade
                      "cascade=true — enumerated dependents ARE included in the retraction set."
                      (str "cascade=false — enumerated dependents are NOT retracted; because "
                           "refs are not :db/isComponent they survive as ORPHANS."))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Retraction set + atomic transaction + audit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- retractable
  "Split the report's per-target entries into the vec of entries that WILL
   be retracted (exists? AND (not protected?)) and the vec skipped.  Both
   preserve report order."
  [report]
  (let [{proceed true skip false}
        (group-by (fn [t] (and (:exists? t) (not (:protected? t))))
                  (:targets report))]
    {:proceed (vec proceed) :skipped (vec skip)}))

(defn- retraction-eids
  "The full set of eids to retract in one tx: each proceeding target's
   `:resolved-eid`, plus (when cascade) each of its dependents' eids.
   De-duplicated, order-stable."
  [proceed cascade]
  (let [target-eids (map :resolved-eid proceed)
        dep-eids    (when cascade
                      (mapcat (fn [t] (map :eid (:dependents t))) proceed))]
    (vec (distinct (concat target-eids (or dep-eids []))))))

(defn- emit-audit-event!
  "Emit one `:mm.event/EntityRetracted` audit event for a persisted
   target-entry via the Keystone Event Substrate.  Mirrors the
   `sandbar.workflow.orchestrate` `try-emit!` pattern (best-effort — the
   audit must not mask a successful retraction) but the caller emits AFTER
   the atomic tx has already committed, so a failure here is logged, never
   thrown.  Carries target ident/eid, dt-type, datom-count, cascade set
   size, reason, and actor when supplied.  The structured payload rides on
   `:event/description` (an EDN string) since the base event schema has no
   per-slot columns for it; `:event/kind` is `:mm.event/EntityRetracted`
   for query/filtering."
  [{:keys [ident resolved-eid dt-type datom-count]} cascade-size reason actor-ent]
  (try
    ;; NB: the actor rides the :event/description EDN payload, NOT
    ;; :event/actor.  Live-probed 2026-07-02 (hot-load ceremony #2): the
    ;; dt/make validation/coercion layer cannot attach :dt/Ref slots on
    ;; this path in ANY form — raw eid and EntityMap are rejected
    ;; (:invalid-type "expects :dt/Ref"), and {:db/id …} / {:db/ident …}
    ;; maps VALIDATE then silently drop (read back nil).  See
    ;; bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.
    ;; Migrate to the typed :event/actor ref when that substrate bug lands.
    (event/log-event!
     :event/ServerEvent
     {:event/name        "EntityRetracted"
      :event/kind        :mm.event/EntityRetracted
      :event/level       :warn
      :event/namespace   "sandbar.retract"
      :event/status      :success
      :event/description (pr-str
                          {:target-ident  ident
                           :target-eid    resolved-eid
                           :dt-type       dt-type
                           :datom-count   datom-count
                           :cascade-size  cascade-size
                           :reason        reason
                           :actor-ident   (:db/ident actor-ent)
                           :actor-eid     (:db/id actor-ent)})})
    (catch Exception e
      ;; Best-effort for real: the retraction tx has already committed, so
      ;; an audit failure must never surface as a retraction failure.
      ;; Found live 2026-07-02 (hot-load ceremony #2 probe): the docstring
      ;; promised this contract but the body threw — a keyword :event/actor
      ;; failed dt/make validation AFTER a successful retraction.
      (log/warn e "EntityRetracted audit-event emission failed"
                {:target-ident ident :target-eid resolved-eid})
      nil)))

(defn retract!
  "First-class entity retraction with the ratified safety layer.

   `targets` — vec of idents (keyword-strings or keywords) or numeric eids,
     1..`max-targets`.
   `opts`:
     :persist — when true, COMMIT the retraction (default false ⇒ dry-run
                that transacts NOTHING and returns the full report).
     :cascade — when true, include each target's enumerated dependents in
                the retraction set (default false ⇒ named targets only).
     :reason  — REQUIRED when :persist (human-readable audit string).
     :actor   — optional actor ref carried into each audit event.
     :acknowledge-dangling — when true, PERMIT a :persist that would leave
                inbound citation edges dangling (a proceeding target with
                nonzero `:inbound-count`).  Default false ⇒ such a persist
                is REFUSED loudly so the caller cannot orphan a citation
                graph on a report that never showed the inbound edges.

   Loud errors (ex-info, nothing retracted):
     - empty targets                          → :retract/no-targets
     - > max-targets                          → :retract/target-cap-exceeded
     - :persist without :reason               → :retract/reason-required
     - :persist over live inbound refs without
       :acknowledge-dangling                  → :retract/inbound-refs-unacknowledged

   Returns the report map.  On :persist the report is augmented with
   `:persist true`, `:retracted-eids`, `:retracted-count`,
   `:skipped` (protected / missing entries), and `:events-emitted`.
   Protected + missing targets are SKIPPED (never abort the batch); the
   retraction tx itself is atomic — if it throws, nothing was retracted."
  [targets {:keys [persist cascade reason actor acknowledge-dangling] :as _opts}]
  (let [targets (vec targets)]
    (when (empty? targets)
      (throw (ex-info "retract requires at least one target"
                      {:reasons #{:retract/no-targets}})))
    (when (> (count targets) max-targets)
      (throw (ex-info (str "retract target cap exceeded: " (count targets)
                           " targets > cap of " max-targets)
                      {:reasons     #{:retract/target-cap-exceeded}
                       :cap         max-targets
                       :target-count (count targets)})))
    (when (and persist (str/blank? (str reason)))
      (throw (ex-info "retract with :persist true requires a non-blank :reason"
                      {:reasons #{:retract/reason-required}})))
    (let [;; Resolve the actor EARLY and LOUDLY (before any tx): a bad
          ;; actor ref must fail the call while nothing has been retracted,
          ;; not corrupt/kill the post-commit audit.  eref/resolve throws
          ;; structured ex-info on malformed/missing refs.  Live-probe
          ;; lesson 2026-07-02: raw keywords fail :event/actor validation.
          actor-ent (when (and persist actor)
                      (eref/resolve actor))
          db     (db/db)
          report (build-report db targets cascade)]
      (if-not persist
        (assoc report :persist false)
        (let [{:keys [proceed skipped]} (retractable report)
              eids (retraction-eids proceed cascade)]
          ;; Inbound-dangle guard (before any tx): retracting a target that
          ;; surviving entities still cite would leave those citations
          ;; pointing at a vanished eid.  The caller must SEE that risk
          ;; (:inbound-refs in the report) and explicitly accept it with
          ;; :acknowledge-dangling — otherwise the persist is refused while
          ;; nothing has been retracted.  Per the inbound-blind-spot bug:
          ;; the verb's dependents:[] must not be read as proof of safety.
          (when-not acknowledge-dangling
            (let [dangling (filter #(pos? (:inbound-count %)) proceed)]
              (when (seq dangling)
                (throw (ex-info
                        (str "retract with :persist true would DANGLE inbound "
                             "citation edges on " (count dangling)
                             " target(s); pass :acknowledge-dangling true to "
                             "proceed or repoint the inbound refs first")
                        {:reasons #{:retract/inbound-refs-unacknowledged}
                         :dangling-targets
                         (mapv (fn [t]
                                 {:target        (:target t)
                                  :resolved-eid  (:resolved-eid t)
                                  :inbound-count (:inbound-count t)})
                               dangling)})))))
          ;; ONE atomic transaction over targets + cascade set.  If it
          ;; throws, nothing was retracted and the error surfaces loudly.
          (when (seq eids)
            @(d/transact (db/conn)
                         (mapv (fn [eid] [:db.fn/retractEntity eid]) eids)))
          ;; Audit AFTER commit — one event per proceeding target,
          ;; best-effort (emit returns nil on failure; keep drops nils).
          (let [events (doall
                        (keep (fn [t]
                                (emit-audit-event!
                                 t
                                 (if cascade (count (:dependents t)) 0)
                                 reason actor-ent))
                              proceed))]
            (-> report
                (assoc :persist         true
                       :reason          reason
                       :retracted-eids  eids
                       :retracted-count (count eids)
                       :skipped         skipped
                       :events-emitted  (count events)
                       :audit-failures  (- (count proceed) (count events))))))))))
