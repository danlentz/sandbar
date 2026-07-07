(ns sandbar.security.query-firewall-test
  "Read-plane NAMESPACE FIREWALL (RPAF) contract — the :auth/* credential-exfil
  closure, ORTHOGONAL to sanitize-where's call-form allowlist.  DB-free: the
  guard fns are pure predicates over keyword namespaces.  Governing ADR:
  decisions/read_plane_namespace_firewall_deny_by_default_closes_auth_exfil_...
  Bug: bugs/read_plane_aggregate_verbs_no_attribute_authz_credential_hash_exfil_..."
  (:require [clojure.test :refer [deftest testing is]]
            [sandbar.security.query :as secq]))

(defn- firewall-reason
  "Return the ex-data :reason if `thunk` throws the firewall ex-info, else nil."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest namespace-allowed?-corpus-and-metamodel
  (testing "corpus + metamodel namespaces (mm / dt / db / value) are allowed"
    (doseq [kw [:mm/Memory :mm.memory/name :mm.tag/value :mm.session/log
                :dt/Class :dt/Property :dt.fn/body
                :db/ident :db.type/string :value/string]]
      (is (secq/read-plane-namespace-allowed? kw) (str kw " must be allowed"))))
  (testing "a keyword with NO namespace (a bare value like :decision) is allowed"
    (is (secq/read-plane-namespace-allowed? :decision))
    (is (secq/read-plane-namespace-allowed? :global))))

(deftest namespace-allowed?-firewalled
  (testing "substrate-internal / secret / operational namespaces are denied"
    (doseq [kw [:auth/api-key-hash :auth/password-hash :auth/session-id
                :auth/ServiceAccount :user/secret :audit/initial-data
                :event/actor :http/remote-addr :api/handler :tx/id
                :job/payload :model/User :twit/name :context/uuid
                :workflow/subject]]
      (is (not (secq/read-plane-namespace-allowed? kw))
          (str kw " must be firewalled")))))

(deftest assert-class-allowed
  (is (= :mm/Memory (secq/assert-class-allowed! :mm/Memory)) "corpus class passes + threads")
  (is (= :dt/Property (secq/assert-class-allowed! :dt/Property)))
  (is (= :namespace-not-read-plane-allowed
         (firewall-reason #(secq/assert-class-allowed! :auth/ServiceAccount)))
      "the group-by-dump class is rejected with the firewall reason"))

(deftest assert-attribute-allowed
  (is (= :mm.memory/name (secq/assert-attribute-allowed! :mm.memory/name)))
  (is (= :namespace-not-read-plane-allowed
         (firewall-reason #(secq/assert-attribute-allowed! :auth/api-key-hash)))))

(deftest assert-entity-allowed
  (testing "entity whose :dt/type is a corpus class passes (keyword + nested-map forms)"
    (is (= {:dt/type :mm/Decision} (secq/assert-entity-allowed! {:dt/type :mm/Decision})))
    (is (map? (secq/assert-entity-allowed! {:dt/type {:db/ident :mm/Memory}})))
    (is (nil? (secq/assert-entity-allowed! nil)) "nil / typeless entity passes"))
  (testing "entity whose :dt/type is a firewalled class is refused (both shapes)"
    (is (= :namespace-not-read-plane-allowed
           (firewall-reason #(secq/assert-entity-allowed! {:dt/type :auth/ServiceAccount :db/id 42}))))
    (is (= :namespace-not-read-plane-allowed
           (firewall-reason #(secq/assert-entity-allowed! {:dt/type {:db/ident :auth/Session}}))))))

(deftest assert-where-namespaces
  (testing "legit corpus :where passes (data triples, allowlisted ops, nil/[])"
    (is (some? (secq/assert-where-namespaces! '[[?e :mm.memory/memory-type :decision]])))
    (is (some? (secq/assert-where-namespaces!
                 '[[?e :mm.memory/name ?n] [(clojure.string/starts-with? ?n "S")]])))
    (is (nil?  (secq/assert-where-namespaces! nil)))
    (is (= []  (secq/assert-where-namespaces! []))))
  (testing "the ORACLE — a data-pattern with a firewalled ATTRIBUTE (index 1) — is rejected"
    (is (= :namespace-not-read-plane-allowed
           (firewall-reason #(secq/assert-where-namespaces! '[[?x :auth/api-key-hash ?h]]))))
    (testing "including a join that reaches an :auth/* attribute in a later triple"
      (is (= :namespace-not-read-plane-allowed
             (firewall-reason #(secq/assert-where-namespaces!
                                 '[[?e :mm.memory/cites ?v] [?v :auth/api-key-hash ?h]]))))))
  (testing "a firewalled keyword in a data-pattern VALUE position is ALSO rejected"
    (testing "the [?x :dt/type :auth/User] existence oracle (unjoined, satisfiable => whole-population leak)"
      (is (= :namespace-not-read-plane-allowed
             (firewall-reason #(secq/assert-where-namespaces! '[[?x :dt/type :auth/User]])))))
    (testing "but a BARE (unnamespaced) value keyword like :decision still passes"
      (is (some? (secq/assert-where-namespaces! '[[?e :mm.memory/memory-type :decision]])))))
  (testing "a firewalled attribute smuggled into a builtin/expression arg is rejected"
    (is (= :namespace-not-read-plane-allowed
           (firewall-reason #(secq/assert-where-namespaces! '[[(missing? $ ?e :auth/api-key-hash)]]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OUTPUT surface (RPAF v2) — returned-entity + slot sanitization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest entity-visible?
  (testing "a corpus memorial (allowed class + :memory.* ident) is visible"
    (is (secq/read-plane-entity-visible? {:dt/type :mm/Decision :db/ident :memory.decisions/foo}))
    (is (secq/read-plane-entity-visible? {:dt/type {:db/ident :mm/Bug} :db/ident :memory.bugs/x})))
  (testing "a metamodel entity (allowed class + allowed ident) is visible"
    (is (secq/read-plane-entity-visible? {:dt/type :dt/Class :db/ident :mm/Memory}))
    (is (secq/read-plane-entity-visible? {:dt/type :dt/Property :db/ident :mm.memory/name})))
  (testing "a firewalled INSTANCE (firewalled :dt/type) is NOT visible"
    (is (not (secq/read-plane-entity-visible? {:dt/type :auth/ServiceAccount :db/ident :auth.accounts/codex})))
    (is (not (secq/read-plane-entity-visible? {:dt/type {:db/ident :auth/Session} :db/id 7}))))
  (testing "a firewalled CLASS-IDENT entity (allowed :dt/Class type, firewalled :db/ident) is NOT visible"
    (is (not (secq/read-plane-entity-visible? {:dt/type :dt/Class :db/ident :auth/User})))
    (is (not (secq/read-plane-entity-visible? {:dt/type :dt/Property :db/ident :auth/api-key-hash}))))
  (testing "typeless / identless entities pass (nothing to leak on that axis)"
    (is (secq/read-plane-entity-visible? {}))
    (is (secq/read-plane-entity-visible? {:db/id 42}))))

(deftest scrub-projection
  (testing "a firewalled entity collapses to the redaction marker (no ident/class/slots)"
    (is (= secq/read-plane-redaction-marker
           (secq/read-plane-scrub-projection {:db/ident :auth.accounts/codex
                                              :dt/type :auth/ServiceAccount
                                              :auth/api-key-hash "bcrypt+sha512$SECRET"})))
    (is (= secq/read-plane-redaction-marker
           (secq/read-plane-scrub-projection {:db/ident :auth/User :dt/type :dt/Class}))
        "class.instances :dt/Class firewalled class ident → redacted"))
  (testing "a visible entity keeps corpus slots but STRIPS firewalled-namespace slots"
    (is (= {:db/id 1 :db/ident :memory.decisions/foo :dt/type :mm/Decision
            :mm.memory/name "n"}
           (secq/read-plane-scrub-projection {:db/id 1 :db/ident :memory.decisions/foo
                                              :dt/type :mm/Decision :mm.memory/name "n"
                                              :http/remote-addr "127.0.0.1"
                                              :event/actor :auth/x})))
        )
  (testing "nil / marker / metadata-only visible pass through unchanged"
    (is (nil? (secq/read-plane-scrub-projection nil)))
    (is (= {:db/id 5 :db/ident :memory.bugs/y :dt/type :mm/Bug}
           (secq/read-plane-scrub-projection {:db/id 5 :db/ident :memory.bugs/y :dt/type :mm/Bug})))))
