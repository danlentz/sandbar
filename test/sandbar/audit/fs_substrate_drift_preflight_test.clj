(ns sandbar.audit.fs-substrate-drift-preflight-test
  "The enrollment preflight of the FS↔substrate drift audit.

   Before an operator maps a tree in `:project-roots`, the audit names the
   conditions a routed write would later refuse — files and documents
   without a durable UUID, file ownership that is absent, ambiguous or
   mismatched, non-canonical and aliased physical paths as the filesystem
   itself resolves them, invalid explicit owners, and every read or
   resolution error — attributed to the inspected tree, with the operator's
   explicit dispositions reported rather than counted.  Detection only.
   Same fixture shape as `sandbar.audit.fs-substrate-drift-roots-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.audit.fs-substrate-drift :as drift]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.project.destination :as dest]
            [sandbar.projection :as pg]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *base* nil)
(def ^:dynamic *roots* nil)

(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- memory-dir [r] (io/file (root r) "memory"))
(defn- memory-file [r p] (io/file (memory-dir r) p))

(defn- remove-tree! [f]
  (when (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f))))
    (doseq [c (.listFiles f)] (remove-tree! c)))
  (.setReadable f true false)
  (.delete f))

(defn- fixture [f]
  (let [base     (.toFile (Files/createTempDirectory "drift-preflight-test-" (make-array FileAttribute 0)))
        original config/value]
    (binding [*base* base *roots* (atom {})]
      (doseq [s ["global" "a" "adhoc"]] (.mkdirs (memory-file s "")))
      (md/register!)
      @(d/transact (db/conn)
                   [{:db/id "public-context" :db/ident :context/drift-preflight-public :dt/type :mm/Context
                     :mm.memory/name "public" :mm.context/firewall-class :public-bottom}
                    {:db/ident :memory.projects/preflight_a
                     :dt/type :mm/Project :mm.memory/name "a"
                     :mm.project/ident :project/preflight-a
                     :mm.project/default-visibility :public
                     :mm.project/firewall-class :public-bottom
                     :mm.project/runs-in-context "public-context"}])
      (reset! *roots* {:project/preflight-a (root "a")})
      (with-redefs [config/value (fn [k & more]
                                  (if (= k :project-roots) @*roots* (apply original k more)))
                    dest/global-root #(root "global")]
        (try (f) (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "drift-preflight" :auth? false}) fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- document!
  "A live document: `:slug` under observations/ (or `:rel` verbatim), owned
   by the mapped project when `:owned?`, carrying a durable UUID unless
   `:uuid?` is false.  Returns its ident."
  [{:keys [slug rel owned? uuid?] :or {uuid? true}}]
  (let [ident (keyword "memory.observations" slug)
        rel   (or rel (str "observations/" slug ".md"))]
    (dt/make :mm/Observation
             (cond-> {:db/ident ident :mm.memory/name slug :mm.memory/rel-path rel
                      :mm.memory/body-raw "Body.\n"}
               uuid?  (assoc :mm/id (java.util.UUID/randomUUID))
               owned? (assoc :mm.memory/owning-project :memory.projects/preflight_a)))
    ident))

(defn- emit!
  "The document's canonical file, written into tree `r` at `rel` (its own
   rel-path by default) exactly as the sink would write it."
  ([ident r] (emit! ident r (:mm.memory/rel-path (db/entity ident))))
  ([ident r rel]
   (let [f (memory-file r rel)]
     (io/make-parents f)
     (spit f (pg/realize-and-emit-entity (db/entity ident)))
     f)))

(defn- write-file!
  "A hand-authored file in tree `r`: the `id:` lines given (none by default)."
  [r rel & ids]
  (let [f (memory-file r rel)]
    (io/make-parents f)
    (spit f (str "---\nname: " rel "\ntype: observation\nscope: global\n"
                 (apply str (map #(str "id: '" % "'\n") ids))
                 "---\n\n# doc\n\nBody.\n"))
    f))

(defn- preflight
  ([r] (preflight r {}))
  ([r opts]
   (let [report (drift/audit-all (merge {:from (root r)} opts))]
     (assoc (:enrollment-preflight report) ::report report))))

(defn- stored-id [ident] (str (:mm/id (db/entity ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-clean-mapped-tree-is-clear-for-enrollment
  (let [ident (document! {:slug "clean" :owned? true})
        _     (emit! ident "a")
        pf    (preflight "a")
        s     (:summary (::report pf))]
    (is (true? (:clear-for-enrollment? pf)) (pr-str (dissoc pf ::report)))
    (is (nil? (:reason pf)))
    (is (= 0 (:unresolved-count pf)))
    (is (= 0 (:error-count pf)))
    (is (= 1 (:ownership-matched-count pf)) "the emitted file carries the stored UUID")
    (is (= {:kind :mapped :root (root "a") :project-key :project/preflight-a} (:tree pf)))
    (doseq [k [:files-without-uuid :files-ambiguous-uuid :documents-without-uuid :ownership
               :noncanonical-paths :alias-groups :invalid-owners :files-of-other-trees :errors]]
      (is (= [] (get pf k)) (str k)))
    (is (= {} (:excluded pf)))
    (is (contains? #{true false :unknown} (get-in pf [:filesystem :case-insensitive?])))
    (testing "the summary carries the three headline numbers"
      (is (= 0 (:enrollment-preflight-unresolved-count s)))
      (is (= 0 (:enrollment-preflight-error-count s)))
      (is (true? (:enrollment-preflight-clear? s))))))

(deftest files-and-documents-without-a-uuid-are-named-as-that
  (testing "a document with no :mm/id and its file with no id line — two conditions, one path, the :db/ident intact"
    (let [ident (document! {:slug "no_uuid" :owned? true :uuid? false})
          _     (emit! ident "a")
          pf    (preflight "a")]
      (is (= [{:rel-path "observations/no_uuid.md"}] (:files-without-uuid pf)))
      (is (= [{:rel-path "observations/no_uuid.md" :entity-ident ident :missing :mm/id}]
             (:documents-without-uuid pf))
          "what is missing is the durable UUID; the document's :db/ident is present and shown")
      (is (= [] (:ownership pf)) "ownership is undecidable without a stored UUID, so it is not called mismatched")
      (is (= 2 (:unresolved-count pf)))
      (is (false? (:clear-for-enrollment? pf)))
      (is (string? (:reason pf)))))
  (testing "a document with a UUID whose hand-authored file claims none: the file lacks the UUID and ownership is absent"
    (let [ident (document! {:slug "has_uuid" :owned? true})
          _     (write-file! "a" "observations/has_uuid.md")
          pf    (preflight "a")
          own   (filter #(= "observations/has_uuid.md" (:rel-path %)) (:ownership pf))]
      (is (some #(= "observations/has_uuid.md" (:rel-path %)) (:files-without-uuid pf)))
      (is (= [{:rel-path "observations/has_uuid.md" :entity-ident ident
               :ownership :absent :stored-id (stored-id ident)}]
             (vec own)))
      (is (= [] (filter #(= "observations/has_uuid.md" (:rel-path %)) (:documents-without-uuid pf)))))))

(deftest ambiguous-and-foreign-file-identities-are-named
  (let [amb  (document! {:slug "ambiguous" :owned? true})
        _    (write-file! "a" "observations/ambiguous.md" (stored-id amb) (str (java.util.UUID/randomUUID)))
        mis  (document! {:slug "mismatched" :owned? true})
        other (str (java.util.UUID/randomUUID))
        _    (write-file! "a" "observations/mismatched.md" other)
        pf   (preflight "a")
        by   (fn [k p] (first (filter #(= p (:rel-path %)) (get pf k))))]
    (testing "two id lines: the file's identity is ambiguous, whatever the values (D7-R2)"
      (is (= 2 (count (:ids (by :files-ambiguous-uuid "observations/ambiguous.md")))))
      (is (= :ambiguous (:ownership (by :ownership "observations/ambiguous.md"))))
      (is (= (stored-id amb) (:stored-id (by :ownership "observations/ambiguous.md")))))
    (testing "a different single id: mismatched, both identities shown"
      (is (= {:rel-path "observations/mismatched.md" :entity-ident mis :ownership :mismatched
              :stored-id (stored-id mis) :file-id other}
             (by :ownership "observations/mismatched.md"))))
    (is (= 0 (:ownership-matched-count pf)))
    (is (= [] (:files-without-uuid pf)))
    (is (false? (:clear-for-enrollment? pf)))))

(deftest physical-path-aliases-and-noncanonical-spellings-come-from-the-filesystem
  (let [real   (document! {:slug "alias" :rel "decisions/alias.md" :owned? true})
        _      (emit! real "a")
        dotted (document! {:slug "dotted" :rel "observations/../decisions/alias.md" :owned? true})
        linked (document! {:slug "linked" :owned? true})
        _      (emit! linked "a")
        _      (Files/createSymbolicLink (.toPath (io/file (memory-dir "a") "obs"))
                                         (.toPath (io/file (memory-dir "a") "observations"))
                                         (make-array FileAttribute 0))
        via-link (document! {:slug "via_link" :rel "obs/linked.md" :owned? true})
        pf     (preflight "a")
        groups (into {} (map (juxt :canonical :rel-paths)) (:alias-groups pf))
        nonc   (set (map :rel-path (:noncanonical-paths pf)))]
    (testing "a dot-segment spelling and the plain spelling resolve to one target"
      (is (= ["decisions/alias.md" "observations/../decisions/alias.md"]
             (get groups (.getCanonicalPath (memory-file "a" "decisions/alias.md")))))
      (is (contains? nonc "observations/../decisions/alias.md"))
      (is (not (contains? nonc "decisions/alias.md"))))
    (testing "a symlinked component is an alias of the real path and a non-canonical spelling"
      (is (= ["obs/linked.md" "observations/linked.md"]
             (get groups (.getCanonicalPath (memory-file "a" "observations/linked.md")))))
      (is (contains? nonc "obs/linked.md")))
    (testing "case is folded only where the filesystem folds it"
      (let [cased (document! {:slug "case" :owned? true})
            _     (write-file! "a" "observations/Case.md" (stored-id cased))
            pf2   (preflight "a")
            folds (get-in pf2 [:filesystem :case-insensitive?])
            g2    (->> (:alias-groups pf2)
                       (filter #(some #{"observations/case.md"} (:rel-paths %)))
                       first)]
        (case folds
          true  (do (is (= ["observations/Case.md" "observations/case.md"] (:rel-paths g2))
                        "a case-folding filesystem: one physical file, two spellings")
                    (when (str/ends-with? (.getCanonicalPath (memory-file "a" "observations/case.md")) "Case.md")
                      ;; the JDK resolved the spelling to the file on disk, as
                      ;; destination/target-path will: that spelling is the one refused
                      (is (contains? (set (map :rel-path (:noncanonical-paths pf2))) "observations/case.md")
                          "the spelling that differs from the file on disk is the one a routed write would refuse")))
          false (is (nil? g2) "a case-sensitive filesystem: two different files, no alias")
          :unknown (is (nil? g2) "no folding is applied when the filesystem could not be asked"))))
    (is (false? (:clear-for-enrollment? pf)))
    (is (some? dotted))
    (is (some? via-link))))

(deftest an-invalid-owner-is-reported-not-excluded
  (let [not-a-project (document! {:slug "not_a_project"})
        bad-ident     :memory.observations/bad_owner]
    @(d/transact (db/conn)
                 [{:db/ident bad-ident :dt/type :mm/Observation :mm.memory/name "bad"
                   :mm.memory/rel-path "observations/bad_owner.md" :mm.memory/body-raw "Body.\n"
                   :mm/id (java.util.UUID/randomUUID)
                   :mm.memory/owning-project [:db/ident not-a-project]}])
    (emit! bad-ident "a")
    (let [pf   (preflight "a")
          row  (first (:invalid-owners pf))
          rep  (::report pf)]
      (testing "named, with why and where its file is"
        (is (= {:rel-path "observations/bad_owner.md" :entity-ident bad-ident
                :owner (str not-a-project) :reason :owner-not-a-project :file-present-in-tree? true}
               row)))
      (is (= 1 (:invalid-owners-store-wide-count pf)))
      (is (= 1 (:invalid-owners-counted-count pf)) "its file sits in this tree, so it counts here")
      (testing "the audit's per-root selection had left the document out — its file is 'missing from the substrate' — and the preflight is what explains that row"
        (is (some #{"observations/bad_owner.md"} (:missing-from-substrate rep))))
      (testing "the same owner is the one a routed write refuses: no second ownership policy"
        (is (= :invalid-owner
               (try (dest/for-entity (db/db) (db/entity bad-ident) (root "global")) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
      (is (false? (:clear-for-enrollment? pf))))
    (testing "another tree does not count it, but still sees the store-wide population"
      (let [pf (preflight "global")]
        (is (= 1 (:invalid-owners-store-wide-count pf)))
        (is (= 1 (:invalid-owners-counted-count pf)) "the global tree is where an unattributable document would project, so it counts there too")))))

(deftest a-file-of-another-tree-is-explained
  (let [ident (document! {:slug "stray" :owned? true})]
    (emit! ident "global")
    (let [pf  (preflight "global")
          rep (::report pf)]
      (is (= [{:rel-path "observations/stray.md" :entity-idents [ident] :selected-root (root "a")}]
             (:files-of-other-trees pf)))
      (is (some #{"observations/stray.md"} (:missing-from-substrate rep)))
      ;; the global tree also holds the seeded bootstrap document, whose own
      ;; conditions (no file here, no UUID) are counted with it; this case
      ;; pins the stray file's row, not the seeded population's tally
      (is (pos? (:unresolved-count pf)))
      (is (false? (:clear-for-enrollment? pf))))))

(deftest excluded-paths-are-explicit-dispositions
  (let [ident (document! {:slug "legacy" :owned? true :uuid? false})
        _     (emit! ident "a")
        pf    (preflight "a" {:exclude ["observations/legacy.md"]})]
    (is (= 0 (:unresolved-count pf)))
    (is (true? (:clear-for-enrollment? pf)))
    (is (= #{:files-without-uuid :documents-without-uuid}
           (set (map :condition (get (:excluded pf) "observations/legacy.md"))))
        "the conditions are still reported, under the disposition")
    (is (= [] (:files-without-uuid pf)))
    (is (= [] (:documents-without-uuid pf)))))

(deftest an-unreadable-file-is-a-named-error-not-a-clean-zero
  (let [ident (document! {:slug "locked" :owned? true})
        f     (emit! ident "a")]
    (.setReadable f false false)
    (if (.canRead f)
      (println "fs-substrate-drift-preflight-test: read permission could not be withdrawn (running with an override); the unreadable-file case is not exercised")
      (let [pf (preflight "a")]
        ;; the file fails at both phases that read it — the identity walk
        ;; and the ingestion parse — and each is its own named error
        (is (= #{{:rel-path "observations/locked.md" :phase :read-identity}
                 {:rel-path "observations/locked.md" :phase :parse}}
               (set (map #(select-keys % [:rel-path :phase]) (:errors pf)))))
        (is (every? (comp string? :error) (:errors pf)))
        (is (= 2 (:error-count pf)))
        (is (false? (:clear-for-enrollment? pf)))
        (is (= [] (:files-without-uuid pf)) "an unreadable file is not called identity-less")))
    (.setReadable f true false)))

(deftest an-ad-hoc-directory-is-never-clear
  (write-file! "adhoc" "observations/loose.md")
  (let [pf (preflight "adhoc")]
    (is (= :ad-hoc (get-in pf [:tree :kind])))
    (is (false? (:clear-for-enrollment? pf)))
    (is (string? (:reason pf)))
    (is (= [{:rel-path "observations/loose.md"}] (:files-without-uuid pf))
        "the file conditions are still computed for an ad hoc walk")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The two false-clear paths the lead's source review named (21:02Z)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest two-documents-at-one-stored-path-are-both-checked-and-named
  ;; The comparison keeps one representative per stored path (identful,
  ;; lowest ident).  Clearance must not: the second document is checked on
  ;; its own and the collision is a condition.
  (let [a  (document! {:slug "twin_a" :rel "observations/twin.md" :owned? true})
        b  (document! {:slug "twin_b" :rel "observations/twin.md" :owned? true :uuid? false})
        _  (emit! a "a")
        pf (preflight "a")]
    (is (= [{:rel-path "observations/twin.md" :entity-idents [a b]}] (:document-collisions pf))
        "the audit's own claimant group, as a condition")
    (is (= [{:rel-path "observations/twin.md" :entity-ident b :missing :mm/id
             :file-id (stored-id a)}]
           (:documents-without-uuid pf))
        "the second document's missing UUID is named although the representative has one")
    (is (= 1 (:ownership-matched-count pf)) "the file belongs to the first document")
    (is (= [] (:ownership pf)) "the second has no UUID to mismatch against; it is under documents-without-uuid")
    (is (= 2 (:unresolved-count pf)))
    (is (false? (:clear-for-enrollment? pf)))
    (testing "a second document WITH a UUID at the same path is a mismatch against the one file"
      (let [c  (document! {:slug "twin_c" :rel "observations/twin.md" :owned? true})
            pf (preflight "a")]
        (is (some #(= {:rel-path "observations/twin.md" :entity-ident c :ownership :mismatched
                       :stored-id (stored-id c) :file-id (stored-id a)} %)
                  (:ownership pf))
            (pr-str (:ownership pf)))
        (is (= [a b c] (:entity-idents (first (:document-collisions pf)))))))))

(deftest a-file-the-parser-rejects-is-a-named-error-even-with-a-readable-uuid
  ;; The ingestion walk drops a parse-failed unit from the flat spec list;
  ;; the identity walk reads only the front matter and may still find the
  ;; id line.  The unit report's failure must reach the preflight as an
  ;; error, so the tree cannot look clear with a file the audit cannot see.
  (let [ident    (document! {:slug "broken" :owned? true})
        _        (emit! ident "a")
        original md/parse-document]
    (with-redefs [md/parse-document (fn [text source]
                                      (if (str/includes? (str source) "broken")
                                        (throw (ex-info "forced parse failure" {:source source}))
                                        (original text source)))]
      (let [pf  (preflight "a")
            rep (::report pf)]
        (is (= [{:rel-path "observations/broken.md" :phase :parse}]
               (mapv #(select-keys % [:rel-path :phase]) (:errors pf))))
        (is (str/includes? (:error (first (:errors pf))) "forced parse failure"))
        (is (= [] (:files-without-uuid pf)) "its id line reads; that is not the problem")
        (is (= 1 (:error-count pf)))
        (is (false? (:clear-for-enrollment? pf)))
        (testing "the main report names the file too, and its count"
          (is (= 1 (:fs-parse-failed-count (:summary rep))))
          (is (= "observations/broken.md" (:rel-path (first (:fs-parse-failed rep)))))
          (is (some #{ident} (:missing-from-fs rep))
              "to the comparison the file is invisible; the parse failure is why"))))))
