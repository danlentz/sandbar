(ns sandbar.util.api-key-verification-cache-test
  "The API-key verification cache (reliability sprint D4, 2026-09-19).

   bcrypt+sha512 verification costs about 280 ms per call and ran on every
   authenticated request: a fixed floor that dwarfed the work behind it and
   spent more than half of the recall hook's 500 ms budget before any
   search ran.  `authenticate-api-key` now caches a successful verdict per
   [service, SHA-256 of the presented key, stored hash] for a TTL.  These
   tests count bcrypt runs through a wrapper around `verify-password` and
   pin: a repeat presentation runs bcrypt once; a wrong key is never cached;
   a rotated key misses the cache and the old key stops working; a
   deactivated account is refused despite a warm cache; the TTL re-verifies;
   the cache never stores the presented key."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name    "api-key-verification-cache-test"
                            :auth?        false
                            :extra-schema [:auth :event]})
  (fn [t] (auth/clear-api-key-verification-cache!) (t) (auth/clear-api-key-verification-cache!)))

(def ^:private service :verification-cache-probe)

(defn- seed-account!
  "A service account whose api-key hashes to `api-key`.  Returns its eid."
  [api-key]
  (:db/id (dt/make :auth/ServiceAccount
                   {:auth/service-name service
                    :auth/api-key-hash (auth/hash-password api-key)
                    :auth/active?      true}
                   {:validate? false})))

(defmacro counting-bcrypt
  "Run `body` with `verify-password` wrapped so `counter` counts its calls."
  [counter & body]
  `(let [real# auth/verify-password]
     (with-redefs [auth/verify-password (fn [k# h#] (swap! ~counter inc) (real# k# h#))]
       ~@body)))

(deftest a-repeat-presentation-runs-bcrypt-once
  (let [key     (str "k-" (random-uuid))
        _       (seed-account! key)
        bcrypts (atom 0)]
    (counting-bcrypt bcrypts
      (is (:success (auth/authenticate-api-key service key)) "first presentation verifies")
      (is (= 1 @bcrypts) "bcrypt ran once")
      (dotimes [_ 5]
        (is (:success (auth/authenticate-api-key service key))))
      (is (= 1 @bcrypts) "five repeats served from the cache"))))

(deftest a-wrong-key-is-refused-and-never-cached
  (let [key     (str "k-" (random-uuid))
        _       (seed-account! key)
        bcrypts (atom 0)]
    (counting-bcrypt bcrypts
      (is (= :invalid-api-key (:reason (auth/authenticate-api-key service "not-the-key"))))
      (is (= :invalid-api-key (:reason (auth/authenticate-api-key service "not-the-key"))))
      (is (= 2 @bcrypts) "each wrong presentation runs bcrypt; failures are not cached")
      (is (:success (auth/authenticate-api-key service key)) "the right key still verifies"))))

(deftest a-rotated-key-misses-the-cache-and-the-old-key-stops-working
  (let [old     (str "k-" (random-uuid))
        new     (str "k-" (random-uuid))
        eid     (seed-account! old)
        bcrypts (atom 0)]
    (counting-bcrypt bcrypts
      (is (:success (auth/authenticate-api-key service old)))
      (is (= 1 @bcrypts))
      ;; rotate: the stored hash changes, so every cached triple for this
      ;; account misses by construction
      (dt/update-entity! eid {:auth/api-key-hash (auth/hash-password new)})
      (is (= :invalid-api-key (:reason (auth/authenticate-api-key service old)))
          "the old key is refused after rotation, cache or no cache")
      (is (:success (auth/authenticate-api-key service new)) "the new key verifies")
      (is (= 3 @bcrypts) "the rotation forced two fresh bcrypt runs")
      (is (:success (auth/authenticate-api-key service new)))
      (is (= 3 @bcrypts) "and the new key is now cached"))))

(deftest a-deactivated-account-is-refused-despite-a-warm-cache
  (let [key (str "k-" (random-uuid))
        eid (seed-account! key)]
    (is (:success (auth/authenticate-api-key service key)) "warm the cache")
    (dt/update-entity! eid {:auth/active? false})
    (is (= :account-inactive (:reason (auth/authenticate-api-key service key)))
        "activity is checked before the cache on every call")))

(deftest the-ttl-forces-re-verification
  (let [key     (str "k-" (random-uuid))
        _       (seed-account! key)
        bcrypts (atom 0)]
    (counting-bcrypt bcrypts
      (binding [auth/*api-key-verification-ttl-ms* 0]
        (is (:success (auth/authenticate-api-key service key)))
        (is (:success (auth/authenticate-api-key service key)))
        (is (= 2 @bcrypts) "a zero TTL re-verifies every time")))))

(deftest the-cache-holds-a-digest-not-the-key
  (let [key (str "k-" (random-uuid))]
    (seed-account! key)
    (is (:success (auth/authenticate-api-key service key)))
    (let [cache @@#'auth/api-key-verification-cache]
      (is (= 1 (count cache)))
      (let [[svc digest stored] (first (keys cache))]
        (is (= service svc))
        (is (= 64 (count digest)) "a SHA-256 hex digest")
        (is (not= key digest) "the presented key is not stored")
        (is (string? stored) "keyed by the stored hash it verified against")))))
