(ns sandbar.identifier
  "ζ Scope B stable-identifier substrate primitive — sandbar-internal staging
  ground for clj-uuid v5 federation-anchor identity computation.

  Per the ζ Scope B ADR §3 (authority-UUID + namespace-UUID + entity-UUID
  derivation chain) + the build-prove-promote discipline (Dan-directive
  2026-05-26: `interaction/build_protocol_extensions_in_sandbar_first_prove_consume_locally_then_promote_to_public_library_release_dan_directive_2026_05_26.md`):
  this namespace BUILDS the extension capabilities sandbar-internally;
  PROVES them via β.2.3 migration tx-fn consumption + 1+ session of
  operational use; only THEN promotes to clj-uuid as a public-release PR.

  The OPAQUE tier of the 3-tier identifier value hierarchy
  (`memory/decisions/three_tier_identifier_value_hierarchy_...md`) is
  operationalized via `:mm/id` slot population at β.2.3 migration time;
  this namespace provides the derivation primitives.

  Per ζ Scope B ADR §3.1: the per-deployment authority-UUID is computed
  from a `tag:` URI seed (RFC 4151; date-scoped permanence) via clj-uuid v5.
  For the claude-corpus deployment, the seed is
  'tag:danlentz.github.io,2026:claude-memory'.

  Per ADR §3.3: per-entity `:mm/id` is derived deterministically from
  (authority-UUID, namespace-name, entity-slug) — any sandbar deployment
  with the same authority-UUID + same namespace-slug + same entity-slug
  computes the SAME entity-UUID. This enables federation without
  coordination.

  Pragmatic scope (per the build-prove-promote discipline's §3.3
  recommendation: don't speculatively build):
  - LOAD-BEARING for v0.2.0: authority-UUID + namespace-uuid +
    entity-uuid + ident-uuid (β.2.3 migration consumption)
  - NOT YET BUILT (deferred): to-urn-string-with-nid (custom NID for
    `urn:sandbar:` if IANA-registered) + to-crockford-base32-string
    (ARK Noid-style transcription-safe form) + UUIDable protocol
    extension for :mm/Memory entity-records (entity-as-namespace-anchor)

  See:
  - decisions/zeta_scope_b_stable_identifier_substrate_primitive_three_slot_model_...md (the ADR; §3 chain)
  - interaction/build_protocol_extensions_in_sandbar_first_prove_consume_locally_then_promote_to_public_library_release_dan_directive_2026_05_26.md (the discipline)
  - libraries/synthesis/stable_identifier_substrate_clj_uuid_extension_three_slot_model_for_sandbar_zeta_2026_05_25.md (the original synthesis)
  - decisions/three_tier_identifier_value_hierarchy_...md (ζ Scope A)
  - preferences/clj_uuid_as_preferred_substrate_primitive_for_zeta_stable_identifier_layer_evolvable_at_will_2026_05_25.md"
  (:require [clj-uuid :as uuid]
            [clojure.string :as str]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authority-UUID — per-deployment frozen-at-init root
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per ζ Scope B ADR §3.1. The `tag:` URI seed (RFC 4151) makes the
;; authority-UUID derivable from a frozen string identifier that survives
;; independent of substrate state — any sandbar JVM with the same seed
;; computes the same authority-UUID, enabling deterministic federation.
;;
;; Wrapped in a Var (via `def`) rather than a constant so it can be
;; rebound at test-time via `with-redefs`. For production deployment,
;; the default seed corresponds to the claude-corpus deployment;
;; alternate deployments rebind this Var at sandbar init.

(def ^{:doc "Default authority-UUID seed for the claude-corpus deployment.
            Operator-supplied for other deployments at sandbar init.
            Per ζ Scope B ADR §3.1 (`tag:` URI; RFC 4151)."}
  +default-authority-seed+
  "tag:danlentz.github.io,2026:claude-memory")


(def ^{:doc "Per-deployment authority-UUID, computed at namespace-load time
            from `+default-authority-seed+`. NEVER regenerated within a
            deployment; treated as tombstone identity. Per ζ Scope B ADR §3.1.
            Override at sandbar init via `(alter-var-root #'+authority+
              (constantly (uuid/v5 uuid/+namespace-url+ <operator-seed>)))`."}
  +authority+
  (uuid/v5 uuid/+namespace-url+ +default-authority-seed+))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespace + entity UUID derivation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per ζ Scope B ADR §3.2 + §3.3:
;;
;;   namespace-UUID = v5(authority-UUID, namespace-name)
;;   entity-UUID    = v5(namespace-UUID,  entity-slug)
;;
;; Determinism: same inputs → same UUID. Federation-ready by construction.

(defn namespace-uuid
  "Derive the namespace-UUID for `namespace-name` (e.g. \"decisions\").
   Per ζ Scope B ADR §3.2.

   Uses the global `+authority+` Var as the v5 namespace anchor.
   Override `+authority+` to compute namespace-UUIDs against a different
   authority (e.g., a federated deployment's authority)."
  [namespace-name]
  {:pre [(string? namespace-name) (not (str/blank? namespace-name))]}
  (uuid/v5 +authority+ namespace-name))


(defn entity-uuid
  "Derive the per-entity :mm/id v5 UUID from `namespace-name` (e.g. \"decisions\")
   + `slug` (e.g. \"three_tier_identifier_value_hierarchy_...\").
   Per ζ Scope B ADR §3.3.

   Equivalent to: (uuid/v5 (namespace-uuid namespace-name) slug).

   Deterministic: same (namespace-name, slug) → same UUID across deployments
   with the same `+authority+`."
  [namespace-name slug]
  {:pre [(string? namespace-name) (not (str/blank? namespace-name))
         (string? slug) (not (str/blank? slug))]}
  (uuid/v5 (namespace-uuid namespace-name) slug))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience — ident → :mm/id derivation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Corpus :mm/Memory entities carry `:db/ident` keywords of shape
;;   :memory.<class>/<slug>      (e.g. :memory.decisions/three_tier_…)
;;   :memory.libraries.<sub>/<slug>  (e.g. :memory.libraries.clojure/core_async)
;;
;; This helper extracts (namespace-name, slug) from such a keyword and
;; computes the entity-UUID via `entity-uuid`. The "memory." prefix is
;; stripped so the namespace-UUID maps to the corpus-namespace concept
;; (e.g. "decisions" not "memory.decisions" — the "memory." prefix is a
;; substrate convention layer above the namespace concept itself).

(def ^{:doc "Substrate convention: corpus :mm/Memory entity idents carry the
            `memory.` namespace prefix; the prefix is stripped before
            namespace-UUID derivation so deployments using different
            prefix-conventions still federate consistently."}
  +memory-prefix+
  "memory.")


(defn ident->namespace-name
  "Extract the namespace-name (without the `memory.` substrate-prefix) from a
   corpus :mm/Memory `:db/ident` keyword. Returns the raw namespace-name
   string suitable for `namespace-uuid` / `entity-uuid`.

   Examples:
     :memory.decisions/foo                        → \"decisions\"
     :memory.libraries.clojure/core_async         → \"libraries.clojure\"
     :memory.observations/bar                     → \"observations\"

   For non-`memory.`-prefixed idents (substrate-internal classes like
   `:dt/Class` or `:mm/Memory`), returns the namespace as-is."
  [ident]
  {:pre [(keyword? ident)]}
  (let [ns-str (namespace ident)]
    (if (and ns-str (str/starts-with? ns-str +memory-prefix+))
      (subs ns-str (count +memory-prefix+))
      ns-str)))


(defn ident-uuid
  "Convenience: derive the entity-UUID directly from a corpus `:db/ident` keyword.
   Composes `ident->namespace-name` + `entity-uuid`. Returns the v5 UUID
   suitable for assertion into `:mm/id`.

   Example:
     (ident-uuid :memory.decisions/three_tier_identifier_value_hierarchy)
     → #uuid \"<deterministic-v5-derived-from-+authority+>\"

   At β.2.3 migration time, per-family tx-fns invoke this to backfill
   `:mm/id` for each touched entity."
  [ident]
  {:pre [(keyword? ident) (namespace ident) (name ident)]}
  (entity-uuid (ident->namespace-name ident) (name ident)))


(comment
  ;; REPL demonstrations
  (str +authority+)
  ;; → some stable UUID string

  (namespace-uuid "decisions")
  ;; → namespace-UUID for the decisions namespace

  (entity-uuid "decisions" "three_tier_identifier_value_hierarchy")
  ;; → entity-UUID; SAME every time + across deployments-with-same-authority

  (ident-uuid :memory.decisions/three_tier_identifier_value_hierarchy)
  ;; → same as the entity-uuid call above

  (ident-uuid :memory.libraries.clojure/core_async)
  ;; → derived from namespace "libraries.clojure" + slug "core_async"
  )
