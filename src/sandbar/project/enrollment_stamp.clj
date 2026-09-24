(ns sandbar.project.enrollment-stamp
  "Attended, stopped-writer completion of a new document's import. Insert
   only the minted UUID into the exact source bytes the import persisted.
   This is not an ordinary projection or an existing-document adoption path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.ref :as ref]
            [sandbar.project.destination :as destination]
            [sandbar.projection :as pg])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption StandardCopyOption OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays UUID]))

(defn stamp-source
  "Plan one UTF-8 source edit. Preserve every character except the inserted
   trailing id line, including CRLF, comments, whitespace and body fences.
   A declared blank, foreign or duplicate id is held. An exact previous stamp
   also supplies its original source for receipt-based repeat verification."
  [source id]
  (let [id (str (UUID/fromString (str id)))
        opening (re-matcher #"\A---(\r?\n)" source)]
    (if-not (.find opening)
      {:outcome :held :reason :frontmatter-required}
      (let [newline (.group opening 1)
            end-open (.end opening)
            closing (re-matcher #"(?m)^---\r?$" source)]
        (if-not (.find closing end-open)
          {:outcome :held :reason :frontmatter-required}
          (let [at (.start closing)
                frontmatter (subs source end-open at)
                ids (filter #(= :id (first %)) (md/frontmatter-key-occurrences frontmatter))
                raw (some-> ids first second str/trim (str/replace #"^['\"]|['\"]$" "") str/trim)
                line (str "id: '" id "'" newline)
                before-line (- at (count line))]
            (cond
              (empty? ids)
              {:outcome :planned :content (str (subs source 0 at) line (subs source at))}

              (and (= 1 (count ids)) (= id raw))
              (cond-> {:outcome :already-present}
                (and (>= before-line end-open) (= line (subs source before-line at)))
                (assoc :unstamped-source (str (subs source 0 before-line) (subs source at))))

              :else {:outcome :held :reason :foreign-or-ambiguous-id})))))))

(defn- read-source [file]
  (let [bytes (Files/readAllBytes (.toPath (io/file file)))
        source (String. bytes StandardCharsets/UTF_8)]
    (when-not (Arrays/equals bytes (.getBytes source StandardCharsets/UTF_8))
      (throw (ex-info "Enrollment requires valid UTF-8; source unchanged"
                      {:reason :invalid-utf8})))
    source))

(defn- write-pinned!
  "Replace one existing file atomically after rechecking its imported hash.
   Writers must remain stopped; this is not a concurrent filesystem protocol."
  [file expected content]
  (let [target (.toPath (io/file file))
        tmp (Files/createTempFile (.getParent target) ".sandbar-enroll-" ".tmp"
                                  (make-array FileAttribute 0))]
    (try
      (Files/write tmp (.getBytes ^String content StandardCharsets/UTF_8) (make-array OpenOption 0))
      (try (Files/setPosixFilePermissions tmp (Files/getPosixFilePermissions target (make-array LinkOption 0)))
           (catch UnsupportedOperationException _ nil))
      (when-not (= expected (pg/sha256-hex (read-source file)))
        (throw (ex-info "Source changed after import; source unchanged by enrollment"
                        {:reason :source-changed})))
      (Files/move tmp target (into-array StandardCopyOption
                                        [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists tmp)))))

(defn- stamp-unit! [database from unit]
  (let [base (select-keys unit [:source :ident :source-sha256 :document-id :identity-minted?])
        held #(assoc base :outcome :held :reason %)]
    (try
      (let [eid (ref/ref->eid database (:ident unit))
            ent (when eid (d/entity database eid))
            id (:mm/id ent)
            root (.getCanonicalPath (io/file from))
            source-file (io/file root (:source unit))
            source-path (.getCanonicalPath source-file)
            fallback (destination/global-root)
            dest (when (:mm.memory/rel-path ent) (destination/for-entity database ent fallback))
            target (when dest (destination/target-path dest (:mm.memory/rel-path ent)))]
        (cond
          (or (nil? id) (not= (str id) (:document-id unit))) (held :stored-identity-mismatch)
          (or (nil? target) (not= source-path target)
              (not= source-path (.getAbsolutePath source-file))) (held :source-is-not-canonical-destination)
          (seq (destination/other-claimants database eid dest (:mm.memory/rel-path ent) fallback)) (held :other-path-claimants)
          :else
          (let [source (read-source source-file)
                hash (pg/sha256-hex source)
                expected (:source-sha256 unit)
                edit (stamp-source source id)
                repeated? (and (= :already-present (:outcome edit))
                               (:unstamped-source edit)
                               (= expected (pg/sha256-hex (:unstamped-source edit))))]
            (cond
              (not (or (= expected hash) repeated?)) (held :source-changed)
              (= :held (:outcome edit)) (held (:reason edit))
              (= :already-present (:outcome edit)) (assoc base :outcome :already-present :stamped-sha256 hash)
              (not (and (= :insert (:mode unit)) (:identity-minted? unit)))
              (held :new-document-receipt-required)
              :else
              (do
                (destination/assert-current! dest fallback)
                (write-pinned! source-file expected (:content edit))
                (assoc base :outcome :stamped :stamped-sha256 (pg/sha256-hex (:content edit))))))))
      (catch Exception ex
        (assoc base :outcome :held :reason (or (:reason (ex-data ex)) :stamp-failed)
                    :error (ex-message ex))))))

(defn stamp!
  "Stamp the successfully persisted units from one maintenance import report.
   Call only while ALL writers remain stopped, with the same store and from
   root. The report's final basis must still match. A held unit is named and
   untouched; other eligible units may complete. The original import receipt
   can be replayed in that same maintenance window after a partial file step."
  [database from persist]
  (let [units (:persisted persist)
        basis-ok? (and (:persist? persist) (= (:final-basis persist) (d/basis-t database)))
        results (mapv (fn [unit]
                        (if basis-ok?
                          (stamp-unit! database from unit)
                          (assoc (select-keys unit [:source :ident]) :outcome :held :reason :database-basis-moved)))
                      units)
        counts (frequencies (map :outcome results))]
    {:results results :attempted (count results)
     :stamped-count (get counts :stamped 0)
     :already-present-count (get counts :already-present 0)
     :held-count (get counts :held 0)
     :complete? (and basis-ok? (zero? (get counts :held 0)))}))
