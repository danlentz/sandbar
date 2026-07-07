(ns sandbar.firewall.enforce
  "S7 — THE RESOLVER-BINDING SITES (BU-3).

  The single namespace that binds the pure `sandbar.firewall.core` to a live
  db by INJECTING a label-resolver.  Three enforcement bodies + one adapter:

    check-entity-flow      EP-1 author-time: src label from the SPEC, governed
                           edges collected off the spec, resolver = spec-index-
                           first-then-DB-else-nil.  Returns {:violations :skipped}.
    check-batch            The `make-all*` floor body — folds check-entity-flow
                           over a batch with a shared spec-index.
    governed-edge-verdicts EP-3 traverse-time: for a src eid + a seq of
                           [slot target-eid] hops, the per-hop refusal verdict.
    verdict->error         adapts a core Verdict to the `validate-data` error
                           map ({:type :firewall-violation :severity :violation})
                           — the ONE shape EP-1 throws, EP-3 projects, S10 records.

  PRINCIPAL-INDEPENDENT throughout: no caller, no token, no `:identity` — every
  decision is a pure predicate over the EDGE's src/tgt labels (the trust model).

  ── R14 / CA-5: ACYCLICITY ─────────────────────────────────────────────────
  Requires ONLY `sandbar.firewall.core` + `sandbar.firewall.label` — NEVER
  `sandbar.db.datatype`.  `sandbar.db.datatype` requires THIS ns (the EP-1/EP-3
  arms, BU-4/BU-5); the reverse edge would be a load cycle.  The db is ALWAYS
  an INJECTED argument (datatype.clj passes its `db/db`), so this ns never
  reaches for a connection and holds no db dependency of its own.

  Spec: S7-PLAN §1.4 / BU-3, §4 (EP-1), §5 (EP-3), CA-1/CA-6."
  (:require [clojure.tools.logging :as log]
            [sandbar.firewall.core :as fw]
            [sandbar.firewall.label :as label]))

;;; ===========================================================================
;;; GOVERNED-EDGE COLLECTION off an entity-spec (props map)
;;; ===========================================================================

(defn- edge-values
  "Normalize a slot value to a seq of individual target-refs — a card-many
  value yields each member; a card-one value yields a singleton; nil yields
  nothing."
  [v]
  (cond
    (nil? v)  nil
    (set? v)  (seq v)
    (and (coll? v) (not (map? v))) (seq v)
    :else     [v]))

(defn collect-governed-edges
  "The `[slot target-ref]` pairs for every GOVERNED slot PRESENT in entity-spec
  `props` — one pair per card-many member.  Only governed slots (flow / carrier
  / tie) contribute; exempt + unknown slots are dropped here (their WARN/audit
  disposition, §2.5/R10, is orthogonal to the flow check).  An ABSENT slot is
  no edge — nothing fires (EP-1 checks the edges IN the spec)."
  [props]
  (into []
        (mapcat (fn [[slot v]]
                  (when (fw/firewall-governed-ref? slot)
                    (map (fn [tref] [slot tref]) (edge-values v)))))
        props))

;;; ===========================================================================
;;; RESOLVERS — the injected impurity (§1.4)
;;; ===========================================================================

(defn- spec-index-first-resolver
  "The EP-1 resolver: resolve a governed target-ref to a Label, consulting the
  intra-batch `spec-index` FIRST (a same-batch forward-ref resolves against its
  sibling spec — CA-6, never over-refused for tx-ordering), then the live db,
  else nil (the §4.4 skip case).  `spec-index` maps a ref key (`:db/ident` /
  tempid / `{:db/ident kw}` upsert map) to that sibling's entity-spec; nil
  spec-index degrades to db-only."
  [db spec-index]
  (fn [target-ref]
    (or (when spec-index
          (when-let [sibling (get spec-index target-ref)]
            (label/label-of db sibling)))
        (label/label-of-ref db target-ref))))

;;; ===========================================================================
;;; EP-1 — AUTHOR-TIME (check-entity-flow, check-batch)
;;; ===========================================================================

(defn check-entity-flow
  "EP-1 author-time flow check for ONE entity-spec — §4.  The SOURCE label is
  computed from `props` itself (the not-yet-written row, `label-from-props`);
  the governed edges are collected off `props`; the resolver is spec-index-
  first-then-DB-else-nil.  Returns the core's `{:violations [Verdict...]
  :skipped [...]}` — a caller (BU-4's commit guard) throws when `:violations`
  is non-empty and WARN-logs `:skipped`.

  Principal-INDEPENDENT: the check reads only `db`/`dt`/`props`, never a
  caller.  `spec-index` threads the intra-batch same-batch forward-refs (CA-6);
  the interactive single-write case passes `nil` (db-only resolver).  `db` is
  the INJECTED database value (datatype.clj's `db/db`).

  `dt` is the class ident; it is folded into `props` as `:dt/type` so
  `label-from-props` dispatches the correct class branch.

  The `:mm.memory/owning-project` edge is checked with the memory's INTRINSIC-
  visibility source label (§8-R8 / T-12): the general source label composes
  owning-project's sensitivity IN, which would swallow the declassification
  guard (an explicit-`:public` memory owned into a `:private` project would be
  re-labelled `:private` and its owning-project edge would then permit).  All
  OTHER governed edges use the composed source label."
  ([db dt props] (check-entity-flow db dt props nil))
  ([db dt props spec-index]
   (let [spec        (assoc props :dt/type dt)
         src-label   (label/label-from-props db spec)
         intrinsic   (label/intrinsic-visibility-label db spec)
         resolver    (spec-index-first-resolver db spec-index)
         all-edges   (collect-governed-edges spec)
         own-proj?   (fn [[slot _]] (= :mm.memory/owning-project slot))
         flow-edges  (remove own-proj? all-edges)
         owner-edges (filter own-proj? all-edges)
         flow-result (fw/violating-governed-edges src-label flow-edges resolver)
         ;; the owning-project declassification check uses the intrinsic label
         owner-result (fw/violating-governed-edges intrinsic owner-edges resolver)]
     {:violations (into (:violations flow-result) (:violations owner-result))
      :skipped    (into (:skipped flow-result) (:skipped owner-result))})))

(defn index-specs-by-ident
  "Build the intra-batch `spec-index` — a map from every resolvable ref key a
  sibling spec can be named by (`:db/ident`, `:db/id` tempid, and the
  `{:db/ident kw}` upsert map) to that spec.  So a same-batch forward-ref
  (`cites` a sibling declared later) resolves against its sibling instead of
  failing as an unknown ref (CA-6)."
  [entity-specs]
  (reduce
    (fn [idx spec]
      (let [id    (:db/ident spec)
            eid   (:db/id spec)]
        (cond-> idx
          id  (assoc id spec
                     {:db/ident id} spec)
          eid (assoc eid spec))))
    {}
    entity-specs))

(defn check-batch
  "The `make-all*` floor body — §4.3.  Builds the intra-batch `spec-index` once,
  then folds `check-entity-flow` over every spec, accumulating `:violations`
  and `:skipped` across the batch.  Firewall-ONLY (the trust-caller bulk
  contract keeps required/type/cardinality the caller's job — R5); the
  confidentiality floor is non-negotiable.

  A caller (BU-4) throws when `:violations` is non-empty and WARN-logs each
  `:skipped` (the best-effort carrier / stub case — bulk re-ingest file-ordering
  must not brick; the S9 closure re-checks).  Accepts an optional pre-built
  `spec-index` (so the VALIDATED `make-all` can thread the SAME index it built
  for its schema ref-range pass — CA-6).  `db` is the INJECTED database value."
  ([db entity-specs]
   (check-batch db entity-specs (index-specs-by-ident entity-specs)))
  ([db entity-specs spec-index]
   (reduce
     (fn [acc spec]
       (let [dt     (:dt/type spec)
             props  (dissoc spec :dt/type)
             result (check-entity-flow db dt props spec-index)]
         (-> acc
             (update :violations into (:violations result))
             (update :skipped into (:skipped result)))))
     {:violations [] :skipped []}
     entity-specs)))

;;; ===========================================================================
;;; EP-3 — TRAVERSE-TIME (governed-edge-verdicts)
;;; ===========================================================================

(defn hop-forbidden?
  "EP-3 per-hop verdict — is the single traversal hop from `src-eid` over
  governed `slot` to `tgt-eid` FORBIDDEN under `db`?  The pure core over an
  eid-resolver instance (`label-of-eid`), principal-INDEPENDENT.  Returns the
  refusal Verdict when forbidden, else nil.

  A NON-governed slot is never forbidden (returns nil) — EP-3 lists exempt
  edges normally.  §5 / R15 / CA-2 (this is the eid-level verdict the edges-of
  wrappers AND `graph-walk-from`'s frontier expansion apply per row)."
  [db src-eid slot tgt-eid]
  (when (fw/firewall-governed-ref? slot)
    (let [src-label (label/label-of-eid db src-eid)
          verdicts  (fw/violating-governed-edges
                      src-label
                      [[slot tgt-eid]]
                      (fn [_] (label/label-of-eid db tgt-eid)))]
      (first (:violations verdicts)))))

(defn governed-edge-verdicts
  "EP-3 traverse-time — for source eid `src-eid` and a seq of `[slot tgt-eid]`
  hops under `db`, the seq of refusal Verdicts (forbidden hops only).  §5.
  Consumed by the edges-of projection + `graph-walk-from` frontier guards
  (BU-5) to REWRITE a forbidden edge as `{:blocked true}` (no `:target`)."
  [db src-eid hops]
  (keep (fn [[slot tgt-eid]] (hop-forbidden? db src-eid slot tgt-eid)) hops))

(defn endpoint-permitted?
  "COARSE seed→endpoint firewall filter — true iff a flow FROM the `src-eid`
  label TO the `tgt-eid` label is permitted by the pure core, comparing ONLY
  the two endpoint labels (no intermediate hop).

  This is the fail-closed fallback for the ONE reachability surface where
  per-hop tracking is unavailable: the `path-via` `:NOT` / `:FILTER` / `:TEST`
  endpoint-only route (the path-data evaluator, which applies the exact per-hop
  EP-3 verdict, does not yet execute those Tier-2 operators).  It is STRICTLY
  WEAKER than `hop-forbidden?`: it correctly drops the common leak (a public
  seed reaching a private endpoint), but a path that dips through a `:public`
  intermediate back into the SEED's OWN private compartment is not caught here
  (only the per-hop evaluator catches that residual).  Principal-INDEPENDENT.
  The complete fix is evaluator support for :NOT / :FILTER / :TEST, at which
  point this coarse filter is retired.  Per S7 leak-sweep 2026-07-06."
  [db src-eid tgt-eid]
  (fw/firewall-permits? (label/label-of-eid db src-eid)
                        (label/label-of-eid db tgt-eid)))

;;; ===========================================================================
;;; VERDICT → validate-data ERROR (§1.4 / R19)
;;; ===========================================================================

(defn verdict->error
  "Adapt a core refusal Verdict to the `validate-data` error map — the ONE
  shape EP-1 throws, `entity.validate` surfaces, and S10 records (no second
  detection path, R19).  `:severity :violation`."
  [verdict]
  {:type       :firewall-violation
   :severity   :violation
   :reason     (:reason verdict)
   :slot       (:slot verdict)
   :target-ref (:target-ref verdict)
   :message    (str "Firewall violation: " (name (:reason verdict))
                    " on " (:slot verdict)
                    " → " (pr-str (:target-ref verdict)))})

(defn verdicts->error-envelope
  "Compose a seq of refusal Verdicts into the ex-info payload EP-1 throws —
  `{:errors [error-map ...]}`, the same envelope shape `validate-data` returns
  and `make`/`update-entity!` already `throw` on.  nil when there are no
  violations."
  [verdicts]
  (when (seq verdicts)
    {:errors (mapv verdict->error verdicts)}))

(defn warn-skipped!
  "WARN-log each `:skipped` (unresolved governed target) record — the best-
  effort carrier / stub disposition (§4.4 / R11): do not brick authoring on a
  dangling rel-path or a stub-then-fill target; the S9 closure re-checks.
  Log-only, deterministic, no LLM."
  [skipped]
  (doseq [s skipped]
    (log/warn :FIREWALL/skipped-unresolved-target
              {:slot (:slot s) :target-ref (:target-ref s)})))
