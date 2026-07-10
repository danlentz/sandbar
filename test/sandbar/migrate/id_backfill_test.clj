(ns sandbar.migrate.id-backfill-test
  "Unit tests for the PURE core of the id: backfill tool — no DB, no IO.

   Per the fidelity-pipeline test-law: the correctness-critical surface
   (`classify-id-form`, `plan-file`, `apply-plan`, `verify-surgical`) has zero
   DB/IO dependency and is verifiable directly.  The DB slot (`id-map-from-db`)
   is validated separately against a `datomic:mem` fixture / the read-only live
   dump.

   Byte-fidelity is asserted by WHOLE-STRING equality against HAND-CONSTRUCTED
   expected output — never by piping both operands through the same
   transform (which is structurally blind to EOF-newline and CRLF corruption).
   Fixtures deliberately cover every EOF shape: single trailing newline, one and
   multiple trailing blank lines, NO trailing newline, and CRLF endings."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [sandbar.migrate.id-backfill :as bf]))

(def uuid1 "facad8b8-728b-582d-ac01-f8c152d020f2")
(def uuid2 "c21f176b-ae53-52e9-a78c-2cbe55dbbb47")
(def idl  (bf/id-line uuid1))          ; "id: 'facad8b8-...'"  — the canonical form

(defn fm [& lines] (str "---\n" (str/join "\n" lines) "\n---\nBody text.\n"))

;;;; ---------------------------------------------------------------------------
;;;; classify-id-form
;;;; ---------------------------------------------------------------------------

(deftest classify-clean-sq
  (is (= {:form :clean-sq :value uuid1}
         (select-keys (bf/classify-id-form [(str "id: '" uuid1 "'")]) [:form :value]))))

(deftest classify-java-tag
  (let [c (bf/classify-id-form [(str "id: !!java.util.UUID '" uuid1 "'")])]
    (is (= :java-tag (:form c)))
    (is (= uuid1 (:value c)))))

(deftest classify-missing
  (is (= :missing (:form (bf/classify-id-form ["name: foo" "type: decision"])))))

(deftest classify-bare-and-double
  (is (= :bare (:form (bf/classify-id-form [(str "id: " uuid1)]))))
  (is (= :double-q (:form (bf/classify-id-form [(str "id: \"" uuid1 "\"")])))))

;;;; ---------------------------------------------------------------------------
;;;; plan-file — all action branches
;;;; ---------------------------------------------------------------------------

(deftest plan-skip-clean
  (is (= :skip-clean (:action (bf/plan-file (fm "name: x" (str "id: '" uuid1 "'")) uuid1)))))

(deftest plan-conflict-on-mismatch
  (let [p (bf/plan-file (fm "name: x" (str "id: '" uuid1 "'")) uuid2)]
    (is (= :conflict (:action p)))
    (is (= (bf/id-line uuid2) (:new-line p)))))

(deftest plan-rewrite-java-tag
  (let [p (bf/plan-file (fm "name: x" (str "id: !!java.util.UUID '" uuid1 "'")) uuid1)]
    (is (= :rewrite (:action p)))
    (is (= idl (:new-line p)))))

(deftest plan-insert-missing
  (let [p (bf/plan-file (fm "name: x" "type: decision") uuid1)]
    (is (= :insert (:action p)))
    (is (= idl (:new-line p)))))

(deftest plan-no-db-id
  (is (= :no-db-id (:action (bf/plan-file (fm "name: x") nil)))))

(deftest plan-no-frontmatter
  (is (= :no-frontmatter (:action (bf/plan-file "# Just a heading\n\nbody\n" uuid1)))))

;;;; ---------------------------------------------------------------------------
;;;; scan-lines — the byte-exact round-trip law (the anti-corruption primitive)
;;;; ---------------------------------------------------------------------------

(deftest scan-lines-round-trips-every-eof-shape
  (doseq [s ["---\nname: x\n---\nbody\n"          ; single trailing nl
             "---\nname: x\n---\nbody\n\n"        ; one trailing blank line
             "---\nname: x\n---\nbody\n\n\n"      ; multiple trailing blank lines
             "---\nname: x\n---\nbody"            ; NO trailing newline
             "---\r\nname: x\r\n---\r\nbody\r\n"  ; CRLF
             "a\n\nb\n\n\n"                       ; internal + trailing blanks
             ""                                   ; empty
             "\n\n\n"]]                           ; only newlines
    (is (= s (apply str (mapcat (juxt :text :term) (bf/scan-lines s))))
        (str "scan-lines must reconstruct byte-for-byte: " (pr-str s)))))

;;;; ---------------------------------------------------------------------------
;;;; apply-plan :insert — WHOLE-STRING equality vs hand-constructed expected.
;;;; These are the genuine falsifiers: the expected output is built by explicit
;;;; concatenation, NOT by round-tripping the input through apply's own path.
;;;; ---------------------------------------------------------------------------

(deftest insert-byte-exact-single-trailing-nl
  (let [content  "---\nname: x\n---\nbody\n"
        expected (str "---\nname: x\n" idl "\n---\nbody\n")
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    (is (= (+ (count content) (count idl) 1) (count out)))))    ; +line +1 LF

(deftest insert-byte-exact-one-trailing-blank-line
  (let [content  "---\nname: x\n---\nbody\n\n"                   ; ends in a blank line
        expected (str "---\nname: x\n" idl "\n---\nbody\n\n")    ; blank line PRESERVED
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    (is (str/ends-with? out "\n\n") "the trailing blank line must survive")
    (is (= (+ (count content) (count idl) 1) (count out)))))

(deftest insert-byte-exact-multiple-trailing-blank-lines
  (let [content  "---\nname: x\n---\nbody\n\n\n"
        expected (str "---\nname: x\n" idl "\n---\nbody\n\n\n")
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    (is (str/ends-with? out "body\n\n\n") "both trailing blank lines must survive")))

(deftest insert-byte-exact-no-trailing-newline
  (let [content  "---\nname: x\n---\nbody"                       ; no final newline
        expected (str "---\nname: x\n" idl "\n---\nbody")        ; still no final newline
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    (is (not (str/ends-with? out "\n")) "the missing final newline must NOT be added")))

(deftest insert-byte-exact-crlf
  (let [content  "---\r\nname: x\r\n---\r\nbody\r\n"
        expected (str "---\r\nname: x\r\n" idl "\r\n---\r\nbody\r\n")  ; CRLF preserved
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    (is (not (str/includes? out "\n\r")) "no LF/CR mixing introduced")
    (is (= (+ (count content) (count idl) 2) (count out)))))     ; +line +2 CRLF

(deftest insert-into-empty-frontmatter
  (let [content  "---\n---\nbody\n"
        expected (str "---\n" idl "\n---\nbody\n")
        p        (bf/plan-file content uuid1)]
    (is (= expected (bf/apply-plan content p)))))

;;;; ---------------------------------------------------------------------------
;;;; apply-plan :rewrite — WHOLE-STRING equality vs hand-constructed expected.
;;;; ---------------------------------------------------------------------------

(deftest rewrite-byte-exact-java-tag-single-nl
  (let [old-line (str "id: !!java.util.UUID '" uuid1 "'")
        content  (str "---\nname: x\n" old-line "\n---\nbody\n")
        expected (str "---\nname: x\n" idl "\n---\nbody\n")
        p        (bf/plan-file content uuid1)
        out      (bf/apply-plan content p)]
    (is (= expected out))
    ;; size invariant: delta == (new line) - (old line text)
    (is (= (- (count idl) (count old-line)) (- (count out) (count content))))))

(deftest rewrite-byte-exact-java-tag-trailing-blank-line
  (let [old-line (str "id: !!java.util.UUID '" uuid1 "'")
        content  (str "---\nname: x\n" old-line "\n---\nbody\n\n")   ; blank-line EOF
        expected (str "---\nname: x\n" idl "\n---\nbody\n\n")        ; blank line PRESERVED
        p        (bf/plan-file content uuid1)]
    (is (= expected (bf/apply-plan content p)))))

(deftest rewrite-byte-exact-bare-crlf
  (let [old-line (str "id: " uuid1)
        content  (str "---\r\nname: x\r\n" old-line "\r\n---\r\nbody\r\n")
        expected (str "---\r\nname: x\r\n" idl "\r\n---\r\nbody\r\n")
        p        (bf/plan-file content uuid1)]
    (is (= expected (bf/apply-plan content p)))))

(deftest rewrite-preserves-a-non-terminal-id-line-position
  ;; id: is NOT the last frontmatter line — surrounding lines & terminators stay put
  (let [content  (str "---\nname: x\nid: " uuid1 "\ntype: decision\n---\nbody\n")
        expected (str "---\nname: x\n" idl "\ntype: decision\n---\nbody\n")
        p        (bf/plan-file content uuid1)]
    (is (= expected (bf/apply-plan content p)))))

;;;; ---------------------------------------------------------------------------
;;;; verify-surgical — the plan-level byte invariant HAS TEETH.
;;;; Feed it deliberately-corrupted `applied` values (exactly the old bug's
;;;; output shapes) and assert it FAILS; feed the correct output and assert OK.
;;;; ---------------------------------------------------------------------------

(deftest verify-surgical-passes-correct-insert-and-rewrite
  (doseq [content ["---\nname: x\n---\nbody\n\n"
                   "---\r\nname: x\r\n---\r\nbody\r\n"
                   (str "---\nname: x\nid: !!java.util.UUID '" uuid1 "'\n---\nbody\n\n")]]
    (let [p   (bf/plan-file content uuid1)
          out (bf/apply-plan content p)]
      (is (:ok? (bf/verify-surgical content out p))
          (str "correct output must verify OK: " (pr-str content))))))

(deftest verify-surgical-catches-dropped-trailing-blank-line
  ;; the EXACT pre-fix corruption: a body ending in a blank line loses one \n
  (let [content "---\nname: x\n---\nbody\n\n"
        p       (bf/plan-file content uuid1)
        good    (bf/apply-plan content p)
        corrupt (subs good 0 (dec (count good)))]   ; drop the final \n
    (is (:ok? (bf/verify-surgical content good p)))
    (is (not (:ok? (bf/verify-surgical content corrupt p)))
        "verify-surgical MUST reject a dropped trailing blank line")))

(deftest verify-surgical-catches-crlf-collapse
  ;; the OTHER pre-fix corruption: CRLF collapsed to LF everywhere
  (let [content "---\r\nname: x\r\n---\r\nbody\r\n"
        p       (bf/plan-file content uuid1)
        good    (bf/apply-plan content p)
        flipped (str/replace good "\r\n" "\n")]
    (is (:ok? (bf/verify-surgical content good p)))
    (is (not (:ok? (bf/verify-surgical content flipped p)))
        "verify-surgical MUST reject a CRLF->LF collapse")))

(deftest verify-surgical-catches-body-mutation
  ;; any non-target byte change is caught, not only EOF/CRLF
  (let [content "---\nname: x\n---\nhello world\n"
        p       (bf/plan-file content uuid1)
        good    (bf/apply-plan content p)
        corrupt (str/replace good "hello world" "hello wxrld")]
    (is (:ok? (bf/verify-surgical content good p)))
    (is (not (:ok? (bf/verify-surgical content corrupt p))))))

;;;; ---------------------------------------------------------------------------
;;;; round-trip + byte-level idempotence
;;;; ---------------------------------------------------------------------------

(deftest insert-then-reparse-is-clean-sq
  (let [content (fm "name: x")
        p       (bf/plan-file content uuid1)
        out     (bf/apply-plan content p)
        c       (bf/classify-id-form (first (bf/split-frontmatter out)))]
    (is (= :clean-sq (:form c)))
    (is (= uuid1 (:value c)))))

(deftest byte-level-idempotence-second-apply-is-noop
  (doseq [content ["---\nname: x\n---\nbody\n\n"                                    ; insert
                   "---\r\nname: x\r\n---\r\nbody\r\n"                              ; insert CRLF
                   (str "---\nname: x\nid: !!java.util.UUID '" uuid1 "'\n---\nb\n\n")]] ; rewrite
    (let [p1   (bf/plan-file content uuid1)
          out1 (bf/apply-plan content p1)
          p2   (bf/plan-file out1 uuid1)
          out2 (bf/apply-plan out1 p2)]
      (is (= :skip-clean (:action p2)) "second plan must be a no-op")
      (is (= out1 out2) "re-applying a skip-clean plan must be byte-identical"))))

;;;; ---------------------------------------------------------------------------
;;;; run — dry-run returns stats + byte-verify counts, writes nothing;
;;;; and REFUSES the phantom :apply?/:guard! options.
;;;; ---------------------------------------------------------------------------

(deftest run-dry-run-shape-and-byte-verify
  (let [tmp  (java.nio.file.Files/createTempDirectory "w1d" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)
        mdir (io/file root "memory" "decisions")
        _    (.mkdirs mdir)
        f    (io/file mdir "x.md")
        _    (spit f "---\nname: x\n---\nbody\n\n")            ; blank-line EOF (the risky shape)
        res  (bf/run {:corpus-root root :id-map {"decisions/x.md" uuid1} :files [(.getPath f)]})]
    (is (= 1 (get-in res [:stats :actionable])))
    (is (= 1 (get-in res [:stats :insert])))
    (is (= 1 (get-in res [:stats :byte-clean])))
    (is (= 0 (get-in res [:stats :byte-drift])))
    (is (true? (:byte-ok? (first (:plans res)))))
    (is (= 1 (count (:diffs res))))
    (testing "file on disk is UNCHANGED by dry-run"
      (is (= "---\nname: x\n---\nbody\n\n" (slurp f))))))

(deftest run-refuses-phantom-write-options
  (is (thrown? clojure.lang.ExceptionInfo
               (bf/run {:corpus-root "/x" :id-map {} :files [] :apply? true}))
      "run must throw on :apply? — a write request cannot silently become a dry-run")
  (is (thrown? clojure.lang.ExceptionInfo
               (bf/run {:corpus-root "/x" :id-map {} :files [] :guard! (fn [_ _])}))
      "run must throw on :guard! — it has no write arm to guard"))
