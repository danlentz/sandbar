(ns sandbar.scripts.roundtrip-diff
  "Print the diff between an original markdown file and its round-trip
   emitted form.  Used during Stage 4.B fidelity work to identify what
   normalization changes happen at parse + emit time."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

(defn -main
  [& [corpus-root rel-path]]
  (when (or (str/blank? (str corpus-root)) (str/blank? (str rel-path)))
    (println "Usage: lein run -m sandbar.scripts.roundtrip-diff <corpus-root> <rel-path>")
    (System/exit 2))
  (let [test-uri "datomic:mem://roundtrip-diff"]
    (d/delete-database test-uri)
    (d/create-database test-uri)
    (reset! db/**conn* (d/connect test-uri))
    (try
      (tu/load-required-schema (db/conn))
      (let [source   (slurp (io/file corpus-root rel-path))
            entities (codec-md/parse-document source rel-path)
            emitted  (codec-md/emit-document entities)
            orig-lines (str/split-lines source)
            emit-lines (str/split-lines emitted)]
        (println (str "=== ORIGINAL (" (count source) " bytes, "
                      (count orig-lines) " lines) ==="))
        (println source)
        (println)
        (println (str "=== EMITTED (" (count emitted) " bytes, "
                      (count emit-lines) " lines) ==="))
        (println emitted)
        (println)
        (println "=== LINE-LEVEL DIFF (first 30 differing lines) ===")
        (loop [[o & ors] orig-lines
               [e & es] emit-lines
               i 0
               shown 0]
          (cond
            (>= shown 30)
            (println (str "... (truncated at 30 diffs)"))

            (and (nil? o) (nil? e))
            (println "(end-of-file)")

            (= o e)
            (recur ors es (inc i) shown)

            :else
            (do
              (println (str "L" i ": ORIG: " (pr-str o)))
              (println (str "L" i ": EMIT: " (pr-str e)))
              (recur ors es (inc i) (inc shown))))))
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)
        (System/exit 0)))))
