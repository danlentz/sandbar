(ns sandbar.project.export-integrity
  "Read-only byte and population integrity of a completed guarded export
   against its own manifest. The manifest is evidence the operator supplies,
   not a signature: a pass proves the tree holds exactly the listed files with
   the listed hashes and nothing else. It establishes no origin, custody,
   disclosure policy, database lineage, freshness, or permission to import,
   checkpoint or publish.

   Plain Clojure with no Sandbar runtime, configuration, credential, database,
   network or git dependency, so the same code runs under Babashka as the
   standalone `bin/verify-export.bb` command and inside the service as the
   integrity pass of the recovery check (sandbar.project.recovery-check)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream PushbackReader StringReader]
           [java.nio.file Files LinkOption FileVisitOption]
           [java.security MessageDigest]))

(def marker-name "export-manifest.edn")
(def nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn refuse!
  "Throw the sanitized refusal: a keyword reason and nothing else. Never echo
   a manifest's text, a private filename or an I/O exception."
  [reason]
  (throw (ex-info "Export integrity check refused." {:reason reason})))

(defn sha256?
  "True for a 64-digit hex string in either case."
  [x]
  (and (string? x) (boolean (re-matches #"[0-9a-fA-F]{64}" x))))

(defn digest
  "Lower-hex SHA-256 of everything readable from `input`, streamed."
  [input]
  (let [md (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 16384)]
    (loop []
      (let [n (.read input buffer)]
        (when (pos? n) (.update md buffer 0 n) (recur))))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn sha-file
  "Lower-hex SHA-256 of the bytes of the regular file at `path`."
  [path]
  (with-open [in (io/input-stream (.toFile path))]
    (digest in)))

(defn parse-manifest
  "Exactly one EDN map, with unknown tagged literals refused; anything else
   refuses as :invalid-manifest."
  [text]
  (try
    (with-open [reader (PushbackReader. (StringReader. text))]
      (let [end (Object.)
            opts {:eof end :readers {}
                  :default (fn [_ _] (refuse! :invalid-manifest))}
            value (edn/read opts reader)]
        (when-not (and (map? value) (identical? end (edn/read opts reader)))
          (refuse! :invalid-manifest))
        value))
    (catch Exception _ (refuse! :invalid-manifest))))

(defn safe-relative?
  "The guarded exporter's rel-path contract, checked before any path is
   resolved: a relative `.md` path with no backslash, no empty, `.` or `..`
   segment, and no leading-dot or control-character segment."
  [s]
  (and (string? s) (not (str/blank? s)) (str/ends-with? s ".md")
       (not (.isAbsolute (io/file s))) (not (str/includes? s "\\"))
       (not-any? #{"" "." ".."} (str/split s #"/" -1))
       (not-any? #(or (str/starts-with? % ".") (re-find #"[\p{Cntrl}]" %))
                 (str/split s #"/"))))

(defn tree-files
  "The set of regular files under `root` as root-relative strings. Symbolic
   links and non-regular entries anywhere in the tree refuse; directories are
   not files."
  [root]
  (with-open [walk (Files/walk root (make-array FileVisitOption 0))]
    (reduce
      (fn [out p]
        (cond
          (Files/isSymbolicLink p) (refuse! :symlink-entry)
          (Files/isDirectory p nofollow) out
          (Files/isRegularFile p nofollow) (conj out (str (.relativize root p)))
          :else (refuse! :nonregular-entry)))
      #{} (iterator-seq (.iterator walk)))))

(defn validate-manifest!
  "The file rows of a complete, well-formed manifest, or a refusal: status
   must be :complete; files and file-set must be vectors that agree as sets;
   every row needs a safe rel-path, a UUID id and a hex sha; paths and ids
   must be unique."
  [m]
  (when-not (= :complete (:manifest/status m)) (refuse! :incomplete-manifest))
  (let [files (:manifest/files m)
        paths (:manifest/file-set m)]
    (when-not (and (vector? files) (vector? paths)
                   (every? map? files)
                   (every? safe-relative? paths)
                   (every? #(and (safe-relative? (:rel-path %))
                                 (uuid? (:id %))
                                 (sha256? (:sha256 %))) files)
                   (= (count paths) (count (set paths)))
                   (= (count files) (count (set (map :rel-path files))))
                   (= (count files) (count (set (map :id files))))
                   (= (set paths) (set (map :rel-path files))))
      (refuse! :invalid-file-list))
    files))

(defn verify-tree*
  "Verify the export tree at `dir` against its manifest and return both the
   small success receipt and the parsed manifest, or throw with a sanitized
   :reason. `expected`, when given, pins the manifest's exact bytes to a
   SHA-256 from trusted custody. No mutation, no following of links."
  [dir expected]
  (when (and expected (not (sha256? expected))) (refuse! :invalid-manifest-pin))
  (let [requested (.toPath (io/file dir))]
    (when (Files/isSymbolicLink requested) (refuse! :symlink-root))
    (when-not (Files/isDirectory requested nofollow) (refuse! :not-directory))
    ;; Parent aliases (e.g. macOS /tmp) can identify the chosen root; entries
    ;; within that root, including the root itself, may not be symbolic links.
    (let [root (.toRealPath requested (make-array LinkOption 0))
          marker (.resolve root marker-name)]
      (when (Files/isSymbolicLink marker) (refuse! :symlink-entry))
      (when-not (Files/isRegularFile marker nofollow) (refuse! :missing-manifest))
      (let [marker-bytes (Files/readAllBytes marker)
            marker-hash (with-open [in (ByteArrayInputStream. marker-bytes)]
                          (digest in))]
        (when (and expected (not= (str/lower-case expected) marker-hash))
          (refuse! :manifest-pin-mismatch))
        (let [m (parse-manifest (String. marker-bytes "UTF-8"))
              files (validate-manifest! m)
              expected-files (conj (set (:manifest/file-set m)) marker-name)]
          (when-not (= expected-files (tree-files root)) (refuse! :file-set-mismatch))
          (doseq [{:keys [rel-path sha256]} files]
            (when-not (= (str/lower-case sha256) (sha-file (.resolve root rel-path)))
              (refuse! :file-hash-mismatch)))
          {:receipt {:status :verified :scope :file-integrity :file-count (count files)
                     :manifest-sha256 marker-hash :manifest-pinned? (boolean expected)}
           :manifest m})))))

(defn verify-tree
  "The success receipt of `verify-tree*` alone — the standalone command's
   printed form, unchanged by the shared extraction."
  [dir expected]
  (:receipt (verify-tree* dir expected)))
