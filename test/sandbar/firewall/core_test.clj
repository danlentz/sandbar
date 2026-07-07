(ns sandbar.firewall.core-test
  "S7 BU-7 — falsification battery for the PURE firewall core (BU-1).

   Exercises `sandbar.firewall.core` in COMPLETE ISOLATION: no DB, no
   fixtures, no schema.  The core is DB-QUERY-FREE (its only dependency is
   clojure.set), so every assertion here is a plain-EDN Label × Label →
   verdict check — which is exactly the property that lets EP-1 (author-time),
   EP-3 (traverse-time), the S9 closure, and S10's emitter reuse it unchanged.

   Battery coverage (S7-PLAN §7):
   - T-8   card-many subset semantics (the ruled R2 generalization)
   - T-11  the four tie-permits? corners, pure (a/b refuse, c/d permit)
   - T-19  semantic identity of `violating-governed-edges` under a PURE-MAP
           resolver (the tests-resolver instance of the injected resolver)
   - T-24  the sensitivity binary the core CONSUMES (public→public / private→
           public / public→private / diamond), driving `firewall-permits?`
   - T-28b census-content invariant: the ref-typed :mm.memory/* edges that
           :mm/Context inherits via :mm/Meta are members of
           `governed-flow-slots` (the pure, DB-free half of the CA-3
           census-from-effective-slots assertion — the live-schema halves,
           census⊆effective + the drift-forcing effective⊆dispositioned
           direction, live in label_test)

   Per interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md
   — verification is tests + memorialization, not REPL verification.

   NB (BU-7 scope): the DB-touching battery (T-1..T-30) binds against
   `sandbar.firewall.label` / `.enforce` and the EP-1/EP-3 commit/traverse
   wiring in `sandbar.db.datatype` — production namespaces BU-2/BU-3/BU-4/BU-5,
   which HAVE landed in this worktree.  Those deftests therefore live in their
   sibling files (`ep1_commit_path_test`, `ep3_traverse_test`, `tie_test`,
   `label_test`, `identity_test`) alongside `sandbar.db.ref-test`.  THIS file is
   deliberately DB-free: it exercises the pure core (BU-1) in isolation — no DB,
   no fixtures, no schema — which is exactly the property that lets EP-1 / EP-3 /
   S9 / S10 reuse it unchanged."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [sandbar.firewall.core :as fw]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Label constructors (plain maps — the §1.1 contract).  Eids are opaque
;; integers here; the pure core never resolves them, it only set-compares
;; :contexts and reads :sensitivity, so integer stand-ins are faithful.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ctx-home 1001)
(def ctx-work 1002)
(def ctx-a    1003)
(def ctx-b    1004)
(def unassigned-ctx 9999)

(defn pub
  "A public label in contexts `ctxs` (default the empty set — the public
   bottom needs no compartment)."
  ([] (pub #{}))
  ([ctxs] {:sensitivity :public :contexts (set ctxs) :project 1}))

(defn priv
  "A private label visible in compartment set `ctxs`."
  [ctxs] {:sensitivity :private :contexts (set ctxs) :project 2})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-24 (consumed by the core) — the three charter directions + generalizations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest firewall-permits-charter-directions
  (testing "public → public : lateral within the bottom → PERMIT"
    (is (true? (fw/firewall-permits? (pub) (pub)))))

  (testing "private any-context → public : the whole point → PERMIT"
    (is (true? (fw/firewall-permits? (priv #{ctx-home}) (pub))))
    (is (true? (fw/firewall-permits? (priv #{ctx-home ctx-work}) (pub #{ctx-a})))))

  (testing "public → private : the core rule → REFUSE"
    (is (false? (fw/firewall-permits? (pub) (priv #{ctx-home})))))

  (testing "private {ctxH} → private {ctxH} : intra-context (across projects) → PERMIT"
    ;; The projects differ (:project 2 both here, but the core ignores :project
    ;; for the flow predicate — it keys ONLY on :sensitivity + :contexts), so
    ;; two private memories sharing a context permit regardless of project.
    (is (true? (fw/firewall-permits? (priv #{ctx-home}) (priv #{ctx-home})))))

  (testing "private {ctxA} → private {ctxB} : the diamond, held out → REFUSE"
    (is (false? (fw/firewall-permits? (priv #{ctx-a}) (priv #{ctx-b}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-8 — card-many SUBSET semantics (the ruled R2 generalization)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest card-many-subset-semantics
  (testing "src {home} ⊆ tgt {home work} : target at least as visible → PERMIT"
    (is (true? (fw/firewall-permits? (priv #{ctx-home})
                                     (priv #{ctx-home ctx-work})))))

  (testing "src {home work} ⊄ tgt {home} : a work-visible source may not depend
            on home-only material → REFUSE (the leak-sound direction, R2)"
    (is (false? (fw/firewall-permits? (priv #{ctx-home ctx-work})
                                      (priv #{ctx-home})))))

  (testing "equal singleton sets degenerate to the W1 scalar rule → PERMIT"
    (is (true? (fw/firewall-permits? (priv #{ctx-home}) (priv #{ctx-home})))))

  (testing "disjoint compartments (neither a subset) → REFUSE both directions"
    (is (false? (fw/firewall-permits? (priv #{ctx-a}) (priv #{ctx-b}))))
    (is (false? (fw/firewall-permits? (priv #{ctx-b}) (priv #{ctx-a}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fail-closed: unknown/malformed sensitivity never masquerades as public
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest firewall-permits-fail-closed
  (testing "UNASSIGNED singleton → UNASSIGNED singleton : one private
            compartment, pre-migration corpus keeps working → PERMIT"
    (is (true? (fw/firewall-permits? (priv #{unassigned-ctx})
                                     (priv #{unassigned-ctx})))))

  (testing "UNASSIGNED → private {home} : fail-closed until assigned → REFUSE"
    (is (false? (fw/firewall-permits? (priv #{unassigned-ctx})
                                      (priv #{ctx-home})))))

  (testing "an unrecognized :sensitivity value on the target is NOT public →
            falls to the subset clause"
    ;; tgt not :public ⇒ clause 1 fails; src must be :private AND subset.
    (is (false? (fw/firewall-permits? (pub) {:sensitivity :bogus :contexts #{}})))
    ;; a private src whose contexts subset an unknown-sensitivity tgt still
    ;; needs the subset to hold; empty ⊆ anything, so this permits — the point
    ;; is only that :bogus is treated as NOT-public (never a leak upward).
    (is (true? (fw/firewall-permits? (priv #{})
                                     {:sensitivity :bogus :contexts #{ctx-home}}))))

  (testing "a nil / missing-key label reads private (not public) on the tgt side"
    (is (false? (fw/firewall-permits? (pub) nil)))
    (is (false? (fw/firewall-permits? (pub) {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11 (pure legs) — the four tie-permits? corners
;;
;; tie-permits? takes (context-side-label project-side-label) and REFUSES iff
;; the context side is PUBLIC and the project side is PRIVATE.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tie-permits-four-corners
  (testing "(a) public-bottom context ∋ private project → REFUSE"
    (is (false? (fw/tie-permits? (pub) (priv #{ctx-home})))))

  (testing "(b) same refusal is symmetric — the WRITING end doesn't matter;
            a private project tying to a public-bottom context is the SAME
            forbidden pair (public compartment ∋ private member)"
    ;; Regardless of which slot carried the write, the orientation into
    ;; (ctx-side, proj-side) is (public, private) → REFUSE.
    (is (false? (fw/tie-permits? (pub) (priv #{ctx-work})))))

  (testing "(c) deadlock regression: a private project ADDING a second private
            context → PERMIT (both ends private — membership, not flow)"
    (is (true? (fw/tie-permits? (priv #{ctx-a}) (priv #{ctx-home ctx-work}))))
    (is (true? (fw/tie-permits? (priv #{ctx-a ctx-b}) (priv #{ctx-home})))))

  (testing "(d) co-load shape: private context ∋ public project → PERMIT"
    (is (true? (fw/tie-permits? (priv #{ctx-home}) (pub)))))

  (testing "public ↔ public → PERMIT"
    (is (true? (fw/tie-permits? (pub) (pub)))))

  (testing "fail-closed: an unknown/nil label on either end reads private, so
            the refusal fires ONLY on an AFFIRMATIVELY-public compartment"
    ;; ctx-side unknown ⇒ private ⇒ never the (public,private) refusal.
    (is (true? (fw/tie-permits? nil (priv #{ctx-home}))))
    (is (true? (fw/tie-permits? {:sensitivity :bogus} (priv #{ctx-home}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Census membership — `firewall-governed-ref?` + set contents (T-28b pure half)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest census-membership
  (testing "flow slots are governed"
    (is (fw/firewall-governed-ref? :mm.memory/cites))
    (is (fw/firewall-governed-ref? :mm.memory/owning-project)))

  (testing "carrier slots are governed"
    (is (fw/firewall-governed-ref? :mm.context/cites))
    (is (fw/firewall-governed-ref? :mm.memory/introduced-in)))

  (testing "tie slots are governed"
    (is (fw/firewall-governed-ref? :mm.project/runs-in-context))
    (is (fw/firewall-governed-ref? :mm.context/visible-projects)))

  (testing "exempt / unknown slots are NOT governed"
    (is (not (fw/firewall-governed-ref? :mm.memory/tags)))
    (is (not (fw/firewall-governed-ref? :mm.memory/part-of)))
    (is (not (fw/firewall-governed-ref? :mm.project/code-repo)))
    (is (not (fw/firewall-governed-ref? :some.novel/edge)))))

(deftest census-includes-inherited-context-refs
  (testing "T-28b (pure half): the ref-typed :mm.memory/* edges that :mm/Context
            inherits via :mm/Context → :mm/Meta → :mm/Memory are members of
            governed-flow-slots — so the SAME keyword governs the edge whether
            it sits on a plain Memory or an inheriting Context (CA-3).  The
            live-schema half (census == effective slots-of after full load)
            lands in label_test once BU-2 provides the effective-slot fixture."
    (doseq [slot [:mm.memory/cites :mm.memory/related :mm.memory/refines
                  :mm.memory/motivated-by :mm.memory/motivates
                  :mm.memory/composes-with :mm.memory/supersedes
                  :mm.memory/superseded-by :mm.memory/informs
                  :mm.memory/informed-by :mm.memory/implements
                  :mm.memory/conforms-to :mm.memory/descends-from
                  :mm.memory/evidenced-by :mm.memory/evidences
                  :mm.memory/documented-by :mm.memory/documents
                  :mm.memory/triggered-by :mm.memory/touches
                  :mm.memory/owning-project]]
      (is (contains? fw/governed-flow-slots slot)
          (str slot " must be in governed-flow-slots"))))

  (testing "the three governed sets are pairwise disjoint (no slot double-classed)"
    (is (empty? (set/intersection fw/governed-flow-slots fw/governed-carrier-slots)))
    (is (empty? (set/intersection fw/governed-flow-slots fw/governed-tie-slots)))
    (is (empty? (set/intersection fw/governed-carrier-slots fw/governed-tie-slots))))

  (testing "governed and exempt sets are disjoint (no slot both governed + exempt)"
    (is (empty? (set/intersection fw/governed-flow-slots fw/exempt-slots)))
    (is (empty? (set/intersection fw/governed-carrier-slots fw/exempt-slots)))
    (is (empty? (set/intersection fw/governed-tie-slots fw/exempt-slots)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-19 (pure-map resolver instance) — `violating-governed-edges` closure finder
;;
;; This is the tests-resolver row of the five-resolver table (§1.4): the core
;; is exercised with `(fn [ref] (get label-map ref))`.  The SAME core function
;; is what EP-1/EP-3/S9 call with a DB-backed resolver; proving it here on a
;; pure map is the semantic-identity anchor (the DB-backed identity legs land
;; in identity_test once BU-2/BU-3 provide `label-of`).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest violating-governed-edges-flow
  (let [src (pub)                                  ; a public source
        tgt-priv (priv #{ctx-home})
        tgt-pub  (pub)
        label-map {:t-priv tgt-priv :t-pub tgt-pub}
        resolve  (fn [ref] (get label-map ref))]

    (testing "a public source citing a PRIVATE target → one flow-forbidden Verdict"
      (let [{:keys [violations skipped]}
            (fw/violating-governed-edges src [[:mm.memory/cites :t-priv]] resolve)]
        (is (= 1 (count violations)))
        (is (empty? skipped))
        (let [v (first violations)]
          (is (false? (:permitted? v)))
          (is (= :flow-forbidden (:reason v)))
          (is (= :mm.memory/cites (:slot v)))
          (is (= :t-priv (:target-ref v)))
          (is (= src (:src-label v)))
          (is (= tgt-priv (:tgt-label v))))))

    (testing "a public source citing a PUBLIC target → no violation"
      (let [{:keys [violations skipped]}
            (fw/violating-governed-edges src [[:mm.memory/cites :t-pub]] resolve)]
        (is (empty? violations))
        (is (empty? skipped))))

    (testing "a private→public edge (the allowed direction) → no violation"
      (let [{:keys [violations]}
            (fw/violating-governed-edges (priv #{ctx-home})
                                         [[:mm.memory/cites :t-pub]] resolve)]
        (is (empty? violations))))

    (testing "an EXEMPT slot to a private target contributes nothing"
      (let [{:keys [violations skipped]}
            (fw/violating-governed-edges src [[:mm.memory/tags :t-priv]] resolve)]
        (is (empty? violations))
        (is (empty? skipped))))

    (testing "an unresolvable governed target → a :skipped record, not a violation"
      (let [{:keys [violations skipped]}
            (fw/violating-governed-edges src [[:mm.memory/cites :nonexistent]] resolve)]
        (is (empty? violations))
        (is (= 1 (count skipped)))
        (is (= {:slot :mm.memory/cites :target-ref :nonexistent :reason :unresolved}
               (first skipped)))))))

(deftest violating-governed-edges-tie
  (let [pub-ctx  (pub)
        priv-proj (priv #{ctx-home})
        label-map {:pub-ctx pub-ctx :priv-proj priv-proj :pub-proj (pub)}
        resolve  (fn [ref] (get label-map ref))]

    (testing "runs-in-context (src=Project, tgt=Context): a PRIVATE project
              tying to a PUBLIC-bottom context → tie-forbidden"
      ;; edge-verdict orients :mm.project/runs-in-context as (tgt=ctx, src=proj)
      (let [{:keys [violations]}
            (fw/violating-governed-edges priv-proj
                                         [[:mm.project/runs-in-context :pub-ctx]]
                                         resolve)]
        (is (= 1 (count violations)))
        (is (= :tie-forbidden (:reason (first violations))))))

    (testing "visible-projects (src=Context, tgt=Project): a PUBLIC-bottom
              context listing a PRIVATE project → tie-forbidden"
      (let [{:keys [violations]}
            (fw/violating-governed-edges pub-ctx
                                         [[:mm.context/visible-projects :priv-proj]]
                                         resolve)]
        (is (= 1 (count violations)))
        (is (= :tie-forbidden (:reason (first violations))))))

    (testing "co-load shape: a PRIVATE context listing a PUBLIC project → PERMIT"
      (let [{:keys [violations]}
            (fw/violating-governed-edges priv-proj
                                         [[:mm.context/visible-projects :pub-proj]]
                                         resolve)]
        (is (empty? violations))))))

(deftest violating-governed-edges-mixed-batch
  (testing "a mixed edge-seq accumulates violations + skips + ignores exempt,
            preserving edge order in the reduce"
    (let [src (pub)
          tgt-priv (priv #{ctx-home})
          resolve  (fn [ref] ({:priv tgt-priv :pub (pub)} ref))
          {:keys [violations skipped]}
          (fw/violating-governed-edges
            src
            [[:mm.memory/cites :priv]        ; violation
             [:mm.memory/tags :priv]         ; exempt → nothing
             [:mm.memory/refines :missing]   ; governed but unresolvable → skip
             [:mm.memory/implements :pub]]   ; governed, permitted → nothing
            resolve)]
      (is (= 1 (count violations)))
      (is (= :mm.memory/cites (:slot (first violations))))
      (is (= 1 (count skipped)))
      (is (= :mm.memory/refines (:slot (first skipped)))))))
