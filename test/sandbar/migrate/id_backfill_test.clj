(ns sandbar.migrate.id-backfill-test
  "Unit tests for the PURE core of the id: backfill tool — no DB, no IO.

   Per the fidelity-pipeline test-law: the correctness-critical surface
   (`classify-id-form`, `plan-file`, `apply-plan`) has zero DB/IO dependency and
   is verifiable directly.  The DB slot (`id-map-from-db`) is validated
   separately against a `datomic:mem` fixture / the read-only live dump."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [sandbar.migrate.id-backfill :as bf]))

(def uuid1 "facad8b8-728b-582d-ac01-f8c152d020f2")
(def uuid2 "c21f176b-ae53-52e9-a78c-2cbe55dbbb47")

(defn fm [& lines] (str "---\n" (str/join "\n" lines) "\n---\nBody text.\n"))

;;;; classify-id-form

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

;;;; plan-file — all action branches

(deftest plan-skip-clean
  (is (= :skip-clean (:action (bf/plan-file (fm "name: x" (str "id: '" uuid1 "'")) uuid1)))))

(deftest plan-conflict-on-mismatch
  (let [p (bf/plan-file (fm "name: x" (str "id: '" uuid1 "'")) uuid2)]
    (is (= :conflict (:action p)))
    (is (= (bf/id-line uuid2) (:new-line p)))))

(deftest plan-rewrite-java-tag
  (let [p (bf/plan-file (fm "name: x" (str "id: !!java.util.UUID '" uuid1 "'")) uuid1)]
    (is (= :rewrite (:action p)))
    (is (= (bf/id-line uuid1) (:new-line p)))))

(deftest plan-insert-missing
  (let [p (bf/plan-file (fm "name: x" "type: decision") uuid1)]
    (is (= :insert (:action p)))
    (is (= (bf/id-line uuid1) (:new-line p)))))

(deftest plan-no-db-id
  (is (= :no-db-id (:action (bf/plan-file (fm "name: x") nil)))))

(deftest plan-no-frontmatter
  (is (= :no-frontmatter (:action (bf/plan-file "# Just a heading\n\nbody\n" uuid1)))))

;;;; apply-plan — byte-level correctness

(deftest apply-insert-appends-trailing-id-preserves-body
  (let [content (fm "name: x" "type: decision")
        p (bf/plan-file content uuid1)
        out (bf/apply-plan content p)]
    (testing "id: lands as LAST frontmatter line"
      (is (= (bf/id-line uuid1) (last (first (bf/split-frontmatter out))))))
    (testing "body byte-identical"
      (is (= (second (bf/split-frontmatter content)) (second (bf/split-frontmatter out)))))
    (testing "exactly one line added"
      (is (= (inc (count (str/split-lines content))) (count (str/split-lines out)))))
    (testing "trailing newline preserved"
      (is (str/ends-with? out "\n")))))

(deftest apply-rewrite-in-place
  (let [content (fm "name: x" (str "id: !!java.util.UUID '" uuid1 "'"))
        p (bf/plan-file content uuid1)
        out (bf/apply-plan content p)]
    (testing "java-tag gone, clean form present"
      (is (str/includes? out (bf/id-line uuid1)))
      (is (not (str/includes? out "!!java.util.UUID"))))
    (testing "line count unchanged (in-place)"
      (is (= (count (str/split-lines content)) (count (str/split-lines out)))))))

;;;; round-trip + idempotence

(deftest insert-then-reparse-is-clean-sq
  (let [content (fm "name: x")
        p (bf/plan-file content uuid1)
        out (bf/apply-plan content p)
        c (bf/classify-id-form (first (bf/split-frontmatter out)))]
    (is (= :clean-sq (:form c)))
    (is (= uuid1 (:value c)))))

(deftest idempotent-second-plan-is-skip-clean
  (doseq [content [(fm "name: x")                                        ; insert case
                   (fm "name: x" (str "id: !!java.util.UUID '" uuid1 "'"))]] ; rewrite case
    (let [p (bf/plan-file content uuid1)
          out (bf/apply-plan content p)
          re (bf/plan-file out uuid1)]
      (is (= :skip-clean (:action re))
          (str "second plan over " (:action p) " output must be a no-op")))))

;;;; run — dry-run returns stats + diffs, writes nothing (files via a temp dir)

(deftest run-dry-run-shape
  (let [tmp (java.nio.file.Files/createTempDirectory "w1d" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)
        mdir (io/file root "memory" "decisions")
        _ (.mkdirs mdir)
        f (io/file mdir "x.md")
        _ (spit f (fm "name: x"))
        res (bf/run {:corpus-root root :id-map {"decisions/x.md" uuid1} :files [(.getPath f)]})]
    (is (= 1 (get-in res [:stats :actionable])))
    (is (= 1 (get-in res [:stats :insert])))
    (is (= 1 (count (:diffs res))))
    (testing "file on disk is UNCHANGED by dry-run"
      (is (= (fm "name: x") (slurp f))))))
