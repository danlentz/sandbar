(ns sandbar.identifier
  "Deterministic UUID derivation for durable entity identity.
   Derive an authority UUID from a tag-URI seed, a namespace UUID from that
   authority and namespace name, and an entity UUID from the namespace and
   entity name. Identical inputs produce identical UUIDs.

   The installed default authority is shared by current creation paths.
   Distinct project identity roots require explicit provisioning and proof;
   this helper alone does not establish cross-project collision isolation.
   Custom URN names and alternative transcription encodings are not supplied
   here. See doc/concepts/markdown-as-canonical.md for identity distinctions."
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
  "Derive the durable UUID for a keyword ident by composing
   ident->namespace-name and entity-uuid under the configured authority.
   Creation and backfill can use the same derivation for compatible :mm/id
   values. Existing durable identity should be preserved during maintenance."
  [ident]
  {:pre [(keyword? ident) (namespace ident) (name ident)]}
  (entity-uuid (ident->namespace-name ident) (name ident)))


(defn rel-path-uuid
  "Derive the entity-UUID from a corpus rel-path (e.g.
   \"sessions/2026-05-26T0400_pickup.md\"): the namespace-name is the directory
   path with `/` as `.` (\"libraries/clojure\" → \"libraries.clojure\"), the slug
   the basename without `.md`.  The SAME value as `ident-uuid` for every ident
   whose name is its slug; for a digit-leading slug the codec prefixes the
   ident (`session-…`) and `ident-uuid` would drift, while the file's declared
   `id:` derives from the slug — so the create path mints from here (D7 2c,
   2026-09-20; Astra's answer: a readability prefix must not create a new
   identity; the authority and namespace derivation are unchanged)."
  [rel-path]
  {:pre [(string? rel-path) (str/includes? rel-path "/")]}
  (let [path (str/replace rel-path #"\.md$" "")
        i    (str/last-index-of path "/")
        ns   (str/replace (subs path 0 i) "/" ".")
        slug (subs path (inc i))]
    (entity-uuid ns slug)))

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
