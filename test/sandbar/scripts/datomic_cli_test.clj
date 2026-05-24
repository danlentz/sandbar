(ns sandbar.scripts.datomic-cli-test
  "Tests for sandbar.scripts.datomic-cli — the Datomic CLI discovery
   helper used by backup-db / restore-db / verify-backup scripts.

   Discovery strategies are tested in ISOLATION via `with-redefs` on
   the `getenv` / `executable?` / `clojure.java.shell/sh` seams — so
   the tests don't depend on the host machine's actual Datomic install
   state (CI agents may or may not have it).

   Per memory/interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md
   — verification is tests + memorialization, NOT REPL verification."
  (:require [clojure.java.shell          :as sh]
            [clojure.test                :refer [deftest is testing]]
            [sandbar.scripts.datomic-cli :as datomic-cli]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; executable?

(deftest executable?-basic
  (testing "nil returns false (not nil — `when` returns nil; assert truthiness)"
    (is (not (datomic-cli/executable? nil))))
  (testing "blank string returns false"
    (is (not (datomic-cli/executable? "")))
    (is (not (datomic-cli/executable? "   "))))
  (testing "nonexistent path returns false"
    (is (not (datomic-cli/executable? "/nonexistent/path/datomic"))))
  (testing "non-string returns false (defensive)"
    (is (not (datomic-cli/executable? 42)))
    (is (not (datomic-cli/executable? :keyword))))
  (testing "a directory is not executable (must be a regular file)"
    (is (not (datomic-cli/executable? "/tmp"))))
  (testing "an executable on $PATH IS executable"
    ;; /bin/ls is universally present + executable on POSIX systems
    (is (datomic-cli/executable? "/bin/ls"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; from-env-bin — Strategy 1

(deftest from-env-bin-unset
  (testing "DATOMIC_BIN unset returns nil"
    (with-redefs [datomic-cli/getenv (constantly nil)]
      (is (nil? (datomic-cli/from-env-bin))))))

(deftest from-env-bin-points-at-nonexistent
  (testing "DATOMIC_BIN points at nonexistent path → nil (falls through)"
    (with-redefs [datomic-cli/getenv (fn [k]
                                       (when (= k "DATOMIC_BIN")
                                         "/nonexistent/datomic"))]
      (is (nil? (datomic-cli/from-env-bin))))))

(deftest from-env-bin-points-at-executable
  (testing "DATOMIC_BIN points at executable → returns it"
    (with-redefs [datomic-cli/getenv (fn [k]
                                       (when (= k "DATOMIC_BIN") "/bin/ls"))]
      (is (= "/bin/ls" (datomic-cli/from-env-bin))))))

(deftest from-env-bin-points-at-directory
  (testing "DATOMIC_BIN points at a directory → nil (not a regular file)"
    (with-redefs [datomic-cli/getenv (fn [k]
                                       (when (= k "DATOMIC_BIN") "/tmp"))]
      (is (nil? (datomic-cli/from-env-bin))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; from-env-home — Strategy 2

(deftest from-env-home-unset
  (testing "DATOMIC_HOME unset returns nil"
    (with-redefs [datomic-cli/getenv (constantly nil)]
      (is (nil? (datomic-cli/from-env-home))))))

(deftest from-env-home-with-no-bin-subdir
  (testing "DATOMIC_HOME set but $home/bin/datomic doesn't exist → nil"
    (with-redefs [datomic-cli/getenv (fn [k]
                                       (when (= k "DATOMIC_HOME") "/tmp"))]
      (is (nil? (datomic-cli/from-env-home))))))

(deftest from-env-home-with-executable
  (testing "DATOMIC_HOME set + $home/bin/datomic is executable → returns it"
    ;; Use /usr as a fake DATOMIC_HOME — /usr/bin/datomic doesn't exist
    ;; on most macs, so to test the success path stub `executable?` to
    ;; accept anything ending in /bin/datomic.
    (with-redefs [datomic-cli/getenv     (fn [k]
                                           (when (= k "DATOMIC_HOME")
                                             "/opt/fake-datomic"))
                  datomic-cli/executable? (fn [p]
                                            (= p "/opt/fake-datomic/bin/datomic"))]
      (is (= "/opt/fake-datomic/bin/datomic" (datomic-cli/from-env-home))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; from-path — Strategy 3

(deftest from-path-which-fails
  (testing "`which datomic` returns non-zero → nil"
    (with-redefs [sh/sh (fn [& _] {:exit 1 :out "" :err ""})]
      (is (nil? (datomic-cli/from-path))))))

(deftest from-path-which-succeeds-but-nonexistent
  (testing "`which datomic` succeeds but path is nonexistent → nil"
    (with-redefs [sh/sh (fn [& _]
                          {:exit 0 :out "/nonexistent/datomic\n" :err ""})]
      (is (nil? (datomic-cli/from-path))))))

(deftest from-path-which-succeeds
  (testing "`which datomic` succeeds + path is executable → returns trimmed path"
    (with-redefs [sh/sh                  (fn [& _]
                                           {:exit 0 :out "/bin/ls\n" :err ""})
                  datomic-cli/executable? (fn [p] (= p "/bin/ls"))]
      (is (= "/bin/ls" (datomic-cli/from-path))))))

(deftest from-path-shell-throws
  (testing "shell-out throws (which not on PATH itself, etc.) → nil"
    (with-redefs [sh/sh (fn [& _] (throw (RuntimeException. "no which")))]
      (is (nil? (datomic-cli/from-path))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; from-common-roots — Strategy 4

(deftest from-common-roots-no-match
  (testing "no directory under conventional roots matches → nil"
    ;; Stub executable? to refuse all candidates; scan still walks roots
    ;; but every produced path is filtered.
    (with-redefs [datomic-cli/executable? (constantly false)]
      (is (nil? (datomic-cli/from-common-roots))))))

;; Note on from-common-roots positive-path:
;;
;; We don't unit-test the executable-candidate branch because (a) it
;; reads the live filesystem (System/getProperty "user.home" + /opt +
;; /usr/local) — which on Dan's machine WILL find the real install at
;; /Users/dan/opt/datomic/bin/datomic and pass the test trivially, but
;; on CI agents would fail.  The end-to-end smoke (bin/sandbar
;; verify-backup) covers the live-discovery path; the unit tests
;; cover the algorithmic branches independent of host state.


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; getenv — wrapper trivially delegates; smoke-test the seam itself

(deftest getenv-delegates-to-system
  (testing "getenv returns System/getenv value for a known env var"
    ;; PATH is always set on any reasonable POSIX environment
    (is (some? (datomic-cli/getenv "PATH"))))
  (testing "getenv returns nil for an unset var"
    (is (nil? (datomic-cli/getenv "DEFINITELY_NOT_A_REAL_ENV_VAR_XYZZY_42")))))
