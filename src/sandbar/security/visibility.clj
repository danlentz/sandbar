(ns sandbar.security.visibility
  "THE read-plane visibility decision — one function for the MCP tools, the
   MCP resources and the REST reads — and the principal binding that lets the
   projection layer apply it to every projected entity.

   Reliability sprint D4b, contract CT-03 (2026-09-19).  Before this, only
   `resources/read` consulted the confidentiality compartment: `entity.find`
   returned a `:private` body to a principal that `resources/read` refused,
   and `resources/list` advertised the URI.  Now every externally reachable
   read routes its per-entity disclosure through `entity-visible-to?`, the
   single-entity reads answer the not-found shape of an absent entity when it
   refuses (no existence oracle), and the projection layer redacts an
   uncleared entity wherever it appears — a search hit, a navigation target, a
   class instance, a nested ref — as the backstop (the F5 shared-splice-site
   discipline, one layer out).  A record hidden on one path cannot reappear
   through another.

   Two rules are kept SEPARATE on purpose (the fleet's CT-03 acceptance):
   namespace secrecy — `sandbar.security.query/read-plane-entity-visible?`,
   a static rule on the entity's class or ident namespace — and compartment
   clearance, decided here.  Neither masks the other.

   Layering: requires the leaf compartment predicates (`sandbar.mcp.clearance`,
   unchanged, which the notify plane also shares) and the type lattice
   (`sandbar.db.datatype`, for the memory-shaped test).  `sandbar.api.projection`
   requires this namespace; nothing in the datatype chain requires the
   projection layer, so there is no load cycle (checked over the require graph
   at landing)."
  (:require [sandbar.db.datatype   :as dt]
            [sandbar.mcp.clearance :as clearance]))

(def ^:dynamic *principal*
  "The authenticated principal on whose behalf the current read runs, or nil.

   Bound by `sandbar.mcp.tools/handle-call` around a verb's handler (the wire
   principal `protocol/dispatch` threads) and by the REST store handlers around
   their projections, so the projection layer's compartment backstop
   (`compartment-scrub`) can decide disclosure for EVERY projected entity
   without each verb threading the principal by hand.

   nil is the in-process caller (CLI, REPL, fixtures, the reactive projection):
   unrestricted, the tools/call gate's own nil convention.  The wire never
   reaches a read with nil: the `/mcp` chain's `require-bearer` and the `/api`
   chain's `require-authentication` refuse a nil identity first."
  nil)

(def compartment-redaction-marker
  "Placeholder the projection layer returns in place of an entity the bound
   principal is not cleared for — no id, ident, class or slot, the same shape
   discipline as `sandbar.security.query/read-plane-redaction-marker`.  A count
   of markers in a collection is a weak structural oracle only; the single-
   entity reads (`entity.find`, `resources/read`, REST `/entities`) answer
   not-found instead, indistinguishable from an absent entity."
  {:mm/redacted "compartment"})

(defn missing-shape
  "The `{:missing? true}` answer a single-entity read gives for an entity it
   will not disclose — byte-identical to the shape `entity-ref/validate`
   produces for an absent entity, so a refusal and an absence cannot be told
   apart (AP-10, the existence-oracle rule)."
  [lookup]
  {:entity nil :missing? true :lookup lookup :reasons #{:entity-ref/not-found}})

(defn compartmented?
  "True iff `entity` carries a confidentiality compartment at all — it is an
   instance of `:mm/Memory` (the domain of `:mm.memory/visibility` and
   `:mm.memory/owning-project`), by class or by inheritance.

   The metamodel (`:dt/Class`, `:dt/Property`), the tag vocabulary, the verb
   catalog and every other non-memory entity are OUTSIDE the compartment model
   and read under the namespace firewall alone — exactly the scoping of the
   firewall label core, whose `label-of` dispatches a compartment only for a
   `:mm/Memory` descendant.  An entity whose class cannot be read (a raw map
   without `:dt/type`, a pre-commit spec) counts as compartmented only when it
   carries one of the two compartment slots itself."
  [entity]
  (boolean
    (if-let [cls (dt/class-ident-of entity)]
      (dt/type-isa? :mm/Memory cls)
      (or (contains? entity :mm.memory/visibility)
          (contains? entity :mm.memory/owning-project)))))

(defn entity-visible-to?
  "THE read-plane visibility decision: may `principal` be shown `entity`?

   Rules, in order:

   - `principal` nil (in-process) → true.  Unrestricted, the tools/call
     gate's nil convention.  (The resources plane keeps its own stricter
     documented nil policy — `:public` and non-compartmented only — and
     applies it BEFORE calling here; see `resources/read-cleared?`.)
   - `entity` nil → false.
   - `entity` not `compartmented?` (not memory-shaped) → true.  Outside the
     compartment model; the namespace firewall is the separate rule.
   - otherwise → `clearance/cleared-for-compartment?` over
     `clearance/entity-compartment`, the S5 clearance predicate unchanged:
     `:public` clears for everyone; a `:private` (or absent-visibility,
     `*default-visibility*`) memory clears only for a principal marked
     `:auth/full-clearance?` or cleared for its owning project; anything
     unknown fails closed.

   `db` may be threaded (the 3-arity) so an ident-bearing owning-project ref
   resolves to its eid; the 2-arity is the pure shape."
  ([principal entity] (entity-visible-to? nil principal entity))
  ([db principal entity]
   (cond
     (nil? principal)              true
     (nil? entity)                 false
     (not (compartmented? entity)) true
     :else (clearance/cleared-for-compartment?
             principal (clearance/entity-compartment db entity)))))

(defn compartment-scrub
  "The projection layer's compartment backstop: given the RAW `entity` and its
   already-scrubbed `projected` map, return the compartment marker when a
   principal is bound and `entity` is not visible to it, else `projected`
   unchanged.  Total; nil in, nil out."
  [entity projected]
  (when projected
    (if (and (some? *principal*)
             (not (entity-visible-to? *principal* entity)))
      compartment-redaction-marker
      projected)))
