(ns sandbar.gate.roundtrip
  "CHECK 1 of the W1.J release gate — DB→FS→(git seam)→DB round-trip.

   Composes the LIVE ends of the projection pipeline with a clearly-marked
   git seam in the middle, then asserts the 8-query semantic contract
   (`sandbar.gate.roundtrip-contract`).  The real future pipeline is
     DB --sink/export(md)--> FS --W1.F(git commit)--> remote
        --W1.F(clone)--> FS --W1.G(import)--> DB
   Today both FS-touching ENDS are live (`project.export` /
   `project.import`); ONLY the git commit+clone middle is unbuilt, so
   `clone-stub!` (a filesystem deep-copy) stands in for it behind a single
   named seam — when W1.F lands, swap `clone-stub!` for the git round and
   NOTHING else in this namespace or the contract changes (that stability
   is why W1.J is authored EARLY per the arc plan, so G1 — round-trip
   green is a hard precondition of the first W1.F commit — is enforceable).

   Fidelity is asserted at the DB level against the SETTLED (at-rest)
   corpus: the real corpus at rest is always the emitted form (the sink
   wrote it), so the reference DB is rebuilt from one export pass before
   the round-trip proper — this measures round-trip FIDELITY, not
   fixture-authoring normalization, keeping Q6 (per-file body SHA) exact."
  (:require [clojure.java.io :as io]
            [sandbar.codec.markdown          :as codec-md]
            [sandbar.db.datatype             :as dt]
            [sandbar.gate.db                 :as gdb]
            [sandbar.gate.roundtrip-contract :as contract]
            [sandbar.projection              :as pg]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tmp-dir helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fresh-tmp-dir ^java.io.File [stem]
  (let [f (java.io.File/createTempFile (str "w1j-" stem "-") "")]
    (.delete f) (.mkdirs f) f))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- copy-tree!
  "Deep-copy every file under `src` into `dst` (created if absent)."
  [^java.io.File src ^java.io.File dst]
  (.mkdirs dst)
  (doseq [^java.io.File c (.listFiles src)]
    (let [target (io/file dst (.getName c))]
      (if (.isDirectory c)
        (copy-tree! c target)
        (io/copy c target)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the live pipeline ends — import (FS→DB) + export (DB→FS)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn import-corpus!
  "FS→DB: mirror the canonical `project.import` persist path (ingest-graph
   → group-by-source → entity-specs->tx-data → make-all* per group).
   Returns `{:persisted N :failed [...] :refused [...]}`.  Requires an
   ambient fresh db (see `sandbar.gate.db/with-fresh-db*`)."
  [from]
  (let [entities (pg/ingest-graph from {})
        groups   (codec-md/group-by-source entities)]
    (reduce
     (fn [acc group]
       (let [ident (:db/ident (first group))]
         (try
           (dt/make-all* (codec-md/entity-specs->tx-data group))
           (update acc :persisted conj ident)
           (catch clojure.lang.ExceptionInfo ex
             (if (some #(= :firewall-violation (:type %)) (:errors (ex-data ex)))
               (update acc :refused conj {:ident ident :error (.getMessage ex)})
               (update acc :failed  conj {:ident ident :error (.getMessage ex)})))
           (catch Throwable ex
             (update acc :failed conj {:ident ident :error (.getMessage ex)})))))
     {:persisted [] :failed [] :refused []}
     groups)))

(defn export-corpus!
  "DB→FS: mirror the canonical `project.export` path (realize each
   :mm/Memory + its section tree via `pg/mm-walker`, then `pg/project-graph`
   to `to`).  Returns the vector of written `{:rel-path ...}` records."
  [to]
  (let [memories    (dt/all-instances-of :mm/Memory)
        entity-maps (vec
                     (mapcat
                      (fn [memory]
                        (let [realized (dt/realize-with memory pg/mm-walker)]
                          (map #(if (dt/type-isa? :mm/Memory (:dt/type %))
                                  (update % :dt/type (fn [t] (or t :mm/Memory)))
                                  %)
                               realized)))
                      memories))]
    (pg/project-graph entity-maps {:to (str to)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE GIT SEAM  (W1.F) — the ONLY stubbed step in CHECK 1
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clone-stub!
  "SEAM (W1.F, UNBUILT): a filesystem deep-copy standing in for the git
   commit → orphan-branch → phantom-ref → clone round.  A real W1.F
   implementation replaces THIS FUNCTION ONLY (commit `export-dir` to the
   per-project corpus repo; `git clone` it into `clone-dir`); the contract
   assertion around it is unchanged.  Returns `clone-dir`."
  [^java.io.File export-dir ^java.io.File clone-dir]
  (copy-tree! export-dir clone-dir)
  clone-dir)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CHECK 1 — one corpus round-trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn round-trip-corpus
  "Round-trip the corpus rooted at `corpus-root` and return
     {:corpus <path> :equivalent? bool :contract <compare-result>
      :import-health {:reference {...} :reconstruct {...}}}

   `opts` key `:allowed-drift` flows to the contract comparison (empty for
   the fixture; the live run passes the documented 226-item drift set)."
  ([corpus-root] (round-trip-corpus corpus-root {}))
  ([corpus-root {:keys [allowed-drift] :or {allowed-drift #{}}}]
   (let [settle-dir  (fresh-tmp-dir "settle")
         export-dir  (fresh-tmp-dir "export")
         clone-dir   (fresh-tmp-dir "clone")]
     (try
       ;; (0) SETTLE — normalize the fixture to its at-rest emitted form.
       (gdb/with-fresh-db* {:name "rt-settle"}
         (fn [] (import-corpus! corpus-root) (export-corpus! settle-dir)))
       ;; (A) REFERENCE — the "live DB" reconstructed from the at-rest corpus,
       ;;     then exported (the checkpoint a W1.F commit would version).
       (let [ref-health (atom nil)
             contract-a (gdb/with-fresh-db* {:name "rt-reference"}
                          (fn []
                            (reset! ref-health (import-corpus! settle-dir))
                            (let [snap (contract/capture)]
                              (export-corpus! export-dir)
                              snap)))
             ;; (git SEAM) commit + clone — stubbed until W1.F.
             _ (clone-stub! export-dir clone-dir)
             ;; (B) RECONSTRUCT — disaster-recovery import of the cloned tree.
             rec-health (atom nil)
             contract-b (gdb/with-fresh-db* {:name "rt-reconstruct"}
                          (fn []
                            (reset! rec-health (import-corpus! clone-dir))
                            (contract/capture)))
             cmp (contract/compare-contracts contract-a contract-b
                                             {:allowed-drift allowed-drift})]
         {:corpus        (str corpus-root)
          :equivalent?   (:equivalent? cmp)
          :contract      cmp
          :import-health {:reference @ref-health :reconstruct @rec-health}})
       (finally
         (rm-rf! settle-dir) (rm-rf! export-dir) (rm-rf! clone-dir))))))

(defn run
  "CHECK 1 over one or more corpus roots.  Green iff every corpus
   round-trips to semantic equivalence.  Returns
     {:pass bool :corpora [<round-trip-corpus result> ...]}."
  ([corpus-roots] (run corpus-roots {}))
  ([corpus-roots opts]
   (let [results (mapv #(round-trip-corpus % opts) corpus-roots)]
     {:pass    (every? :equivalent? results)
      :corpora results})))
