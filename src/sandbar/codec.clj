(ns sandbar.codec
  "Consumer API for codec registration, selection, parsing and emission.
   Select by explicit :format, MIME type or class :dt/native-codec metadata.
   Concrete codecs own their grammar and fidelity contracts. The mediator
   does not automatically enforce supports? and does not imply a universal
   default or a portable database backup. See doc/concepts/codec-layer.md."
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
  "Return the :dt/native-codec declared on the class entity, or nil.
   Read the class itself through dt/native-codec-of-class. Looking up the
   class of that class would instead inspect :dt/Class and lose the
   requested class's representation choice."
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
