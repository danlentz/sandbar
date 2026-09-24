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
  (:require [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.db.ref :as ref]
            [sandbar.mcp.clearance :as clearance])
  (:import (datomic Entity)))

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

(defn- resolve-in [database value]
  (when database
    (when-let [eid (ref/ref->eid database value)]
      (d/entity database eid))))

(defn- normalize-entity [database entity]
  (if (or (nil? entity) (map? entity) (instance? Entity entity))
    entity
    (resolve-in (or database (db/db)) entity)))

(defn- instance-of? [class-ident entity]
  (let [t (:dt/type entity)]
    (dt/type-isa? class-ident (if (keyword? t) t (:db/ident t)))))

(defn compartmented?
  "True iff `entity` carries a confidentiality compartment at all — it is an
   instance of `:mm/Memory`, or one of its document-owned `:mm/Section` or
   `:mm/Frontmatter` components, including subclasses. Components inherit
   authority from the host; a label copied onto a component is not authority.

   The metamodel (`:dt/Class`, `:dt/Property`), the tag vocabulary, the verb
   catalog and other entities are OUTSIDE the compartment model
   and read under the namespace firewall alone — exactly the scoping of the
   firewall label core, whose `label-of` dispatches a compartment only for a
   `:mm/Memory` descendant.  An entity whose class cannot be read (a raw map
   without `:dt/type`, a pre-commit spec) counts as compartmented only when it
   carries one of the two compartment slots itself."
  [entity]
  (let [entity (normalize-entity nil entity)]
    (boolean
      (if (:dt/type entity)
        (or (instance-of? :mm/Memory entity)
            (instance-of? :mm/Section entity)
            (instance-of? :mm/Frontmatter entity))
        (or (contains? entity :mm.memory/visibility)
            (contains? entity :mm.memory/owning-project))))))

(defn- entity-db [database entity]
  (or database (when (instance? Entity entity) (d/entity-db entity))))

(defn- section-document
  "Follow only Section parents to a Memory in one immutable database.
   Absent, cyclic, untyped and wrong-kind chains have no read authority."
  [database section]
  (loop [current (resolve-in database section) seen #{}]
    (when-let [eid (:db/id current)]
      (when-not (contains? seen eid)
        (cond
          (instance-of? :mm/Memory current) current
          (instance-of? :mm/Section current)
          (recur (resolve-in database (:mm.section/parent current)) (conj seen eid)))))))

(defn- frontmatter-document
  "The carrier must have exactly one owning Memory via the actual component
   edge. Its path-derived ident and any copied labels prove no ownership."
  [database carrier]
  (when-let [eid (and database (ref/ref->eid database carrier))]
    (let [owners (d/q '[:find [?owner ...] :in $ ?carrier
                        :where [?owner :mm.memory/frontmatter ?carrier]]
                      database eid)]
      (when (= 1 (count owners))
        (let [owner (d/entity database (first owners))]
          (when (instance-of? :mm/Memory owner) owner))))))

(defn read-compartment
  "Read authority for a memory or its owned content, at `database` or the
   Datomic entity's snapshot. Sections use their parent chain; frontmatter
   uses its unique host edge. Invalid ownership returns an unknown compartment
   that no principal clears, even with full clearance. Never copy host labels.
   Raw component maps need an explicit database to establish ownership."
  ([entity] (read-compartment nil entity))
  ([database entity]
   (let [entity (normalize-entity database entity)
         database (entity-db database entity)
         owner (cond
                 (instance-of? :mm/Section entity) (section-document database entity)
                 (instance-of? :mm/Frontmatter entity) (frontmatter-document database entity)
                 :else entity)]
     (if owner
       (clearance/entity-compartment database owner)
       {:visibility ::invalid-ownership :project nil}))))

(defn entity-visible-to?
  "THE read-plane visibility decision: may `principal` be shown `entity`?

   Rules, in order:

   - `principal` nil (in-process) → true.  Unrestricted, the tools/call
     gate's nil convention.  (The resources plane keeps its own stricter
     documented nil policy — `:public` and non-compartmented only — and
     applies it BEFORE calling here; see `resources/read-cleared?`.)
   - `entity` nil → false.
   - `entity` not `compartmented?` (not memory or owned content) → true. Outside the
     compartment model; the namespace firewall is the separate rule.
   - otherwise → `clearance/cleared-for-compartment?` over
     `read-compartment`, preserving the S5 clearance rules:
     `:public` clears for everyone; a `:private` (or absent-visibility,
     `*default-visibility*`) memory clears only for a principal marked
     `:auth/full-clearance?` or cleared for its owning project; anything
     unknown or invalid component ownership fails closed.

   The 3-arity accepts an explicit immutable database; otherwise a Datomic
   entity supplies its own snapshot. Both ownership and project-clearance
   references resolve against that database. Raw memory maps remain supported."
  ([principal entity] (entity-visible-to? nil principal entity))
  ([db principal entity]
   (if (nil? principal)
     true
     (let [entity (normalize-entity db entity)]
       (cond
         (nil? entity)                 false
         (not (compartmented? entity)) true
         :else (let [db (entity-db db entity)]
                 (clearance/cleared-for-compartment?
                   db principal (read-compartment db entity))))))))

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
