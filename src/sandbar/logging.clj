(ns sandbar.logging
  "Sandbar's single public observability API.

   Per memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md
   the callsite surface is SIX macros (`info` / `warn` / `error` /
   `debug` / `trace` / `profile`); vendor surface (Telemere / Tufte) is
   internal engine never exposed at callsites.

   ## Quick reference

       (require '[sandbar.logging :as sb-log])

       ;; --- structured form (preferred) ---
       (sb-log/info ::sys-init)                              ; bare marker
       (sb-log/info ::sys-init {:duration-ms 42})            ; with data
       (sb-log/info ::sys-init {:duration-ms 42} :db-only)   ; with memorial-flag
       (sb-log/info ::sys-init \"booted\" {:duration-ms 42}) ; msg + data
       (sb-log/info ::sys-init \"booted\" {:duration-ms 42} :first-class)

       ;; --- human-string shorthand (ADR §D.2) ---
       (sb-log/info \"Datomic transactor reconnected\")
       (sb-log/info \"User logged in\" {:user-id 42})

       ;; --- error: typed error kind carries a Throwable ---
       (sb-log/error ::tx-failed ex)                         ; throwable
       (sb-log/error ::tx-failed ex {:tx-id 17592186})       ; + data
       (sb-log/error ::tx-failed ex {:tx-id 17}    :db-only) ; + memorial

       ;; --- profile: Tufte span (no memorial-flag) ---
       (sb-log/profile :search-hot-path
         (do-the-search ...))

   ## Memorial flag

   The optional final positional keyword on `info` / `warn` / `error` /
   `debug` / `trace` flags the signal for the Stage D memorial-projection
   handler:

   - `:db-only`     — durable `:mm/Log` (or subtype) entity in Datomic
   - `:first-class` — durable + FS-projected to `memory/logs/<>.md`
   - `:inline`      — embedded in host memorial frontmatter

   Absent flag = transient (handlers fire; no DB persistence).

   ## Human-string shorthand + synthetic event-id

   When the first arg is a string (no `::event-id`), the macro auto-
   derives a synthetic stable id from `*ns*` + line + a hash of the
   message.  Anonymous-at-callsite events remain RANKABLE + FACETABLE
   post-hoc — the substrate compensating for callsite ergonomics per
   ADR §D.2 compensation #1.

   ## Initialization

   Telemere handler setup happens via `sandbar.logging.init/start!`,
   called by `sandbar.core/start` BEFORE any other startup work."
  (:require [taoensso.telemere :as tel]
            [taoensso.tufte    :as tufte]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Expansion helpers — private; live at compile-time only
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +memorial-flags+ #{:db-only :first-class :inline})

(defn- memorial-flag-literal?
  "True when `x` is one of the known memorial-flag keyword literals.
   Detected at macro-expansion time so the signal map can carry
   `:data {:memorial <flag>}` without runtime dispatch."
  [x]
  (boolean (and (keyword? x) (contains? +memorial-flags+ x))))

(defn- synthetic-event-id
  "Auto-derive a synthetic event-id keyword from calling ns + line +
   message-hash.  Per ADR §D.2 compensation #1 — anonymous callsites
   stay RANKABLE + FACETABLE post-hoc.  Pure expansion-time fn."
  [form msg]
  (let [ns       (ns-name *ns*)
        line     (or (:line (meta form)) 0)
        msg-hash (format "%08x" (hash msg))]
    (keyword (str ns) (str "line-" line "-" msg-hash))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; info / warn / debug / trace — level-keyed signal macros
;;
;; Four arities per macro:
;;   1-arg  (info ::id)                   — bare structured marker
;;          (info \"msg\")                  — human shorthand; synthetic id
;;   2-arg  (info ::id data-map)          — structured + data
;;          (info ::id \"msg\")             — structured + msg
;;          (info ::id :memorial-flag)    — flag with no other payload
;;          (info \"msg\" data-map)         — human shorthand + data
;;   3-arg  (info ::id data :memorial)    — data with flag
;;          (info ::id \"msg\" data)        — msg + data
;;   4-arg  (info ::id \"msg\" data :flag) — full form
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro info
  ([arg]
   (cond
     (string? arg)
     `(tel/log! {:level :info :id ~(synthetic-event-id &form arg) :msg ~arg})
     :else
     `(tel/log! {:level :info :id ~arg})))
  ([x y]
   (cond
     (and (string? x) (map? y))
     `(tel/log! {:level :info :id ~(synthetic-event-id &form x)
                 :msg ~x :data ~y})
     (memorial-flag-literal? y)
     `(tel/log! {:level :info :id ~x :data {:memorial ~y}})
     (string? y)
     `(tel/log! {:level :info :id ~x :msg ~y})
     :else
     `(tel/log! {:level :info :id ~x :data ~y})))
  ([x y z]
   (cond
     (memorial-flag-literal? z)
     `(tel/log! {:level :info :id ~x :data (assoc ~y :memorial ~z)})
     :else
     `(tel/log! {:level :info :id ~x :msg ~y :data ~z})))
  ([x y z w]
   `(tel/log! {:level :info :id ~x :msg ~y :data (assoc ~z :memorial ~w)})))

(defmacro warn
  ([arg]
   (cond
     (string? arg)
     `(tel/log! {:level :warn :id ~(synthetic-event-id &form arg) :msg ~arg})
     :else
     `(tel/log! {:level :warn :id ~arg})))
  ([x y]
   (cond
     (and (string? x) (map? y))
     `(tel/log! {:level :warn :id ~(synthetic-event-id &form x)
                 :msg ~x :data ~y})
     (memorial-flag-literal? y)
     `(tel/log! {:level :warn :id ~x :data {:memorial ~y}})
     (string? y)
     `(tel/log! {:level :warn :id ~x :msg ~y})
     :else
     `(tel/log! {:level :warn :id ~x :data ~y})))
  ([x y z]
   (cond
     (memorial-flag-literal? z)
     `(tel/log! {:level :warn :id ~x :data (assoc ~y :memorial ~z)})
     :else
     `(tel/log! {:level :warn :id ~x :msg ~y :data ~z})))
  ([x y z w]
   `(tel/log! {:level :warn :id ~x :msg ~y :data (assoc ~z :memorial ~w)})))

(defmacro debug
  ([arg]
   (cond
     (string? arg)
     `(tel/log! {:level :debug :id ~(synthetic-event-id &form arg) :msg ~arg})
     :else
     `(tel/log! {:level :debug :id ~arg})))
  ([x y]
   (cond
     (and (string? x) (map? y))
     `(tel/log! {:level :debug :id ~(synthetic-event-id &form x)
                 :msg ~x :data ~y})
     (memorial-flag-literal? y)
     `(tel/log! {:level :debug :id ~x :data {:memorial ~y}})
     (string? y)
     `(tel/log! {:level :debug :id ~x :msg ~y})
     :else
     `(tel/log! {:level :debug :id ~x :data ~y})))
  ([x y z]
   (cond
     (memorial-flag-literal? z)
     `(tel/log! {:level :debug :id ~x :data (assoc ~y :memorial ~z)})
     :else
     `(tel/log! {:level :debug :id ~x :msg ~y :data ~z})))
  ([x y z w]
   `(tel/log! {:level :debug :id ~x :msg ~y :data (assoc ~z :memorial ~w)})))

(defmacro trace
  ([arg]
   (cond
     (string? arg)
     `(tel/log! {:level :trace :id ~(synthetic-event-id &form arg) :msg ~arg})
     :else
     `(tel/log! {:level :trace :id ~arg})))
  ([x y]
   (cond
     (and (string? x) (map? y))
     `(tel/log! {:level :trace :id ~(synthetic-event-id &form x)
                 :msg ~x :data ~y})
     (memorial-flag-literal? y)
     `(tel/log! {:level :trace :id ~x :data {:memorial ~y}})
     (string? y)
     `(tel/log! {:level :trace :id ~x :msg ~y})
     :else
     `(tel/log! {:level :trace :id ~x :data ~y})))
  ([x y z]
   (cond
     (memorial-flag-literal? z)
     `(tel/log! {:level :trace :id ~x :data (assoc ~y :memorial ~z)})
     :else
     `(tel/log! {:level :trace :id ~x :msg ~y :data ~z})))
  ([x y z w]
   `(tel/log! {:level :trace :id ~x :msg ~y :data (assoc ~z :memorial ~w)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error — typed error signal with a Throwable in the 2-arg slot
;;
;; Reserves the 2-arg slot for the Throwable (when not a literal string
;; or memorial-flag).  Routes to `tel/error!` for typed `:error` kind
;; so error-aware handlers can distinguish from `:log` kind.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro error
  ([arg]
   (cond
     (string? arg)
     `(tel/log! {:level :error :id ~(synthetic-event-id &form arg) :msg ~arg})
     :else
     `(tel/log! {:level :error :id ~arg})))
  ([x y]
   (cond
     (and (string? x) (map? y))
     `(tel/log! {:level :error :id ~(synthetic-event-id &form x)
                 :msg ~x :data ~y})
     (memorial-flag-literal? y)
     `(tel/log! {:level :error :id ~x :data {:memorial ~y}})
     (string? y)
     `(tel/log! {:level :error :id ~x :msg ~y})
     :else
     `(tel/error! {:id ~x :error ~y})))
  ([x y z]
   (cond
     (memorial-flag-literal? z)
     `(tel/error! {:id ~x :error ~y :data {:memorial ~z}})
     :else
     `(tel/error! {:id ~x :error ~y :data ~z})))
  ([x y z w]
   `(tel/error! {:id ~x :error ~y :data (assoc ~z :memorial ~w)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Profile — Tufte span (no memorial-flag per ADR §D.1)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro profile
  "Wrap `body` in a Tufte profiling span keyed by `span-name`.
   Returns the body's value."
  [span-name & body]
  `(tufte/p ~span-name (do ~@body)))
