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
;; private-scope? — EXACTLY [:trust-scope/private <keyword>], nothing looser.
;;   The scope-key vocabulary every gate keys on: `route/trust-scope-of` emits
;;   a 2-element vector whose key is the KEYWORD :mm.project/ident
;;   (:db.type/keyword in schema; the sentinel :project/UNASSIGNED is a keyword
;;   too), and the docs say "exactly" — so a junk-tailed vector or a
;;   non-keyword key is NOT a private scope.  The pre-tightening form admitted
;;   [:trust-scope/private :k :extra :junk] and non-keyword keys, so a
;;   malformed owner could resolve, look up entries, and even satisfy
;;   reachable? against its own malformed twin.  Fail-closed now: malformed ⇒
;;   not a scope ⇒ every consumer refuses.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest private-scope-shape-is-exact
  (testing "the ONE well-formed shape: a 2-element vector with a KEYWORD key"
    (is (true? (reg/private-scope? [:trust-scope/private :proj/alpha])))
    (is (= :proj/alpha (reg/private-scope-key [:trust-scope/private :proj/alpha]))))
  (testing "EXACTLY means exactly — junk-tailed vectors are NOT private scopes
            (FAILS against the pre-tightening form, which admitted
            [:trust-scope/private :k :extra :junk])"
    (doseq [bad [[:trust-scope/private :proj/alpha :extra]
                 [:trust-scope/private :proj/alpha :extra :junk]]]
      (is (false? (reg/private-scope? bad)) (pr-str bad))
      (is (nil? (reg/private-scope-key bad))
          "a junk-tailed vector yields NO key — consumers must not honor it")))
  (testing "non-KEYWORD second elements are NOT private scopes (the project key
            is :mm.project/ident — :db.type/keyword in schema — and
            route/trust-scope-of emits keywords only; FAILS against the
            some?-only pre-tightening form)"
    (doseq [bad [[:trust-scope/private "alpha"]
                 [:trust-scope/private 42]
                 [:trust-scope/private [:proj/alpha]]
                 [:trust-scope/private {:key :proj/alpha}]]]
      (is (false? (reg/private-scope? bad)) (pr-str bad))))
  (testing "short / bare / nil-keyed / non-vector shapes stay refused"
    (doseq [bad [[:trust-scope/private nil]
                 [:trust-scope/private]
                 :trust-scope/private
                 '(:trust-scope/private :proj/alpha)   ; a LIST is not the vector shape
                 nil]]
      (is (false? (reg/private-scope? bad)) (pr-str bad))))
  (testing "ripple — reachable?: a junk-tailed 'owner' reaches NOTHING, not even
            its own junk-tailed twin (pre-tightening, private-scope? admitted
            the junk tail and (= owner target) made twin→twin reachable —
            FAILS against that form)"
    (let [junk [:trust-scope/private :proj/alpha :junk]]
      (is (false? (reg/reachable? junk junk)))
      (is (false? (reg/reachable? junk [:trust-scope/private :proj/alpha])))
      (is (false? (reg/reachable? [:trust-scope/private :proj/alpha] junk)))))
  (testing "ripple — owner resolution: a junk-tailed / string-keyed :owner-scope
            is UNRESOLVED, and the composite refuses it loudly at bring-up
            (pre-tightening both shapes slipped past the owner gate)"
    (doseq [bad-owner [[:trust-scope/private :proj/alpha :junk]
                       [:trust-scope/private "alpha"]]]
      (let [r (assoc private-owner-registry :owner-scope bad-owner)]
        (is (false? (reg/owner-scope-resolved? r)) (pr-str bad-owner))
        (is (= :registry-owner-unresolved
               (refusal-marker #(reg/validate-registry! r)))
            (pr-str bad-owner)))))
  (testing "ripple — scope-entry: a junk-tailed target looks up NOTHING (the
            pre-tightening form resolved it to the :proj/alpha entry)"
    (is (nil? (reg/scope-entry private-owner-registry
                               [:trust-scope/private :proj/alpha :junk])))))

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
;; PORT DOCTRINE — the FORGEABLE A-1 hole the judge reproduced: a private-PORT
;;   transactor endpoint mislabeled into the :PUBLIC slot.  The credential
;;   air-gap sweeps :private ENTRIES and reachable? filters by SCOPE — NEITHER
;;   inspects the PORT — so a public owner would reach, and resolve-endpoint
;;   would hand out, the ruled-private 4336 port sitting in its own :public
;;   slot.  assert-endpoint-port-doctrine! (a validate-registry! gate) closes it
;;   across EVERY slot.  FAILS against the pre-fix impl whose :public slot port
;;   was unchecked.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest port-doctrine-refuses-forged-public-slot
  (let [forged {:schema-version 1
                :owner-scope :trust-scope/public
                :private {}
                :public {:transactor-endpoint private-endpoint}}]  ; ← 4336 in :public
    (testing "the exact gap: WITHOUT the port doctrine the forgery is reachable —
              the air-gap (sweeps :private only) PASSES it, and a public owner's
              reachable set / resolver surface the ruled-PRIVATE 4336 port
              straight from the :public slot"
      (is (nil? (reg/assert-credential-air-gap! forged))
          "air-gap does NOT catch a private-PORT endpoint mislabeled into :public")
      (is (contains? (reg/reachable-endpoints forged) private-endpoint)
          "public→public is reachable by SCOPE, so the private-port URL is on the
           surface of the UNVALIDATED map — WHY a PORT gate is needed, not only
           the scope air-gap")
      (is (= private-endpoint (reg/resolve-endpoint forged :trust-scope/public))
          "and the resolver would hand the public process the ruled-private port"))
    (testing "the port doctrine CLOSES it: assert-endpoint-port-doctrine! AND the
              validate-registry! composite REFUSE the forged public slot with
              :endpoint-port-doctrine-violation.  FAILS against the pre-fix impl
              with an unchecked :public slot"
      (is (= :endpoint-port-doctrine-violation
             (refusal-marker #(reg/assert-endpoint-port-doctrine! forged))))
      (is (= :endpoint-port-doctrine-violation
             (refusal-marker #(reg/validate-registry! forged)))
          "validate-registry! refuses the forged public slot")
      (let [data (try (reg/assert-endpoint-port-doctrine! forged)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= [:public] (mapv :slot (:violations data)))
            "the violation names the :public slot as the offender")
        (is (= [4336] (mapv :port (:violations data)))
            "and surfaces the ruled-private port 4336")))
    (testing "end-to-end: bring-up over a forged FILE ABORTS with the same marker
              (the A4 component/script must let it abort the process)"
      (let [dir (doto (java.io.File. "target") .mkdirs)
            tmp (java.io.File/createTempFile "forged-public-slot" ".edn" dir)]
        (try
          (spit tmp (pr-str forged))
          (is (= :endpoint-port-doctrine-violation
                 (refusal-marker #(reg/bring-up-registry! (str tmp)))))
          (finally (.delete tmp)))))))

(deftest port-doctrine-reverse-co-location-and-positive-control
  (testing "the SYMMETRIC leg: a :private slot endpoint on a PUBLIC transactor
            port (4334) — a private scope co-located on the public transactor —
            is REFUSED (DEP-1 one-transactor-per-scope).  FAILS against a
            :public-slot-only check"
    (let [co-located (assoc-in private-owner-registry
                               [:private :proj/alpha :transactor-endpoint]
                               public-endpoint)]  ; alpha's transactor on the PUBLIC 4334
      (is (= :endpoint-port-doctrine-violation
             (refusal-marker #(reg/assert-endpoint-port-doctrine! co-located))))
      (is (= :endpoint-port-doctrine-violation
             (refusal-marker #(reg/validate-registry! co-located))))
      (let [data (try (reg/assert-endpoint-port-doctrine! co-located)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= [:private] (mapv :slot (:violations data))))
        (is (= [4334] (mapv :port (:violations data)))))))
  (testing "POSITIVE control: the well-formed registries — public on 4334, private
            on 4336 — PASS the port doctrine (returns nil) and validate end-to-end,
            so the gate rejects nothing legitimate"
    (is (nil? (reg/assert-endpoint-port-doctrine! clean-public-registry)))
    (is (nil? (reg/assert-endpoint-port-doctrine! private-owner-registry)))
    (is (= clean-public-registry (reg/validate-registry! clean-public-registry)))
    (is (= private-owner-registry (reg/validate-registry! private-owner-registry))))
  (testing "the ONE retained asymmetry stays GREEN: a private owner holding a
            :public ENTRY on the PUBLIC port (4334) is COHERENT — the port
            doctrine passes it and validate-registry! returns it; the
            private→public live connect is refused at resolve-endpoint, not here"
    (let [alpha+public (assoc private-owner-registry :public
                              {:transactor-endpoint public-endpoint :sid "global"
                               :corpus-repo public-repo})]
      (is (nil? (reg/assert-endpoint-port-doctrine! alpha+public)))
      (is (= alpha+public (reg/validate-registry! alpha+public)))))
  (testing "endpoint-transactor-port parses the port for BOTH plain-host and
            bracketed-IPv6 forms, and returns nil for a genuinely portless /
            storage endpoint — the PARSER's contract (the GATE's fail-closed
            handling of a nil-port SLOT is pinned in
            port-doctrine-refuses-bracketed-ipv6-forgery below)"
    (is (= 4336 (reg/endpoint-transactor-port private-endpoint)))
    (is (= 4334 (reg/endpoint-transactor-port public-endpoint)))
    (is (= 4336 (reg/endpoint-transactor-port "datomic:dev://[::1]:4336/"))
        "bracketed IPv6 loopback: the port is read AFTER the ], not truncated at
         the first inner colon (FAILS against the ://[^/:]+:(\\d+) parser)")
    (is (= 4334 (reg/endpoint-transactor-port "datomic:dev://[2001:db8::1]:4334/"))
        "bracketed IPv6 global form parses too")
    (is (nil? (reg/endpoint-transactor-port "datomic:mem://scratch")))
    (is (nil? (reg/endpoint-transactor-port "datomic:dev://localhost/nodb")))
    (is (nil? (reg/endpoint-transactor-port "datomic:dev://[::1]/nodb"))
        "a bracketed IPv6 WITHOUT a :PORT suffix carries no port")
    (is (nil? (reg/endpoint-transactor-port nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PORT DOCTRINE — bracketed-IPv6 A-1 re-opening (the round-4 hole).  The round-2
;;   fix closed the STRING-host forgery (localhost:4336 in :public), but the port
;;   parser's host class [^/:]+ STOPS at the first colon — so a bracketed-IPv6
;;   endpoint datomic:dev://[::1]:4336/ read a nil port, and the gate SKIPPED
;;   nil-port slots (fail-OPEN), so the private-port forgery slipped straight
;;   back in via the IPv6 bracket form (in BOTH slot directions).  The fix parses
;;   the bracketed-IPv6 port correctly AND fail-CLOSES on any unparseable port.
;;   Verified over the FULL chain (validate-registry! → reachable-endpoints →
;;   resolve-endpoint), both loopback [::1] and global [2001:db8::1] forms, both
;;   directions, plus positive controls so the fix is not over-broad.  FAILS
;;   against the round-2 impl (nil-port bracket parse + nil-port skip).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest port-doctrine-refuses-bracketed-ipv6-forgery
  (let [ipv6-loopback "datomic:dev://[::1]:4336/"           ; PRIVATE port, loopback
        ipv6-global   "datomic:dev://[2001:db8::1]:4336/"]  ; PRIVATE port, global form
    (testing "FORWARD leg — a PRIVATE-port (4336) bracketed-IPv6 endpoint in the
              :PUBLIC slot is REFUSED across the FULL chain, both the loopback
              [::1] and global [2001:db8::1] forms.  FAILS against the round-2
              impl whose parser returned nil for the bracket form and whose gate
              skipped the nil-port slot"
      (doseq [ep [ipv6-loopback ipv6-global]]
        (let [forged (assoc clean-public-registry :public {:transactor-endpoint ep})]
          ;; the parser now reads the ruled-private port straight out of the
          ;; bracketed IPv6 host (the crux the round-2 [^/:]+ class missed)
          (is (= 4336 (reg/endpoint-transactor-port ep)) ep)
          (is (= :endpoint-port-doctrine-violation
                 (refusal-marker #(reg/validate-registry! forged)))
              (str "validate-registry! must refuse " ep " in the :public slot"))
          (is (= :endpoint-port-doctrine-violation
                 (refusal-marker #(reg/assert-endpoint-port-doctrine! forged))))
          (let [data (try (reg/assert-endpoint-port-doctrine! forged)
                          (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= [:public] (mapv :slot (:violations data)))
                "the :public slot is named the offender")
            (is (= [4336] (mapv :port (:violations data)))
                "the PARSED private port 4336 is surfaced, not nil")
            (is (= [:scope-class-mismatch] (mapv :reason (:violations data)))
                "a parsed-but-wrong-class port is a scope-class-mismatch"))
          ;; the UNVALIDATED forged map still surfaces it (public→public by
          ;; SCOPE) — exactly why validate-registry! must run + refuse first
          (is (contains? (reg/reachable-endpoints forged) ep)
              "the forged IPv6 private-port URL is on the UNVALIDATED public surface")
          (is (= ep (reg/resolve-endpoint forged :trust-scope/public))
              "and resolve-endpoint would hand the public process the private port"))))
    (testing "REVERSE leg — a PUBLIC-port (4334) bracketed-IPv6 endpoint in a
              :PRIVATE slot is REFUSED too (the same nil-port skip exempted it
              before): a private scope co-located on the public transactor (DEP-1)"
      (doseq [ep ["datomic:dev://[::1]:4334/" "datomic:dev://[2001:db8::1]:4334/"]]
        (let [co-located (assoc-in private-owner-registry
                                   [:private :proj/alpha :transactor-endpoint] ep)]
          (is (= 4334 (reg/endpoint-transactor-port ep)) ep)
          (is (= :endpoint-port-doctrine-violation
                 (refusal-marker #(reg/validate-registry! co-located)))
              (str "validate-registry! must refuse " ep " in the :private slot"))
          (let [data (try (reg/assert-endpoint-port-doctrine! co-located)
                          (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= [:private] (mapv :slot (:violations data))))
            (is (= [4334] (mapv :port (:violations data))))
            (is (= [:scope-class-mismatch] (mapv :reason (:violations data))))))))
    (testing "POSITIVE controls — the fix is NOT over-broad: a legitimate IPv6
              PUBLIC endpoint on 4334 in the :public slot AND a legitimate IPv6
              PRIVATE endpoint on 4336 in the :private slot BOTH still VALIDATE
              (return self) and resolve to their own scope"
      (let [pub-ipv6 (assoc clean-public-registry :public
                            {:transactor-endpoint "datomic:dev://[::1]:4334/"
                             :sid "global" :corpus-repo public-repo
                             :local-disk-path "/Users/you/claude"})]
        (is (nil? (reg/assert-endpoint-port-doctrine! pub-ipv6)))
        (is (= pub-ipv6 (reg/validate-registry! pub-ipv6)))
        (is (= "datomic:dev://[::1]:4334/"
               (reg/resolve-endpoint pub-ipv6 :trust-scope/public))))
      (let [priv-ipv6 (assoc-in private-owner-registry
                                [:private :proj/alpha :transactor-endpoint]
                                "datomic:dev://[2001:db8::1]:4336/")]
        (is (nil? (reg/assert-endpoint-port-doctrine! priv-ipv6)))
        (is (= priv-ipv6 (reg/validate-registry! priv-ipv6)))
        (is (= "datomic:dev://[2001:db8::1]:4336/"
               (reg/resolve-endpoint priv-ipv6 [:trust-scope/private :proj/alpha])))))
    (testing "FAIL-CLOSED — a PRESENT endpoint whose port cannot be parsed at all
              (portless / bracketed-IPv6-without-port / not-even-a-URL) in a slot
              is REFUSED with :unparseable-port, never SKIPPED — the safe default
              that stops any endpoint form the parser cannot place from slipping
              the gate.  FAILS against the (some? port) fail-OPEN skip"
      (doseq [bad ["datomic:mem://scratch"                  ; genuinely portless
                   "datomic:dev://localhost/nodb"           ; dev, no port
                   "datomic:dev://[::1]/nodb"               ; bracketed IPv6, no :PORT
                   "not-even-a-uri"]]
        (let [reg (assoc clean-public-registry :public {:transactor-endpoint bad})]
          (is (= :endpoint-port-doctrine-violation
                 (refusal-marker #(reg/validate-registry! reg)))
              (str "a present-but-unparseable slot endpoint must fail CLOSED: " bad))
          (let [data (try (reg/assert-endpoint-port-doctrine! reg)
                          (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= [:unparseable-port] (mapv :reason (:violations data)))
                (str "the violation reason is :unparseable-port for " bad))
            (is (= [nil] (mapv :port (:violations data)))
                "the surfaced port is nil (nothing could be parsed)")))))
    (testing "a slot with NO :transactor-endpoint at all stays EXEMPT from the
              port gate (that is resolve-endpoint's :no-such-scope concern) — the
              fail-closed rule fires ONLY on a PRESENT-but-unparseable endpoint"
      (let [no-ep (assoc clean-public-registry :public {:sid "global"})]
        (is (nil? (reg/assert-endpoint-port-doctrine! no-ep))
            "an endpoint-less :public slot places no port in the doctrine")))))

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
;; air-gap PRIVATE↔PRIVATE leg — a PRIVATE-owner registry ([:trust-scope/private
;;   k]) refuses ANY :private entry keyed to a scope OTHER than k.  alpha's
;;   registry names ONLY alpha's location, never beta's (W1.deploy §1 / brief
;;   line 28: "separation holds across every public↔private AND private↔private
;;   boundary").  Same ENTRY-LEVEL breadth as the public leg — the foreign entry
;;   is refused whether it bears a :transactor-endpoint or only a disk/repo
;;   handle.  FAILS against an impl that gates ONLY the public direction.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest air-gap-refuses-foreign-private-entry-under-private-owner
  (testing "the clean R9 posture stays GREEN: an alpha-owner registry carrying
            ONLY its own :proj/alpha entry + :public-corpus-ref passes the
            air-gap AND validate-registry! returns itself (positive control)"
    (is (nil? (reg/assert-credential-air-gap! private-owner-registry)))
    (is (= private-owner-registry (reg/validate-registry! private-owner-registry))))
  (testing "an alpha-owner registry carrying beta's FULL entry is REFUSED at BOTH
            assert-credential-air-gap! AND validate-registry! with
            :credential-air-gap-violation, naming :proj/beta (FAILS against an
            impl that gates only the PUBLIC direction and lets a private process
            hold a FOREIGN private scope's location — private↔private §1)"
    (let [alpha+beta (assoc-in private-owner-registry [:private :proj/beta]
                               {:transactor-endpoint "datomic:dev://localhost:4338/"
                                :sid "beta"
                                :corpus-repo "git@example.com:org/beta-private.git"
                                :local-disk-path "/Users/you/src/beta"})]
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! alpha+beta))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! alpha+beta))))
      (is (= [:proj/beta]
             (try (reg/assert-credential-air-gap! alpha+beta)
                  (catch clojure.lang.ExceptionInfo e (:private-scope-keys (ex-data e)))))
          "ex-data names the FOREIGN key, NOT the owner's own :proj/alpha")
      (is (= :private-owner-holds-foreign-private
             (try (reg/assert-credential-air-gap! alpha+beta)
                  (catch clojure.lang.ExceptionInfo e (:air-gap-direction (ex-data e)))))
          "the private leg is disambiguated from the public leg by :air-gap-direction")))
  (testing "ENTRY-LEVEL, not endpoint-only: a beta entry bearing ONLY a
            :local-disk-path (NO transactor endpoint) under an alpha owner is
            STILL REFUSED at both entry points — a foreign scope's disk handle is
            as much a disclosure as its endpoint (FAILS against an endpoint-only
            private leg)"
    (let [alpha+beta-disk (assoc-in private-owner-registry [:private :proj/beta]
                                    {:local-disk-path "/Users/you/src/beta"})]  ; no endpoint
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! alpha+beta-disk))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! alpha+beta-disk))))
      (is (= []
             (try (reg/assert-credential-air-gap! alpha+beta-disk)
                  (catch clojure.lang.ExceptionInfo e (:with-transactor-endpoint (ex-data e)))))
          "a disk-only foreign entry refuses with an EMPTY :with-transactor-endpoint
           sub-case (it carried no live endpoint) — the refusal is entry-level")))
  (testing "the sharpest sub-case — a foreign beta entry WITH a live transactor
            endpoint — surfaces :proj/beta in :with-transactor-endpoint"
    (let [alpha+beta-ep (assoc-in private-owner-registry [:private :proj/beta]
                                  {:transactor-endpoint "datomic:dev://localhost:4338/"})]
      (is (= [:proj/beta]
             (try (reg/assert-credential-air-gap! alpha+beta-ep)
                  (catch clojure.lang.ExceptionInfo e (:with-transactor-endpoint (ex-data e))))))))
  (testing "the ONE retained, INTENDED asymmetry: an alpha-owner registry holding
            a :public transactor ENTRY (alongside its own :proj/alpha) is
            permitted-but-unreachable — the air-gap does NOT refuse it and
            validate-registry! returns it (public handles are shared-bottom, not
            secrets; R9 private-only is a recommendation), yet the live
            private→public connect is STILL refused at resolve-endpoint"
    (let [alpha+public (assoc private-owner-registry :public
                              {:transactor-endpoint public-endpoint :sid "global"
                               :corpus-repo public-repo})]
      (is (nil? (reg/assert-credential-air-gap! alpha+public))
          "the private-holds-public posture passes the air-gap BY DESIGN")
      (is (= alpha+public (reg/validate-registry! alpha+public))
          "and validates end-to-end (permitted-but-unreachable, not refused)")
      (is (= :cross-scope-connection-refused
             (refusal-marker #(reg/resolve-endpoint alpha+public :trust-scope/public)))
          "but the live public connect REFUSES at the resolver boundary (A-2)"))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; nil-keyed :private entry — the pathological NO-KEY entry is FOREIGN under
;;   EVERY owner (the explicit allowed-key SET partition in
;;   foreign-private-entries — a public owner's allowed set is #{}, so nil is
;;   not in it; a private owner's is #{k}, so nil is not in it either).
;;   Dedicated pin for the 2026-07-10 make-up exam's named coverage gap:
;;   correct-by-source, now test-locked.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest nil-keyed-private-entry-is-foreign
  (testing "under a PUBLIC owner: a nil-keyed private entry (endpoint-bearing)
            is enumerated FOREIGN and REFUSED at every entry point.  FAILS
            against a bare not=-on-owner-key partition that special-cases nil
            instead of the allowed-SET containment"
    (let [nil-keyed (assoc-in clean-public-registry [:private nil]
                              {:transactor-endpoint private-endpoint
                               :corpus-repo "git@example.com:org/mystery.git"
                               :local-disk-path "/Users/you/src/mystery"})]
      (is (= [nil] (mapv first (reg/foreign-private-entries nil-keyed)))
          "foreign-private-entries names the nil key itself")
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! nil-keyed))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! nil-keyed)))
          "and the bring-up composite refuses it end-to-end")
      (let [data (try (reg/assert-credential-air-gap! nil-keyed)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :public-owner-holds-private (:air-gap-direction data))
            "the public leg is the direction that fired")
        (is (= [nil] (:private-scope-keys data))
            "ex-data surfaces the nil key, not a scrubbed/empty key list")
        (is (= [nil] (:with-transactor-endpoint data))
            "the sharpest (live-endpoint) sub-case surfaces the nil-keyed entry too"))
      (is (not (contains? (reg/reachable-endpoints nil-keyed) private-endpoint))
          "A-1 holds regardless: the nil-keyed private endpoint is never on the
           public connection surface even before the loud refusal")))
  (testing "under a PRIVATE owner (alpha): the SAME nil-keyed entry is foreign
            (nil ∉ #{:proj/alpha}) — refused on the private↔private leg, and
            ENTRY-LEVEL (a disk-only nil-keyed entry still refuses)"
    (let [nil-keyed (assoc-in private-owner-registry [:private nil]
                              {:local-disk-path "/Users/you/src/mystery"})]
      (is (= [nil] (mapv first (reg/foreign-private-entries nil-keyed))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/assert-credential-air-gap! nil-keyed))))
      (is (= :credential-air-gap-violation
             (refusal-marker #(reg/validate-registry! nil-keyed))))
      (let [data (try (reg/assert-credential-air-gap! nil-keyed)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :private-owner-holds-foreign-private (:air-gap-direction data)))
        (is (= [nil] (:private-scope-keys data)))
        (is (= [] (:with-transactor-endpoint data))
            "disk-only ⇒ empty endpoint sub-case: the refusal is entry-level")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DISCOVERY (the ruled read seam) — SANDBAR_STORE_REGISTRY env override, else
;;   the per-project file under the process's start directory (fork-5).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest registry-path-resolution-seam
  (testing "the env seam OVERRIDES when set + non-blank (per-machine ops, R2)"
    (is (= "/somewhere/custom-registry.edn"
           (reg/registry-path "/somewhere/custom-registry.edn" "/Users/you/src/alpha"))))
  (testing "unset / blank env falls back to the PER-PROJECT file under the
            directory the process started from (fork-5: the project's OWN
            registry — per-project, never shared)"
    (doseq [absent [nil "" "   "]]
      (is (= "/Users/you/src/alpha/etc/sandbar-store-registry.edn"
             (reg/registry-path absent "/Users/you/src/alpha")))))
  (testing "the 0-arity resolves against the live env + user.dir to SOME path
            without throwing (shape-only: existence is load-registry's job —
            resolution never invents a permissive default)"
    (is (string? (reg/registry-path)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; bring-up-registry! — the ONE call the A4-ruled in-process component /
;;   bring-up script makes at process start: resolve → load → validate.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest bring-up-composite-load-validate
  (let [dir (doto (java.io.File. "target") .mkdirs)
        tmp (java.io.File/createTempFile "store-registry-test" ".edn" dir)]
    (try
      (testing "bring-up over a CLEAN registry file returns the validated map
                (validate-registry! invoked at start — the A4 posture's call)"
        (spit tmp (pr-str clean-public-registry))
        (is (= clean-public-registry (reg/bring-up-registry! (str tmp)))))
      (testing "bring-up over a POISONED file ABORTS with the air-gap marker —
                the component/script must let this abort the process, so a
                misconfigured registry can never quietly serve"
        (spit tmp (pr-str (assoc-in clean-public-registry [:private :proj/alpha]
                                    {:transactor-endpoint private-endpoint})))
        (is (= :credential-air-gap-violation
               (refusal-marker #(reg/bring-up-registry! (str tmp))))))
      (finally (.delete tmp))))
  (testing "bring-up over a MISSING path refuses loudly (:registry-not-found —
            fail-closed; a process with no registry file does not come up open)"
    (is (= :registry-not-found
           (refusal-marker #(reg/bring-up-registry! "/no/such/registry.edn")))))
  (testing "docs==behavior: the SHIPPED public template passes every bring-up
            gate as-committed (edn read takes the FIRST form — the public
            registry map; the private companion is commented prose)"
    (let [tpl (reg/bring-up-registry! "etc/sandbar-store-registry.example.edn")]
      (is (= :trust-scope/public (reg/owner-scope tpl)))
      (is (= {} (:private tpl)) "the public template carries NO private entry")
      (is (= #{"datomic:dev://localhost:4334/"} (reg/reachable-endpoints tpl))
          "and its connection surface is exactly the public 4334 endpoint"))))
