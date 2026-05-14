(ns sandbar.search.analysis
  "Sandbar analyzer — tokenizer + Porter stemmer beneath the BM25F scorer.

  Pipeline: lowercase → split on `[\\p{L}\\p{N}]+` Unicode-aware letter/digit
  runs → ASCII-guarded Porter-stem.  Output: vector of normalized tokens.
  Same input produces same output byte-for-byte; required for stable
  IDF tables + term-frequency counts.

  Unicode-aware tokenizer; ASCII-guarded Porter stemmer.  Tokenizer
  splits on `[\\p{L}\\p{N}]+` (Java regex Unicode classes — any letter,
  any number), preserving Greek letters, diacritic'd proper names, and
  other non-ASCII content.  The Porter stemmer applies only when the
  token is pure ASCII letters/digits; non-ASCII tokens pass through
  unchanged because Porter is an English-morphology algorithm with no
  rules outside ASCII.

  Ported 2026-05-13 from the corpus's `etc/lib/analysis.clj`
  (Robertson-Zaragoza BM25F discipline; `decisions/unicode_tokenizer_ascii_stemmer.md`)
  to Sandbar substrate per
  `authorizations/move_bm25f_and_generic_primitives_to_sandbar_2026_05_13.md`.

  Porter stemmer source attribution:
  - Porter, M.F. 1980.  'An algorithm for suffix stripping.'
    Program 14(3): 130-137.
  - Definitive specification (Porter's own — more authoritative than the
    1980 paper per his statement at the linked URL):
    https://tartarus.org/martin/PorterStemmer/def.txt
  - Reference implementation:
    https://tartarus.org/martin/PorterStemmer/c.txt

  Per fulltext arc Stage 4a of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vowel / consonant predicates
;;
;; Porter's y-rule: y is a vowel iff preceded by a consonant.
;; This recursive definition terminates because each call is on
;; an earlier index.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- vowel-at?
  [^String s i]
  (let [c (.charAt s i)]
    (cond
      (#{\a \e \i \o \u} c) true
      (= \y c)              (and (pos? i) (not (vowel-at? s (dec i))))
      :else                 false)))

(defn- contains-vowel?
  "Condition *v* — stem contains at least one vowel."
  [^String s]
  (some #(vowel-at? s %) (range (count s))))

(defn- measure
  "Compute Porter's measure m: the number of VC blocks in the
   word's [C](VC){m}[V] decomposition.  Examples: tr → 0,
   tree → 0, troubles → 2, oaten → 2."
  [^String s]
  (let [n (count s)]
    (loop [i 0, prev-vowel? false, started? false, vc 0]
      (if (>= i n)
        vc
        (let [v? (vowel-at? s i)]
          (cond
            (not started?)               (recur (inc i) v? true vc)
            (and (not v?) prev-vowel?)   (recur (inc i) v? true (inc vc))
            :else                        (recur (inc i) v? true vc)))))))

(defn- ends-double-consonant?
  "Condition *d — last two letters are the same consonant."
  [^String s]
  (let [n (count s)]
    (and (>= n 2)
         (= (.charAt s (dec n)) (.charAt s (- n 2)))
         (not (vowel-at? s (dec n))))))

(defn- ends-cvc?
  "Condition *o — stem ends in cvc where the second c is not
   w, x, or y.  Used by the 1b post-processing and 5a rules."
  [^String s]
  (let [n (count s)]
    (and (>= n 3)
         (not (vowel-at? s (- n 3)))
         (vowel-at? s (- n 2))
         (not (vowel-at? s (dec n)))
         (not (#{\w \x \y} (.charAt s (dec n)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule application
;;
;; A rule is [suffix replacement condition?] where condition?
;; is a predicate on the prospective stem (the word minus the
;; suffix).  Within a step, rules are tried in spec order; the
;; first matching + condition-passing rule fires.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ends? [s suf] (str/ends-with? s suf))
(defn- stem-of [s suf] (subs s 0 (- (count s) (count suf))))

(defn- try-rules
  "Walk rules in order; return result of FIRST suffix match
   (whether or not its condition fires); else return s.

   Porter convention: only one rule per step can apply,
   determined by the first suffix that matches.  If the
   condition fails, the step is a no-op — rules with shorter
   subsuming suffixes are NOT retried.  This matches Porter's
   ANSI C reference, which uses if/else-if chains terminated
   by `break` after each suffix test."
  [s rules]
  (loop [rs rules]
    (if (empty? rs)
      s
      (let [[suf rep cond?] (first rs)]
        (if (ends? s suf)
          (let [stem (stem-of s suf)]
            (if (cond? stem)
              (str stem rep)
              s))
          (recur (rest rs)))))))

(def ^:private always (constantly true))
(defn- m>0 [stem] (pos?  (measure stem)))
(defn- m>1 [stem] (>     (measure stem) 1))
(defn- m=1 [stem] (=     (measure stem) 1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 1a — plurals
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +step-1a+
  [["sses" "ss" always]
   ["ies"  "i"  always]
   ["ss"   "ss" always]
   ["s"    ""   always]])

(defn- step-1a [s] (try-rules s +step-1a+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 1b — past tense + gerund, with the famous post-processing
;;
;; If the -ed or -ing rule fires, run a clean-up pass: -at/-bl/-iz
;; get an -e appended; trailing double consonants (not l/s/z) get
;; one letter dropped; short stems ending in cvc get an -e appended.
;; This is what makes "running" → "run" rather than "runn", and
;; "filing" → "file" rather than "fil".
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- step-1b-cleanup
  [^String s]
  (let [n (count s)
        last-char (when (pos? n) (.charAt s (dec n)))]
    (cond
      (ends? s "at") (str s "e")
      (ends? s "bl") (str s "e")
      (ends? s "iz") (str s "e")
      (and (ends-double-consonant? s)
           (not (#{\l \s \z} last-char)))   (subs s 0 (dec n))
      (and (m=1 s) (ends-cvc? s))           (str s "e")
      :else s)))

(defn- step-1b
  [s]
  (cond
    ;; EED matched: fire iff m>0 on stem.  No fallthrough to
    ;; ED/ING even if condition fails — Porter convention.
    (ends? s "eed")
    (if (m>0 (stem-of s "eed"))
      (str (stem-of s "eed") "ee")
      s)

    ;; ED or ING matched (mutually exclusive on a single word):
    ;; fire iff stem contains a vowel.  No fallthrough.
    (or (ends? s "ed") (ends? s "ing"))
    (let [stripped (if (ends? s "ed")
                     (stem-of s "ed")
                     (stem-of s "ing"))]
      (if (contains-vowel? stripped)
        (step-1b-cleanup stripped)
        s))

    :else s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 1c — terminal y → i (when stem contains a vowel)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +step-1c+ [["y" "i" contains-vowel?]])

(defn- step-1c [s] (try-rules s +step-1c+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 2 — derivational suffix folding (m>0)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +step-2+
  [["ational" "ate"  m>0]
   ["tional"  "tion" m>0]
   ["enci"    "ence" m>0]
   ["anci"    "ance" m>0]
   ["izer"    "ize"  m>0]
   ["abli"    "able" m>0]
   ["alli"    "al"   m>0]
   ["entli"   "ent"  m>0]
   ["eli"     "e"    m>0]
   ["ousli"   "ous"  m>0]
   ["ization" "ize"  m>0]
   ["ation"   "ate"  m>0]
   ["ator"    "ate"  m>0]
   ["alism"   "al"   m>0]
   ["iveness" "ive"  m>0]
   ["fulness" "ful"  m>0]
   ["ousness" "ous"  m>0]
   ["aliti"   "al"   m>0]
   ["iviti"   "ive"  m>0]
   ["biliti"  "ble"  m>0]])

(defn- step-2 [s] (try-rules s +step-2+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 3 — further derivation (m>0)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +step-3+
  [["icate" "ic" m>0]
   ["ative" ""   m>0]
   ["alize" "al" m>0]
   ["iciti" "ic" m>0]
   ["ical"  "ic" m>0]
   ["ful"   ""   m>0]
   ["ness"  ""   m>0]])

(defn- step-3 [s] (try-rules s +step-3+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 4 — terminal abstract suffixes (m>1).  ION is special-cased:
;; only fires if the stem-after-removal ends in s or t.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ion-condition
  [^String stem]
  (and (m>1 stem)
       (pos? (count stem))
       (#{\s \t} (.charAt stem (dec (count stem))))))

(def ^:private +step-4+
  [["al"    "" m>1]
   ["ance"  "" m>1]
   ["ence"  "" m>1]
   ["er"    "" m>1]
   ["ic"    "" m>1]
   ["able"  "" m>1]
   ["ible"  "" m>1]
   ["ant"   "" m>1]
   ["ement" "" m>1]
   ["ment"  "" m>1]
   ["ent"   "" m>1]
   ["ion"   "" ion-condition]
   ["ou"    "" m>1]
   ["ism"   "" m>1]
   ["ate"   "" m>1]
   ["iti"   "" m>1]
   ["ous"   "" m>1]
   ["ive"   "" m>1]
   ["ize"   "" m>1]])

(defn- step-4 [s] (try-rules s +step-4+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 5 — final cleanup
;;
;; 5a: drop terminal -e if (m>1) or (m=1 and not *o)
;; 5b: collapse final -ll to -l if (m>1 and *d and ends in l)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- step-5a
  [s]
  (if (ends? s "e")
    (let [stem (stem-of s "e")]
      (cond
        (m>1 stem)                        stem
        (and (m=1 stem)
             (not (ends-cvc? stem)))      stem
        :else                             s))
    s))

(defn- step-5b
  [^String s]
  (let [n (count s)]
    (if (and (>= n 2)
             (= \l (.charAt s (dec n)))
             (ends-double-consonant? s)
             (m>1 (subs s 0 (dec n))))
      (subs s 0 (dec n))
      s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ascii-letters-or-digits?
  "True when every character in s is in the ASCII range
   `[a-z0-9]`.  The Porter algorithm is defined only on ASCII
   English; non-ASCII input gets passed through the stemmer
   unchanged."
  [^String s]
  (every? (fn [^Character c]
            (let [n (int c)]
              (or (and (>= n (int \a)) (<= n (int \z)))
                  (and (>= n (int \0)) (<= n (int \9))))))
          s))

(defn stem
  "Apply the full Porter algorithm to a single token.
   Pass-through cases:
   - blank/nil → \"\"
   - length < 3 → input unchanged (Porter convention; short
     words don't benefit from stemming and the rules can
     produce degenerate output)
   - non-ASCII content → input unchanged (Porter's rules don't
     apply outside ASCII letters)"
  [^String s]
  (cond
    (str/blank? s)               (or s "")
    (< (count s) 3)              s
    (not (ascii-letters-or-digits? s)) s
    :else                        (-> s
                                     step-1a step-1b step-1c
                                     step-2  step-3
                                     step-4
                                     step-5a step-5b)))

(defn raw-tokens
  "Tokenize without stemming: lowercase + Unicode letter/digit runs.
   `[\\p{L}\\p{N}]+` covers ASCII alphanumerics, Greek letters,
   diacritic'd Latin, etc.  Punctuation, dashes, arrows, and
   symbols remain token boundaries.  Useful for tests + for
   entity-linking-style exact-match paths."
  [s]
  (if (str/blank? s)
    []
    (re-seq #"[\p{L}\p{N}]+" (str/lower-case s))))

(defn tokenize
  "Full analyzer pipeline: lowercase → ASCII alphanum split → stem.
   Returns a vector of stemmed tokens.  Empty input → empty vec."
  [s]
  (mapv stem (raw-tokens s)))
