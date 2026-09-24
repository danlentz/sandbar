(ns sandbar.codec.json-readable-keyword-test
  "JSON source must refuse unreadable keyword conversions before a caller can
   persist a partial object or array. HTTP tests separately exercise persistence."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.codec.json :as cjson]
            [sandbar.codec.protocol :as proto]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "json-readable-keyword" :auth? false}))

(defn- parse [obj]
  (proto/parse (cjson/make-codec) (json/generate-string obj) {}))

(defn- exception [obj]
  (try (parse obj) nil (catch clojure.lang.ExceptionInfo ex ex)))

(defn- readable? [x]
  (try (= x (edn/read-string (pr-str x))) (catch Exception _ false)))

(defn- refusal [obj field original]
  (let [before (d/basis-t (db/db)) ex (exception obj) message (some-> ex ex-message)
        data (some-> ex ex-data)]
    (is (instance? clojure.lang.ExceptionInfo ex) (str "Expected source refusal: " (pr-str obj)))
    (is (and (string? message) (str/includes? message field)) (pr-str message))
    (is (and (string? message) (str/includes? message (pr-str original))) (pr-str message))
    (is (map? data))
    (is (readable? data) "Diagnostic data must itself survive EDN")
    (is (map? (json/parse-string (json/generate-string data))))
    (is (= before (d/basis-t (db/db))) "Parsing/refusal never transacts")
    ex))

(deftest keyword-values-refuse-with-source-context
  (doseq [value ["later this year" "soon;ish" "a,b" "x\"y" "q3/2026" "a\\b" ":2026-Q3"]]
    (refusal {"_class" "mm/Idea" "timeframe" value} "timeframe" value)))

(deftest valid-keywords-and-ordinary-prose-are-preserved
  (doseq [value ["now" "2026-Q3" "future—gated" "café" "horizon/near"]]
    (let [r (parse {"_class" "mm/Idea" "timeframe" value
                    "triggered-by" "later this year; still ordinary prose"})]
      (is (= (keyword value) (:mm.idea/timeframe r)))
      (is (readable? (:mm.idea/timeframe r)))
      (is (= "later this year; still ordinary prose" (:mm.idea/triggered-by r)))))
  (let [r (parse {"_class" ":mm/Memory" "type" "idea" "scope" "project"})]
    (is (= :mm/Memory (:dt/type r)))
    (is (= :idea (:mm.memory/memory-type r)))
    (is (= :project (:mm.memory/scope r)))))

(deftest reference-conversions-use-the-same-refusal
  (refusal {"_class" "mm/Memory" "cites" ["memory.ideas/good" "memory.ideas/two words"]}
           "cites" "memory.ideas/two words")
  (refusal {"_class" "mm/Idea" "superseded-by" "memory.ideas/semi;colon"}
           "superseded-by" "memory.ideas/semi;colon")
  (let [r (parse {"_class" "mm/Memory" "cites" ["memory.ideas/good" "memory.ideas/naïve→ok"]})]
    (is (= [:memory.ideas/good :memory.ideas/naïve→ok] (:mm.memory/cites r)))
    (is (readable? r))))

(deftest entity-arrays-refuse-as-one-parse
  (let [good {"_class" "mm/Idea" "timeframe" "now"}
        bad {"_class" "mm/Idea" "timeframe" "soon;ish"}]
    (is (= [{:dt/type :mm/Idea :mm.idea/timeframe :now}
            {:dt/type :mm/Idea :mm.idea/timeframe :now}] (parse [good good])))
    (refusal [good bad] "timeframe" "soon;ish")
    (refusal [bad good] "timeframe" "soon;ish")))

(deftest diagnostic-size-is-bounded
  (let [value (str "two words " (apply str (repeat 5000 "x")))
        ex (exception {"_class" "mm/Idea" "timeframe" value})]
    (is (some? ex))
    (is (and ex (< (count (ex-message ex)) 1500)))
    (is (and ex (< (count (pr-str (ex-data ex))) 1500)))
    (is (and ex (readable? (ex-data ex))))))

(deftest nested-reference-maps-remain-outside-string-conversion
  ;; This documents the boundary, not nested-entity validation or persistence.
  (let [m {"db/id" 123}
        r (parse {"_class" "mm/Memory" "cites" [m]})]
    (is (= [m] (:mm.memory/cites r)))))
