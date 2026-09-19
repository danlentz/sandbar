(ns sandbar.scripts.backup-db-lock-test
  "Lock lifecycle of the backup script (sandbar.scripts.backup-db), pinned
   after bugs/backup_db_leaves_its_lock_file_behind_after_a_successful_run_...
   (2026-09-19): acquire writes this JVM's PID; release deletes the file; a
   lock whose PID is dead or unreadable is taken over; a lock whose PID is
   alive is refused and left untouched.  Pure file operations: no database."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [sandbar.scripts.backup-db :as b])
  (:import (java.io File StringWriter)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-lock-path []
  (let [dir (.toFile (Files/createTempDirectory "backup-lock-test"
                                                (into-array FileAttribute [])))]
    (str dir "/.lock")))

(defn- our-pid [] (.pid (java.lang.ProcessHandle/current)))

(deftest acquire-writes-our-pid-and-release-deletes
  (let [p (temp-lock-path)
        f (b/acquire-lock! p)]
    (is (.exists f) "lock file created")
    (is (= (our-pid) (b/lock-holder-pid f)) "lock holds this JVM's pid")
    (b/release-lock! f)
    (is (not (.exists f)) "release deletes the lock")
    (b/release-lock! f)
    (is (not (.exists f)) "release is idempotent")))

(deftest stale-lock-with-dead-pid-is-taken-over
  (let [p (temp-lock-path)]
    (spit p "999999999\n")                     ; no such process can exist
    (is (false? (b/pid-alive? 999999999)))
    (let [err (StringWriter.)
          f   (binding [*err* err] (b/acquire-lock! p))]
      (is (= (our-pid) (b/lock-holder-pid f)) "the stale lock was taken over")
      (is (str/includes? (str err) "stale lock") "the takeover is reported")
      (b/release-lock! f)
      (is (not (.exists f))))))

(deftest unreadable-lock-is-treated-as-stale
  (let [p (temp-lock-path)]
    (spit p "not-a-pid\n")
    (is (nil? (b/lock-holder-pid (File. p))))
    (let [f (binding [*err* (StringWriter.)] (b/acquire-lock! p))]
      (is (= (our-pid) (b/lock-holder-pid f)))
      (b/release-lock! f))))

(deftest live-lock-is-refused-and-left-untouched
  (let [p (temp-lock-path)]
    (spit p (str (our-pid) "\n"))              ; our own pid is certainly alive
    (is (true? (b/pid-alive? (our-pid))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is alive" (b/acquire-lock! p)))
    (let [e (try (b/acquire-lock! p) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :lock-held (:reason (ex-data e))))
      (is (= (our-pid) (:pid (ex-data e)))))
    (is (= (str (our-pid)) (str/trim (slurp p))) "the live lock is left in place")
    (.delete (File. p))))

(deftest parse-segments-takes-the-last-progress-line
  (let [parse #'b/parse-segments
        out   "Copied 0 segments, skipped 0 segments.\nCopied 6016 segments, skipped 0 segments.\n:succeeded\n"]
    (is (= {:copied 6016 :skipped 0} (parse out)) "the last line is the total, not the preflight zero")
    (is (nil? (parse "no progress lines here")))
    (is (nil? (parse nil)))))
