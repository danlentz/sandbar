(ns sandbar.deploy.registry-test
  "W1.deploy — the store-registry + CREDENTIAL AIR-GAP acceptance suite
  (falsification-first).  Covers the adversary set of W1.deploy §5 and the
  §6 gate rows DEP-3 (cross-scope connection impossible) + DEP-4 (private store
  ≠ public repo).  Each test FAILS against a wrong implementation — the named
  gate-failure where a public process holds a private-scope credential/endpoint,
  or a registry that routes a private corpus to the public repo.

  PURE — the registry is per-machine config; no DB fixture, no connection."
  (:require [clojure.test :refer [deftest testing is]]
            [sandbar.deploy.registry :as reg]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers + fixtures-as-data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- refusal-marker
  "Run `thunk`; return the `:sandbar/error` marker of any ex-info it throws, or
  `::no-throw` when it returns normally.  Lets a test assert the SPECIFIC
  refuse-to-serve marker fired (not merely that something threw)."
  [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:sandbar/error (ex-data e) ::threw-unmarked))))

(def public-endpoint  "datomic:dev://localhost:4334/")
(def private-endpoint "datomic:dev://localhost:4336/")   ; +2 slot, NOT 4335 (public h2)
(def public-repo      "git@example.com:org/public-corpus.git")

(def clean-public-registry
  "A well-formed PUBLIC-owner registry: public entry only, no private endpoint."
  {:schema-version 1
   :owner-scope    :trust-scope/public
   :public  {:transactor-endpoint public-endpoint
             :sid "global" :corpus-repo public-repo
             :local-disk-path "/Users/you/claude"}
   :private {}})

(def private-owner-registry
  "A PRIVATE-owner registry (the sandboxed work-machine posture): its own
  private scope + public reachable BY REFERENCE only (no public transactor)."
  {:schema-version 1
   :owner-scope       [:trust-scope/private :proj/alpha]
   :public-corpus-ref public-repo
   :private {:proj/alpha {:transactor-endpoint private-endpoint
                          :sid "alpha"
                          :corpus-repo "git@example.com:org/alpha-private.git"
                          :local-disk-path "/Users/you/src/alpha"}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reachable? — the process-boundary truth table (the connection rule)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest reachable-truth-table
  (testing "a public owner reaches ONLY public — never any private (A-1 core)"
    (is (true?  (reg/reachable? :trust-scope/public :trust-scope/public)))
    (is (false? (reg/reachable? :trust-scope/public [:trust-scope/private :proj/alpha])))
    (is (false? (reg/reachable? :trust-scope/public [:trust-scope/private :proj/beta]))))
  (testing "a private owner reaches ONLY its OWN scope (not public transactor,
            not another private — cross-private stays unreachable)"
    (is (true?  (reg/reachable? [:trust-scope/private :proj/alpha]
                                [:trust-scope/private :proj/alpha])))
    (is (false? (reg/reachable? [:trust-scope/private :proj/alpha] :trust-scope/public)))
    (is (false? (reg/reachable? [:trust-scope/private :proj/alpha]
                                [:trust-scope/private :proj/beta]))))
  (testing "a malformed / nil owner reaches nothing (fail-closed)"
    (is (false? (reg/reachable? nil :trust-scope/public)))
    (is (false? (reg/reachable? :garbage :trust-scope/public)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A-1 — the private endpoint is ABSENT from a public session's connection
;;   surface.  reachable-endpoints of a public registry never contains a
;;   private endpoint.  FAILS against an impl where the public process can
;;   enumerate/reach a private-scope endpoint.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a1-private-endpoint-absent-from-public-connection-surface
  (testing "a clean public registry's reachable set is exactly {public-endpoint}"
    (is (= #{public-endpoint} (reg/reachable-endpoints clean-public-registry))))
  (testing "even a POISONED public registry (a private endpoint hand-edited in)
            never makes the private endpoint REACHABLE — the filter drops it"
    (let [poisoned (assoc-in clean-public-registry
                             [:private :proj/alpha]
                             {:transactor-endpoint private-endpoint
                              :corpus-repo "x" :local-disk-path "y"})]
      (is (not (contains? (reg/reachable-endpoints poisoned) private-endpoint))
          "the private endpoint must be ABSENT from the public connection surface
           (A-1) even when present in the file — FAILS against an impl that
           returns every declared endpoint regardless of owner scope"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; assert-credential-air-gap! — the LOUD refusal (A-1 / DEP-3).  A public-owner
;;   registry that CARRIES a private transactor endpoint is refused outright
;;   (mere presence is the exfil risk, independent of reachability filtering).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest credential-air-gap-refuses-private-endpoint-in-public-registry
  (testing "a clean public registry PASSES the air-gap (returns nil)"
    (is (nil? (reg/assert-credential-air-gap! clean-public-registry))))
  (testing "a public registry carrying a private transactor endpoint is REFUSED
            with the :credential-air-gap-violation marker (FAILS against an impl
            that lets the public process hold a private credential)"
    (let [poisoned (assoc-in clean-public-registry
                             [:private :proj/alpha]
                             {:transactor-endpoint private-endpoint
                              :corpus-repo "x" :local-disk-path "y"})]
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! poisoned))))))
  (testing "a PRIVATE-owner registry holding its own private endpoint is NOT a
            violation (the air-gap gates the PUBLIC process, not the private one)"
    (is (nil? (reg/assert-credential-air-gap! private-owner-registry)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A-2 / DEP-3 — an explicit cross-scope connection attempt FAILS at the
;;   resolver/process boundary (not at a filter).  Simulated at the config layer.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a2-cross-scope-connect-refused-at-boundary
  (testing "a public-owner process resolving its OWN public endpoint succeeds"
    (is (= public-endpoint
           (reg/resolve-endpoint clean-public-registry :trust-scope/public))))
  (testing "a public-owner process attempting to resolve a PRIVATE endpoint is
            REFUSED at the boundary with :cross-scope-connection-refused —
            NOT served a filtered/empty result (FAILS against a filter impl)"
    (is (= :cross-scope-connection-refused
           (refusal-marker
             #(reg/resolve-endpoint clean-public-registry
                                    [:trust-scope/private :proj/alpha])))))
  (testing "even if the public registry were poisoned with a real private
            endpoint, resolving it still REFUSES at the boundary (the resolver
            gates on reachability, not on entry presence)"
    (let [poisoned (assoc-in clean-public-registry
                             [:private :proj/alpha]
                             {:transactor-endpoint private-endpoint})]
      (is (= :cross-scope-connection-refused
             (refusal-marker
               #(reg/resolve-endpoint poisoned [:trust-scope/private :proj/alpha]))))))
  (testing "a private-owner process resolves its OWN scope, but cross-private
            (alpha→beta) REFUSES at the boundary"
    (is (= private-endpoint
           (reg/resolve-endpoint private-owner-registry [:trust-scope/private :proj/alpha])))
    (is (= :cross-scope-connection-refused
           (refusal-marker
             #(reg/resolve-endpoint private-owner-registry
                                    [:trust-scope/private :proj/beta])))))
  (testing "a private-owner process may NOT resolve a live PUBLIC transactor
            (public is by-reference only — R9); the connect REFUSES"
    (is (= :cross-scope-connection-refused
           (refusal-marker
             #(reg/resolve-endpoint private-owner-registry :trust-scope/public))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public-corpus-reference — the by-reference half of the air-gap (R9).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-by-reference-is-a-repo-handle-not-a-transactor
  (testing "a private-owner registry reaches public read-only BY REFERENCE (a
            repo string) — and resolving public as a live transactor still fails"
    (is (= public-repo (reg/public-corpus-reference private-owner-registry)))
    (is (= :cross-scope-connection-refused
           (refusal-marker
             #(reg/resolve-endpoint private-owner-registry :trust-scope/public)))
        "the reference is a git handle, NOT a connectable transactor endpoint")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-4 — a private store's corpus-repo is NEVER the public corpus repo.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep4-private-repo-never-public-repo
  (testing "a registry whose private entry routes to its OWN repo PASSES"
    (let [both {:schema-version 1
                :owner-scope :trust-scope/private
                :public  {:corpus-repo public-repo}
                :private {:proj/alpha {:transactor-endpoint private-endpoint
                                       :corpus-repo "git@example.com:org/alpha.git"}}}]
      (is (nil? (reg/assert-private-repo-distinct! both)))))
  (testing "a registry that routes a PRIVATE corpus to the PUBLIC repo is
            REFUSED with :private-repo-collision (FAILS against a registry that
            points a private store at the public repo — the catastrophic-push
            shape)"
    (let [collide {:schema-version 1
                   :owner-scope :trust-scope/private
                   :public  {:corpus-repo public-repo}
                   :private {:proj/alpha {:transactor-endpoint private-endpoint
                                          :corpus-repo public-repo}}}]  ; ← routes to PUBLIC
      (is (= :private-repo-collision
             (refusal-marker #(reg/assert-private-repo-distinct! collide))))))
  (testing "a private-ONLY owner registry whose private :corpus-repo is DISTINCT
            from its :public-corpus-ref passes (the R9 work-machine posture done
            right — meaningful now that :public-corpus-ref is in the check, no
            longer a vacuous no-public-entry pass)"
    (is (nil? (reg/assert-private-repo-distinct! private-owner-registry))))
  (testing "a private-ONLY owner registry whose private :corpus-repo EQUALS its
            :public-corpus-ref (the R9 by-reference public repo it carries) is
            REFUSED with :private-repo-collision.  The earlier round vacuously
            PASSED this — it compared only against [:public :corpus-repo], absent
            in a private-only registry — so a private store could route straight
            to the public repo undetected.  FAILS against an impl that ignores
            :public-corpus-ref"
    (let [collide-ref {:schema-version 1
                       :owner-scope [:trust-scope/private :proj/alpha]
                       :public-corpus-ref public-repo
                       :private {:proj/alpha {:transactor-endpoint private-endpoint
                                              :corpus-repo public-repo}}}]  ; ← == :public-corpus-ref
      (is (= :private-repo-collision
             (refusal-marker #(reg/assert-private-repo-distinct! collide-ref)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; owner-scope loudness — a registry that cannot name its own trust scope is
;;   REFUSED loudly at bring-up (:registry-owner-unresolved), never a silent
;;   air-gap no-op.  reachable? already fail-closes CONNECTIONS; the refuse
;;   CONTRACT must fire too.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest owner-scope-must-be-well-formed
  (testing "a well-formed public / private owner scope resolves"
    (is (true? (reg/owner-scope-resolved? clean-public-registry)))
    (is (true? (reg/owner-scope-resolved? private-owner-registry))))
  (testing "an ABSENT / nil / malformed :owner-scope is UNRESOLVED and REFUSED
            loudly with :registry-owner-unresolved at EVERY bring-up entry point
            — assert-owner-scope-resolved!, validate-registry! (FIRST, before any
            other gate), AND the standalone air-gap assertion (no longer a silent
            no-op for a non-:public owner).  FAILS against the earlier round's
            air-gap body that fired only for an exactly-:public owner"
    (doseq [bad [{:schema-version 1 :public {:transactor-endpoint public-endpoint}}   ; absent
                 {:schema-version 1 :owner-scope nil :public {}}                       ; nil
                 {:schema-version 1 :owner-scope :garbage :public {}}                  ; unknown keyword
                 {:schema-version 1 :owner-scope :trust-scope/private :public {}}      ; bare kw, not [.. key]
                 {:schema-version 1 :owner-scope [:trust-scope/private nil] :public {}}; vector, nil key
                 {:schema-version 1 :owner-scope [:trust-scope/private] :public {}}]]  ; vector, missing key
      (is (false? (reg/owner-scope-resolved? bad)))
      (is (= :registry-owner-unresolved
             (refusal-marker #(reg/assert-owner-scope-resolved! bad))))
      (is (= :registry-owner-unresolved
             (refusal-marker #(reg/validate-registry! bad)))
          "validate-registry! refuses a malformed owner FIRST")
      (is (= :registry-owner-unresolved
             (refusal-marker #(reg/assert-credential-air-gap! bad)))
          "and the standalone air-gap assertion is loud, not a silent no-op"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; air-gap BREADTH — a public-owner registry refuses ANY private entry, not
;;   only endpoint-bearing ones.  A public process has no business holding a
;;   private scope's :local-disk-path or :corpus-repo either.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest air-gap-refuses-any-private-entry-under-public-owner
  (testing "a public-owner registry carrying a private entry with ONLY a
            :local-disk-path (NO transactor endpoint) is STILL REFUSED with
            :credential-air-gap-violation — closing the disclosed disk-path hole.
            FAILS against the endpoint-only form that tolerated this"
    (let [disk-only (assoc-in clean-public-registry [:private :proj/alpha]
                              {:local-disk-path "/Users/you/src/alpha"})]  ; no endpoint
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! disk-only))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! disk-only))))))
  (testing "a private entry carrying ONLY a :corpus-repo handle under a public
            owner is likewise refused (no legitimate use for it)"
    (let [repo-only (assoc-in clean-public-registry [:private :proj/alpha]
                              {:corpus-repo "git@example.com:org/alpha-private.git"})]
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! repo-only))))))
  (testing "the sharpest sub-case (a live transactor endpoint) still refuses,
            and is surfaced in :with-transactor-endpoint for the operator"
    (let [with-ep (assoc-in clean-public-registry [:private :proj/beta]
                            {:transactor-endpoint private-endpoint})]
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! with-ep))))
      (is (= [:proj/beta]
             (try (reg/assert-credential-air-gap! with-ep)
                  (catch clojure.lang.ExceptionInfo e
                    (:with-transactor-endpoint (ex-data e))))))))
  (testing "an EMPTY private map under a public owner is fine (no entries)"
    (is (nil? (reg/assert-credential-air-gap!
                (assoc clean-public-registry :private {}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; validate-registry! — the composite gate + fail-closed load.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-registry-composite
  (testing "clean public + clean private registries both validate (return self)"
    (is (= clean-public-registry (reg/validate-registry! clean-public-registry)))
    (is (= private-owner-registry (reg/validate-registry! private-owner-registry))))
  (testing "the composite refuses a credential-air-gap violation"
    (let [poisoned (assoc-in clean-public-registry [:private :proj/alpha]
                             {:transactor-endpoint private-endpoint :corpus-repo "x"})]
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! poisoned)))))))

(deftest fail-closed-loading
  (testing "a missing registry file REFUSES loudly (never a permissive default)"
    (is (= :registry-not-found
           (refusal-marker #(reg/load-registry "/no/such/store-registry.edn")))))
  (testing "unreadable EDN REFUSES loudly with :registry-parse-error"
    (is (= :registry-parse-error
           (refusal-marker #(reg/parse-registry "{:unbalanced ")))))
  (testing "well-formed EDN parses to the map"
    (is (= clean-public-registry (reg/parse-registry (pr-str clean-public-registry))))))
