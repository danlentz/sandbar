(ns sandbar.search.analysis-test
  "Tests for sandbar.search.analysis — Stage 4a of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Validates the ported Porter stemmer + Unicode-aware tokenizer against:
  - Porter's canonical step-by-step examples (def.txt + reference impl)
  - Pass-through edge cases (blank, short, non-ASCII)
  - Tokenizer Unicode behavior (Greek, diacritics, mixed scripts)
  - Pipeline composition (raw-tokens → tokenize → stem)"
  (:require [clojure.test :refer :all]
            [sandbar.search.analysis :as analysis]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Porter canonical examples
;; Drawn from https://tartarus.org/martin/PorterStemmer/def.txt + paper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stem-step-1a-plurals-test
  (testing "Step 1a plural rules — sses → ss, ies → i, ss → ss, s → ε"
    (is (= "caress"   (analysis/stem "caresses")))
    (is (= "poni"     (analysis/stem "ponies")))
    (is (= "caress"   (analysis/stem "caress")))
    (is (= "cat"      (analysis/stem "cats")))))

(deftest stem-step-1b-past-tense-and-gerund-test
  (testing "Step 1b — eed/ed/ing"
    (is (= "feed"     (analysis/stem "feed")))
    (is (= "agre"     (analysis/stem "agreed")))    ; eed m>0 → ee
    (is (= "plaster"  (analysis/stem "plastered"))) ; ed; contains vowel
    (is (= "bled"     (analysis/stem "bled")))      ; ed; no vowel in stem; passthrough
    (is (= "motor"    (analysis/stem "motoring")))  ; ing; contains vowel
    (is (= "sing"     (analysis/stem "sing"))))     ; ing; no vowel in stem
  (testing "Step 1b cleanup composed with later steps — final stem reflects full pipeline"
    ;; "conflated": step-1b cleanup makes "conflate" (at-rule); step-5a then strips the e (m=2 > 1).
    (is (= "conflat" (analysis/stem "conflated")))
    ;; "troubled": step-1b cleanup makes "trouble" (bl-rule); step-5a strips e (m=1, not cvc).
    (is (= "troubl"  (analysis/stem "troubled")))
    ;; "sized": step-1b cleanup makes "size" (iz-rule); step-5a strips e... actually m=0 for "siz", so e stays.
    (is (= "size"    (analysis/stem "sized")))
    (is (= "hop"     (analysis/stem "hopping")))   ; ing → hopp → double-cons drop → hop
    (is (= "tan"     (analysis/stem "tanned")))    ; ed → tann → double-cons drop → tan
    (is (= "fall"    (analysis/stem "falling")))   ; ing → fall → double-cons but ll, no drop
    (is (= "hiss"    (analysis/stem "hissing"))))  ; ing → hiss → double-cons but ss, no drop
  (testing "Step 1b cleanup — cvc + m=1 appends e (fil → file)"
    (is (= "file"    (analysis/stem "filing")))))

(deftest stem-step-1c-terminal-y-test
  (testing "Step 1c terminal y → i if stem contains vowel"
    (is (= "happi"    (analysis/stem "happy")))
    (is (= "sky"      (analysis/stem "sky")))))     ; y stays; no vowel in stem

(deftest stem-step-2-derivational-test
  (testing "Step 2 — derivational suffix folding (m>0)"
    (is (= "relat"    (analysis/stem "relational")))      ; ational → ate, then step-4
    (is (= "condit"   (analysis/stem "conditional")))     ; tional → tion, then step-4
    (is (= "valenc"   (analysis/stem "valenci")))         ; enci → ence
    (is (= "hesit"    (analysis/stem "hesitanci")))       ; anci → ance, then step-4
    (is (= "digit"    (analysis/stem "digitizer")))       ; izer → ize, then step-4
    (is (= "conform"  (analysis/stem "conformabli")))     ; abli → able, then step-4
    (is (= "radic"    (analysis/stem "radicalli")))       ; alli → al, then step-4
    (is (= "differ"   (analysis/stem "differentli")))     ; entli → ent, then step-4
    (is (= "vile"     (analysis/stem "vileli")))          ; eli → e, then step-5a
    (is (= "analog"   (analysis/stem "analogousli")))     ; ousli → ous, then step-4
    (is (= "vietnam"  (analysis/stem "vietnamization")))  ; ization → ize, then step-4
    (is (= "predic"   (analysis/stem "predication")))     ; ation → ate, then step-4
    (is (= "oper"     (analysis/stem "operator")))        ; ator → ate, then step-4
    (is (= "feudal"   (analysis/stem "feudalism")))       ; alism → al
    (is (= "decis"    (analysis/stem "decisiveness")))    ; iveness → ive, then step-4
    ;; "hopefulness": step-2 fulness→ful gives "hopeful"; step-3 ful→ε gives "hope".
    (is (= "hope"     (analysis/stem "hopefulness")))
    (is (= "callous"  (analysis/stem "callousness")))     ; ousness → ous, no further match
    (is (= "formal"   (analysis/stem "formaliti")))       ; aliti → al
    (is (= "sensit"   (analysis/stem "sensitiviti")))     ; iviti → ive, then step-4
    (is (= "sensibl"  (analysis/stem "sensibiliti")))))   ; biliti → ble, then step-4

(deftest stem-step-3-further-derivation-test
  (testing "Step 3 — further derivation (m>0)"
    (is (= "triplic"  (analysis/stem "triplicate")))   ; icate → ic
    (is (= "form"     (analysis/stem "formative")))    ; ative → ε
    (is (= "formal"   (analysis/stem "formalize")))    ; alize → al
    ;; "electriciti": step-3 iciti→ic gives "electric"; step-4 ic→ε (m>1) gives "electr".
    (is (= "electr"   (analysis/stem "electriciti")))
    ;; "electrical": step-3 ical→ic gives "electric"; step-4 ic→ε gives "electr".
    (is (= "electr"   (analysis/stem "electrical")))
    (is (= "hope"     (analysis/stem "hopeful")))      ; ful → ε, then step-5a
    (is (= "good"     (analysis/stem "goodness")))))   ; ness → ε

(deftest stem-step-4-terminal-abstract-test
  (testing "Step 4 — terminal abstract suffixes (m>1)"
    (is (= "reviv"    (analysis/stem "revival")))      ; al → ε
    (is (= "allow"    (analysis/stem "allowance")))    ; ance → ε
    (is (= "infer"    (analysis/stem "inference")))    ; ence → ε
    (is (= "airlin"   (analysis/stem "airliner")))     ; er → ε
    (is (= "gyroscop" (analysis/stem "gyroscopic")))   ; ic → ε
    (is (= "adjust"   (analysis/stem "adjustable")))   ; able → ε
    (is (= "defens"   (analysis/stem "defensible")))   ; ible → ε
    (is (= "irrit"    (analysis/stem "irritant")))     ; ant → ε
    (is (= "replac"   (analysis/stem "replacement")))  ; ement → ε
    (is (= "adjust"   (analysis/stem "adjustment")))   ; ment → ε
    (is (= "depend"   (analysis/stem "dependent")))    ; ent → ε
    (is (= "adopt"    (analysis/stem "adoption")))     ; ion → ε (only s/t before)
    (is (= "homolog"  (analysis/stem "homologous")))   ; ous → ε
    (is (= "commun"   (analysis/stem "communism")))    ; ism → ε
    (is (= "activ"    (analysis/stem "activate")))     ; ate → ε
    (is (= "angular"  (analysis/stem "angulariti")))   ; iti → ε
    (is (= "effect"   (analysis/stem "effective")))    ; ive → ε
    (is (= "bowdler"  (analysis/stem "bowdlerize"))))) ; ize → ε

(deftest stem-step-5a-terminal-e-test
  (testing "Step 5a — drop terminal -e if m>1 or (m=1 and not *o)"
    (is (= "probat"   (analysis/stem "probate")))     ; m>1 → drop e
    (is (= "rate"     (analysis/stem "rate")))        ; m=1 AND *o (rate-e=rat, r-a-t is cvc) → keep e
    ;; "cease": stem "ceas" has m=1 but is NOT *o (n-3=e is a vowel, so cvc-check fails);
    ;; step-5a's "m=1 AND NOT *o" branch fires → drop e → "ceas".
    (is (= "ceas"     (analysis/stem "cease")))))

(deftest stem-step-5b-double-l-test
  (testing "Step 5b — collapse final ll to l if m>1"
    ;; "controll": measure of stem "control" is 2 (m>1) so step-5b fires → "control".
    (is (= "control" (analysis/stem "controll")))
    ;; "roll": measure of stem "rol" is 1 (m=1, not m>1) so step-5b doesn't fire → "roll".
    (is (= "roll"    (analysis/stem "roll")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pass-through edge cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stem-pass-through-test
  (testing "Blank/nil → empty string"
    (is (= "" (analysis/stem "")))
    (is (= "" (analysis/stem nil)))
    (is (= "   " (analysis/stem "   "))))           ; whitespace is blank
  (testing "Length < 3 → unchanged"
    (is (= "a"  (analysis/stem "a")))
    (is (= "is" (analysis/stem "is")))
    (is (= "go" (analysis/stem "go"))))
  (testing "Non-ASCII content → unchanged (Porter is ASCII-only)"
    (is (= "café"       (analysis/stem "café")))      ; diacritic
    (is (= "naïve"      (analysis/stem "naïve")))     ; diaeresis
    (is (= "αβγ"        (analysis/stem "αβγ")))       ; Greek
    (is (= "数据库"      (analysis/stem "数据库")))))   ; CJK

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tokenizer behavior — Unicode + punctuation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest raw-tokens-basic-test
  (testing "raw-tokens splits on non-letter/digit runs and lowercases"
    (is (= ["hello" "world"]    (analysis/raw-tokens "Hello, World!")))
    (is (= ["foo" "bar"]        (analysis/raw-tokens "foo-bar")))
    (is (= ["abc" "123" "def"]  (analysis/raw-tokens "abc 123 def")))
    (is (= ["abc123"]           (analysis/raw-tokens "abc123")))
    (is (= []                   (analysis/raw-tokens "")))
    (is (= []                   (analysis/raw-tokens "   ")))))

(deftest raw-tokens-unicode-test
  (testing "raw-tokens preserves Unicode letters + digits"
    (is (= ["café"]             (analysis/raw-tokens "café")))
    (is (= ["naïve" "pâté"]     (analysis/raw-tokens "naïve pâté")))
    (is (= ["αβγ" "δεζ"]        (analysis/raw-tokens "αβγ δεζ")))
    (is (= ["数据库" "搜索"]      (analysis/raw-tokens "数据库 搜索")))
    (is (= ["greek" "αβγ" "mix"]
           (analysis/raw-tokens "greek αβγ mix"))))
  (testing "raw-tokens lowercases ASCII; Unicode lowercased per java.lang.Character"
    (is (= ["café"]             (analysis/raw-tokens "Café")))
    (is (= ["αβγ"]              (analysis/raw-tokens "ΑΒΓ"))))) ; Greek uppercase → lowercase

(deftest raw-tokens-symbol-splits-test
  (testing "Punctuation, dashes, arrows split tokens"
    (is (= ["foo" "bar" "baz"]  (analysis/raw-tokens "foo->bar=>baz")))
    (is (= ["a" "b" "c"]        (analysis/raw-tokens "a.b.c")))
    (is (= ["x" "y"]            (analysis/raw-tokens "x/y")))
    (is (= ["one" "two"]        (analysis/raw-tokens "one_two")))))  ; underscore is not a letter

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pipeline composition — tokenize = raw-tokens + stem
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tokenize-pipeline-test
  (testing "tokenize lowercases, splits, and stems (Porter-not-lemmatize semantics)"
    ;; "running" stems to "run" via step-1b cleanup (double-cons drop).  Porter
    ;; doesn't lemmatize past tense; "running" doesn't go to "ran".
    (is (= ["the" "cat" "run"]
           (analysis/tokenize "The cats running")))
    (is (= ["a" "comput" "scienc" "code" "introduct"]
           (analysis/tokenize "A computer science coded introduction"))))
  (testing "tokenize empty/blank"
    (is (= [] (analysis/tokenize "")))
    (is (= [] (analysis/tokenize "   "))))
  (testing "tokenize is byte-stable (same input → same output)"
    (let [input "The quick brown foxes are jumping over lazy dogs"]
      (is (= (analysis/tokenize input) (analysis/tokenize input))))))

(deftest tokenize-mixed-ascii-unicode-test
  (testing "tokenize stems ASCII tokens; passes Unicode tokens through"
    ;; "cafés" is one non-ASCII token (the é blocks ASCII-only stem-guard);
    ;; passes through unchanged (does NOT split off trailing s).  "are"
    ;; stems to "ar" via step-5a (m=1, NOT cvc).  "opening" stems to "open"
    ;; via step-1b ing→open.
    (is (= ["cafés" "ar" "open"]
           (analysis/tokenize "Cafés are opening")))
    ;; "greek" stems to "greek" (no rules match).  "αβγ" passthrough.
    ;; "letters" → step-1a s→ε → "letter" → step-4 no → step-5a no → "letter".
    (is (= ["greek" "αβγ" "letter"]
           (analysis/tokenize "Greek αβγ letters")))))
