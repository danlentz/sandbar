(ns sandbar.search.bm25f
  "BM25F retrieval scoring — canonical multi-field BM25 per
  Robertson & Zaragoza (2009) §3.4.  Sandbar substrate port from corpus
  per `authorizations/move_bm25f_and_generic_primitives_to_sandbar_2026_05_13.md`.

  Pipeline:

      entities  →  (mapv analyze-entity)  →  analyzed
                                          ↓
                                       corpus-stats  →  stats
                                          ↓
      query     →  (analysis/tokenize)   →  (score q-toks ae stats)

  `analyze-entity` is the per-entity analyzer (tokenize each weighted
  slot, frequencies, length).  Weights come from the entity's class
  `:dt/bm25f-weights` declaration via `dt/bm25f-weights-of` — no
  hardcoded consumer-class knowledge in the substrate.  `corpus-stats`
  aggregates corpus-wide N, df-by-term, avgdl-by-slot.  `score` is the
  scoring kernel.  All three are pure.

  Canonical form vs. per-field-weighted-sum:

      tilde_tf(t, D) = Σ_f w_f · tf(t, D_f) / (1 - b + b · |D_f|/avgdl_f)
      B_TF(t, D)     = (k1 + 1) · tilde_tf / (k1 + tilde_tf)
      score(Q, D)    = Σ_{t ∈ Q} IDF(t) · B_TF(t, D)

  Length-normalized TFs accumulate across fields BEFORE saturation —
  Robertson & Zaragoza 2009 §3.4.  An older variant (per-field BM25,
  weighted-summed) is sometimes called BM25F too but saturates each
  field independently; the difference matters when a query term appears
  across multiple curated fields.  See
  `decisions/bm25f_canonical_robertson_zaragoza_form.md`.

  References:
  - Robertson, S.E., Zaragoza, H. (2009).  'The Probabilistic Relevance
    Framework: BM25 and Beyond.'  Foundations and Trends in Information
    Retrieval 3(4): 333–389.  §3.2 + §3.4.
  - Robertson, S.E., Zaragoza, H., Taylor, M. (2004).  'Simple BM25
    extension to multiple weighted fields.'  CIKM '04.
  - Apache Lucene `BM25Similarity` — JVM canonical reference for
    parameter defaults (k1=1.2, b=0.75).

  Ported 2026-05-13 from `etc/lib/bm25f.clj` (Robertson-Zaragoza canonical)
  to Sandbar substrate.  Field-extraction adapted from corpus-frontmatter-
  shape to metamodel-driven via `dt/bm25f-weights-of` per substrate-quality
  discipline (no hardcoded consumer-class knowledge).

  Per fulltext arc Stage 4b of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [sandbar.db.datatype  :as dt]
            [sandbar.db.datomic   :as db]
            [sandbar.search.analysis :as a]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tunable parameters       [Robertson & Zaragoza 2009:§3.2 "BM25 PARAMETERS"]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +k1+
  "TF saturation parameter.  Higher k1 → more linear TF response;
  lower → faster saturation.  1.2 is the long-standing TREC default
  (Robertson & Zaragoza 2009 §3.2 cites 1.2–2.0 as typical) and matches
  Apache Lucene's `BM25Similarity` default."
  1.2)

(def ^:const +b+
  "Length-normalization parameter.  b=1 fully normalizes; b=0 disables.
  0.75 is the TREC default (Robertson & Zaragoza 2009 §3.2) and matches
  Lucene's default."
  0.75)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Field extraction — metamodel-driven (no hardcoded class knowledge)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- raw-field
  "Extract the raw text of `slot-ident` from `entity-map`.  Generic over
  Sandbar entity-map shape — no class-specific knowledge.  Returns nil
  if the slot is absent or holds a non-string value.

  Cardinality-many string-valued slots collapse to a space-joined
  string before tokenization (analogous to corpus's `:tags` handling).
  Non-string slot values (refs, keywords, instants, etc.) return nil —
  BM25F operates on string fields only."
  [entity-map slot-ident]
  (let [v (get entity-map slot-ident)]
    (cond
      (string? v)     v
      (sequential? v) (let [strs (filter string? v)]
                        (when (seq strs)
                          (clojure.string/join " " strs)))
      :else           nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-entity analysis
;;
;; Tokenization is the dominant cost per entity; we run it once and
;; cache term-frequencies + lengths in the analyzed form.  The analyzed
;; form is the kernel input for scoring + stats.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn analyze-entity
  "Tokenize each fulltext-weighted slot of `entity-map` per `class-ident`'s
  `:dt/bm25f-weights` declaration.  Returns the analyzed form used by
  the scoring kernel.

  Shape:

      {:entity  the original entity-map (back-reference for output)
       :eid     entity id (when available)
       :fields  {<slot-ident> {:tf {term count} :len token-count}}}

  Reads field set from `dt/bm25f-weights-of class-ident` — substrate-
  quality discipline (no hardcoded consumer-class knowledge).  Slot
  values are extracted via `raw-field` which handles string +
  cardinality-many-string cases.  Non-string slots yield empty
  `{:tf {} :len 0}` so the scoring kernel can still compose."
  [class-ident entity-map]
  (let [weights (dt/bm25f-weights-of class-ident)]
    {:entity entity-map
     :eid    (:db/id entity-map)
     :fields (into {}
                   (map (fn [slot-ident]
                          (let [tokens (a/tokenize (raw-field entity-map slot-ident))]
                            [slot-ident {:tf  (frequencies tokens)
                                         :len (count tokens)}])))
                   (keys weights))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Corpus statistics — N, df-by-term, avgdl-by-slot
;;
;; Computed once per corpus walk; reused for every query.  The df
;; accumulator counts entities containing the term (DOCUMENT frequency),
;; not term-occurrences (which is the per-entity tf).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- terms-in
  "Distinct terms appearing in any field of an analyzed entity."
  [analyzed-entity]
  (->> (:fields analyzed-entity)
       vals
       (mapcat (comp keys :tf))
       set))

(defn- field-idents-of
  "Union of all slot-idents appearing across the analyzed-entities'
  `:fields` maps.  Used to compute per-slot avgdl without assuming a
  single canonical field set."
  [analyzed-entities]
  (into #{}
        (mapcat (comp keys :fields))
        analyzed-entities))

(defn corpus-stats
  "Aggregate corpus-wide statistics needed for BM25F scoring.

      {:N      total entity count
       :df     {term doc-count}        ; document frequency
       :avgdl  {slot-ident avg-token-count}}

  Pure: same input always produces same output.  Slot set is the union
  across entities — supports heterogeneous-class corpora where different
  classes declare different `:dt/bm25f-weights` slot sets."
  [analyzed-entities]
  (let [n      (count analyzed-entities)
        df     (->> analyzed-entities
                    (mapcat terms-in)
                    frequencies)
        slots  (field-idents-of analyzed-entities)
        avgdl  (into {}
                     (for [slot slots]
                       (let [lens (map #(get-in % [:fields slot :len] 0)
                                       analyzed-entities)]
                         [slot (if (zero? n)
                                 0.0
                                 (/ (double (reduce + 0 lens)) n))])))]
    {:N n :df df :avgdl avgdl}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scoring kernel — byte-faithful port from corpus etc/lib/bm25f.clj
;;
;; idf:           Smoothed Robertson IDF (Lucene form), log(1 + ratio).
;; norm-tf:       per-field length-normalized term frequency.
;; tilde-tf:      cross-field weighted sum of norm-tf — Σ_f w_f · norm-tf_f.
;; b-tf:          saturation curve (k1+1) · tilde / (k1 + tilde).
;; score:         Σ_{t ∈ Q} IDF(t) · B_TF(t, D).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- idf
  "Robertson IDF, smoothed Lucene form: `log(1 + (N - df + 0.5) /
  (df + 0.5))`.

  Why the +1 smoothing rather than the original Robertson form
  `log((N - df + 0.5) / (df + 0.5))`:
  - The original is zero exactly at df = N/2 and negative for df > N/2.
    On small corpora this produces degenerate scores: a query term
    appearing in half the corpus gives every match a score of zero.
  - The +1 form is always > 0 (until df = N), preserves the same
    monotonic relationship in df, and avoids the zero-score corner case.
    Apache Lucene `BM25Similarity` uses this form; so do Elasticsearch,
    Solr, and Vespa.

  Returns 0 for unseen terms (df = 0)."
  [^long n ^long df-t]
  (if (zero? df-t)
    0.0
    (Math/log (+ 1.0 (/ (+ (- n df-t) 0.5)
                        (+ df-t   0.5))))))

(defn- norm-tf
  "Per-field length-normalized TF: tf / (1 - b + b · |D_f|/avgdl_f).
  Zero when tf is zero or the field is empty."
  [tf len avgdl-f]
  (cond
    (zero? tf)              0.0
    (or (nil? avgdl-f)
        (zero? avgdl-f))    (double tf)
    :else                   (/ (double tf)
                               (+ (- 1.0 +b+)
                                  (* +b+ (/ (double len)
                                            (double avgdl-f)))))))

(defn- tilde-tf
  "Cross-field weighted sum of length-normalized TFs.  This is
  `Σ_f w_f · norm-tf_f(t, D)` — accumulated BEFORE saturation, the
  load-bearing canonical-BM25F move (Robertson & Zaragoza 2009 §3.4).

  Fields absent from `field-weights` contribute zero."
  [term fields avgdl field-weights]
  (reduce-kv
    (fn [acc field weight]
      (let [{:keys [tf len]} (get fields field)]
        (+ acc (* weight (norm-tf (get tf term 0)
                                  len
                                  (get avgdl field))))))
    0.0
    field-weights))

(defn- b-tf
  "Saturation curve: (k1+1) · tilde / (k1 + tilde).  Bounded above by
  k1+1 as tilde → ∞; this is what makes BM25 robust to spam-style
  term-stuffing."
  [tilde]
  (if (zero? tilde)
    0.0
    (/ (* (+ +k1+ 1.0) tilde)
       (+ +k1+ tilde))))

(defn score
  "BM25F score for a tokenized query against one analyzed entity, given
  precomputed corpus stats.  Pure; deterministic.  Returns a non-negative
  double, 0.0 when no query term has any field-presence in the entity.

  `field-weights` is the per-slot weight map (e.g. from
  `dt/bm25f-weights-of class-ident` or supplied per-query).  Required
  argument — substrate does not hardcode defaults; consumers always pass
  weights derived from the class declaration or a per-query override."
  [query-tokens analyzed-entity stats field-weights]
  (let [{:keys [N df avgdl]} stats
        fields               (:fields analyzed-entity)]
    (reduce
      (fn [acc term]
        (+ acc (* (idf N (get df term 0))
                  (b-tf (tilde-tf term fields avgdl field-weights)))))
      0.0
      (distinct query-tokens))))

(defn score-explain
  "Same as `score` but returns the per-term breakdown for `--explain`
  mode.  The shape is intended for human reading and machine consumption
  alike."
  [query-tokens analyzed-entity stats field-weights]
  (let [{:keys [N df avgdl]} stats
        fields               (:fields analyzed-entity)
        per-term
        (vec
          (for [term (distinct query-tokens)
                :let [df-t  (get df term 0)
                      idf-v (idf N df-t)
                      tt    (tilde-tf term fields avgdl field-weights)
                      bt    (b-tf tt)]]
            {:term         term
             :df           df-t
             :idf          idf-v
             :tilde-tf     tt
             :b-tf         bt
             :contribution (* idf-v bt)}))]
    {:score (reduce + 0.0 (map :contribution per-term))
     :terms per-term}))
