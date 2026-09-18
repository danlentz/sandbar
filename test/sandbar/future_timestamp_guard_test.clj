(ns sandbar.future-timestamp-guard-test
  "Future-timestamp guard contract (2026-09-18, reliability sprint item 2.4).

  `:mm.memory/created` and `:mm.memory/last-touched` order the record — the
  session-open banner, the recall re-ranking and every recency view sort on
  them — and hand-stamped values were observed running one to three hours
  ahead of UTC (observations/manually_stamped_timestamps_run_ahead_of_utc_-
  stamp_from_clock_read_before_write_mechanize_future_timestamp_guard_-
  2026_09_18).  The substrate now refuses either slot more than the allowed
  skew (sixty seconds by default) AHEAD of server time on create and on
  update, and defaults both to server time when absent on create.

  Pins:
   (a) create: a future `created` / `last-touched` is refused with the
       validation-failure envelope naming the slot, the value, the server
       time and the allowed skew; nothing is transacted; the floor ignores
       `:validate? false`;
   (b) create: a value within the skew and a value in the past are accepted;
       absent values default to server time (both slots, one clock read);
   (c) update: a future value is refused and the stored value is untouched;
       a value within the skew and an unrelated update pass;
   (d) other instant slots (`:mm.memory/last-reviewed`) are not guarded;
   (e) the skew is configurable (`dt/*future-timestamp-skew-ms*`, config key
       `:future-timestamp-skew-seconds`, sixty-second default);
   (f) the codec path: frontmatter with a future `created:` is refused via
       `dt/make :format :markdown`; a clock-read stamp is accepted; absent
       stamps are defaulted;
   (g) the MCP boundary: `sandbar.entity.create` (slots AND format markdown)
       and `sandbar.entity.update` project the refusal as an isError envelope
       naming the slot; `sandbar.entity.validate` reports the same verdict
       read-only."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.search :as search]
            [sandbar.store :as store]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "future-timestamp-guard" :auth? false})
  (fn [t]
    ;; The markdown codec is registered at boot in production; register it
    ;; here so the :format :markdown create path resolves.  Idempotent.
    (codec-md/register!)
    (try (t)
         ;; Drain the async BM25F refreshes the MCP creates enqueue, so no
         ;; work escapes this namespace into the next fixture's database.
         (finally (search/await-bm25f-quiescent!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private second-ms 1000)
(def ^:private minute-ms (* 60 second-ms))
(def ^:private hour-ms   (* 60 minute-ms))

(defn- from-now
  "A java.util.Date `offset-ms` from the current server clock."
  ^java.util.Date [offset-ms]
  (java.util.Date. (+ (System/currentTimeMillis) (long offset-ms))))

(defn- iso [^java.util.Date d] (str (.toInstant d)))

(defn- ms [^java.util.Date d] (.getTime d))

(defn- props [nm]
  {:mm.memory/rel-path    (str "test/" nm ".md")
   :mm.memory/name        nm
   :mm.memory/memory-type :decision
   :mm.memory/body-raw    "body"})

(defn- thrown
  "Run `f`; return {:message :data} of the ExceptionInfo it throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         {:message (ex-message e) :data (ex-data e)})))

(defn- eid-by-name [nm]
  (d/q '[:find ?e . :in $ ?n :where [?e :mm.memory/name ?n]] (db/db) nm))

(defn- future-errors [data]
  (filterv #(= :future-timestamp (:type %)) (:errors data)))

(defn- md-source
  "A minimal corpus-shaped markdown document; `fm-lines` are extra
   frontmatter lines (e.g. \"created: 2099-01-01\")."
  [& fm-lines]
  (str "---\n"
       "name: codec future\n"
       "type: decision\n"
       (apply str (map #(str % "\n") fm-lines))
       "---\n"
       "# Codec future\n\nBody.\n"))

(defn- call [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- user-error? [response] (true? (-> response :result :isError)))
(defn- response-text [response] (-> response :result :content first :text))
(defn- success? [response]
  (and (some? (-> response :result :content))
       (not (user-error? response))
       (nil? (:error response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) create refuses a future value
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-rejects-future-created
  (let [value  (from-now hour-ms)
        before (from-now 0)
        result (thrown #(dt/make :mm/Memory
                                 (assoc (props "future-created") :mm.memory/created value)))
        after  (from-now 0)
        errs   (future-errors (:data result))]
    (is (some? result) "a created one hour ahead is refused")
    (is (= "Validation failed" (:message result)) "same message as a schema failure")
    (is (= :future-timestamp (-> result :data :sandbar/error)))
    (is (= :create (-> result :data :phase)))
    (is (= :mm/Memory (-> result :data :class)))
    (is (string? (-> result :data :hint)))
    (is (= 1 (count errs)))
    (let [err (first errs)]
      (is (= :mm.memory/created (:slot err)) "names the slot")
      (is (= value (:value err)) "carries the offending value")
      (is (instance? java.util.Date (:server-time err)) "carries the server time")
      (is (<= (ms before) (ms (:server-time err)) (ms after))
          "the server time is the clock at the write")
      (is (= 60000 (:allowed-skew-ms err)) "sixty-second default skew")
      (is (< 60000 (:ahead-ms err) (+ hour-ms 60000)))
      (is (re-find #"ahead of server time" (:message err))))
    (is (nil? (eid-by-name "future-created")) "nothing was transacted")))

(deftest create-rejects-future-last-touched-and-reports-every-guarded-slot
  (testing "last-touched alone"
    (let [value  (from-now (* 2 minute-ms))
          result (thrown #(dt/make :mm/Memory
                                   (assoc (props "future-touched") :mm.memory/last-touched value)))]
      (is (= [:mm.memory/last-touched] (mapv :slot (future-errors (:data result)))))
      (is (nil? (eid-by-name "future-touched")))))
  (testing "both slots ahead → both reported, in slot order"
    (let [result (thrown #(dt/make :mm/Memory
                                   (assoc (props "future-both")
                                          :mm.memory/created      (from-now hour-ms)
                                          :mm.memory/last-touched (from-now hour-ms))))]
      (is (= [:mm.memory/created :mm.memory/last-touched]
             (mapv :slot (future-errors (:data result))))))))

(deftest create-guard-is-a-floor-independent-of-validate
  (let [result (thrown #(dt/make :mm/Memory
                                 (assoc (props "unvalidated-future") :mm.memory/created (from-now hour-ms))
                                 {:validate? false}))]
    (is (some? result) ":validate? false skips schema checks, not the clock")
    (is (= :future-timestamp (-> result :data :sandbar/error)))
    (is (nil? (eid-by-name "unvalidated-future")))))

(deftest create-via-store-path-is-guarded
  ;; store/create-memory! is the canonical create path the MCP verb uses;
  ;; it bottoms out in dt/make, so the floor applies unchanged.
  (let [result (thrown #(store/create-memory! :mm/Memory
                                              (assoc (props "store-future") :mm.memory/created (from-now hour-ms))))]
    (is (= [:mm.memory/created] (mapv :slot (future-errors (:data result)))))
    (is (nil? (eid-by-name "store-future")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) create accepts within-skew / past values; defaults absent ones
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-accepts-within-skew-and-past-values
  (testing "thirty seconds ahead is within the sixty-second skew"
    (let [v (from-now (* 30 second-ms))
          e (dt/make :mm/Memory (assoc (props "near-future")
                                       :mm.memory/created      v
                                       :mm.memory/last-touched v))]
      (is (= v (:mm.memory/created e)))
      (is (= v (:mm.memory/last-touched e)))))
  (testing "a clock read taken just before the write is accepted"
    (let [v (from-now 0)
          e (dt/make :mm/Memory (assoc (props "clock-read") :mm.memory/created v))]
      (is (= v (:mm.memory/created e)))))
  (testing "a past value is accepted unchanged"
    (let [v #inst "2025-01-01T00:00:00Z"
          e (dt/make :mm/Memory (assoc (props "past") :mm.memory/created v))]
      (is (= v (:mm.memory/created e))))))

(deftest create-defaults-both-slots-to-server-time-when-absent
  (let [before  (from-now 0)
        e       (dt/make :mm/Memory (props "defaulted"))
        after   (from-now 0)
        created (:mm.memory/created e)
        touched (:mm.memory/last-touched e)]
    (is (instance? java.util.Date created) "created defaulted")
    (is (instance? java.util.Date touched) "last-touched defaulted")
    (is (= created touched) "both slots stamped from the same clock read")
    (is (<= (ms before) (ms created) (ms after))
        "the default is the server clock at the write, never ahead of it")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest update-rejects-future-value
  (let [e      (dt/make :mm/Memory (props "upd"))
        eid    (:db/id e)
        stored (:mm.memory/last-touched e)
        value  (from-now hour-ms)
        result (thrown #(dt/update-entity! eid {:mm.memory/last-touched value}))
        errs   (future-errors (:data result))]
    (is (some? result) "a last-touched one hour ahead is refused on update")
    (is (= "Validation failed on update" (:message result)))
    (is (= :future-timestamp (-> result :data :sandbar/error)))
    (is (= :update (-> result :data :phase)))
    (is (= [:mm.memory/last-touched] (mapv :slot errs)))
    (is (= value (:value (first errs))))
    (is (instance? java.util.Date (:server-time (first errs))))
    (is (= 60000 (:allowed-skew-ms (first errs))))
    (is (= stored (:mm.memory/last-touched (db/entity eid))) "stored value untouched")
    (testing "created is guarded on update too, and the floor ignores :validate? false"
      (let [r (thrown #(dt/update-entity! eid {:mm.memory/created (from-now hour-ms)}
                                          {:validate? false}))]
        (is (= [:mm.memory/created] (mapv :slot (future-errors (:data r)))))
        (is (= (:mm.memory/created e) (:mm.memory/created (db/entity eid))))))))

(deftest update-accepts-within-skew-and-unrelated-updates
  (let [e   (dt/make :mm/Memory (props "upd-ok"))
        eid (:db/id e)
        v   (from-now (* 20 second-ms))]
    (dt/update-entity! eid {:mm.memory/last-touched v})
    (is (= v (:mm.memory/last-touched (db/entity eid))) "twenty seconds ahead is within skew")
    (dt/update-entity! eid {:mm.memory/name "upd-ok renamed"})
    (is (= "upd-ok renamed" (:mm.memory/name (db/entity eid))) "an update without the slots passes")
    (let [past #inst "2026-01-01T00:00:00Z"]
      (dt/update-entity! eid {:mm.memory/created past})
      (is (= past (:mm.memory/created (db/entity eid))) "re-stamping into the past passes"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) other instant slots are untouched
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest other-instant-slots-are-not-guarded
  (let [v (from-now (* 365 24 hour-ms))
        e (dt/make :mm/Memory (assoc (props "reviewed-later") :mm.memory/last-reviewed v))]
    (is (= v (:mm.memory/last-reviewed e)) "last-reviewed may live in the future")
    (dt/update-entity! (:db/id e) {:mm.memory/last-reviewed (from-now (* 2 365 24 hour-ms))})
    (is (some? (:mm.memory/last-reviewed (db/entity (:db/id e)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (e) the skew is configurable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest skew-defaults-to-sixty-seconds-and-is-configurable
  (is (= 60000 (dt/future-timestamp-skew-ms)) "sixty seconds by default")
  (is (= [:mm.memory/created :mm.memory/last-touched] dt/future-timestamp-guarded-slots))
  (testing "a looser skew admits the value"
    (binding [dt/*future-timestamp-skew-ms* (* 2 hour-ms)]
      (is (= (* 2 hour-ms) (dt/future-timestamp-skew-ms)))
      (let [v (from-now hour-ms)
            e (dt/make :mm/Memory (assoc (props "loose") :mm.memory/created v))]
        (is (= v (:mm.memory/created e))))))
  (testing "a tighter skew refuses a value the default admits"
    (binding [dt/*future-timestamp-skew-ms* (* 10 second-ms)]
      (let [r (thrown #(dt/make :mm/Memory
                                (assoc (props "tight") :mm.memory/created (from-now (* 30 second-ms)))))]
        (is (some? r))
        (is (= 10000 (-> r :data :errors first :allowed-skew-ms)))))))

(deftest future-timestamp-errors-is-relative-to-the-given-clock
  ;; Pure contract of the shared checker (commit floor + advisory arm).
  (let [now   #inst "2026-09-18T12:00:00Z"
        ahead #inst "2026-09-18T12:05:00Z"
        near  #inst "2026-09-18T12:00:59Z"
        past  #inst "2026-09-18T11:00:00Z"]
    (is (= [:mm.memory/created]
           (mapv :slot (dt/future-timestamp-errors
                         {:mm.memory/created ahead :mm.memory/last-touched past} now))))
    (is (empty? (dt/future-timestamp-errors
                  {:mm.memory/created near :mm.memory/last-touched now} now))
        "fifty-nine seconds ahead is within the skew; equal to now is fine")
    (is (= [:mm.memory/created :mm.memory/last-touched]
           (mapv :slot (dt/future-timestamp-errors
                         {:mm.memory/created ahead :mm.memory/last-touched ahead} now))))
    (is (empty? (dt/future-timestamp-errors {:mm.memory/created "2099-01-01"} now))
        "a non-instant value is left to schema type validation")
    (is (empty? (dt/future-timestamp-errors {:mm.memory/last-reviewed ahead} now))
        "unguarded slots are ignored")
    (let [err (first (dt/future-timestamp-errors {:mm.memory/created ahead} now))]
      (is (= 300000 (:ahead-ms err)))
      (is (= now (:server-time err)))
      (is (= ahead (:value err)))
      (is (= 60000 (:allowed-skew-ms err)))
      (is (= :future-timestamp (:type err))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (f) the codec path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest codec-path-rejects-future-created-frontmatter
  (let [r (thrown #(dt/make :mm/Memory (props "codec-future")
                            {:format :markdown
                             :source (md-source "created: 2099-01-01"
                                                "last-touched: 2099-01-01")}))]
    (is (some? r) "a frontmatter created: in the future is refused on create")
    (is (= :future-timestamp (-> r :data :sandbar/error)))
    (is (= [:mm.memory/created :mm.memory/last-touched]
           (mapv :slot (future-errors (:data r)))))
    (is (= #inst "2099-01-01T00:00:00Z" (-> r :data :errors first :value))
        "the parsed date-only frontmatter value is reported")
    (is (nil? (eid-by-name "codec-future")) "nothing was transacted")))

(deftest codec-path-accepts-a-clock-read-stamp-and-defaults-absent-slots
  (testing "a frontmatter stamp read from the clock (within skew) is accepted"
    (let [v (from-now 0)
          e (dt/make :mm/Memory (props "codec-now")
                     {:format :markdown
                      :source (md-source (str "created: " (iso v))
                                         (str "last-touched: " (iso v)))})]
      (is (= v (:mm.memory/created e)))
      (is (= v (:mm.memory/last-touched e)))))
  (testing "absent frontmatter stamps default to server time"
    (let [before (from-now 0)
          e      (dt/make :mm/Memory (props "codec-absent")
                          {:format :markdown :source (md-source)})
          after  (from-now 0)]
      (is (<= (ms before) (ms (:mm.memory/created e)) (ms after)))
      (is (= (:mm.memory/created e) (:mm.memory/last-touched e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (g) the MCP boundary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mcp-entity-create-projects-the-refusal
  (testing "explicit slot"
    (let [resp (call "sandbar_entity_create"
                     {"class" ":mm/Memory"
                      "slots" {"mm.memory/rel-path"    "test/mcp_future.md"
                               "mm.memory/name"        "mcp future"
                               "mm.memory/memory-type" "decision"
                               "mm.memory/body-raw"    "body"
                               "mm.memory/created"     "2099-01-01T00:00:00Z"}})]
      (is (user-error? resp) (str "expected isError, got: " (pr-str resp)))
      (is (re-find #"future-timestamp" (response-text resp)))
      (is (re-find #"mm.memory/created" (response-text resp)))
      (is (re-find #"allowed-skew-ms" (response-text resp)))
      (is (nil? (eid-by-name "mcp future")) "nothing was transacted")))
  (testing "codec path (format markdown)"
    (let [resp (call "sandbar_entity_create"
                     {"class"  ":mm/Memory"
                      "slots"  {"mm.memory/rel-path" "test/mcp_codec_future.md"}
                      "format" "markdown"
                      "source" (md-source "created: 2099-01-01")})]
      (is (user-error? resp) (str "expected isError, got: " (pr-str resp)))
      (is (re-find #"future-timestamp" (response-text resp)))
      (is (re-find #"mm.memory/created" (response-text resp)))
      (is (nil? (eid-by-name "codec future")) "nothing was transacted")))
  (testing "absent stamps default to server time on the wire path"
    (let [before (from-now 0)
          resp   (call "sandbar_entity_create"
                       {"class" ":mm/Memory"
                        "slots" {"mm.memory/rel-path"    "test/mcp_defaulted.md"
                                 "mm.memory/name"        "mcp defaulted"
                                 "mm.memory/memory-type" "decision"
                                 "mm.memory/body-raw"    "body"}})
          after  (from-now 0)
          e      (db/entity (eid-by-name "mcp defaulted"))]
      (is (success? resp) (str "expected success, got: " (response-text resp)))
      (is (<= (ms before) (ms (:mm.memory/created e)) (ms after)))
      (is (= (:mm.memory/created e) (:mm.memory/last-touched e))))))

(deftest mcp-entity-update-projects-the-refusal
  (let [e    (store/create-memory! :mm/Memory (props "mcp-upd"))
        eid  (:db/id e)
        resp (call "sandbar_entity_update"
                   {"entity" eid
                    "slots"  {"mm.memory/last-touched" "2099-01-01"}})]
    (is (user-error? resp) (str "expected isError, got: " (pr-str resp)))
    (is (re-find #"future-timestamp" (response-text resp)))
    (is (re-find #"mm.memory/last-touched" (response-text resp)))
    (is (= (:mm.memory/last-touched e) (:mm.memory/last-touched (db/entity eid)))
        "stored value untouched")
    (testing "a stamp read from the clock is accepted"
      (let [v    (from-now 0)
            resp (call "sandbar_entity_update"
                       {"entity" eid
                        "slots"  {"mm.memory/last-touched" (iso v)}})]
        (is (success? resp) (str "expected success, got: " (response-text resp)))
        (is (= v (:mm.memory/last-touched (db/entity eid))))))))

(deftest mcp-entity-validate-reports-the-same-verdict-read-only
  (let [slots {"mm.memory/rel-path"    "test/validate_future.md"
               "mm.memory/name"        "validate future"
               "mm.memory/memory-type" "decision"
               "mm.memory/body-raw"    "body"}
        bad   (call "sandbar_entity_validate"
                    {"class" ":mm/Memory"
                     "slots" (assoc slots "mm.memory/created" "2099-01-01T00:00:00Z")})
        good  (call "sandbar_entity_validate"
                    {"class" ":mm/Memory"
                     "slots" (assoc slots "mm.memory/created" (iso (from-now 0)))})]
    (is (success? bad) "validate is read-only — it reports, it does not throw")
    (is (re-find #"\"valid\?\"\s*:\s*false" (response-text bad)))
    (is (re-find #"future-timestamp" (response-text bad)))
    (is (re-find #"mm.memory/created" (response-text bad)))
    (is (success? good))
    (is (re-find #"\"valid\?\"\s*:\s*true" (response-text good)))
    (is (nil? (eid-by-name "validate future")) "validate never transacts")))
