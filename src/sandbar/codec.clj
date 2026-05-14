(ns sandbar.codec
  "Codec mediator — consumer-facing API for the codec layer per
   decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md §2.3.

   Wraps the codec registry, format detection (explicit hint vs MIME
   vs per-class `:dt/native-codec`), and dispatch.

   ## Consumer use

     ;; Explicit format
     (sandbar.codec/parse \"...markdown...\"
                          {:format :markdown :class :mm/Memory})

     ;; MIME-driven dispatch (e.g., from REST Content-Type)
     (sandbar.codec/parse-mime \"text/markdown\" content)

     ;; Class-default codec (mm/Memory → :markdown via :dt/native-codec)
     (sandbar.codec/emit entity)

     ;; Explicit format on emit
     (sandbar.codec/emit entity {:format :markdown :pretty? true})

     ;; Codec registry management
     (sandbar.codec/register! :markdown my-markdown-codec)
     (sandbar.codec/unregister! :markdown)
     (sandbar.codec/list-codecs)
     (sandbar.codec/codec-for :markdown)

   ## Layering discipline

   Codecs operate at the MODEL layer (`:dt/Class`, `:dt/slots`, class
   hierarchy); never at the Datomic-schema layer.  Per
   interaction/export_format_must_be_neutral_and_database_agnostic_2026_05_12.md
   the wire format MUST be portable across model-equivalent backends.

   The mediator targets `sandbar.db.datatype/*` for per-class default-
   codec lookups — never raw `datomic.api`."
  (:require [clojure.tools.logging :as log]
            [sandbar.codec.protocol :as proto]
            [sandbar.db.datatype    :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codec registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Registry shape:
;;   {format-keyword → codec-instance}
;;
;; format-keyword is a stable identifier (e.g., :markdown / :ttl /
;; :edn-ttl-hybrid / :json).  Codecs may declare multiple MIME types
;; they handle; mediator's parse-mime dispatch uses a derived
;; mime → format lookup.

(defonce ^:private +codecs+ (atom {}))

(defn register!
  "Register a codec under a format keyword.  Idempotent (re-registering
   replaces the existing codec).  Returns the format keyword."
  [format codec]
  (when-not (keyword? format)
    (throw (ex-info "format must be a keyword" {:format format})))
  (when-not (satisfies? proto/Codec codec)
    (throw (ex-info "codec must satisfy sandbar.codec.protocol/Codec"
                    {:format format :codec codec})))
  (swap! +codecs+ assoc format codec)
  (log/info :SANDBAR/CODEC-REGISTER
            {:format format :mime-types (proto/mime-types codec)})
  format)

(defn unregister!
  "Remove a codec from the registry.  Idempotent."
  [format]
  (swap! +codecs+ dissoc format)
  nil)

(defn list-codecs
  "Return a vector of `{:format ... :mime-types [...]}` for every
   registered codec, sorted by format keyword."
  []
  (->> @+codecs+
       (map (fn [[fmt codec]]
              {:format     fmt
               :mime-types (proto/mime-types codec)}))
       (sort-by :format)
       vec))

(defn codec-for
  "Look up the codec registered for `format`.  Returns nil if absent."
  [format]
  (get @+codecs+ format))

(defn codec-for-mime
  "Look up the codec whose mime-types include `mime-type`.  Returns
   `[format codec]` or nil.  When multiple codecs claim the same MIME
   type, the first one registered wins (registration ordering is the
   tie-breaker)."
  [mime-type]
  (some (fn [[fmt codec]]
          (when (some #(= mime-type %) (proto/mime-types codec))
            [fmt codec]))
        @+codecs+))

(defn clear-all!
  "Test-only: drop all codec registrations.  Production callers should
   not invoke this."
  []
  (reset! +codecs+ {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-class default codec — :dt/native-codec mechanism
;;
;; Per ADR §2.5: classes declare their preferred wire format via the
;; :dt/native-codec attribute on :dt/Class entities.  Mediator reads
;; this when no explicit :format hint is provided + dispatches.

(defn native-codec-for-class
  "Return the `:dt/native-codec` format keyword declared on `class-ident`
   (a :dt/Class entity's ident), or nil if no default is declared.

   Delegates to `sandbar.db.datatype/native-codec-of-class` — the
   purpose-built helper that reads the codec directly off the class
   entity (NOT via `:dt/type` traversal).  Resolves codex MUST-FIX #1
   per
   decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md.

   The prior implementation called `(dt/class-of class-ident)` which
   resolved to `:dt/Class` (the meta-class), then read `:dt/native-codec`
   off it — invariably nil since the meta-class has no native codec.
   Stage A added the new `dt/native-codec-of-class` primitive precisely
   to express this lookup correctly."
  [class-ident]
  (when class-ident
    (try
      (dt/native-codec-of-class class-ident)
      (catch Exception _ nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Format resolution
;;
;; Two-step resolution per ADR §2.5:
;;   1. Explicit :format in opts wins
;;   2. Else :dt/native-codec on the entity's class
;;   3. Else error (no implicit default; consumers must choose)

(defn- resolve-format
  "Pick the codec format for an entity given opts.  Returns the format
   keyword.  Throws when no format can be resolved.

   Resolution order:
     1. Explicit `:format` in opts
     2. `:mime-type` in opts → look up via codec-for-mime registry
     3. `:class` in opts → read class's `:dt/native-codec`
     4. Entity's `:dt/type` → read class's `:dt/native-codec`
     5. Throw

   `:mime-type` resolution per codex SHOULD-FIX #3 — the Layer-4
   `doc/api/codec-protocol.md` documented this path; this implementation
   now matches the docs."
  [{:keys [format mime-type class] :as opts} entity]
  (or format
      (when mime-type
        (when-let [[fmt _codec] (codec-for-mime mime-type)]
          fmt))
      (and class       (native-codec-for-class class))
      (and entity      (native-codec-for-class (:dt/type entity)))
      (throw (ex-info "No codec format can be resolved.  Pass {:format ...}, {:mime-type ...}, or set :dt/native-codec on the class."
                      {:opts opts :entity-class (:dt/type entity)}))))

(defn- resolve-codec
  "Look up the codec instance for resolved format.  Throws on miss."
  [format]
  (or (codec-for format)
      (throw (ex-info (str "No codec registered for format " format)
                      {:format         format
                       :known-formats  (sort (keys @+codecs+))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Consumer-facing dispatch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse
  "Parse input using the codec selected by `opts`.

   `opts` keys:
     :format — codec format keyword (e.g., :markdown).  REQUIRED unless
               :class is provided AND that class has :dt/native-codec
               declared.
     :class  — class-ident hint passed through to the codec; used for
               default-codec resolution when :format is absent.
     ...     — remaining keys forwarded to the codec.

   Returns: entity-spec map OR coll of entity-spec maps."
  ([input] (parse input {}))
  ([input opts]
   (let [fmt   (resolve-format opts nil)
         codec (resolve-codec fmt)]
     (proto/parse codec input opts))))

(defn parse-mime
  "Parse input using the codec registered for `mime-type`.  Throws if
   no codec claims that MIME type."
  ([mime-type input] (parse-mime mime-type input {}))
  ([mime-type input opts]
   (if-let [[fmt codec] (codec-for-mime mime-type)]
     (proto/parse codec input (assoc opts :format fmt))
     (throw (ex-info (str "No codec registered for MIME type " mime-type)
                     {:mime-type mime-type
                      :known     (mapcat #(proto/mime-types %) (vals @+codecs+))})))))

(defn parse-for-class
  "Parse input using the codec resolved from the class's `:dt/native-codec`
   declaration.  Convenience for the class-default routing path:
   equivalent to `(parse input (assoc opts :class class-ident))`.

   Per codex SHOULD-FIX #3 — Layer-4 `doc/api/codec-protocol.md` documents
   this convenience; the implementation now matches the docs."
  ([class-ident input] (parse-for-class class-ident input {}))
  ([class-ident input opts]
   (parse input (assoc opts :class class-ident))))

(defn emit
  "Emit entity as a native-representation string.  Format resolution:
   explicit :format in opts > :mime-type in opts > entity's class's
   :dt/native-codec > error."
  ([entity] (emit entity {}))
  ([entity opts]
   (let [fmt   (resolve-format opts entity)
         codec (resolve-codec fmt)]
     (proto/emit codec entity opts))))

(defn emit-mime
  "Emit entity using the codec registered for `mime-type`.  Throws if
   no codec claims that MIME type.

   Per codex SHOULD-FIX #3 — Layer-4 `doc/api/codec-protocol.md` documents
   this convenience; the implementation now matches the docs."
  ([mime-type entity] (emit-mime mime-type entity {}))
  ([mime-type entity opts]
   (if-let [[fmt codec] (codec-for-mime mime-type)]
     (proto/emit codec entity (assoc opts :format fmt))
     (throw (ex-info (str "No codec registered for MIME type " mime-type)
                     {:mime-type mime-type
                      :known     (mapcat #(proto/mime-types %) (vals @+codecs+))})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip-test convenience
;;
;; Per ADR §2.2: each codec exposes round-trip-test as a protocol method.
;; The mediator provides a wrapper that resolves the codec by format.

(defn round-trip-test
  "Invoke `round-trip-test` on the codec for `format` with `entity`.
   Useful for golden-fixture tests + diagnostics."
  [format entity]
  (let [codec (resolve-codec format)]
    (proto/round-trip-test codec entity)))
