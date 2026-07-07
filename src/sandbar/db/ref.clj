(ns sandbar.db.ref
  "The public ref→eid normalizer — the ONE canon that resolves any
   `:db.type/ref` slot value to the `:db/id` of the live entity it names, or
   `nil` when it names no live entity.

   This is a TRUE LEAF namespace (S7 BU-0 / CA-5 / ruling R14): it requires
   ONLY `datomic.api` and takes the database as an INJECTED argument.  It MUST
   NOT require `sandbar.db.datatype` NOR `sandbar.db.datomic`, and MUST NOT
   expose any convenience arity that would need either — leaf-purity is
   BUILD-CRITICAL.  It is why `sandbar.firewall.*` and `sandbar.mcp.clearance`
   can import `ref->eid` without transitively pulling `sandbar.db.datatype`
   (whose post-S7 `datatype → enforce → firewall.core` require chain would
   otherwise form a load cycle).  A build-time assertion (S7 BU-7) fails the
   build if this ns's deps ever contain `sandbar.db.datatype` or
   `sandbar.db.datomic`.

   SENTINEL-COLLAPSE CURE (S6-review #1): an ident keyword resolves through
   `(:db/id (d/entity db kw))`, NEVER `(:db/id kw)`.  Datomic renders a ref to
   an ident-bearing entity (a `:project/UNASSIGNED` sentinel, an interned
   ident-bearing project) as the `:db/ident` KEYWORD; naively reading `:db/id`
   off that keyword yields `nil` and VOIDS the ownership.  Resolving the
   keyword through the db recovers the real eid, so a sentinel or interned
   ident-bearing ref never collapses to `nil`.

   Callers that MUST agree on ref shapes (the card-many replace diff, `make`'s
   pre-transact ref coercion, `value-matches-range?`'s existence check, the
   schema-shape GC in `sandbar.db.datomic`, and the clearance-plane compartment
   reads) all route through this canon.

   Provenance:
   - bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md
     (validation-and-transaction disagreed on ref shapes; upsert maps validated
     then silently dropped on the single-tx create path).
   - bugs/clearance_helper_collapses_ident_bearing_project_refs_to_nil_confirmed_failclosed_latent_failopen_s6_mustfix_2026_07_06.md
     (the (:db/id keyword) sentinel collapse this ns cures at the substrate)."
  (:require [datomic.api :as d]))

(defn ref->eid
  "Resolve ref value `v` to the `:db/id` of the live entity it names under
   database `db`, or `nil` when it resolves to no live entity.

   Accepts every shape a caller can hand a `:db.type/ref` slot:
   - `nil`                              → nil (a non-edge)
   - a `{:db/id eid}` / Datomic Entity  → the `:db/id` read directly
   - a single-key upsert map `{:db/ident kw}` / `{<unique-attr> v}` (the codec's
     ref shape) → resolved via an explicit lookup-ref, because `d/entity`
     returns an associative value UNCHANGED (an upsert map would otherwise
     resolve to itself and yield a nil `:db/id`)
   - an ident KEYWORD → `(:db/id (d/entity db kw))` (the sentinel-collapse cure:
     NEVER `(:db/id kw)`, which yields nil and voids ident-bearing ownership)
   - an eid Long or a lookup-ref vector `[:unique-attr v]` → resolved via
     `d/entity`

   `db` is the INJECTED database value (an immutable `d/db` snapshot); this ns
   never reaches for a connection.  `nil` return ⇒ unresolvable — the caller
   decides whether that is a non-matching diff member, a loud validation
   rejection, or a fail-closed skip; this canon never guesses.

   Total + exception-safe: a malformed lookup-ref / bogus value that makes
   `d/entity` throw resolves to `nil` rather than propagating."
  [db v]
  (cond
    (nil? v)
    nil

    ;; A map/Entity already carrying :db/id — read it directly.
    (and (associative? v) (contains? v :db/id))
    (:db/id v)

    ;; Single-key upsert map (e.g. {:db/ident kw}): d/entity returns an
    ;; associative value as-is, so resolve via an explicit lookup-ref.
    (and (map? v) (= 1 (count v)))
    (let [[k val] (first v)]
      (some-> (try (d/entity db [k val]) (catch Throwable _ nil)) :db/id))

    ;; Ident keyword — the sentinel-collapse cure: resolve THROUGH the db,
    ;; never (:db/id kw).
    (keyword? v)
    (some-> (try (d/entity db v) (catch Throwable _ nil)) :db/id)

    ;; eid Long or lookup-ref vector [:unique-attr v].
    :else
    (some-> (try (d/entity db v) (catch Throwable _ nil)) :db/id)))
