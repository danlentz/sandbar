(ns sandbar.project.recovery-check
  "Read-only origin comparison of one completed guarded export with the same
   existing database. It answers two operator questions from the tree, its
   trusted private receipt and one database snapshot: does this verified set
   of files have a trusted origin in the database I intend to use, and has
   that database moved since the export? It never imports, writes, restores,
   commits or approves an import; a matching hash and basis is a snapshot
   fact, not import safety, so every result carries :import-approved? false.

   Order, fixed by the private nature of the evidence: authorization, then the
   operator configuration (project, destination, existing staging child), then
   the tree's byte integrity through the shared core, then the ready audit
   located only under the destination's private audit root by the manifest's
   reference parsed as a UUID, then the receipt's associations, and only then
   one comparison of the recorded database identity and basis with the live
   snapshot. Integrity, authorization and read failures are refusals; a
   receipt that is absent, stale, malformed or disagreeing is the
   :origin-unverified state, reported with a keyword reason."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.project.export :as export]
            [sandbar.project.export-integrity :as integrity]
            [sandbar.security.visibility :as visibility])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util UUID]))

(def states
  "The five descriptive outcomes. None of them is an eligibility label."
  #{:same-store-same-basis :store-advanced :store-behind-export
    :different-store :origin-unverified})

(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "A sanitized refusal: the MCP boundary maps it to an isError envelope that
   names a keyword reason and phase, never a path, an identity or exception
   text."
  [reason phase]
  (throw (ex-info "Recovery check refused; inspect the operator configuration or private audit."
                  {:sandbar/error :recovery-check-refusal :reason reason :phase phase})))

(defn- unverified!
  "Abort the origin comparison with the :origin-unverified state. The reason
   is a keyword; the caller turns it into the checked payload."
  [reason]
  (throw (ex-info "Export origin unverified" {::unverified true :reason reason})))

(defn- authorize!
  "This diagnostic reads private origin evidence, so it requires an
   authenticated principal marked with full clearance. The principal's role
   may be read-only: the check writes nothing. Ordinary project-scoped readers
   are not widened by this rule; they are simply not admitted here."
  [principal]
  (when (nil? principal) (refuse! :authentication-required :authorization))
  (when-not (true? (:auth/full-clearance? principal))
    (refuse! :private-origin-clearance-required :authorization)))

(defn- verify! [dir expected]
  (try
    (integrity/verify-tree* dir expected)
    (catch clojure.lang.ExceptionInfo ex
      (refuse! (or (:reason (ex-data ex)) :unreadable-tree) :integrity))
    (catch Exception _ (refuse! :unreadable-tree :integrity))))

(defn- held-count!
  "The manifest's hold count for every audience: absent means none; anything
   but a positive integer is not a manifest the exporter writes."
  [manifest]
  (let [holds (:manifest/exclusions manifest)
        n (:count holds)]
    (cond (nil? holds) 0
          (and (map? holds) (integer? n) (pos? n)) n
          :else (refuse! :invalid-manifest :integrity))))

(defn- audit-ref->uuid
  "The manifest's audit reference as a UUID, or nil. Only the canonical
   serialization of a parsed UUID ever becomes part of a filename."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (UUID/fromString s) (catch IllegalArgumentException _ nil))))

(defn- parse-audit
  "Exactly one EDN map with the default tagged literals (#inst, #uuid) and
   nothing else, or nil."
  [text]
  (try
    (with-open [reader (PushbackReader. (StringReader. text))]
      (let [end (Object.)
            opts {:eof end :default (fn [_ _] (throw (ex-info "tag" {})))}
            value (edn/read opts reader)]
        (when (and (map? value) (identical? end (edn/read opts reader)))
          value)))
    (catch Exception _ nil)))

(defn- read-audit!
  "The ready audit for `uuid` from `audit-root` alone: a regular, private,
   non-symlinked file named by the canonical UUID. An audit anywhere else,
   including beside the export, is not evidence."
  [audit-root ^UUID uuid]
  (let [root (Paths/get audit-root (make-array String 0))
        file (.resolve root (str uuid "-ready.edn"))]
    (when-not (= root (.getParent file)) (unverified! :audit-ref-malformed))
    (when (Files/isSymbolicLink file) (unverified! :audit-symlink))
    (when-not (Files/isRegularFile file nofollow) (unverified! :audit-missing))
    (when-not (= (PosixFilePermissions/fromString "rw-------")
                 (Files/getPosixFilePermissions file nofollow))
      (unverified! :audit-not-private))
    (let [text (try (String. (Files/readAllBytes file) "UTF-8")
                    (catch Exception _ (refuse! :unreadable-audit :read)))]
      (or (parse-audit text) (unverified! :audit-malformed)))))

(defn- associate!
  "The receipt must be the ready audit of this very marker for this very
   destination: same reference and phase, the exact marker hash, a well-formed
   database identity and basis agreeing with the marker, the same destination
   name, audience and project key, and the same file rows. Returns the
   recorded origin {:store-id :basis}."
  [audit manifest receipt dest ^UUID uuid]
  (let [plan (:audit/plan audit)
        recorded-sha (:audit/manifest-sha256 audit)
        store-id (:store-id plan)
        basis (:basis plan)]
    (when-not (= :ready (:audit/phase audit)) (unverified! :audit-phase-mismatch))
    (when-not (= (str uuid) (:audit/ref audit)) (unverified! :audit-ref-mismatch))
    (when (or (nil? recorded-sha) (nil? store-id)) (unverified! :receipt-predates-origin-binding))
    (when-not (integrity/sha256? recorded-sha) (unverified! :manifest-hash-malformed))
    (when-not (= (str/lower-case recorded-sha) (:manifest-sha256 receipt))
      (unverified! :manifest-hash-mismatch))
    (when-not (and (string? store-id) (not (str/blank? store-id))) (unverified! :store-id-malformed))
    (when-not (and (integer? basis) (not (neg? basis))) (unverified! :basis-malformed))
    (when-not (= basis (:manifest/basis-t manifest)) (unverified! :basis-mismatch))
    (when-not (= (:name dest) (get-in plan [:destination :name])
                 (get-in manifest [:manifest/target :destination]))
      (unverified! :destination-mismatch))
    (when-not (= (:audience dest) (get-in plan [:destination :audience])
                 (:manifest/audience manifest) (get-in manifest [:manifest/target :audience]))
      (unverified! :audience-mismatch))
    (when-not (= (:project-key dest) (get-in plan [:destination :project-key]))
      (unverified! :project-mismatch))
    (when-not (and (vector? (:audit/files audit))
                   (= (set (:manifest/files manifest)) (set (:audit/files audit))))
      (unverified! :file-list-mismatch))
    {:store-id store-id :basis basis}))

(defn- compare-snapshot
  "One reading of the live database's identity and basis against the
   recorded origin. Bases are ordered only within one database identity."
  [^datomic.Database database {:keys [store-id basis]}]
  (let [live-id (.id database)
        live-basis (d/basis-t database)]
    (cond (not= store-id live-id) :different-store
          (= basis live-basis) :same-store-same-basis
          (< basis live-basis) :store-advanced
          :else :store-behind-export)))

(defn check
  "Run the comparison for {:project :destination :from [:expect-manifest]}
   and return the checked payload: {:status :checked :scope
   :snapshot-comparison :state <state> :file-count :audience :held-count
   :manifest-sha256 :import-approved? false [:reason]}. Coverage is the
   listed files of this export at its audience, never the project or the
   store. Throws a sanitized refusal for authorization, configuration,
   integrity and read failures."
  [{:keys [expect-manifest] :as opts}]
  (try
    (authorize! visibility/*principal*)
    (let [database (db/db)
          dest (export/destination! database opts :existing)
          {:keys [receipt manifest]} (verify! (:from dest) expect-manifest)
          held (held-count! manifest)
          base {:status :checked :scope :snapshot-comparison
                :file-count (:file-count receipt) :audience (:audience dest)
                :held-count held :manifest-sha256 (:manifest-sha256 receipt)
                :import-approved? false}
          outcome (try
                    (let [uuid (or (audit-ref->uuid (:manifest/audit-ref manifest))
                                   (unverified! :audit-ref-malformed))
                          audit (read-audit! (:audit-root dest) uuid)
                          origin (associate! audit manifest receipt dest uuid)]
                      {:state (compare-snapshot database origin)})
                    (catch clojure.lang.ExceptionInfo ex
                      (if (::unverified (ex-data ex))
                        {:state :origin-unverified :reason (:reason (ex-data ex))}
                        (throw ex))))]
      (merge base outcome))
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (cond (= :recovery-check-refusal (:sandbar/error data)) (throw ex)
              ;; The shared destination resolver refuses with its own reasons.
              (= :export-refusal (:sandbar/error data)) (refuse! (:reason data) :destination)
              :else (refuse! :invalid-recovery-check-request :request))))
    (catch Exception _ (refuse! :invalid-recovery-check-request :request))))
