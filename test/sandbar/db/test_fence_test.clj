(ns sandbar.db.test-fence-test
  "The test fence (2026-09-19).  Under `lein test` the `:test` profile sets
   `-Dsandbar.db.uri=datomic:mem://sandbar-test-fallback`, so every code path
   that reaches the database through the connection fallback (no scratch
   fixture bound in `db/**conn*`) lands in a throwaway in-memory store and
   never in the configured live one.

   Why: the 2026-09-19 census found SystemEvent rows arriving in the live
   store in bursts of about 65 in the very minutes the full suite ran — the
   core scheduler tests bind a fresh scheduler state but no database, and
   `sandbar.db.datomic/conn` fell back to the configured URI."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]))

(deftest fallback-uri-is-in-memory-under-the-test-profile
  (is (= "datomic:mem://sandbar-test-fallback"
         (System/getProperty db/uri-override-property))
      "the :test lein profile must set the override property")
  (is (db/mem-uri? (db/db-uri))
      "the no-arg db-uri honours the override"))

(deftest fallback-connection-is-a-throwaway-store
  (let [prior @db/**conn*]
    (reset! db/**conn* nil)
    (try
      (let [c  (db/conn)
            db (d/db c)
            ;; The fallback store is shared by every fixture-less access in
            ;; this JVM, so it may carry schema (some test loads it through
            ;; the fallback; `warn-fence-fallback-once!` names the first
            ;; such frame in the log).  What the fence guarantees is that
            ;; the store is NOT the configured live one, whose memory
            ;; population is in the tens of thousands.
            memories (if (d/entid db :mm/Memory)
                       (or (d/q '[:find (count ?e) . :where [?e :dt/type :mm/Memory]] db) 0)
                       0)]
        (is (some? c) "the fallback creates the in-memory store on demand")
        (is (< memories 1000)
            (str "the fallback store must be a throwaway, not the live "
                 "corpus; it holds " memories " :mm/Memory entities")))
      (finally (reset! db/**conn* prior)))))

(deftest explicit-spec-uri-is-unaffected-by-the-override
  (is (= "datomic:mem://foo" (db/db-uri {:url "datomic:mem://" :sid "foo"}))))

(deftest mem-uri-predicate
  (is (db/mem-uri? "datomic:mem://anything"))
  (is (not (db/mem-uri? "datomic:dev://localhost:4334/sandbar")))
  (is (not (db/mem-uri? nil))))
