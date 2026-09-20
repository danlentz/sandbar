(ns sandbar.retract-file-test
  "A persisted retraction removes the projected file the entity owned — the
   retraction half of the filesystem/database bijection (the D4 finding of
   2026-09-19 and RT-06's deletion acceptance in Astra's review, folded into
   D7 on 2026-09-20).

   The bug: `entity.retract` with persist removed the memorial and its
   search hit, but the reactive sink only ever wrote files, so the projected
   Markdown stayed on disk as a file-only orphan; D7's cleanup would have
   turned every twin retraction into new drift.

   What this pins, against a scratch corpus root and the REAL retract verb:
     - the file the retracted entity owned is removed and reported;
     - a file another live entity still claims is kept, whichever twin is
       retracted (so an identless twin never removes the identful entity's
       file);
     - a file whose `id:` is not the retracted entity's is kept (a reused
       path is never removed for its earlier owner);
     - no file reports `:absent`;
     - an I/O failure is reported as `:failed` with its message and never
       hides the retraction that did commit;
     - a dry run lists each target's rel-path and the file effect a persist
       would have, and removes nothing;
     - an older queued projection that drains after the retraction does not
       resurrect the file (Astra's interleaving case);
     - a projection IN FLIGHT when the retraction commits — its content
       emitted, about to take the path's monitor — writes nothing, because
       the liveness decision is taken under that monitor (D7-R1, Astra
       2026-09-20);
     - a file whose front matter declares two ids is kept for disposition,
       never removed on the strength of the last one (D7-R2, Astra
       2026-09-20);
     - the claimant decision is taken under the monitor on the current
       database, so a claimant that arrives after the retraction committed
       but before the removal keeps the file (the second half of D7-R1)."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [clojure.test           :refer [deftest is testing use-fixtures]]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.retract        :as retract]
            [sandbar.store          :as store]
            [sandbar.test-util      :as tu])
  (:import [java.io File]))

(def ^:dynamic *root* nil)

(defn- delete-tree! [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn- scratch-root-fixture [f]
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "retract-file-" (System/currentTimeMillis) "-" (rand-int 1000000)))]
    (.mkdirs (io/file root "memory"))
    (md/register!)
    (binding [*root* root]
      (with-redefs [sinks/corpus-root (constantly (.getPath root))]
        (try (f) (finally (delete-tree! root)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "retract-file-test" :auth? false}) scratch-root-fixture)

(defn- corpus-file ^File [rel-path]
  (io/file *root* "memory" rel-path))

(defn- create-and-project!
  "Create an observation through the canonical store path and run the REAL
   sink on it, so its file exists under the scratch root.  Returns the entity."
  [rel-path]
  (let [e     (store/create-memory! :mm/Observation
                                    {:mm.memory/rel-path    rel-path
                                     :mm.memory/name        (str "retract file probe " rel-path)
                                     :mm.memory/description "a memorial whose file must go with it"
                                     :mm.memory/memory-type :observation
                                     :mm.memory/scope       :project})
        eid   (:db/id e)
        slots (into {:db/id eid} (db/entity eid))]
    (sinks/fs-projection-sink eid slots)
    (is (.exists (corpus-file rel-path)) "the file was projected")
    e))

(defn- retract! [target]
  (retract/retract! [target] {:persist true :cascade true :reason "retract-file-test"}))

(deftest the-file-the-retracted-entity-owned-is-removed
  (let [rel-path "observations/owned_probe.md"
        e        (create-and-project! rel-path)
        report   (retract! (:db/ident e))]
    (is (= 1 (:retracted-count report)))
    (is (= [{:rel-path rel-path :outcome :removed}] (:files report)) (pr-str (:files report)))
    (is (= 1 (:files-removed report)))
    (is (not (.exists (corpus-file rel-path))) "the file is gone")))

(deftest a-file-another-live-entity-claims-is-kept-whichever-twin-goes
  (let [rel-path "observations/claimed_probe.md"
        e        (create-and-project! rel-path)
        twin-eid (get-in @(d/transact (db/conn)
                                      [{:db/id "twin" :dt/type :mm/Observation
                                        :mm.memory/rel-path rel-path
                                        :mm.memory/name "an identless twin on the same path"
                                        :mm.memory/memory-type :observation}])
                         [:tempids "twin"])]
    (testing "retracting the identless twin keeps the identful entity's file"
      (let [report (retract! twin-eid)]
        (is (= 1 (:retracted-count report)))
        (is (= [{:rel-path rel-path :outcome :kept :reason :another-entity-claims-the-path}] (:files report)))
        (is (.exists (corpus-file rel-path)))))
    (testing "the sole remaining owner takes its file with it"
      (let [report (retract! (:db/ident e))]
        (is (= [{:rel-path rel-path :outcome :removed}] (:files report)))
        (is (not (.exists (corpus-file rel-path))))))))

(deftest a-file-whose-id-is-not-the-entitys-is-kept
  (let [rel-path "observations/reused_path_probe.md"
        e        (create-and-project! rel-path)
        f        (corpus-file rel-path)
        other-id "00000000-0000-4000-8000-000000000000"]
    (spit f (str/replace (slurp f) #"(?m)^id:.*$" (str "id: '" other-id "'")))
    (is (= other-id (sinks/projected-file-id f)) "the file now carries another id")
    (let [report (retract! (:db/ident e))]
      (is (= 1 (:retracted-count report)) "the retraction itself committed")
      (is (= [{:rel-path rel-path :outcome :kept :reason :file-id-differs :file-id other-id}] (:files report)))
      (is (.exists f) "the file is kept for its owner"))))

(deftest no-file-reports-absent
  (let [rel-path "observations/never_projected_probe.md"
        e        (store/create-memory! :mm/Observation
                                       {:mm.memory/rel-path    rel-path
                                        :mm.memory/name        "never projected"
                                        :mm.memory/description "no file to remove"
                                        :mm.memory/memory-type :observation
                                        :mm.memory/scope       :project})
        report   (retract! (:db/ident e))]
    (is (= 1 (:retracted-count report)))
    (is (= [{:rel-path rel-path :outcome :absent}] (:files report)))))

(deftest an-io-failure-is-reported-and-never-hides-the-retraction
  (let [rel-path "observations/io_failure_probe.md"
        e        (create-and-project! rel-path)
        report   (with-redefs-fn {#'sinks/delete-file! (fn [_] (throw (java.io.IOException. "disk says no")))}
                   #(retract! (:db/ident e)))]
    (is (true? (:persist report)))
    (is (= 1 (:retracted-count report)) "the retraction committed")
    (is (= :failed (-> report :files first :outcome)))
    (is (= "disk says no" (-> report :files first :error)))
    (is (zero? (:files-removed report)))
    (is (.exists (corpus-file rel-path)) "the file is still there for the operator")))

(deftest a-dry-run-names-the-file-effect-and-removes-nothing
  (let [rel-path "observations/dry_run_probe.md"
        e        (create-and-project! rel-path)
        report   (retract/retract! [(:db/ident e)] {})]
    (is (false? (:persist report)))
    (is (= rel-path (-> report :targets first :rel-path)))
    (is (some? (-> report :targets first :mm-id)))
    (is (= {:rel-path rel-path :outcome :would-remove} (-> report :targets first :file-effect)))
    (is (nil? (:files report)))
    (is (.exists (corpus-file rel-path)))))

(deftest an-older-queued-write-cannot-resurrect-a-removed-file
  (let [rel-path  "observations/stale_write_probe.md"
        e         (create-and-project! rel-path)
        eid       (:db/id e)
        old-slots (into {:db/id eid} (db/entity eid))
        report    (retract! (:db/ident e))]
    (is (= [{:rel-path rel-path :outcome :removed}] (:files report)))
    (is (not (.exists (corpus-file rel-path))))
    ;; the drain of a projection enqueued before the retraction arrives now
    (sinks/fs-projection-sink eid old-slots)
    (is (not (.exists (corpus-file rel-path))) "the stale write was skipped: the entity is gone")))

(deftest a-file-without-an-id-is-left-for-the-operator
  (let [rel-path "observations/no_id_probe.md"
        e        (create-and-project! rel-path)
        f        (corpus-file rel-path)]
    (spit f (str/replace (slurp f) #"(?m)^id:.*\n" ""))
    (is (nil? (sinks/projected-file-id f)))
    (let [report (retract! (:db/ident e))]
      (is (= [{:rel-path rel-path :outcome :kept :reason :file-has-no-id}] (:files report)))
      (is (.exists f)))))

(deftest an-in-flight-write-that-passed-its-check-cannot-recreate-a-removed-file
  ;; D7-R1 (Astra, 2026-09-20).  The writer has emitted its content and is
  ;; about to take the path's monitor when the retraction commits and removes
  ;; the file.  Before the fix the liveness decision was taken before the
  ;; monitor, so the resumed writer wrote its stale content over the removal;
  ;; now the decision is taken under the monitor and finds the entity gone.
  ;; The seam is `path-lock` itself: the first arrival is the writer.  Only
  ;; scheduling is controlled; the database check, retraction, parser, lock
  ;; and write are the real implementation.
  (let [rel-path      "observations/in_flight_probe.md"
        e             (create-and-project! rel-path)
        eid           (:db/id e)
        old-slots     (into {:db/id eid} (db/entity eid))
        f             (corpus-file rel-path)
        at-monitor    (promise)
        release       (promise)
        original-lock @#'sinks/path-lock
        paused?       (atom false)]
    (with-redefs-fn
      {#'sinks/path-lock
       (fn [path]
         (when (compare-and-set! paused? false true)
           (deliver at-monitor true)
           (assert (= true (deref release 10000 ::timeout)) "release timeout"))
         (original-lock path))}
      (fn []
        (let [writer (future (sinks/fs-projection-sink eid old-slots))]
          (try
            (is (= true (deref at-monitor 10000 ::timeout))
                "the writer is in flight: content emitted, about to take the monitor")
            (let [report (retract! (:db/ident e))]
              (is (= [{:rel-path rel-path :outcome :removed}] (:files report)))
              (is (not (.exists f)) "the removal completed while the writer waited")
              (is (db/entity-retracted? eid)))
            (deliver release true)
            (is (not= ::timeout (deref writer 10000 ::timeout)) "the writer finished")
            (is (not (.exists f)) "the resumed writer found the entity gone and wrote nothing")
            (finally
              (deliver release true)
              (deref writer 10000 ::timeout))))))))

(deftest conflicting-duplicate-file-identities-keep-the-file-for-disposition
  ;; D7-R2 (Astra, 2026-09-20).  A foreign id is declared above the
  ;; emitter's own; the parsed map would show only the last one.  Ownership
  ;; is read from the raw declarations, so the file is ambiguous and kept.
  (let [rel-path   "observations/duplicate_id_probe.md"
        e          (create-and-project! rel-path)
        f          (corpus-file rel-path)
        own-id     (str (:mm/id e))
        foreign-id "00000000-0000-4000-8000-000000000000"]
    (spit f (str/replace-first (slurp f) "---\n" (str "---\nid: '" foreign-id "'\n")))
    (is (= 2 (count (re-seq #"(?m)^id:" (slurp f)))) "the file declares two ids")
    (is (= {:ambiguous? true :ids [foreign-id own-id]} (sinks/projected-file-identity f)))
    (is (nil? (sinks/projected-file-id f)) "no single id can be read off an ambiguous file")
    (let [report (retract! (:db/ident e))]
      (is (= 1 (:retracted-count report)) "the retraction itself committed")
      (is (= [{:rel-path rel-path :outcome :kept :reason :file-id-ambiguous
               :file-ids [foreign-id own-id]}]
             (:files report)))
      (is (zero? (:files-removed report)))
      (is (.exists f) "ambiguous ownership keeps the file for disposition"))))

(deftest the-claimant-decision-is-taken-under-the-monitor-on-the-current-database
  ;; The second half of D7-R1 (Astra, 2026-09-20): a claimant that arrives
  ;; after the retraction committed but before the removal takes the monitor
  ;; must keep the file.  The seam is the removal's own arrival at
  ;; `path-lock` (the first call made once the entity is retracted; the dry
  ;; run's earlier call passes through); while it waits, a new entity takes
  ;; the rel-path.
  (let [rel-path      "observations/late_claimant_probe.md"
        e             (create-and-project! rel-path)
        eid           (:db/id e)
        f             (corpus-file rel-path)
        at-monitor    (promise)
        release       (promise)
        original-lock @#'sinks/path-lock
        paused?       (atom false)]
    (with-redefs-fn
      {#'sinks/path-lock
       (fn [path]
         (when (and (db/entity-retracted? eid) (compare-and-set! paused? false true))
           (deliver at-monitor true)
           (assert (= true (deref release 10000 ::timeout)) "release timeout"))
         (original-lock path))}
      (fn []
        (let [retraction (future (retract! (:db/ident e)))]
          (try
            (is (= true (deref at-monitor 10000 ::timeout))
                "the retraction committed and its removal is about to take the monitor")
            @(d/transact (db/conn)
                         [{:db/id "late" :dt/type :mm/Observation
                           :mm.memory/rel-path rel-path
                           :mm.memory/name "a claimant that arrived after the retraction committed"
                           :mm.memory/memory-type :observation}])
            (deliver release true)
            (let [report (deref retraction 10000 ::timeout)]
              (is (not= ::timeout report) "the retraction finished")
              (is (= 1 (:retracted-count report)))
              (is (= [{:rel-path rel-path :outcome :kept :reason :another-entity-claims-the-path}]
                     (:files report)))
              (is (.exists f) "the file is kept for the entity that now claims the path"))
            (finally
              (deliver release true)
              (deref retraction 10000 ::timeout))))))))
