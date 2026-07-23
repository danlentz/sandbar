(ns sandbar.schedule.recurrence
  "γ.2 Step 3 — lib-recur boundary wrapper for RFC 5545 RRULE iteration.

   Per the γ.1 scheduler ADR §2.3 + the ontology-alignment ADR's R.2(a)
   layered-discipline: lib-recur (`org.dmfs:lib-recur 0.17.1`; Apache;
   ~14KB + ~100KB transitive closure via `org.dmfs/rfc5545-datetime` +
   `org.dmfs/jems2`) is consumed BEHIND this namespace.  NO `org.dmfs.*`
   types appear in public signatures.  Consumers see Clojure data maps +
   `java.time.Instant` + IANA TZ-id strings.

   ## Public surface

   - `parse-rrule`     RRULE string → sandbar-internal schedule-data map
   - `emit-rrule`      schedule-data map → RRULE string (round-trips
                       to the input string verbatim)
   - `iterate-from`    schedule-data + from-instant → LAZY seq of
                       java.time.Instant fire-times (infinite if RRULE
                       lacks UNTIL / COUNT)
   - `next-n`          schedule-data + from-instant + n → vec of next N
                       Instants (bounded even on infinite RRULE)
   - `until-instant`   schedule-data + from + end → vec of Instants in the
                       half-open window [from, end)

   ## Schedule-data shape

   Returned by `parse-rrule` and accepted by all consumer fns:

       {:rrule-string  \"FREQ=MINUTELY;INTERVAL=15\"   ;; RFC 5545 RRULE string
        :dtstart       #inst \"2026-05-27T00:00:00Z\"  ;; java.time.Instant
        :timezone-id   \"UTC\"}                         ;; IANA TZ id string

   ## Boundary discipline (per γ.1 ADR §2.3 R.2(a))

   Internal types kept off public signatures:
   - `org.dmfs.rfc5545.recur.RecurrenceRule`
   - `org.dmfs.rfc5545.recur.RecurrenceRuleIterator`
   - `org.dmfs.rfc5545.DateTime`
   - `org.dmfs.rfc5545.recurrenceset.{OfRule,Within,FastForwarded}`
   - `org.dmfs.rfc5545.InstanceIterator`

   Future iteration may swap lib-recur for an alternative (iCal4j; in-house
   RRULE iterator) — the swap is internal-only because consumers never
   import these types.

   ## See also

   - γ.1 ADR: `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
   - Ontology-alignment R.2(a): `:memory.decisions/sandbar_ontology_alignment_is_the_goal_implementation_is_replaceable_boundary_pragmatism_when_layered_or_seamless_2026_05_23`
   - γ implementation plan (Claude-Code plan-mode artifact): `~/.claude/plans/golden-squishing-flamingo.md` Step 3"
  (:require [clojure.string :as str])
  (:import [java.time Instant ZoneId]
           [java.util TimeZone]
           [org.dmfs.rfc5545 DateTime InstanceIterator]
           [org.dmfs.rfc5545.recur RecurrenceRule]
           [org.dmfs.rfc5545.recurrenceset OfRule]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type-translation helpers (private — sandbar ↔ lib-recur boundary)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^TimeZone utc-timezone
  "Canonical UTC `java.util.TimeZone` instance — fallback when
   `:timezone-id` is absent from schedule-data."
  (TimeZone/getTimeZone "UTC"))

(defn- ^TimeZone ->timezone
  "Coerce a TZ id (string, java.time.ZoneId, or java.util.TimeZone) to
   `java.util.TimeZone` (lib-recur's expected shape).  Returns UTC when
   the input is nil.  Throws ex-info on unsupported input type."
  [tz-id]
  (cond
    (nil? tz-id)                utc-timezone
    (instance? TimeZone tz-id)  tz-id
    (instance? ZoneId tz-id)    (TimeZone/getTimeZone ^ZoneId tz-id)
    (string? tz-id)             (TimeZone/getTimeZone ^String tz-id)
    :else
    (throw (ex-info "Cannot coerce to java.util.TimeZone — expect IANA TZ id string, java.time.ZoneId, or java.util.TimeZone"
                    {:reason :unsupported-timezone-input
                     :input  tz-id
                     :type   (class tz-id)}))))

(defn- ^DateTime ->datetime
  "Build an `org.dmfs.rfc5545.DateTime` from a `java.time.Instant` + a
   `java.util.TimeZone`.  Internal — the lib-recur RecurrenceSet
   constructors require DateTime."
  [^Instant instant ^TimeZone tz]
  (DateTime. tz (.toEpochMilli instant)))

(defn- ^Instant datetime->instant
  "Extract a `java.time.Instant` from an `org.dmfs.rfc5545.DateTime`.
   Internal — translates iterator output back into the consumer-facing
   value shape."
  [^DateTime dt]
  (Instant/ofEpochMilli (.getTimestamp dt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API — parse-rrule / emit-rrule
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-rrule
  "Parse `rrule-string` + bind it to `dtstart` (a `java.time.Instant`) +
   `timezone-id` (an IANA TZ string; default \"UTC\") into a
   sandbar-internal schedule-data map.

   Validates the RRULE via lib-recur's parser; rejects via ex-info with
   `:reason :invalid-rrule` when the string fails to parse.  Rejects
   non-Instant `dtstart` via ex-info with `:reason :invalid-dtstart`.

   ## Returns

       {:rrule-string <string> :dtstart <Instant> :timezone-id <string>}

   ## Arity

   `(parse-rrule rrule-string dtstart)`               ;; defaults TZ to UTC
   `(parse-rrule rrule-string dtstart timezone-id)`"
  ([rrule-string dtstart]
   (parse-rrule rrule-string dtstart "UTC"))
  ([rrule-string dtstart timezone-id]
   (when-not (string? rrule-string)
     (throw (ex-info "rrule-string must be a String"
                     {:reason :invalid-rrule
                      :value  rrule-string
                      :type   (class rrule-string)})))
   (when-not (instance? Instant dtstart)
     (throw (ex-info "dtstart must be a java.time.Instant"
                     {:reason :invalid-dtstart
                      :value  dtstart
                      :type   (class dtstart)})))
   ;; Round-trip parse to validate; surface lib-recur's exception
   ;; structurally so callers can pattern-match on :reason.
   (try
     (RecurrenceRule. ^String rrule-string)
     (catch Exception e
       (throw (ex-info (str "Invalid RRULE: " (.getMessage e))
                       {:reason       :invalid-rrule
                        :rrule-string rrule-string
                        :cause        (.getMessage e)}
                       e))))
   {:rrule-string rrule-string
    :dtstart      dtstart
    :timezone-id  (or timezone-id "UTC")}))

(defn emit-rrule
  "Emit the RRULE string from `schedule-data`.

   Currently returns the stored `:rrule-string` verbatim — preserves the
   operator-authored shape (e.g., `FREQ=WEEKLY;BYDAY=MO,WE,FR` stays in
   that form, not normalized to lib-recur's canonical re-serialization).

   Round-trip property: `(emit-rrule (parse-rrule s i)) = s`."
  [{:keys [rrule-string]}]
  rrule-string)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API — iterate-from / next-n / until-instant
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ^InstanceIterator schedule-iterator
  "Build a lib-recur InstanceIterator over the schedule's RecurrenceSet
   (anchored at `:dtstart`), fast-forwarded to `from-instant` when
   non-nil.  Returns the org.dmfs.* iterator instance for use within
   this namespace ONLY (never exposed to consumers per boundary R.2(a))."
  [{:keys [rrule-string dtstart timezone-id]} from-instant]
  (let [tz         (->timezone timezone-id)
        rule       (RecurrenceRule. ^String rrule-string)
        dtstart-dt (->datetime dtstart tz)
        rec-set    (OfRule. rule dtstart-dt)
        iter       ^InstanceIterator (.iterator rec-set)]
    (when from-instant
      (.fastForward iter (->datetime from-instant tz)))
    iter))

(defn iterate-from
  "Return a LAZY seq of `java.time.Instant` fire-times for `schedule-data`,
   starting at or after `from-instant`.  When `from-instant` is nil,
   iteration starts from the schedule's `:dtstart` (its first natural
   fire).

   The seq is INFINITE when the underlying RRULE lacks UNTIL and COUNT
   terminators — consumers must `take` a bounded prefix OR use
   `until-instant` to window.  Finite when RRULE encodes UNTIL/COUNT."
  [schedule-data from-instant]
  (let [iter (schedule-iterator schedule-data from-instant)]
    (letfn [(step []
              (lazy-seq
                (when (.hasNext iter)
                  (let [dt ^DateTime (.next iter)]
                    (cons (datetime->instant dt) (step))))))]
      (step))))

(defn next-n
  "Return a vec of the next `n` `java.time.Instant` fire-times of
   `schedule-data` at or after `from-instant`.  Bounded by `n` even when
   the underlying RRULE is infinite.  May return fewer than `n` Instants
   when the RRULE terminates (UNTIL / COUNT) before `n` fires elapse."
  [schedule-data from-instant n]
  (vec (take n (iterate-from schedule-data from-instant))))

(defn until-instant
  "Return a vec of `java.time.Instant` fire-times of `schedule-data` in
   the HALF-OPEN window [`from-instant`, `end-instant`) — start
   inclusive, end exclusive.  Empty vec when no fires occur in the
   window OR when the schedule never fires."
  [schedule-data ^Instant from-instant ^Instant end-instant]
  (vec
    (take-while (fn [^Instant i] (.isBefore i end-instant))
                (iterate-from schedule-data from-instant))))
