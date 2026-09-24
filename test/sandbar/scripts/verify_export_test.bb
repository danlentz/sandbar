#!/usr/bin/env bb
;; Black-box tests for the read-only export integrity check
;; (`sandbar verify-export <dir> [--expect-manifest <sha256>]`, backed by
;; bin/verify-export.bb; W1.H first build, 2026-09-21).  Every case builds its
;; own export tree in a temp directory from the manifest shape the guarded
;; exporter writes (namespaced :manifest/* map, #uuid ids, lower-hex SHA-256),
;; runs the REAL command — the valid-tree, pin, changed-bytes, missing-file,
;; absent-marker, symlinked-file, argument/help and non-directory cases through
;; BOTH entry points (the bin/sandbar wrapper and the script), the remaining
;; corruption cases through the script only — and, for verification success and
;; refusal, reads back exactly one EDN form (help/usage print text).  No Sandbar
;; runtime, configuration, token, database or network is involved; every wrapper
;; run gets SANDBAR_CLIENT_DIR pointing at a directory that is observed after the
;; run, and the public-success, help and argument cases assert it is still
;; absent, proving the dispatch precedes the wrapper's mkdir.
;;
;;   bb test/sandbar/scripts/verify_export_test.bb
(ns sandbar.scripts.verify-export-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]])
  (:import [java.io PushbackReader StringReader]
           [java.security MessageDigest]))

(def repo (str (fs/canonicalize (fs/parent (fs/parent (fs/parent (fs/parent *file*)))))))
(def wrapper (str (fs/path repo "bin" "sandbar")))
(def script (str (fs/path repo "bin" "verify-export.bb")))
(def marker "export-manifest.edn")

;; ---- helpers -----------------------------------------------------------------------------
(defn sha256-bytes [^bytes bs]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bs))))
(defn sha256-str [^String s] (sha256-bytes (.getBytes s "UTF-8")))
(defn sha256-file [f] (sha256-bytes (fs/read-all-bytes f)))

(defn manifest-edn
  "The exporter's manifest shape (as retained from composition run
   5748378470092138483): namespaced :manifest/* keys, :files rows with #uuid
   ids, :file-set as a vector.  `files` is [{:rel-path :sha256}]; ids are
   minted here unless supplied."
  [{:keys [audience files status extra]
    :or {audience :public status :complete}}]
  (let [rows (mapv (fn [{:keys [rel-path sha256 id]}]
                     {:rel-path rel-path :id (or id (random-uuid)) :sha256 sha256})
                   files)
        run (str (random-uuid))]
    (pr-str (merge {:manifest/audit-ref run
                    :manifest/status status
                    :manifest/audience audience
                    :manifest/files rows
                    :manifest/target {:destination (name audience) :audience audience}
                    :manifest/dependencies []
                    :manifest/run run
                    :manifest/file-set (mapv :rel-path rows)
                    :manifest/firewall-class audience
                    :manifest/basis-t 1415}
                   extra))))

(defn make-tree!
  "Write `contents` ({rel-path text}) under a fresh temp tree plus a manifest
   that describes exactly those files.  Returns {:dir :manifest-sha :contents}.
   `manifest-fn` may rewrite the manifest text before it is written."
  ([contents] (make-tree! contents {}))
  ([contents {:keys [audience manifest-fn parent] :or {audience :public manifest-fn identity}}]
   (let [root (str (fs/canonicalize (fs/create-temp-dir {:prefix "verify-export-" :dir parent})))
         dir  (str (fs/path root "export child"))]            ; a space in the path, deliberately
     (fs/create-dirs dir)
     (doseq [[rel text] contents]
       (let [f (fs/path dir rel)]
         (fs/create-dirs (fs/parent f))
         (spit (str f) text)))
     (let [text (manifest-fn (manifest-edn {:audience audience
                                            :files (mapv (fn [[rel text]] {:rel-path rel :sha256 (sha256-str text)}) contents)}))]
       (spit (str (fs/path dir marker)) text)
       {:dir dir :root root :manifest-sha (sha256-str text) :contents contents}))))

(def sample
  {"decisions/one.md" "---\nname: One\n---\nBody one — with a dash.\n"
   "notes/with space.md" "---\nname: Spaced\n---\nSecond body.\n"})

(defn snapshot [dir]
  (into {} (map (fn [f] [(str (fs/relativize dir f)) (sha256-file f)])
                (filter fs/regular-file? (fs/glob dir "**" {:hidden true})))))

(defn read-forms [s]
  (with-open [r (PushbackReader. (StringReader. s))]
    (loop [acc []]
      (let [x (edn/read {:eof ::eof} r)]
        (if (= x ::eof) acc (recur (conj acc x)))))))

(defn- rewrite-manifest! [dir f]
  (let [m (edn/read-string (slurp (str (fs/path dir marker))))]
    (spit (str (fs/path dir marker)) (pr-str (f m)))))

(defn run-cli
  "Run the verifier through :wrapper or :script with `args`.  Returns
   {:exit :out :err :forms :client-created?}.  The wrapper is given an absent
   SANDBAR_CLIENT_DIR and no token; both must stay untouched."
  [entry args]
  (let [absent (str (fs/path (fs/create-temp-dir {:prefix "verify-export-client-"}) "never-created"))
        env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
             "SANDBAR_HOME" repo "SANDBAR_CLIENT_DIR" absent "SANDBAR_TOKEN" ""}
        cmd (case entry
              :wrapper (into ["bash" wrapper "verify-export"] args)
              :script  (into ["bb" script] args))
        ;; :shutdown nil — no process-cleanup hook for these short synchronous
        ;; runs (the sandboxed runner otherwise prints sysctl diagnostics on the
        ;; test process stderr after the assertions have passed).
        r (apply p/shell {:out :string :err :string :continue true :extra-env env :shutdown nil} cmd)]
    {:exit (:exit r) :out (:out r) :err (:err r)
     :forms (try (read-forms (:out r)) (catch Exception _ ::unparseable))
     :client-created? (fs/exists? absent)}))

(defn verified? [r n]
  (and (= 0 (:exit r)) (= 1 (count (:forms r)))
       (let [m (first (:forms r))]
         (and (= :verified (:status m)) (= :file-integrity (:scope m)) (= n (:file-count m))
              (string? (:manifest-sha256 m)) (re-matches #"[0-9a-f]{64}" (:manifest-sha256 m))))))

(defn refused? [r]
  (and (= 1 (:exit r)) (= 1 (count (:forms r)))
       (let [m (first (:forms r))] (and (= :refused (:status m)) (keyword? (:reason m))))))

(defn sanitized? [r dir]
  (let [text (str (:out r) (:err r))]
    (and (not (str/includes? text dir))
         (not (str/includes? text "Body one"))
         (not (str/includes? text "decisions/one.md")))))

(defmacro both-entries [[sym] & body]
  `(doseq [~sym [:wrapper :script]]
     (testing (str "via " (name ~sym)) ~@body)))

;; ---- valid trees ------------------------------------------------------------------------
(deftest complete-public-tree-verifies-unchanged-and-unpinned
  (let [{:keys [dir]} (make-tree! sample)
        before (snapshot dir)]
    (both-entries [entry]
      (let [r (run-cli entry [dir])]
        (is (verified? r 2) (pr-str r))
        (is (false? (:manifest-pinned? (first (:forms r)))))
        (is (= "" (:err r)) "stderr is empty on success")
        (is (false? (:client-created? r)) "the wrapper never creates its client config dir")
        (is (= before (snapshot dir)) "the tree is byte-identical after a verification")))))

(deftest expected-manifest-pin-accepts-matching-any-case-and-refuses-mismatch
  (let [{:keys [dir manifest-sha]} (make-tree! sample)]
    (both-entries [entry]
      (let [r (run-cli entry [dir "--expect-manifest" manifest-sha])]
        (is (verified? r 2) (pr-str r))
        (is (true? (:manifest-pinned? (first (:forms r)))))
        (is (= manifest-sha (:manifest-sha256 (first (:forms r))))))
      (let [r (run-cli entry [dir "--expect-manifest" (str/upper-case manifest-sha)])]
        (is (verified? r 2) "an upper-case pin matches; the receipt reports lower-hex"))
      (let [wrong (str "0" (subs manifest-sha 1))
            r (run-cli entry [dir "--expect-manifest" (if (= wrong manifest-sha) (str "1" (subs manifest-sha 1)) wrong)])]
        (is (refused? r) (pr-str r))
        (is (sanitized? r dir))))))

(deftest private-audience-tree-with-private-permissions-verifies
  (let [{:keys [dir]} (make-tree! {"observations/private-work.md" "---\nname: Private\nvisibility: private\n---\nPrivate body.\n"}
                                  {:audience :private})]
    (fs/set-posix-file-permissions dir "rwx------")
    (fs/set-posix-file-permissions (fs/path dir "observations") "rwx------")
    (doseq [f (filter fs/regular-file? (fs/glob dir "**"))] (fs/set-posix-file-permissions f "rw-------"))
    (both-entries [entry]
      (is (verified? (run-cli entry [dir]) 1)))))

(deftest empty-complete-export-verifies-with-zero-files
  (let [{:keys [dir]} (make-tree! {})]
    (both-entries [entry]
      (let [r (run-cli entry [dir])]
        (is (verified? r 0) (pr-str r))))))

(deftest manifest-hash-case-is-tolerated
  (let [{:keys [dir]} (make-tree! sample {:manifest-fn #(str/replace % #"\"([0-9a-f]{64})\"" (fn [[_ h]] (str "\"" (str/upper-case h) "\"")))})]
    (is (verified? (run-cli :script [dir]) 2) "upper-case file hashes in the manifest still verify")))

;; ---- content and population defects ------------------------------------------------------
(deftest changed-bytes-are-refused-and-nothing-private-is-echoed
  (let [{:keys [dir]} (make-tree! sample)]
    (spit (str (fs/path dir "decisions/one.md")) "---\nname: One\n---\nBody one — tampered.\n")
    (both-entries [entry]
      (let [r (run-cli entry [dir])]
        (is (refused? r) (pr-str r))
        (is (sanitized? r dir))
        (is (= "" (:err r)) "refusals go to stdout as the single EDN form")))))

(deftest missing-listed-file-is-refused
  (let [{:keys [dir]} (make-tree! sample)]
    (fs/delete (fs/path dir "notes/with space.md"))
    (both-entries [entry] (is (refused? (run-cli entry [dir]))))))

(deftest extra-files-are-refused-including-a-leftover-pending-marker
  (doseq [extra ["notes/unlisted.md" "stray.txt" ".export-manifest.edn.pending" "notes/.hidden.md"]]
    (let [{:keys [dir]} (make-tree! sample)]
      (fs/create-dirs (fs/parent (fs/path dir extra)))
      (spit (str (fs/path dir extra)) "x")
      (testing extra
        (is (refused? (run-cli :script [dir])) extra)))))

(deftest empty-extra-directory-is-tolerated-but-a-file-inside-it-is-not
  (let [{:keys [dir]} (make-tree! sample)]
    (fs/create-dirs (fs/path dir "empty-dir"))
    (is (verified? (run-cli :script [dir]) 2) "an empty directory adds no file")
    (spit (str (fs/path dir "empty-dir" "late.md")) "late")
    (is (refused? (run-cli :script [dir])))))

;; ---- marker defects -----------------------------------------------------------------------
(deftest absent-partial-malformed-and-trailing-markers-are-refused
  (testing "absent"
    (let [{:keys [dir]} (make-tree! sample)]
      (fs/delete (fs/path dir marker))
      (both-entries [entry] (is (refused? (run-cli entry [dir]))))))
  (testing "partial (truncated bytes)"
    (let [{:keys [dir]} (make-tree! sample)
          text (slurp (str (fs/path dir marker)))]
      (spit (str (fs/path dir marker)) (subs text 0 (quot (count text) 2)))
      (is (refused? (run-cli :script [dir])))))
  (testing "malformed EDN"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn (fn [_] "{:manifest/status :complete :manifest/files [")})]
      (is (refused? (run-cli :script [dir])))))
  (testing "trailing form after the map"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn #(str % " {:sneaky 1}")})]
      (is (refused? (run-cli :script [dir])))))
  (testing "not a map"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn (fn [_] "[1 2 3]")})]
      (is (refused? (run-cli :script [dir])))))
  (testing "unknown tagged literal"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn #(str/replace % "#uuid " "#custom/tag ")})]
      (is (refused? (run-cli :script [dir])))))
  (testing "incomplete status (rewritten through the parsed map — pr-str renders the namespaced map as #:manifest{...}, so a textual key replace would silently miss)"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(assoc % :manifest/status :incomplete))
      (is (refused? (run-cli :script [dir])))))
  (testing "status absent"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(dissoc % :manifest/status))
      (is (refused? (run-cli :script [dir])))))
  (testing "empty marker"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn (fn [_] "")})]
      (is (refused? (run-cli :script [dir]))))))

;; ---- list defects -------------------------------------------------------------------------
(deftest duplicate-and-mismatched-lists-are-refused
  (testing "duplicate path in file-set"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(update % :manifest/file-set conj "decisions/one.md"))
      (is (refused? (run-cli :script [dir])))))
  (testing "file-set names a path files does not"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(update % :manifest/file-set conj "notes/other.md"))
      (is (refused? (run-cli :script [dir])))))
  (testing "files lists a row file-set does not"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(update % :manifest/files conj {:rel-path "notes/other.md" :id (random-uuid) :sha256 (sha256-str "x")}))
      (is (refused? (run-cli :script [dir])))))
  (testing "duplicate ids"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir (fn [m] (let [id (:id (first (:manifest/files m)))]
                                       (assoc m :manifest/files (mapv #(assoc % :id id) (:manifest/files m))))))
      (is (refused? (run-cli :script [dir])))))
  (testing "id is a string, not a uuid"
    (let [{:keys [dir]} (make-tree! sample {:manifest-fn #(str/replace % "#uuid " "")})]
      (is (refused? (run-cli :script [dir])))))
  (testing "file-set is a set literal, not a vector"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(update % :manifest/file-set set))
      (is (refused? (run-cli :script [dir])))))
  (testing "a row without :sha256"
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir #(update % :manifest/files (fn [rows] (mapv (fn [r] (dissoc r :sha256)) rows))))
      (is (refused? (run-cli :script [dir]))))))

;; ---- path defects -------------------------------------------------------------------------
(deftest unsafe-manifest-paths-are-refused
  (doseq [bad ["/etc/passwd.md" "../escape.md" "notes\\back.md" "./dotted.md" "notes/plain.txt" "" "notes//double.md" ".hidden/x.md"]]
    (let [{:keys [dir]} (make-tree! sample)]
      (rewrite-manifest! dir (fn [m] (-> m
                                       (update :manifest/file-set conj bad)
                                       (update :manifest/files conj {:rel-path bad :id (random-uuid) :sha256 (sha256-str "x")}))))
      (testing (pr-str bad)
        (let [r (run-cli :script [dir])]
          (is (refused? r) (pr-str r))
          (is (sanitized? r dir)))))))

;; ---- symlink defects ----------------------------------------------------------------------
(deftest symlinks-anywhere-are-refused
  (testing "a listed file replaced by a symlink with identical bytes"
    (let [{:keys [dir root]} (make-tree! sample)
          target (fs/path root "outside.md")]
      (spit (str target) (get sample "decisions/one.md"))
      (fs/delete (fs/path dir "decisions/one.md"))
      (fs/create-sym-link (fs/path dir "decisions/one.md") target)
      (both-entries [entry] (is (refused? (run-cli entry [dir]))))))
  (testing "a symlinked directory inside the tree"
    (let [{:keys [dir root]} (make-tree! sample)]
      (fs/create-dirs (fs/path root "elsewhere"))
      (fs/create-sym-link (fs/path dir "linked") (fs/path root "elsewhere"))
      (is (refused? (run-cli :script [dir])))))
  (testing "a symlinked marker"
    (let [{:keys [dir root]} (make-tree! sample)
          real (fs/path root "real-manifest.edn")]
      (fs/move (fs/path dir marker) real)
      (fs/create-sym-link (fs/path dir marker) real)
      (is (refused? (run-cli :script [dir])))))
  (testing "a symlinked root"
    (let [{:keys [dir root]} (make-tree! sample)
          link (fs/path root "root-link")]
      (fs/create-sym-link link dir)
      (is (refused? (run-cli :script [(str link)])))
      (is (verified? (run-cli :script [dir]) 2) "the real root still verifies"))))

;; ---- arguments, help, and non-directories -----------------------------------------------
(deftest argument-errors-exit-2-and-help-exits-0-without-reading-a-tree
  (both-entries [entry]
    (let [r (run-cli entry ["--help"])]
      (is (= 0 (:exit r)))
      (is (str/includes? (:out r) "usage:"))
      (is (false? (:client-created? r))))
    (is (= 0 (:exit (run-cli entry ["-h"]))))
    (doseq [args [[] ["a" "b"] ["--expect-manifest" "abc"] ["-x"]
                  ["some-dir" "--expect-manifest" "not-a-sha"]
                  ["some-dir" "--expect-manifest"]
                  ["some-dir" "--other" "0000000000000000000000000000000000000000000000000000000000000000"]]]
      (let [r (run-cli entry args)]
        (is (= 2 (:exit r)) (pr-str args))
        (is (false? (:client-created? r)))))))

(deftest nonexistent-and-non-directory-targets-are-refused-not-crashed
  (let [{:keys [dir root]} (make-tree! sample)]
    (both-entries [entry]
      (let [r (run-cli entry [(str (fs/path root "does not exist"))])]
        (is (refused? r) (pr-str r)))
      (let [r (run-cli entry [(str (fs/path dir "decisions/one.md"))])]
        (is (refused? r) "a regular file is not an export tree")))))

(deftest output-is-exactly-one-edn-form-in-every-outcome
  (let [{:keys [dir]} (make-tree! sample)
        good (run-cli :script [dir])
        _ (spit (str (fs/path dir "decisions/one.md")) "changed")
        bad (run-cli :script [dir])]
    (is (= 1 (count (:forms good))))
    (is (= 1 (count (:forms bad))))
    (is (map? (first (:forms good))))
    (is (map? (first (:forms bad))))))

(let [{:keys [fail error]} (run-tests 'sandbar.scripts.verify-export-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
