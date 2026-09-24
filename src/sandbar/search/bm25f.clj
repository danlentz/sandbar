(ns sandbar.search.bm25f
  "Canonical multi-field BM25F analysis, corpus statistics and scoring.

  analyze-entity builds per-field token frequencies and lengths; corpus-stats
  computes population size, document frequencies and average field lengths;
  score applies the kernel to distinct analyzed query terms. Field extraction
  follows model declarations and may resolve reference targets. The statistics
  and scoring calculations operate on the resulting analyzed values.

  For a query term, normalize each field frequency by its field length,
  multiply by its field weight, sum across fields, then saturate the combined
  frequency. Summing independently saturated field scores is a different
  function. Reference target fields contribute selected text at one hop;
  their numeric weights are not recursively propagated.

  The construction follows Robertson and Zaragoza (2009), The Probabilistic
  Relevance Framework: BM25 and Beyond, section 3.6 (multi-stream BM25F), and
  Robertson, Zaragoza and Taylor (2004), Simple BM25 extension to multiple
  weighted fields. Sandbar uses k1=1.2 and b=0.75. See
  doc/concepts/fulltext-search.md for the exact implemented formula."
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

(declare ref-target-text)

(defn- raw-field
  "Extract text for slot-ident from an entity map, or nil when absent.
  Many string values become a space-joined string. For supported references,
  the declared target class selects primitive text fields through its BM25F
  declaration; their text is concatenated into this referring field.
  Expansion is one hop. The referring field's weight applies; target numeric
  weights are not multiplied into this text or propagated recursively."
  [entity-map slot-ident]
  (let [v (get entity-map slot-ident)]
    (cond
      (string? v)     v
      ;; Card-many slots — Datomic returns them as sets (for refs) or
      ;; sequences (for primitives); cover both via `coll?` excluding maps.
      (and (coll? v) (not (map? v)))
                      (or (let [strs (filter string? v)]
                            (when (seq strs)
                              (clojure.string/join " " strs)))
                          (ref-target-text slot-ident v))
      ;; Datomic returns ident-bearing references as keywords, including
      ;; card-one refs. Resolve them just like numeric eids.
      (or (number? v) (keyword? v)
          (and (associative? v) (:db/id v)))
                      (ref-target-text slot-ident v)
      :else           nil)))

(defn- ref-target-text
  "If `slot-ident` is a ref slot pointing at a class with `:dt/bm25f-weights`,
  resolve the value(s) to target entity-maps and return the concatenated
  text from each target's declared BM25F fields. Returns nil for non-ref slots,
  refs to classes without weights, or unresolvable refs.

  This is the substrate-quality primitive for typed-edge participation
  in BM25F: ANY class can declare a ref slot in its `:dt/bm25f-weights`
  and text from the target class's selected primitive fields joins the
  source field. Target field weights select fields but their numeric values
  do not propagate into this text. Two-deep traversal would
  require an explicit recursion-depth parameter; current implementation
  is one level (target's primitive slots only)."
  [slot-ident value]
  (let [target-class (dt/range-of slot-ident)]
    (when (and (keyword? target-class)
               (not= "db.type" (namespace target-class)))
      (when-let [target-weights (dt/effective-bm25f-weights-of target-class)]
        (let [refs        (cond
                            (nil? value)                  nil
                            (and (coll? value)
                                 (not (associative? value))) (seq value)  ; set/seq of refs
                            :else                          [value])
              target-maps (keep (fn [r]
                                  (cond
                                    (associative? r) r
                                    (or (number? r) (keyword? r))
                                                     (try
                                                       (db/entity r)
                                                       (catch Throwable _ nil))
                                    :else            nil))
                                refs)
              target-texts (for [tm        target-maps
                                 [slot _w] target-weights
                                 :let      [v   (get tm slot)
                                            txt (cond
                                                  (string? v) v
                                                  ;; Card-many strings (Datomic returns as sets)
                                                  (and (coll? v) (not (associative? v)))
                                                  (let [strs (filter string? v)]
                                                    (when (seq strs)
                                                      (clojure.string/join " " strs)))
                                                  :else nil)]
                                 :when     txt]
                             txt)]
          (when (seq target-texts)
            (clojure.string/join " " target-texts)))))))

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

  Reads field set from `dt/effective-bm25f-weights-of class-ident` — substrate-
  quality discipline (no hardcoded consumer-class knowledge).  Slot
  values are extracted via `raw-field` which handles string +
  cardinality-many-string cases.  Non-string slots yield empty
  `{:tf {} :len 0}` so the scoring kernel can still compose."
  [class-ident entity-map]
  (let [weights (dt/effective-bm25f-weights-of class-ident)]
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
  load-bearing canonical-BM25F move (Robertson & Zaragoza 2009 §3.6).

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
  `dt/effective-bm25f-weights-of class-ident` or supplied per-query).  Required
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
