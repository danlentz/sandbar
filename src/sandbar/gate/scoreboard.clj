(ns sandbar.gate.scoreboard
  "CHECK 2 of the W1.J release gate — the firewall attack scoreboard.

   Three batteries compose the v1 8-attack scoreboard + the four
   absence probes (W1 arc plan §W1.H acceptance):

   A. DIRECTIONAL ATTACKS (mechanical, LIVE today — the b72fd99 directional
      firewall in the write+traverse plane): public→private REFUSED,
      cross-private REFUSED, {:validate? false} still firewalls, and a
      legacy forbidden edge traverses as :blocked with NO :target leaked.
      These fire against a seeded compartment world (mirrors
      ceremony8_composition_test).

   B. ABSENCE PROBES (physical exclusion — the side-channel closure), each
      with a cleared-session NEGATIVE CONTROL: entity.find a private slug →
      MISSING; aggregate count over the private project → zero; tag-histogram
      → private-only bin absent; BM25F on the private-only term → zero-hit +
      zero-IDF.  The uncleared (public-scope) DB is built from the PUBLIC
      store only; the cleared DB from BOTH stores.  Building the uncleared DB
      by simply not loading the private store is the SEAM standing in for
      W1.deploy's closure-bounded `db-firewall-closure` build — when that
      lands, the closure produces this same uncleared DB automatically and
      NONE of the probe assertions change (that stability is why W1.J is
      authored early).

   C. DISCIPLINARY RESIDUAL (documented, NOT claimed mechanical): content-leak
      via novel private terminology.  Per §1.3 truth #1 the mechanical firewall
      cannot catch UNSEEN private terminology in public prose; this is
      backstopped by isolation (absence) + an authorship-provenance flag +
      the declassification/sanitize gate.  The scoreboard reports it as
      DOCUMENTED-DISCIPLINARY (an accepted outcome per the plan — 'provably
      prevented OR explicitly documented-as-disciplinary-residual with its
      backstop named'), never as mechanically green.

   GREEN iff: every directional attack is REFUSED, every absence probe shows
   absence-in-uncleared AND presence-in-cleared (the negative control), and
   the residual is present + documented."
  (:require [clojure.string     :as str]
            [datomic.api        :as d]
            [sandbar.aggregate  :as agg]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.gate.db    :as gdb]
            [sandbar.gate.fixture :as fx]
            [sandbar.gate.roundtrip :as rt]
            [sandbar.search     :as search]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; refusal predicate + safe runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fw-violation?
  "True iff `thunk` throws the directional firewall (EP-1) refusal — an
   ex-info whose :errors carry a :firewall-violation (mirrors
   ceremony8_composition_test)."
  [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))))))

(defn- safe
  "Run `thunk`; return its value, or `{::error msg}` on throw — a probe
   error becomes a RED row, never a gate crash."
  [thunk]
  (try (thunk) (catch Throwable e {::error (.getMessage e)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A. DIRECTIONAL ATTACKS — mechanical, live (seeded compartment world)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-directional-attacks!
  "Seed the compartment world + run the mechanical directional battery.
   Returns a vector of attack rows.  Self-contained (its own mem DB)."
  []
  (gdb/with-fresh-db* {:name "fw-directional"}
    (fn []
      (fx/seed-directional-world!)
      (let [priv-eid (fx/eid-of :mem/privA-target)
            rows
            [{:id :ATK-1 :name "directional-inversion (public->private cite)"
              :plane :mechanical :enforced-today true
              :pass (fw-violation?
                      #(dt/make :mm/Memory
                                {:mm.memory/name "atk1-leaker"
                                 :mm.memory/visibility :public
                                 :mm.memory/owning-project :proj/pub
                                 :mm.memory/cites priv-eid}))
              :expect "REFUSED on the edge"}
             ;; cross-private diamond (privA -> privB) — needs a 2nd private ctx
             (let [_ (fx/seed-context! :ctx/workB :project-isolated)
                   _ (fx/seed-project! :proj/privB :private :ctx/workB)
                   privB (fx/seed-memory! :mem/privB-target :private :proj/privB)]
               {:id :ATK-2 :name "cross-private diamond (privA->privB cite)"
                :plane :mechanical :enforced-today true
                :pass (fw-violation?
                        #(dt/make :mm/Memory
                                  {:mm.memory/name "atk2-diamond"
                                   :mm.memory/visibility :private
                                   :mm.memory/owning-project :proj/privA
                                   :mm.memory/cites privB}))
                :expect "REFUSED (cross-compartment)"})
             {:id :ATK-3 :name "validate?:false bypass"
              :plane :mechanical :enforced-today true
              :pass (fw-violation?
                      #(dt/make :mm/Memory
                                {:mm.memory/name "atk3-raw-leaker"
                                 :mm.memory/visibility :public
                                 :mm.memory/owning-project :proj/pub
                                 :mm.memory/cites priv-eid}
                                {:validate? false}))
              :expect "firewall floor STILL fires"}
             ;; EP-3 traverse of a LEGACY forbidden edge — blocked, no :target
             (let [_ (fx/raw-transact!
                       [{:db/ident :mem/legacy-pub-src :dt/type :mm/Memory
                         :mm.memory/name "atk4-legacy"
                         :mm.memory/visibility :public
                         :mm.memory/owning-project :proj/pub
                         :mm.memory/cites priv-eid}])
                   edges (safe #(dt/outbound-edges-of (fx/eid-of :mem/legacy-pub-src)))
                   cites (when (sequential? edges)
                           (filter #(= :mm.memory/cites (:predicate %)) edges))]
               {:id :ATK-4 :name "traverse-hop leak (EP-3 legacy edge)"
                :plane :mechanical :enforced-today true
                :pass (boolean (and (seq cites)
                                    (every? :blocked cites)
                                    (not-any? #(contains? % :target) cites)))
                :expect ":blocked hop, NO :target key leaked"})]]
        (mapv #(assoc % :verdict (if (:pass %) :PASS :FAIL)) rows)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B. ABSENCE PROBES — physical exclusion, two-store fixture + neg. control
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ident-present?
  "True iff `ident` resolves to an entity in the current db (entity.find
   presence signal)."
  [ident]
  (some? (d/q '[:find ?e . :in $ ?id :where [?e :db/ident ?id]] (db/db) ident)))

(defn- discover-private-slug
  "The :db/ident of a private (Zephyrite) memory in the current db, or nil."
  []
  (d/q '[:find ?id . :in $ ?tok
         :where [?e :db/ident ?id] [?e :mm.memory/name ?n]
                [(clojure.string/includes? ?n ?tok)]]
       (db/db) fx/private-name-token))

(defn- corpus-doc-frequency
  "Index-free document frequency of `term` (case-insensitive) across
   :mm.memory/body-raw — the timing-independent zero-IDF signal (df 0 ⇒
   the term is not in the corpus vocabulary at all ⇒ IDF undefined/zero)."
  [term]
  (or (d/q '[:find (count ?e) . :in $ ?term
             :where [?e :mm.memory/body-raw ?b]
                    [(clojure.string/lower-case ?b) ?lb]
                    [(clojure.string/includes? ?lb ?term)]]
           (db/db) (str/lower-case term))
      0))

(defn- capture-absence-signals
  "Capture the four absence signals from the current db.  `known-slug`, when
   supplied, is the private slug discovered in the cleared DB (so the
   uncleared entity.find probes the SAME ident); otherwise it is discovered
   here (the cleared pass)."
  [known-slug]
  (let [slug (or known-slug (discover-private-slug))]
    {:private-slug slug
     :entity-find  (when slug (ident-present? slug))
     :agg-count    (safe #(:count (agg/count-by
                                    {:class :mm/Memory
                                     :where [['?e :mm.memory/name '?n]
                                             [(list 'clojure.string/includes?
                                                    '?n fx/private-name-token)]]})))
     :tag-present  (safe #(boolean (some (fn [b] (= fx/private-tag-value (:value b)))
                                         (:histogram (agg/tag-histogram {})))))
     :bm25-hits    (safe #(:total (search/search-bm25f
                                    {:class :mm/Memory :query fx/private-term})))
     :bm25-df      (safe #(corpus-doc-frequency fx/private-term))}))

(defn run-absence-probes!
  "Build the cleared (public+private) and uncleared (public-only) DBs, run
   the four absence probes against each, and return the four probe rows —
   each carrying the uncleared signal, the cleared negative-control signal,
   and a PASS iff (absent-in-uncleared AND present-in-cleared)."
  []
  (let [cleared   (gdb/with-fresh-db* {:name "abs-cleared"}
                    (fn []
                      (rt/import-corpus! (fx/store-dir :public-corpus))
                      (rt/import-corpus! (fx/store-dir :second-project))
                      (fx/type-corpus-tags!)  ; canonicalize free-text tags → typed :mm/Tag
                      (capture-absence-signals nil)))
        priv-slug (:private-slug cleared)
        uncleared (gdb/with-fresh-db* {:name "abs-uncleared"}
                    (fn []
                      (rt/import-corpus! (fx/store-dir :public-corpus))
                      (fx/type-corpus-tags!)  ; uncleared: only public tags exist to type
                      (capture-absence-signals priv-slug)))
        row (fn [id nm absent? present? uncl cl expect]
              {:id id :name nm :plane :physical-exclusion :enforced-today :seam
               :uncleared uncl :cleared cl
               :absent-in-uncleared? absent? :present-in-cleared? present?
               :pass (boolean (and absent? present?))
               :verdict (if (and absent? present?) :PASS :FAIL)
               :expect expect})]
    [(row :ABS-1 "entity.find private slug"
          (false? (:entity-find uncleared)) (true? (:entity-find cleared))
          (:entity-find uncleared) (:entity-find cleared)
          "uncleared MISSING / cleared FOUND")
     (row :ABS-2 "aggregate count over private project"
          (= 0 (:agg-count uncleared)) (and (number? (:agg-count cleared))
                                            (pos? (:agg-count cleared)))
          (:agg-count uncleared) (:agg-count cleared)
          "uncleared 0 / cleared >0")
     (row :ABS-3 "tag-histogram private-only bin"
          (= false (:tag-present uncleared)) (= true (:tag-present cleared))
          (:tag-present uncleared) (:tag-present cleared)
          "uncleared bin absent / cleared present")
     (row :ABS-4 "BM25F private-only term (zero-hit + zero-IDF)"
          (and (= 0 (:bm25-hits uncleared)) (= 0 (:bm25-df uncleared)))
          (and (number? (:bm25-hits cleared)) (pos? (:bm25-hits cleared))
               (number? (:bm25-df cleared))   (pos? (:bm25-df cleared)))
          {:hits (:bm25-hits uncleared) :df (:bm25-df uncleared)}
          {:hits (:bm25-hits cleared)   :df (:bm25-df cleared)}
          "uncleared zero-hit+zero-IDF / cleared hits+df")]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; C. DISCIPLINARY RESIDUAL — documented, not mechanically claimed
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def content-leak-residual
  "The v1 attack the firewall does NOT claim to prevent mechanically."
  {:id :ATK-CONTENT :name "content-leak via novel private terminology"
   :plane :disciplinary :enforced-today false
   :verdict :DOCUMENTED-DISCIPLINARY
   :pass true ; a documented residual is an ACCEPTED scoreboard outcome
   :claim "NOT prevented mechanically (a denylist cannot catch UNSEEN terms)"
   :backstop "isolation (absence) + authorship-provenance flag + declassification/sanitize gate"
   :ref "W1-PROVENANCE-ARC-PLAN-DRAFT §1.3 truth #1 / §W1.H"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; compose the scoreboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run
  "Run CHECK 2.  Returns
     {:pass bool
      :directional-attacks [...]  ; A — 4 mechanical rows
      :absence-probes      [...]  ; B — 4 physical-exclusion rows (w/ neg control)
      :disciplinary-residual {...}} ; C — 1 documented row
   Green iff every directional attack + every absence probe passes."
  []
  (let [attacks  (run-directional-attacks!)
        absence  (run-absence-probes!)]
    {:pass (and (every? :pass attacks) (every? :pass absence))
     :directional-attacks attacks
     :absence-probes absence
     :disciplinary-residual content-leak-residual}))
