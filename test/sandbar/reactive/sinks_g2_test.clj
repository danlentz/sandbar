(ns sandbar.reactive.sinks-g2-test
  "G2 rel-path traversal-sanitizer receipts (W1 Phase-0): every reactive
   fs-projection write target must canonicalize STRICTLY under
   `<corpus-root>/memory`, and any escape attempt is refused fail-closed.

   Covers bugs/reactive_sink_rel_path_traversal_exposure_pre_existing_2026_07_04
   — the sink previously derived its write target from the entity rel-path with
   no `..`/absolute/symlink normalization.  The attack corpus is the four
   classes the W1 acceptance names: dot-dot traversal, absolute paths, symlink
   escapes, and encoded/backslash separators.

   No DB fixture — the containment guard is a pure fn over the filesystem;
   `sinks/corpus-root` is redef'd to an isolated tmp root so nothing outside
   the sandbox is ever a write candidate (the live corpus is never touched)."
  (:require [clojure.test    :refer :all]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [sandbar.reactive.sinks :as sinks])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — isolated tmp corpus root with a `memory/` write base and a
;; sibling `outside/` directory that escapes should try (and fail) to reach.

(def ^:dynamic *root* nil)

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn tmp-root-fixture [f]
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "g2-corpus-" (System/currentTimeMillis) "-" (rand-int 1000000)))
        mem  (io/file root "memory")]
    (.mkdirs mem)
    (.mkdirs (io/file root "outside"))          ;; escape-target sibling
    (binding [*root* root]
      (with-redefs [sinks/corpus-root (constantly (.getPath root))]
        (try (f) (finally (delete-tree! root)))))))

(use-fixtures :each tmp-root-fixture)

(defn- refusal
  "Run `thunk`; return the `:sandbar/error` of any thrown ex-info, else nil."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:sandbar/error (ex-data e)))))

(defn- mem-canon []
  (.getCanonicalPath (io/file *root* "memory")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Positive control — legitimate rel-paths resolve CONTAINED under the root.

(deftest legit-paths-contained
  (testing "normal rel-paths resolve to a canonical path under <root>/memory"
    (doseq [rp ["decisions/foo.md"
                "a/b/c/deep.md"
                "single.md"
                "interaction/dan_correction_x.md"]]
      (let [p (sinks/contained-target-path rp)]
        (is (str/starts-with? p (str (mem-canon) java.io.File/separator))
            (str rp " must resolve under memory root; got " p))
        (is (str/ends-with? p rp)
            (str rp " canonical target should end with the rel-path; got " p))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attack class 1 — dot-dot traversal escapes are REFUSED.

(deftest dotdot-traversal-refused
  (testing "any `..`-bearing rel-path that resolves outside <root>/memory is refused"
    (doseq [rp ["../../etc/evil.md"          ;; the bug's exact example
                "../secrets.md"              ;; one level above memory
                "../outside/evil.md"         ;; into the sibling escape dir
                "decisions/../../escape.md"  ;; climb back out through a subdir
                "a/b/../../../../../../etc/passwd"]] ;; over-climb past FS root
      (is (= :rel-path-traversal-refusal
             (refusal #(sinks/contained-target-path rp)))
          (str rp " must be refused as a traversal escape"))
      ;; and the escape target must not exist as a side effect
      (is (not (.exists (io/file *root* "outside" "evil.md")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attack class 2 — absolute rel-paths are REFUSED (a rel-path is relative by
;; contract; an absolute form is malformed/hostile).

(deftest absolute-paths-refused
  (testing "absolute rel-paths are refused outright (before target derivation)"
    (doseq [rp ["/etc/passwd"
                "/tmp/evil.md"
                "/Users/dan/claude/memory/decisions/pwn.md"
                "/"]]
      (is (= :rel-path-traversal-refusal
             (refusal #(sinks/contained-target-path rp)))
          (str rp " (absolute) must be refused")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attack class 3 — symlink escape is REFUSED (getCanonicalFile resolves the
;; link target, which lexical `.normalize` would miss).

(deftest symlink-escape-refused
  (testing "a symlink inside memory/ pointing OUTSIDE the corpus is refused —
            the write cannot follow the link out of the corpus tree"
    (let [mem     (io/file *root* "memory")
          outside (io/file *root* "outside")
          link    (io/file mem "escape-link")]
      (Files/createSymbolicLink (.toPath link) (.toPath outside)
                                (make-array FileAttribute 0))
      ;; "escape-link/evil.md" canonicalizes to <root>/outside/evil.md
      (is (= :rel-path-traversal-refusal
             (refusal #(sinks/contained-target-path "escape-link/evil.md")))
          "a write through an escaping symlink must be refused")
      (is (not (.exists (io/file outside "evil.md")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attack class 4 — encoded / backslash "separators".  On the JVM/unix these
;; are LITERAL filename characters (not decoded, not path separators), so they
;; cannot escape: the canonical-containment guard proves they stay under root.
;; (The security invariant — "no write escapes root" — holds; these forms are
;; simply contained rather than refused, which is the correct outcome.)

(deftest encoded-separators-inert-and-contained
  (testing "URL-encoded / backslash separators are literal chars → contained, never escaping"
    (doseq [rp ["%2e%2e%2fevil.md"
                "..%2f..%2fevil.md"
                "..\\..\\evil.md"
                "%2e%2e/evil.md"
                "%2f%2f%2fetc%2fpasswd"]]
      (let [err (refusal #(sinks/contained-target-path rp))]
        (if err
          ;; If the platform ever DID decode one, it must fail CLOSED, never escape.
          (is (= :rel-path-traversal-refusal err)
              (str rp " if refused must be the containment refusal"))
          (let [p (sinks/contained-target-path rp)]
            (is (str/starts-with? p (str (mem-canon) java.io.File/separator))
                (str rp " must stay contained under memory root; got " p))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Containment guard — `assert-under-corpus-root!` is the pre-write predicate
;; `contained-target-path` (the sink's target-derivation choke point) runs on
;; every write.  `atomic-write!` is a general primitive that writes whatever
;; it is handed; containment is enforced ABOVE it, at derivation.

(deftest assert-under-corpus-root!-refuses-escapes
  (testing "the containment guard refuses File targets that escape <root>/memory"
    ;; absolute path outside root
    (is (= :rel-path-traversal-refusal
           (refusal #(sinks/assert-under-corpus-root! (io/file "/etc/passwd"))))
        "an absolute path outside root must be refused")
    ;; ../-escaping File computed from the memory base
    (is (= :rel-path-traversal-refusal
           (refusal #(sinks/assert-under-corpus-root!
                       (io/file *root* "memory" ".." "outside" "via-dotdot.md"))))
        "a ../-escaping File must be refused")
    ;; a legit in-root File is allowed (returns the canonical File)
    (is (instance? java.io.File
                   (sinks/assert-under-corpus-root!
                     (io/file *root* "memory" "decisions" "ok.md")))
        "a legitimate in-root File must be allowed")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prefix-sibling false-accept guard — a directory whose name is a PREFIX of
;; memory/ must not be treated as contained.

(deftest prefix-sibling-not-contained
  (testing "<root>/memory-evil is NOT under <root>/memory (trailing-separator guard)"
    (let [sibling (io/file *root* "memory-evil" "x.md")]
      (.mkdirs (.getParentFile sibling))
      (is (= :rel-path-traversal-refusal
             (refusal #(sinks/assert-under-corpus-root! sibling)))))))
