(ns sandbar.scripts.full-corpus-roundtrip
  "Stage 4.A onset — full-corpus inverse-projection round-trip check.

   For each markdown memorial:
     1. Read original source
     2. parse → entity-specs (canonical-form via parse-document)
     3. emit-document → emitted source
     4. compare original vs emitted

   Aggregates per-file match / mismatch + first-N mismatch samples.

   Per parent 0.1.1 arc plan §2 Stage 4 (inverse projection: sandbar Datomic
   → fs/markdown round-trip).  This is the FILESYSTEM round-trip check —
   parse + emit are pure functions on a file's contents.  Datomic-round-trip
   (transact + query + emit) is a follow-on Stage 4.B+.

   ## Usage

     lein run -m sandbar.scripts.full-corpus-roundtrip <corpus-root>

   ## Output

     Stdout: summary counts + first-N mismatch samples (showing how the
     emitted differs from the original)."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

(defn- markdown-files
  "Walk `root` for `.md` files."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (sort-by #(.getPath ^java.io.File %))))

(defn- rel-path
  [^java.io.File root ^java.io.File file]
  (let [root-path (.getCanonicalPath root)
        file-path (.getCanonicalPath file)]
    (if (str/starts-with? file-path (str root-path "/"))
      (subs file-path (inc (count root-path)))
      file-path)))

(defn roundtrip-file
  "Round-trip one file.  Returns
     {:rel-path <string>
      :match? <bool>
      :original-bytes <N>
      :emitted-bytes <N>
      :error <string-or-nil>
      :diff-sample <string-or-nil>}"
  [^java.io.File root ^java.io.File file]
  (let [rp     (rel-path root file)
        source (try (slurp file) (catch Exception e (.getMessage e)))]
    (try
      (let [entities (codec-md/parse-document source rp)
            emitted  (codec-md/emit-document entities)
            match?   (= source emitted)]
        {:rel-path       rp
         :match?         match?
         :original-bytes (count source)
         :emitted-bytes  (count emitted)
         :error          nil
         :diff-sample    (when (not match?)
                           (str "ORIG (first 200): "
                                (subs source 0 (min 200 (count source)))
                                "\nEMITTED (first 200): "
                                (subs emitted 0 (min 200 (count emitted)))))})
      (catch Throwable ex
        {:rel-path rp :match? false :error (.getMessage ex)
         :original-bytes (count source) :emitted-bytes 0}))))

(defn run!
  [{:keys [root limit] :or {limit 0}}]
  (when (str/blank? (str root))
    (throw (ex-info "full-corpus-roundtrip/run! requires :root" {})))
  (let [root-file (io/file root)
        files     (cond->> (markdown-files root-file)
                    (pos? limit) (take limit))
        test-uri  "datomic:mem://full-corpus-roundtrip"]
    (d/delete-database test-uri)
    (d/create-database test-uri)
    (reset! db/**conn* (d/connect test-uri))
    (try
      (println "Loading sandbar schema for codec introspection...")
      (tu/load-required-schema (db/conn))
      (println (str "Round-tripping " (count files) " files from " root "..."))
      (let [t0         (System/currentTimeMillis)
            results    (mapv #(roundtrip-file root-file %) files)
            t1         (System/currentTimeMillis)
            matches    (filter :match? results)
            mismatches (remove :match? results)
            errors     (filter :error results)]
        (println (str "Completed in " (- t1 t0) "ms"))
        (println (str "  matches:    " (count matches)))
        (println (str "  mismatches: " (count mismatches)))
        (println (str "  errors:     " (count errors)))
        (println (str "  match rate: "
                      (format "%.1f%%" (* 100.0 (/ (count matches) (max 1 (count files)))))))
        (when (seq mismatches)
          (println "\nFirst 5 mismatch samples (non-error):")
          (doseq [m (take 5 (remove :error mismatches))]
            (println (str "  " (:rel-path m) " — orig=" (:original-bytes m)
                          "B emitted=" (:emitted-bytes m) "B"))))
        (when (seq errors)
          (println "\nFirst 5 errors:")
          (doseq [e (take 5 errors)]
            (println (str "  " (:rel-path e) ": "
                          (subs (str (:error e)) 0
                                (min 150 (count (str (:error e)))))))))
        {:total (count files)
         :matches (count matches)
         :mismatches (count mismatches)
         :errors (count errors)})
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)))))

(defn- parse-args
  [args]
  (loop [[a & more] args
         out {:limit 0}]
    (cond
      (nil? a)            out
      (= a "--limit")     (recur (rest more) (assoc out :limit (Long/parseLong (first more))))
      (nil? (:root out))  (recur more (assoc out :root a))
      :else               (recur more out))))

(defn -main
  [& args]
  (let [opts (parse-args args)]
    (when (str/blank? (str (:root opts)))
      (println "Usage: lein run -m sandbar.scripts.full-corpus-roundtrip <corpus-root> [--limit N]")
      (System/exit 2))
    (run! opts)
    (System/exit 0)))
