(ns sandbar.reactive.sinks-name-max-test
  "NAME_MAX emit-path writability-guard receipts (2026-07-21 filename-length
   hardening): every projection rel-path segment must fit the per-segment
   filesystem name budget — 255 UTF-8 bytes for directory segments, 251 for
   the filename (reserving `atomic-write!`'s 4-byte `.tmp` suffix) — refused
   fail-closed at the create boundary via `assert-rel-path-name-max!`.

   Pure + lexical receipts, no DB fixture (sibling of the G2 traversal
   receipts in sinks-g2-test).  Includes the OS premise canary: the
   platforms this git-tracked corpus supports (APFS dev / ext4+tmpfs CI)
   all refuse a 256-byte ASCII name, and the `.tmp` sibling of a 252-byte
   filename IS such a name — the exact window where the target name is
   legal, every other guard passes, and the sink's write then dies
   ENAMETOOLONG + warn+swallowed (the silent DB-only-orphan / FS↔DB
   bijection break the guard closes)."
  (:require [clojure.test    :refer :all]
            [clojure.java.io :as io]
            [sandbar.reactive.sinks :as sinks]))

(defn- nm
  "An `n`-character (= n-UTF-8-byte) ASCII segment stub."
  [n]
  (apply str (repeat n "a")))

(defn- refusal-data
  "Run `thunk`; return the ex-data of any thrown ex-info, else nil."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Budget constants — single-sourced from the write protocol.

(deftest budget-constants-single-sourced
  (testing "the filename budget derives from NAME_MAX minus the atomic-write
            tmp suffix — one source, so protocol and guard cannot drift"
    (is (= 255 sinks/name-max-bytes))
    (is (= ".tmp" sinks/atomic-write-tmp-suffix))
    (is (= 251 sinks/filename-max-bytes))
    (is (= sinks/filename-max-bytes
           (- sinks/name-max-bytes
              (count (.getBytes ^String sinks/atomic-write-tmp-suffix "UTF-8"))))
        "251 is DERIVED (255 - |\".tmp\"|), not an independent constant")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Final-segment (filename) boundary — 251 passes, 252 refused.

(deftest final-segment-boundary
  (testing "ordinary rel-paths pass through unchanged"
    (doseq [rp ["decisions/foo.md"
                "a/b/c/deep.md"
                "single.md"
                "interaction/dan_correction_x.md"]]
      (is (= rp (sinks/assert-rel-path-name-max! rp))
          (str rp " must pass and be returned unchanged"))))
  (testing "a filename at EXACTLY the 251-byte budget passes"
    (let [ok (str "decisions/" (nm 248) ".md")]      ;; 248 + ".md" = 251 bytes
      (is (= ok (sinks/assert-rel-path-name-max! ok)))))
  (testing "a 252-byte filename (tmp sibling = 256 > NAME_MAX) is refused"
    (let [bad  (str "decisions/" (nm 249) ".md")     ;; 249 + ".md" = 252 bytes
          data (refusal-data #(sinks/assert-rel-path-name-max! bad))]
      (is (= :rel-path-segment-too-long (:sandbar/error data)))
      (is (true? (:final-segment? data)) "flagged as the filename budget")
      (is (= 252 (:segment-bytes data)))
      (is (= sinks/filename-max-bytes (:limit-bytes data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Directory-segment boundary — full 255 (no tmp suffix lands on directories).

(deftest directory-segment-boundary
  (testing "a 255-byte directory segment passes; 256 is refused at 255"
    (let [ok (str (nm 255) "/x.md")]
      (is (= ok (sinks/assert-rel-path-name-max! ok))))
    (let [bad  (str (nm 256) "/x.md")
          data (refusal-data #(sinks/assert-rel-path-name-max! bad))]
      (is (= :rel-path-segment-too-long (:sandbar/error data)))
      (is (false? (:final-segment? data)) "flagged as a DIRECTORY segment")
      (is (= 256 (:segment-bytes data)))
      (is (= sinks/name-max-bytes (:limit-bytes data))
          "directory budget is full NAME_MAX, not the filename 251")))
  (testing "EVERY segment is checked, not just first/last"
    (let [data (refusal-data
                #(sinks/assert-rel-path-name-max!
                  (str "decisions/" (nm 256) "/x.md")))]
      (is (= :rel-path-segment-too-long (:sandbar/error data)))
      (is (false? (:final-segment? data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTF-8 BYTES, not characters — the ext4/git portable floor.  The same
;; 255-byte multibyte segment is refused as a FILENAME (251 budget) yet
;; passes as a DIRECTORY (255 budget): one segment, both budgets receipted.

(deftest utf8-bytes-measured-not-chars
  (let [seg (str (apply str (repeat 84 "中")) ".md")]  ;; 84×3 + 3 = 255 bytes, 87 chars
    (testing "255 bytes / 87 chars as the FILENAME → refused over the 251 budget
              (APFS would write it locally — 255 UTF-16-unit limit — but ext4
              enforces BYTES, so a git checkout of the corpus would fail on
              Linux; the byte measure is the portable floor)"
      (let [data (refusal-data
                  #(sinks/assert-rel-path-name-max! (str "decisions/" seg)))]
        (is (= :rel-path-segment-too-long (:sandbar/error data)))
        (is (= 255 (:segment-bytes data)) "measured in UTF-8 bytes, not chars")))
    (testing "the SAME 255-byte segment as a DIRECTORY → passes (255 budget)"
      (is (= (str seg "/x.md")
             (sinks/assert-rel-path-name-max! (str seg "/x.md")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Refusal message — must NAME the limit and the consequence (actionable).

(deftest refusal-message-names-the-limit
  (let [msg (try (sinks/assert-rel-path-name-max!
                  (str "decisions/" (nm 300) ".md"))
                 nil
                 (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
    (is (some? msg))
    (is (re-find #"251" msg) "names the effective filename budget")
    (is (re-find #"NAME_MAX" msg) "names the filesystem limit")
    (is (re-find #"255" msg) "names the per-segment NAME_MAX value")
    (is (re-find #"\.tmp" msg) "explains the atomic-write suffix reservation")
    (is (re-find #"ENAMETOOLONG" msg) "names the failure it prevents")
    (is (re-find #"orphan" msg) "names the DB-only-orphan consequence")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OS premise canary — the budget encodes REAL platform behavior: a 252-byte
;; ASCII target name is writable, but its `.tmp` atomic-write sibling (256
;; bytes) is refused by the OS.  Runs in an isolated java.io.tmpdir sandbox
;; (APFS and ext4/tmpfs agree on 255-byte ASCII NAME_MAX).

(deftest os-premise-canary-tmp-window
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "name-max-canary-" (System/currentTimeMillis)
                          "-" (rand-int 1000000)))]
    (.mkdirs dir)
    (try
      (let [fname (str (nm 249) ".md")]              ;; 252-byte target name
        (is (= 252 (count (.getBytes fname "UTF-8"))))
        (testing "the 252-byte TARGET name itself is legal on this filesystem"
          (spit (io/file dir fname) "x")
          (is (.exists (io/file dir fname))))
        (testing "…but its atomic-write `.tmp` sibling exceeds NAME_MAX and the
                  OS refuses it — the exact silent-orphan window the guard's
                  251-byte filename budget closes"
          (is (thrown? java.io.IOException
                       (spit (io/file dir (str fname sinks/atomic-write-tmp-suffix))
                             "x")))))
      (finally
        (doseq [^java.io.File f (reverse (file-seq dir))]
          (.delete f))))))
