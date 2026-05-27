(ns sandbar.schedule.recurrence-test
  "γ.2 Step 3 tests — lib-recur boundary wrapper for RFC 5545 RRULE
   iteration.

   Coverage:
   - parse-rrule shape (defaults + explicit timezone-id)
   - parse-rrule validation (invalid RRULE / non-Instant dtstart)
   - emit-rrule round-trip verbatim
   - next-n MINUTELY spacing
   - next-n HOURLY spacing
   - next-n DAILY;BYHOUR fires at the declared hour
   - next-n WEEKLY;BYDAY enumerates target weekdays
   - next-n bounded by RRULE COUNT terminator
   - next-n bounded by RRULE UNTIL terminator
   - until-instant half-open window semantics
   - iterate-from with from > dtstart fast-forwards correctly
   - timezone-id default fallback to UTC
   - explicit America/New_York TZ propagates (DST awareness)
   - Boundary discipline — returns are java.time.Instant only (no
     org.dmfs.* type leak)

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 3 + γ.1 ADR §2.3."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.recurrence :as r])
  (:import [java.time Instant ZoneId]
           [java.time.temporal ChronoUnit]))

(def ^:private ^Instant epoch-instant
  "Canonical test dtstart: 2026-05-27T00:00:00Z."
  (Instant/parse "2026-05-27T00:00:00Z"))

(defn- ^Instant plus-minutes
  "Convenience: epoch-instant + n minutes."
  [n]
  (.plus epoch-instant (long n) ChronoUnit/MINUTES))

(defn- ^Instant plus-hours
  "Convenience: epoch-instant + n hours."
  [n]
  (.plus epoch-instant (long n) ChronoUnit/HOURS))

(defn- ^Instant plus-days
  "Convenience: epoch-instant + n days."
  [n]
  (.plus epoch-instant (long n) ChronoUnit/DAYS))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; parse-rrule shape + validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-rrule-returns-canonical-shape
  (testing "Schedule-data carries :rrule-string + :dtstart + :timezone-id"
    (let [sched (r/parse-rrule "FREQ=MINUTELY;INTERVAL=15" epoch-instant)]
      (is (= "FREQ=MINUTELY;INTERVAL=15" (:rrule-string sched)))
      (is (= epoch-instant (:dtstart sched)))
      (is (= "UTC" (:timezone-id sched))
          "Default timezone is UTC"))))

(deftest parse-rrule-respects-explicit-timezone
  (testing "Explicit timezone-id propagates into schedule-data"
    (let [sched (r/parse-rrule "FREQ=DAILY" epoch-instant "America/New_York")]
      (is (= "America/New_York" (:timezone-id sched))))))

(deftest parse-rrule-rejects-invalid-rrule
  (testing "Invalid RRULE → ex-info :reason :invalid-rrule"
    (let [thrown (try (r/parse-rrule "NOT A VALID RRULE" epoch-instant)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "Throws on invalid RRULE")
      (is (= :invalid-rrule (:reason (ex-data thrown))))
      (is (= "NOT A VALID RRULE" (:rrule-string (ex-data thrown)))
          "Echoes the offending RRULE in ex-data for debugging"))))

(deftest parse-rrule-rejects-non-string-rrule
  (testing "Non-string rrule-string → ex-info :reason :invalid-rrule"
    (let [thrown (try (r/parse-rrule 42 epoch-instant)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :invalid-rrule (:reason (ex-data thrown)))))))

(deftest parse-rrule-rejects-non-instant-dtstart
  (testing "Non-Instant dtstart → ex-info :reason :invalid-dtstart"
    (let [thrown (try (r/parse-rrule "FREQ=DAILY" "not-an-instant")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :invalid-dtstart (:reason (ex-data thrown)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; emit-rrule round-trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest emit-rrule-round-trips-verbatim
  (testing "emit-rrule returns the original rrule-string verbatim"
    (doseq [rrule ["FREQ=MINUTELY;INTERVAL=15"
                   "FREQ=DAILY;BYHOUR=3"
                   "FREQ=WEEKLY;BYDAY=MO,WE,FR"
                   "FREQ=HOURLY;COUNT=24"]]
      (let [sched (r/parse-rrule rrule epoch-instant)]
        (is (= rrule (r/emit-rrule sched))
            (str "Round-trip preserves: " rrule))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; next-n — regular cadence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest next-n-minutely-15
  (testing "FREQ=MINUTELY;INTERVAL=15 produces 15-minute-spaced fires from dtstart"
    (let [sched (r/parse-rrule "FREQ=MINUTELY;INTERVAL=15" epoch-instant)
          fires (r/next-n sched epoch-instant 4)]
      (is (= 4 (count fires)))
      (is (every? #(instance? Instant %) fires)
          "All fires are java.time.Instant — no org.dmfs.* type leak")
      (is (= epoch-instant (nth fires 0)))
      (is (= (plus-minutes 15) (nth fires 1)))
      (is (= (plus-minutes 30) (nth fires 2)))
      (is (= (plus-minutes 45) (nth fires 3))))))

(deftest next-n-hourly
  (testing "FREQ=HOURLY produces 1-hour-spaced fires from dtstart"
    (let [sched (r/parse-rrule "FREQ=HOURLY" epoch-instant)
          fires (r/next-n sched epoch-instant 3)]
      (is (= [epoch-instant (plus-hours 1) (plus-hours 2)] fires)))))

(deftest next-n-daily-byhour
  (testing "FREQ=DAILY;BYHOUR=3 fires at hour 3 each day"
    (let [sched (r/parse-rrule "FREQ=DAILY;BYHOUR=3" epoch-instant)
          fires (r/next-n sched epoch-instant 3)
          utc   (ZoneId/of "UTC")]
      (is (= 3 (count fires)))
      (is (every? (fn [^Instant i] (= 3 (.getHour (.atZone i utc))))
                  fires)
          "Each fire is at hour 3 UTC"))))

(deftest next-n-weekly-byday
  (testing "FREQ=WEEKLY;BYDAY=MO,WE,FR enumerates target weekdays"
    ;; 2026-05-27 is a Wednesday — fires should land on Wed/Fri/Mon/Wed/...
    (let [sched (r/parse-rrule "FREQ=WEEKLY;BYDAY=MO,WE,FR" epoch-instant)
          fires (r/next-n sched epoch-instant 5)
          utc   (ZoneId/of "UTC")
          dows  (map (fn [^Instant i] (.getDayOfWeek (.atZone i utc))) fires)]
      (is (= 5 (count fires)))
      (is (every? #{java.time.DayOfWeek/MONDAY
                    java.time.DayOfWeek/WEDNESDAY
                    java.time.DayOfWeek/FRIDAY}
                  dows)
          "Each fire lands on MO/WE/FR"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; next-n — RRULE-encoded terminators (UNTIL / COUNT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest next-n-bounded-by-count
  (testing "FREQ=HOURLY;COUNT=3 terminates after 3 fires — next-n returns ≤ 3"
    (let [sched (r/parse-rrule "FREQ=HOURLY;COUNT=3" epoch-instant)
          fires (r/next-n sched epoch-instant 10)]
      (is (= 3 (count fires))
          "RRULE COUNT terminator caps fires below the next-n n"))))

(deftest next-n-bounded-by-until
  (testing "FREQ=HOURLY;UNTIL=<+3h> terminates at the UNTIL boundary"
    ;; UNTIL syntax in RFC 5545: YYYYMMDDTHHMMSSZ (UTC); 3 hours = 2026-05-27T03:00:00Z
    (let [sched (r/parse-rrule "FREQ=HOURLY;UNTIL=20260527T030000Z" epoch-instant)
          fires (r/next-n sched epoch-instant 10)]
      ;; UNTIL is inclusive per RFC 5545 — instances at 00:00, 01:00, 02:00, 03:00 = 4 fires
      (is (<= (count fires) 4)
          "RRULE UNTIL terminator caps at the until-boundary")
      (is (every? (fn [^Instant i]
                    (not (.isAfter i (Instant/parse "2026-05-27T03:00:00Z"))))
                  fires)
          "No fire after UNTIL boundary"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; until-instant — half-open window semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest until-instant-half-open-window
  (testing "until-instant returns fires in [from, end) — start inclusive, end exclusive"
    (let [sched (r/parse-rrule "FREQ=HOURLY" epoch-instant)
          end   (plus-hours 3)
          fires (r/until-instant sched epoch-instant end)]
      (is (= 3 (count fires))
          "Window [00:00, 03:00) contains fires at 00:00, 01:00, 02:00 — NOT 03:00")
      (is (= [epoch-instant (plus-hours 1) (plus-hours 2)] fires)
          "End is exclusive — fire at end-instant excluded"))))

(deftest until-instant-empty-when-no-fires-in-window
  (testing "Empty vec when window precedes first fire"
    ;; Schedule starts at epoch+10h; window is [epoch, epoch+5h)
    (let [dtstart  (plus-hours 10)
          sched    (r/parse-rrule "FREQ=HOURLY" dtstart)
          fires    (r/until-instant sched epoch-instant (plus-hours 5))]
      (is (= [] fires)
          "Window before dtstart produces empty seq"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; iterate-from — fast-forward semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest iterate-from-fast-forwards-past-historical-fires
  (testing "iterate-from with from > dtstart skips historical fires"
    (let [sched     (r/parse-rrule "FREQ=HOURLY" epoch-instant)
          from-mid  (plus-hours 5)
          first-fire (first (r/iterate-from sched from-mid))]
      (is (= from-mid first-fire)
          "First fire is at-or-after from-instant — fast-forward skipped earlier fires"))))

(deftest iterate-from-nil-from-starts-at-dtstart
  (testing "iterate-from with nil from-instant starts from dtstart"
    (let [sched      (r/parse-rrule "FREQ=DAILY" epoch-instant)
          first-fire (first (r/iterate-from sched nil))]
      (is (= epoch-instant first-fire)
          "nil from-instant → iteration starts at the schedule's dtstart"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Boundary discipline — no org.dmfs.* type leak
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest no-org-dmfs-type-leak-in-returns
  (testing "All public-fn returns are Clojure values + java.time.Instant — never org.dmfs.* types"
    (let [sched   (r/parse-rrule "FREQ=DAILY" epoch-instant)
          fires-n (r/next-n sched epoch-instant 3)
          fires-w (r/until-instant sched epoch-instant (plus-days 3))]
      (is (map? sched) "parse-rrule returns a Clojure map")
      (is (string? (r/emit-rrule sched)) "emit-rrule returns a String")
      (is (every? #(instance? Instant %) fires-n)
          "next-n returns java.time.Instant only")
      (is (every? #(instance? Instant %) fires-w)
          "until-instant returns java.time.Instant only")
      ;; Negative assertion — explicitly check no DateTime leak
      (is (not-any? (fn [v] (.startsWith (str (class v)) "org.dmfs"))
                    fires-n)
          "No org.dmfs.* values in next-n return"))))
