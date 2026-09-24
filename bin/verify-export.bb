#!/usr/bin/env bb
(ns sandbar.scripts.verify-export
  "Read-only integrity check for a completed guarded export. No Sandbar runtime,
   credentials, configuration, database or git is loaded. Run on a settled tree.
   The check is sandbar.project.export-integrity, shared with the service's
   recovery check; this script is its standalone command."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;; The shared core is plain Clojure under this checkout's src/, located from
;; this script's own path so the bin/sandbar dispatch and a direct invocation
;; load the same source. Nothing else from src/ is loaded.
(cp/add-classpath (str (fs/path (fs/parent (fs/parent (fs/canonicalize *file*))) "src")))
(require '[sandbar.project.export-integrity :as integrity])

(def usage "usage: sandbar verify-export <dir> [--expect-manifest <sha256>]
Read-only file integrity against export-manifest.edn. Requires a settled tree.
An optional SHA-256 from trusted custody pins the manifest bytes.
Does not establish origin, audit custody, disclosure policy, database lineage,
freshness, or permission to import, checkpoint or publish.")

(defn arguments [args]
  (cond
    (or (= args ["--help"]) (= args ["-h"])) {:help? true}
    (and (= 1 (count args)) (not (str/starts-with? (first args) "-")))
    {:dir (first args)}
    (and (= 3 (count args)) (= "--expect-manifest" (second args))
         (not (str/starts-with? (first args) "-")) (integrity/sha256? (nth args 2)))
    {:dir (first args) :expected (nth args 2)}
    :else nil))

(defn main [args]
  (if-let [{:keys [help? dir expected]} (arguments args)]
    (if help?
      (do (println usage) 0)
      (try
        (prn (integrity/verify-tree dir expected))
        0
        (catch Exception ex
          (prn {:status :refused :reason (or (:reason (ex-data ex)) :unreadable-tree)})
          1)))
    (do (binding [*out* *err*] (println usage)) 2)))

(when (= (System/getProperty "babashka.file") *file*)
  (System/exit (main (vec *command-line-args*))))
