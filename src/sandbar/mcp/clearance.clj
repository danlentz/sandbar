(ns sandbar.mcp.clearance
  "Compartment-clearance predicates for the notify/read plane (AP-S4-2 / DEP-S5→S9).

   Compares a subscriber PRINCIPAL's cleared-project set against an ENTITY's
   confidentiality compartment (`:mm.memory/visibility` + `:mm.memory/owning-project`)
   and answers one question: may this principal see this entity?  Every S5
   notify/read enforcement point (EP-N1 subscribe-refusal, EP-N2 delivery-time
   filter, EP-N3 read-body check) routes its authorization decision through
   `cleared-for-compartment?` / `subscriber-cleared-for-entity?`.

   Fails CLOSED by construction: an unknown visibility level, a nil/absent
   clearance on a restricted principal, or a missing visibility slot all
   resolve to NOT-cleared.  A subscriber the predicate cannot POSITIVELY clear
   is excluded — the filter keeps-if-cleared, never drops-if-forbidden.

   INERT-UNTIL-S6.  The compartment slots this reads — `:mm.memory/visibility`
   and `:mm.memory/owning-project` on the entity, `:auth/cleared-projects` and
   `:auth/full-clearance?` on the principal — DO NOT EXIST in the schema yet;
   they mint at S6 (grep-proven absent, DESIGN-D3 §7).  Pre-S6 every entity
   reads visibility=nil, so `entity-compartment` defaults it to
   `*default-visibility*` (fail-closed `:private`, AP-6); `principal-clears-project?`
   reads a nil cleared-project set → clears nothing.  Net pre-S6 posture: the
   gate delivers a restricted entity's notification/body to NOBODY except a
   `:public`-visibility entity or a full-clearance token.  S6 LOOSENS this by
   minting real compartments; the gate is safe-by-default the instant it lands.

   Pure predicates — no DB reads, no wire deps, leaf namespace.  All slot reads
   are keyword-as-fn lookups over already-loaded entity/principal maps.

   Spec: DESIGN-D3-NOTIFY-PLANE-GATE.md §2 (verbatim), S5-PLAN.md §2.2 item 3.")

(def ^:dynamic *default-visibility*
  "Visibility assumed when `:mm.memory/visibility` is ABSENT — the pre-S6
   posture, and any legacy row S6's backfill misses.

   FAIL-CLOSED default = `:private` (AP-6, ratified): before S6 mints the
   visibility slot every entity reads as `:private`, so a restricted principal
   is denied by default and only a `:public`-tagged entity or a full-clearance
   token gets through.  Overriding to `:public` would flip the pre-S6 posture
   to allow-by-default (the gate no-ops until S6 backfill) — a Dan ruling
   (OF-D3-1), not a code default.  Constraint C (fail-closed for an
   unknown/unscoped principal) mandates `:private`."
  :private)

(defn entity-compartment
  "Return `entity`'s confidentiality compartment as `{:visibility kw :project id-or-nil}`.

   `:visibility` is the entity's `:mm.memory/visibility` keyword, or
   `*default-visibility*` when the slot is absent (the fail-closed pre-S6
   default).  `:project` is the `:db/id` of the entity's `:mm.memory/owning-project`
   ref — the compartment key for a `:private` entity — or nil for a `:public`
   entity (and pre-S6, when the ref slot is absent).

   INERT-UNTIL-S6: both slots mint at S6, so pre-S6 this returns
   `{:visibility *default-visibility* :project nil}` for every entity."
  [entity]
  {:visibility (or (:mm.memory/visibility entity) *default-visibility*)
   :project    (:db/id (:mm.memory/owning-project entity))})

(defn principal-clears-project?
  "True iff `principal` is cleared for the project-compartment `project-eid`.

   A principal explicitly marked `:auth/full-clearance? true` short-circuits
   to true (AP-11 mechanism (b), ratified): the operator's own Bearer sees
   every compartment by construction, not by a nil-principal accident.
   Otherwise the principal's `:auth/cleared-projects` set (a multi-ref to
   `:mm/Project`) is read and membership of `project-eid` decides it.

   Fail-closed: a nil principal, an absent `:auth/cleared-projects` slot, or a
   `project-eid` the principal does not clear all resolve to false.

   INERT-UNTIL-S6: both `:auth/full-clearance?` and `:auth/cleared-projects`
   mint at S6, so pre-S6 a restricted (non-full-clearance) principal reads an
   empty cleared set and clears NOTHING."
  [principal project-eid]
  (boolean
    (when principal
      (or (:auth/full-clearance? principal)
          (let [cleared (set (map :db/id (:auth/cleared-projects principal)))]
            (contains? cleared project-eid))))))

(defn cleared-for-compartment?
  "THE clearance predicate.  True iff `principal` may see an entity in
   `compartment` (an `{:visibility :project}` map from `entity-compartment`).

   Rules (fail-closed):
   - `:public`  → cleared (the lattice bottom; everyone reads down into it)
   - `:private` → cleared iff `principal` clears the owning project
   - any other / unknown visibility → NOT cleared (fail-closed)

   The nil-principal case is NOT decided here — the CALLER contract governs it
   (`subscriber-cleared-for-entity?` for the notify plane: nil = `:public`
   only).  This predicate assumes a non-nil principal; `principal-clears-project?`
   is itself nil-safe (returns false), so a nil principal here would be treated
   as clearing nothing — but callers should route nil through their own policy."
  [principal {:keys [visibility project]}]
  (case visibility
    :public  true
    :private (principal-clears-project? principal project)
    ;; unknown lattice level — fail closed
    false))

(defn subscriber-cleared-for-entity?
  "Delivery-time gate: may the subscriber identified by `subscriber-id` receive
   a notification about `entity`?  Recovers the subscriber's principal from the
   `subscribers` registry map (`notifications/all-subscribers` shape:
   subscriber-id → `{:identity principal …}`) and evaluates the compartment
   predicate against `entity`'s compartment.

   NIL-IDENTITY POLICY (notify-plane, distinct from the tools/call gate; AP-8
   ratified): a subscriber with no `:identity` is cleared for a `:public`
   compartment ONLY.  A remote SSE listener is never the trusted local caller,
   so the fail-open nil=full-access convention of the tools/call gate does NOT
   cross to the notify plane — copying it would re-open the exact private→any
   leak this gate closes.  (Defense-in-depth: `/mcp/sse` inherits require-bearer,
   so wire nil-identity should be unreachable; this guards against chain-order
   drift.)"
  [subscribers entity subscriber-id]
  (let [principal   (:identity (get subscribers subscriber-id))
        compartment (entity-compartment entity)]
    (if (nil? principal)
      (= :public (:visibility compartment))
      (cleared-for-compartment? principal compartment))))
