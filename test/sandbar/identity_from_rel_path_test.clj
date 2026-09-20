(ns sandbar.identity-from-rel-path-test
  "The create path mints `mm/id` from the rel-path slug (D7 2c, 2026-09-20):
   the same value as the ident derivation for every plain name, and the
   file's own derivation for a digit-leading slug whose ident the codec
   prefixes — a readability prefix must not create a new identity (Astra's
   answer to the identity question).  An explicit id always wins."
  (:require [clojure.string    :as str]
            [clojure.test      :refer [deftest is use-fixtures]]
            [sandbar.identifier :as ident]
            [sandbar.store      :as store]
            [sandbar.test-util  :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "identity-from-rel-path" :auth? false}))

(deftest the-slug-derivation-agrees-with-the-ident-derivation-for-plain-names
  (is (= (ident/ident-uuid :memory.decisions/foo) (ident/rel-path-uuid "decisions/foo.md")))
  (is (= (ident/ident-uuid :memory.patterns.architectural.layering/topical_namespaces)
         (ident/rel-path-uuid "patterns/architectural/layering/topical_namespaces.md"))
      "a nested directory is the dotted namespace"))

(deftest a-codec-prefix-does-not-change-the-identity
  (let [slug "2026-05-26T0400_pickup_probe" rp (str "sessions/" slug ".md")]
    (is (= (ident/entity-uuid "sessions" slug) (ident/rel-path-uuid rp)))
    (is (= (ident/ident-uuid (keyword "memory.sessions" slug)) (ident/rel-path-uuid rp))
        "the digit-leading name's own derivation is the file's")
    (is (not= (ident/ident-uuid (keyword "memory.sessions" (str "session-" slug))) (ident/rel-path-uuid rp))
        "the prefixed ident's derivation would have drifted")))

(deftest the-create-path-mints-from-the-slug-and-an-explicit-id-wins
  (let [rp "observations/2026-09-20T1150_identity_probe.md"
        e  (store/create-memory! :mm/Observation {:mm.memory/rel-path rp :mm.memory/name "identity probe"
                                                   :mm.memory/description "a digit-leading slug"
                                                   :mm.memory/memory-type :observation :mm.memory/scope :project})]
    (is (= (ident/rel-path-uuid rp) (:mm/id e)) "minted from the slug")
    (is (str/ends-with? (name (:db/ident e)) "2026-09-20T1150_identity_probe") "the ident keeps the slug (with the codec's prefix, if any)"))
  (let [explicit #uuid "11111111-1111-5111-8111-111111111111"
        e (store/create-memory! :mm/Observation {:mm.memory/rel-path "observations/explicit_identity_probe.md"
                                                  :mm.memory/name "explicit" :mm.memory/description "an explicit id"
                                                  :mm.memory/memory-type :observation :mm.memory/scope :project
                                                  :mm/id explicit})]
    (is (= explicit (:mm/id e)) "an explicit id is never overridden")))

(deftest a-repeat-create-preserves-an-assigned-identity-and-a-repeat-first-create-is-stable
  ;; Astra's D7-R6 (2026-09-20 14:03Z): a legacy row created under the old
  ;; rule holds an id derived from its prefixed ident; recreating it through
  ;; the store with the same path and no incoming id must keep that id, not
  ;; remint from the slug.  A plain repeat create keeps the slug id.
  (let [rp     "observations/2026-09-20T1200_legacy_probe.md"
        legacy #uuid "9601d789-1d7f-5aa4-9d83-791dfd49fa09"
        props  {:mm.memory/rel-path rp :mm.memory/name "legacy probe" :mm.memory/description "created under the old rule"
                :mm.memory/memory-type :observation :mm.memory/scope :project}
        first  (store/create-memory! :mm/Observation (assoc props :mm/id legacy))
        again  (store/create-memory! :mm/Observation props)]
    (is (= legacy (:mm/id first)))
    (is (= (:db/id first) (:db/id again)) "the same row")
    (is (= legacy (:mm/id again)) "the assigned identity is preserved on a repeat create without an incoming id"))
  (let [rp    "observations/2026-09-20T1200_fresh_probe.md"
        props {:mm.memory/rel-path rp :mm.memory/name "fresh probe" :mm.memory/description "first created under the slug rule"
               :mm.memory/memory-type :observation :mm.memory/scope :project}
        a     (store/create-memory! :mm/Observation props)
        b     (store/create-memory! :mm/Observation props)]
    (is (= (ident/rel-path-uuid rp) (:mm/id a) (:mm/id b)) "a repeat first create is stable at the slug id")))
