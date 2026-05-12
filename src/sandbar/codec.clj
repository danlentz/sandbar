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

   Reads the model-layer attribute via `dt/*`; never queries Datomic
   directly.  Returns nil if the class is not found or has no native
   codec declared."
  [class-ident]
  (when class-ident
    (try
      (let [cls (dt/class-of class-ident)]
        ;; class-of can return either the class entity or, when given a
        ;; class-ident keyword, the class itself; some Sandbar shapes
        ;; return a map with :dt/native-codec, others wrap.  Pick the
        ;; native-codec slot if present.
        (or (:dt/native-codec cls)
            (when (instance? clojure.lang.IPersistentMap class-ident)
              (:dt/native-codec class-ident))))
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
   keyword.  Throws when no format can be resolved."
  [{:keys [format class] :as opts} entity]
  (or format
      (and class       (native-codec-for-class class))
      (and entity      (native-codec-for-class (:dt/type entity)))
      (throw (ex-info "No codec format can be resolved.  Pass {:format ...} or set :dt/native-codec on the class."
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

(defn emit
  "Emit entity as a native-representation string.  Format resolution:
   explicit :format in opts > entity's class's :dt/native-codec > error."
  ([entity] (emit entity {}))
  ([entity opts]
   (let [fmt   (resolve-format opts entity)
         codec (resolve-codec fmt)]
     (proto/emit codec entity opts))))

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
