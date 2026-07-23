(ns sandbar.scripts.reproject-bootstrap
  "Re-project sandbar's substrate state to client-project bootstrap memorials.

   Per plans/sandbar_bootstrap_authority_arc_2026_05_21.md Stage 3 +
   decisions/bootstrap_authority_q1_q5_resolutions_2026_05_21.md.

   Default mode: SAFE — writes to a scratch directory.  --canonical
   flag overwrites the client-project memory/.  --dry-run shows what
   WOULD change without writing.

   Per interaction/bootstrap_rendering_must_preserve_narrative_human_readable_content_2026_05_21.md
   — output quality is currently INSUFFICIENT for safe canonical overwrite
   (introspection-only; no narrative).  Stage 2.D template work gates
   the --canonical flag's acceptance.

   Usage:
     lein reproject-bootstrap                       # scratch dir default
     lein reproject-bootstrap -- <out-dir>          # explicit dir
     lein reproject-bootstrap -- --dry-run          # report only
     lein reproject-bootstrap -- --canonical        # CANONICAL overwrite
     lein reproject-bootstrap -- --class :mm/Memory # restrict to one class"
  (:require [clojure.java.io          :as io]
            [clojure.string           :as str]
            [datomic.api              :as d]
            [sandbar.bootstrap.render :as render]
            [sandbar.db.datomic       :as db]
            [sandbar.test-util        :as tu]))

(defn- timestamp-suffix []
  (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))

(defn- parse-argv
  "Parse the argv vector.  Returns {:mode :out-dir :class :dry-run? :canonical?}."
  [argv]
  (loop [args argv
         opts {:mode :scratch
               :out-dir nil
               :class nil
               :dry-run? false
               :canonical? false}]
    (if (empty? args)
      opts
      (let [[a & rest] args]
        (case a
          "--dry-run"   (recur rest (assoc opts :dry-run? true))
          "--canonical" (recur rest (assoc opts :canonical? true :mode :canonical))
          "--class"     (recur (drop 1 rest)
                                (assoc opts :class (keyword (subs (first rest) 1))))
          (recur rest (assoc opts :out-dir a :mode :explicit)))))))

(defn- resolve-out-dir
  "Compute the target dir per the mode."
  [{:keys [mode out-dir canonical?]}]
  (cond
    canonical? (or (System/getenv "SANDBAR_CLIENT_DIR")
                   (str (System/getenv "HOME") "/claude"))
    (= mode :explicit) out-dir
    :else (str "/tmp/sandbar-reproject-" (timestamp-suffix))))

(defn- write-file!
  "Write content to <out-dir>/<rel-path>, creating parent dirs as needed."
  [out-dir rel-path content]
  (let [f (io/file out-dir rel-path)]
    (io/make-parents f)
    (spit f content)))

(defn -main
  [& argv]
  (let [opts    (parse-argv argv)
        out-dir (resolve-out-dir opts)]
    (println (format "reproject-bootstrap mode=%s out-dir=%s class=%s dry-run?=%s"
                     (:mode opts) out-dir (or (:class opts) "all") (:dry-run? opts)))
    ;; Bring up a fresh in-memory DB so render introspects current schema
    ;; (NOTE: For full corpus-state render, the running sandbar's DB should
    ;; be the source — that's Stage 4 refinement.  This scratch path renders
    ;; from schema-only state.)
    (let [test-uri "datomic:mem://reproject-bootstrap"]
      (d/delete-database test-uri)
      (d/create-database test-uri)
      (reset! db/**conn* (d/connect test-uri))
      (tu/load-required-schema (db/conn))
      (let [renders (cond
                      (:class opts) [(render/render-class (:class opts))]
                      :else         (render/render-all-bootstrap))
            n       (count renders)]
        (println (format "rendering %d memorial(s)..." n))
        (when (:canonical? opts)
          (binding [*out* *err*]
            (println "WARNING: --canonical mode will overwrite client-project memory/")
            (println "Per interaction/bootstrap_rendering_must_preserve_narrative_human_readable_content_2026_05_21.md")
            (println "the renderer is INSUFFICIENT for safe canonical overwrite (introspection-only;")
            (println "no narrative templates yet).  Stage 2.D template work gates this acceptance.")))
        (doseq [{:keys [rel-path content]} renders]
          (if (:dry-run? opts)
            (println (format "  WOULD WRITE %d bytes → %s" (count content) rel-path))
            (do (write-file! out-dir rel-path content)
                (println (format "  wrote %d bytes → %s/%s" (count content) out-dir rel-path)))))
        (println (format "done.  %d memorial(s) %s."
                         n
                         (if (:dry-run? opts) "would-have-been-rendered" "rendered")))
        (System/exit 0)))))
