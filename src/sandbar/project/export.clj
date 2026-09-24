(ns sandbar.project.export
  "Bounded, attended project export. Plan on one immutable database, hold whole
   documents, and write only into fresh operator-authorized staging. Canonical
   files and source entities are never modified. This is not a backup or a
   semantic prose declassifier."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.core :as flow]
            [sandbar.firewall.label :as label]
            [sandbar.project.destination :as destination]
            [sandbar.project.provenance :as prov]
            [sandbar.project.route :as route]
            [sandbar.projection :as projection]
            [sandbar.security.visibility :as visibility])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption StandardCopyOption CopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util UUID]))

(def manifest-name "export-manifest.edn")
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private private-dir-attrs
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                               (PosixFilePermissions/fromString "rwx------"))]))

(defn- refuse! [reason]
  ;; Never carry caller paths, private identities or exception text onto MCP.
  (throw (ex-info "Guarded export refused; inspect the operator configuration or private audit."
                  {:sandbar/error :export-refusal :reason reason})))

(defn- hold! [reason & [details]]
  (throw (ex-info "Document held" {:hold reason :details details})))

(defn- sha [text]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                        (.getBytes (str text) "UTF-8")))))
(defn- canonical-data [x]
  (cond
    (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                  (map (fn [[k v]] [k (canonical-data v)])) x)
    (set? x) (vec (sort-by pr-str (map canonical-data x)))
    (sequential? x) (mapv canonical-data x)
    :else x))
(defn- path [x] (.toPath (io/file x)))
(defn- canonical [x] (.getCanonicalPath (io/file x)))
(defn- inside? [a b] (.startsWith (path b) (path a)))
(defn- overlaps? [a b] (or (inside? a b) (inside? b a)))
(defn- exists? [x] (Files/exists (path x) nofollow))

(defn- directory! [value reason]
  (when-not (and (string? value) (.isAbsolute (io/file value))
                 (.isDirectory (io/file value)))
    (refuse! reason))
  ;; Canonicalize aliases at the root, but never allow a changing alias in the
  ;; requested child. Revalidation immediately before effects binds the roots.
  (canonical value))

(defn- private-audit-root! [root]
  (let [permissions (Files/getPosixFilePermissions (path root) nofollow)
        allowed (PosixFilePermissions/fromString "rwx------")]
    (when-not (= allowed permissions) (refuse! :audit-root-must-be-private)))
  root)

(defn destination!
  "Resolve only operator-owned configuration. The requested project must be an
   enrolled Project. In the default :fresh mode `to` must be one fresh direct
   child of the staging root (the export target, returned as :to); in
   :existing mode `from` must be one existing real directory that is a direct
   child of the staging root (a completed export the read-only recovery check
   reads, returned as :from). Every other check is shared, so the two modes
   cannot drift apart in what they authorize."
  ([database opts] (destination! database opts :fresh))
  ([database {:keys [project destination to from]} mode]
   (let [project (when-let [eid (ref/ref->eid database project)] (d/entity database eid))
         key (route/project-key project)
         roots (destination/validate-roots! database (destination/global-root))
         spec (get (config/value :export-destinations) destination)
         child (if (= :existing mode) from to)]
     (when-not (and project
                    (label/class-isa? database :mm/Project (label/class-ident-of project))
                    (not= key route/unassigned-project-ident) (contains? roots key))
       (refuse! :explicit-enrolled-project-required))
     (when-not (and (string? destination) (map? spec)
                    (= key (:project spec)) (#{:public :private} (:audience spec)))
       (refuse! :unauthorized-destination))
     (let [staging (directory! (:staging-root spec) :staging-root-required)
           audit (private-audit-root! (directory! (:audit-root spec) :audit-root-required))
           sources (cons (canonical (destination/global-root)) (vals roots))
           all-staging (map #(directory! (:staging-root %) :staging-root-required)
                            (vals (config/value :export-destinations)))
           target (when (and (string? child) (.isAbsolute (io/file child))) (canonical child))
           direct-child? (and target
                              (= staging (some-> (io/file target) .getParentFile .getPath))
                              (= target (.getPath (io/file child))))]
       (when (or (some #(overlaps? staging %) sources)
                 (some #(overlaps? audit %) sources)
                 (some #(overlaps? audit %) all-staging))
         (refuse! :overlapping-destinations))
       (if (= :existing mode)
         (when-not (and direct-child? (Files/isDirectory (path child) nofollow))
           (refuse! :existing-staging-child-required))
         (when-not (and direct-child? (not (exists? child)))
           (refuse! :fresh-staging-child-required)))
       (assoc {:name destination :project (:db/id project) :project-key key
               :audience (:audience spec) :source-root (get roots key)
               :staging-root staging :audit-root audit
               :label (if (= :public (:audience spec)) {:sensitivity :public :contexts #{}}
                          (assoc (label/label-of database project) :sensitivity :private))}
              (if (= :existing mode) :from :to) target)))))

(defn- memory? [database e]
  (when-let [cls (label/class-ident-of e)]
    (label/class-isa? database :mm/Memory cls)))

(defn- entity! [database value]
  (when-let [eid (ref/ref->eid database value)]
    (when (seq (d/datoms database :eavt eid)) (d/entity database eid))))

(defn- target-label [database e]
  ;; Read-plane nil visibility is private even when routing inherits public.
  (when (memory? database e)
    (cond-> (label/label-of database e)
      (nil? (:mm.memory/visibility e)) (assoc :sensitivity :private))))

(defn- readable? [database principal e]
  (visibility/entity-visible-to? database principal e))

(defn- permitted-target? [database principal dest source target]
  (let [l (target-label database target)
        src (cond-> (label/label-of database source)
              (= :public (:mm.memory/visibility source)) (assoc :sensitivity :public))]
    (and l (readable? database principal target)
         (flow/firewall-permits? (:label dest) l)
         ;; All emitted memory references reveal identity, including the
         ;; structural/actor edges exempt from the author-time flow policy.
         (flow/firewall-permits? src l))))

(defn- slot-values [database slot value]
  (if (= :db.cardinality/many (:db/cardinality (d/entity database slot)))
    (vec value) [value]))

(defn- resolve-path [database value name-link?]
  (when (string? value)
    (let [s (first (str/split value #"#" 2))
          s (str/replace s #"^memory/" "")
          ident (cond (str/starts-with? s ":") (keyword (subs s 1))
                      (str/starts-with? s "memory.") (keyword s)
                      :else (try (md/rel-path->memory-ident s)
                                 (catch clojure.lang.ExceptionInfo _ nil)))
          from-path (d/q '[:find [?e ...] :in $ ?p :where [?e :mm.memory/rel-path ?p]]
                         database s)
          ;; Exact names denote references only inside explicit link forms.
          ;; A document's name or ordinary description is not a dependency.
          from-name (when name-link?
                      (d/q '[:find [?e ...] :in $ ?n :where [?e :mm.memory/name ?n]]
                           database s))
          ids (set (cond-> (vec (concat from-path from-name))
                     (entity! database ident) (conj (:db/id (entity! database ident)))))]
      (when (= 1 (count ids)) (d/entity database (first ids))))))

(defn- check-reference! [database principal dest source slot value carrier-kind]
  (let [target (if (= :entity carrier-kind)
                 (entity! database value)
                 (resolve-path database value (= :link carrier-kind)))
        ;; A tag slot emits the vocabulary VALUE alone, not the tag's metadata.
        tag? (and (= (ref/ref->eid database :mm/Tag)
                     (ref/ref->eid database (:dt/range (d/entity database slot))))
                  (string? (:mm.tag/value target))
                  (not (str/blank? (:mm.tag/value target))))]
    (when-not (or tag? (and target (permitted-target? database principal dest source target)))
      (hold! :unsafe-reference {:slot slot :target (or (:db/ident target) value)}))
    (when-not tag? {:entity (:db/ident target) :id (:mm/id target)
                   :rel-path (:mm.memory/rel-path target)
                   :external? (not= (:project dest)
                                    (:db/id (route/owning-project database target)))})))

(defn- without-code
  "Mask ordinary fenced blocks and matched inline code for link discovery only.
   Code remains unchanged in the emitted document and author-classified, like
   prose. Unclosed/unsupported fences fail closed."
  [text]
  (let [outside
        (loop [lines (str/split-lines (or text "")) fence nil out []]
          (if-let [line (first lines)]
            (if fence
              (let [close (re-matches #" {0,3}([\x60~]{3,})[ \t]*" line)
                    run (second close)]
                (recur (rest lines)
                       (if (and run (every? #(= (first fence) %) run)
                                (>= (count run) (count fence))) nil fence) out))
              (let [open (re-matches #" {0,3}(\x60{3,}|~{3,})(.*)" line)]
                (if open
                  (do (when (str/includes? (nth open 2) (str (char 96)))
                        (hold! :unsupported-code-markup))
                      (recur (rest lines) (second open) (conj out "")))
                  (recur (rest lines) nil (conj out line)))))
            (do (when fence (hold! :unsupported-code-markup))
                (str/join "\n" out))))]
    ;; Matching runs, including double-backtick code containing a backtick.
    (str/replace outside #"(?s)(?<!\x60)(\x60+)(?!\x60).*?(?<!\x60)\1(?!\x60)" "")))

(defn- text-targets
  "Bounded corpus wiki/inline links; unsupported reference/HTML markup holds.
   Unique exact memory names, rel-paths and idents are resolved by resolve-path."
  [text]
  (let [text (without-code text)
        wiki #"\[\[([^\]\n]+)\]\]"
        inline #"\[([^\]\n]*)\]\(([^()\s]+)\)"
        w (map #(first (str/split (second %) #"\|" 2)) (re-seq wiki text))
        i (map #(nth % 2) (re-seq inline text))
        rest (-> text (str/replace wiki "") (str/replace inline ""))]
    (when (re-find #"[\[\]<>]" rest) (hold! :unsupported-text-markup))
    (vec (concat w (remove #(or (re-find #"(?i)^(?:https?|mailto):" %)
                                (str/starts-with? % "#")) i)))))

(defn- check-text! [database principal dest source text]
  (mapv #(check-reference! database principal dest source :text % :link)
        (text-targets (or text ""))))

(defn- document-bundle [database memory]
  (let [rows (mapv #(assoc % :db/id (or (:db/id %) (ref/ref->eid database (:db/ident %))))
                   (dt/realize-with memory projection/mm-walker))
        sections (remove #(= (:db/id memory) (:db/id %)) rows)
        ids (set (map :db/id sections))
        mid (:db/id memory)]
    (doseq [s sections]
      (when-not (and (label/class-isa? database :mm/Section (:dt/type s))
                     (contains? (conj ids mid) (ref/ref->eid database (:mm.section/parent s))))
        (hold! :foreign-owned-section {:section (:db/ident s)}))
      ;; Every parent chain must terminate at this exact document.
      (loop [e s seen #{}]
        (let [id (:db/id e)]
          (when (or (nil? id) (contains? seen id)) (hold! :section-cycle))
          (when-not (= mid id)
            (recur (entity! database (:mm.section/parent e)) (conj seen id)))))
      (when-let [next (:mm.section/next-sibling s)]
        (let [n (entity! database next)]
          (when-not (and (contains? ids (:db/id n))
                         (= (ref/ref->eid database (:mm.section/parent s))
                            (ref/ref->eid database (:mm.section/parent n))))
            (hold! :foreign-section-sibling)))))
    (when-let [first (:mm.memory/first-section memory)]
      (when-not (contains? ids (ref/ref->eid database first)) (hold! :foreign-first-section)))
    (into [(into {} memory)] sections)))

(defn- valid-rel-path? [rp]
  (and (string? rp) (not (str/blank? rp)) (str/ends-with? rp ".md")
       (not (.isAbsolute (io/file rp))) (not (str/includes? rp "\\"))
       (not-any? #{"" "." ".."} (str/split rp #"/" -1))
       (not-any? #(or (str/starts-with? % ".") (re-find #"[\p{Cntrl}]" %))
                 (str/split rp #"/"))))

(defn- check-represented-content! [bundle parsed]
  (let [root (first bundle) sections (rest bundle)
        norm #(str/replace (str/trimr (or % "")) "\r\n" "\n")
        shape #(-> (select-keys % [:db/ident :mm.section/heading :mm.section/heading-level
                                   :mm.section/parent :mm.section/body])
                   (update :mm.section/body norm))]
    (if (seq sections)
      (let [ordered (md/section-tree sections (:db/ident root) (:mm.memory/first-section root))]
        (when-not (and (= (set (map :db/ident sections)) (set (map :db/ident ordered)))
                       (= (mapv shape ordered) (mapv shape (rest parsed))))
          (hold! :round-trip-sections)))
      (let [slot (md/body-slot-for (:dt/type root))]
        (when-not (= (norm (get root slot)) (norm (get (first parsed) slot)))
          (hold! :round-trip-body))))))

(defn- classify-document [database principal dest e]
  (let [base {:entity (:db/ident e) :id (:mm/id e) :rel-path (:mm.memory/rel-path e)}]
    (try
      (when-not (and (readable? database principal e)
                     (target-label database e)
                     (flow/firewall-permits? (:label dest) (target-label database e)))
        (hold! :destination-or-caller-clearance))
      (when-not (and (valid-rel-path? (:rel-path base))
                     (= (:entity base) (md/rel-path->memory-ident (:rel-path base)))
                     (instance? UUID (:id base))
                     (or (nil? (:mm.memory/identity e))
                         (= (:id base) (:mm.memory/identity e))))
        (hold! :path-or-identity))
      (let [carrier (:mm.memory/frontmatter e)
            extras (md/read-carrier-extra carrier)
            bundle (document-bundle database e)
            slots (md/emitted-frontmatter-slots (md/strip-derived-memory-attrs (first bundle)))]
        (when carrier
          (when-not (and (entity! database carrier) extras) (hold! :unreadable-frontmatter))
          (let [owners (d/q '[:find [?e ...] :in $ ?c :where [?e :mm.memory/frontmatter ?c]]
                            database (ref/ref->eid database carrier))]
            (when-not (= #{(:db/id e)} (set owners)) (hold! :shared-frontmatter)))
          (when (seq (:extras extras)) (hold! :unknown-frontmatter {:keys (keys (:extras extras))})))
        (let [references
              (vec (keep identity
                     (mapcat
                       (fn [[slot value]]
                         (cond
                           (= :db.type/ref (:db/valueType (d/entity database slot)))
                           (map #(check-reference! database principal dest e slot % :entity)
                                (slot-values database slot value))
                           (contains? flow/governed-carrier-slots slot)
                           (map #(check-reference! database principal dest e slot % :path)
                                (slot-values database slot value))
                           :else
                           (mapcat (fn [v]
                                     (cond
                                       (not (string? v)) []
                                       (or (resolve-path database v false)
                                           (re-find #"(?i)(?:^:?memory[./]|^[^\s]+\.md(?:#.*)?$)" v))
                                       [(check-reference! database principal dest e slot v :path)]
                                       :else (check-text! database principal dest e v)))
                                   (slot-values database slot value))))
                       slots)))
              rendered (md/emit-document bundle)
              parsed (md/parse-document rendered (:rel-path base))
              parsed-root (first parsed)
              body (get parsed-root (md/body-slot-for (:dt/type e)))
              body-refs (check-text! database principal dest e body)
              reparsed (md/emit-document parsed)]
          (when-not (and (= (:entity base) (:db/ident parsed-root))
                         (= (:id base) (:mm/id parsed-root))
                         (= (:dt/type e) (:dt/type parsed-root))
                         (= rendered reparsed))
            (hold! :round-trip-identity-or-content
                    {:ident-preserved? (= (:entity base) (:db/ident parsed-root))
                     :id-preserved? (= (:id base) (:mm/id parsed-root))
                     :class-preserved? (= (:dt/type e) (:dt/type parsed-root))
                     :text-preserved? (= rendered reparsed)}))
          (check-represented-content! bundle parsed)
          ;; Stable emission alone can hide loss before the first emission.
          ;; A body/section disagreement has no authority oracle: hold it.
          (when (seq (rest bundle))
            (let [source-body (get (first bundle) (md/body-slot-for (:dt/type e)))
                  norm #(str/replace (str/trimr (or % "")) "\r\n" "\n")]
              (when-not (= (norm source-body) (norm body))
                (hold! :body-section-disagreement))))
          ;; Reparse must preserve every checked declared reference, not merely
          ;; yield stable text after an already-lossy first emission.
          (doseq [[slot value] slots
                  :when (= :db.type/ref (:db/valueType (d/entity database slot)))]
            (let [ids #(set (map (partial ref/ref->eid database)
                                (slot-values database slot %)))]
              (when-not (= (ids value) (ids (get parsed-root slot)))
                (hold! :round-trip-reference {:slot slot}))))
          (assoc base :status :included :text rendered :sha256 (sha rendered)
                      :references (vec (distinct (concat references body-refs))))))
      (catch clojure.lang.ExceptionInfo ex
        (assoc base :status :held :reason (or (:hold (ex-data ex)) :render-or-policy-error)
                    :details (:details (ex-data ex))))
      (catch Exception ex
        ;; Kept privately. Never log/return exception messages from a carrier.
        (assoc base :status :held :reason :render-or-policy-error
                    :details {:exception (.getName (class ex)) :message (.getMessage ex)})))))

(defn plan
  "Pure with respect to DB/filesystem mutation. The returned plan is PRIVATE;
   callers must use the public summary, never serialize this map onto MCP."
  [database principal opts]
  (binding [db/*read-snapshot* database]
    (let [dest (destination! database opts)
          candidates (->> (d/q '[:find [?e ...] :in $ ?p
                                 :where [?e :mm.memory/owning-project ?p]] database (:project dest))
                          (map #(d/entity database %))
                          (filter #(memory? database %))
                          (filter #(projection/entity-passes-filter? (into {} %) (:filter opts)))
                          (sort-by :db/id))
          rows (mapv #(classify-document database principal dest %) candidates)
          included (filterv #(= :included (:status %)) rows)
          paths (map :rel-path included)]
      (when (or (not= (count paths) (count (set (map str/lower-case paths))))
                (some (fn [a] (some #(str/starts-with? % (str a "/")) paths)) paths))
        (refuse! :path-collision))
      (let [basis (d/basis-t database)
            ;; The planned snapshot's own database identity travels with the
            ;; basis: a token from another database is never accepted, and the
            ;; private audits name the store the basis belongs to.
            store-id (.id ^datomic.Database database)
            token-data {:basis basis :store-id store-id :destination dest :filter (:filter opts)
                        :principal (when principal
                                     (select-keys (into {} principal)
                                                  [:db/id :auth/full-clearance? :auth/cleared-projects]))
                        :rows (mapv #(dissoc % :text) rows)}
            token (sha (pr-str (canonical-data token-data)))]
        {:basis basis :store-id store-id :filter (:filter opts) :destination dest :rows rows
         :plan-token token
         :included-count (count included) :held-count (- (count rows) (count included))}))))

(defn- public-summary [p]
  (select-keys p [:basis :plan-token :included-count :held-count]))

(defn write-new!
  "Create and force one file. Never replace an existing file; used only after
   complete preflight. A seam for focused I/O-failure tests."
  [file content private?]
  (let [attrs (into-array FileAttribute
                         [(PosixFilePermissions/asFileAttribute
                            (PosixFilePermissions/fromString (if private? "rw-------" "rw-r--r--")))])]
    (Files/createFile (path file) attrs)
    (with-open [ch (FileChannel/open (path file)
                                   (into-array OpenOption [StandardOpenOption/WRITE]))]
      (let [buffer (ByteBuffer/wrap (.getBytes (str content) "UTF-8"))]
        (while (.hasRemaining buffer) (.write ch buffer)))
      (.force ch true))))

(defn- audit! [p audit-ref phase more]
  (let [audit (merge {:audit/ref audit-ref :audit/phase phase
                     :audit/at (java.util.Date.) :audit/plan (dissoc p :rows)
                     :audit/documents (mapv #(dissoc % :text) (:rows p))} more)
        file (io/file (get-in p [:destination :audit-root])
                      (str audit-ref "-" (name phase) ".edn"))]
    (write-new! file (pr-str audit) true)))

(defn export!
  "Preview by default. Preview writes only a private audit; execute requires its
   token, replans before effects, and publishes a completion manifest LAST.
   The response contains counts and opaque IDs, never held identities/paths."
  [{:keys [dry-run expect-plan provenance] :or {dry-run true} :as opts}]
  (try
    (let [database (db/db)
          p (plan database visibility/*principal* opts)
          audit-ref (str (UUID/randomUUID))
          summary (assoc (public-summary p) :audit-ref audit-ref)
          dest (:destination p)]
      (when (and (not dry-run) (not= expect-plan (:plan-token p)))
        (refuse! :preview-changed))
      (try
        ;; Revalidate path/config choices before any audit/output writes. This
        ;; catches accidental operator changes; it is not a live-writer lock.
        (when-not (= dest (destination! database opts)) (refuse! :destination-changed))
        (when-not (= (:basis p) (d/basis-t (db/db))) (refuse! :basis-changed))
        (when-not (= (:store-id p) (.id ^datomic.Database (db/db))) (refuse! :store-changed))
        (audit! p audit-ref (if dry-run :preview :prepared) {})
        (if dry-run
          (assoc summary :status :preview :complete? false :exported-count 0)
          (let [included (filterv #(= :included (:status %)) (:rows p))
                written (atom [])
                marker (io/file (:to dest) manifest-name)
                marker-written? (atom false)]
            (try
              (Files/createDirectory (path (:to dest)) private-dir-attrs)
              (doseq [{:keys [rel-path text sha256]} included]
                (let [file (io/file (:to dest) rel-path)]
                  (Files/createDirectories (.getParent (path file)) private-dir-attrs)
                  (write-new! file text (= :private (:audience dest)))
                  (when-not (= sha256 (sha (slurp file))) (throw (ex-info "Write hash mismatch" {})))
                  (swap! written conj rel-path)))
              (let [files (mapv #(select-keys % [:rel-path :id :sha256]) included)
                    exclusions (filterv #(= :held (:status %)) (:rows p))
                    manifest (assoc
                               (prov/export-manifest
                                 {:run audit-ref :basis-t (:basis p) :status :complete
                                  :target {:destination (:name dest) :audience (:audience dest)}
                                  ;; Exact holds stay audit-side for every destination.
                                  :firewall-class :public :file-set @written
                                  :exclusions exclusions :audit-ref audit-ref
                                  :salt (str (UUID/randomUUID))})
                               :manifest/audience (:audience dest)
                               :manifest/firewall-class (:audience dest)
                               :manifest/files files
                               :manifest/dependencies
                               (vec (distinct (mapcat :references included))))
                    ;; Serialized once: the hash the private receipt records is
                    ;; the hash of the bytes the marker will carry.
                    marker-text (pr-str manifest)]
                ;; The ready audit is the export's origin receipt: it binds the
                ;; exact marker bytes to the planned snapshot (store identity,
                ;; basis, selection) before the marker is published. The marker
                ;; itself carries no database identity.
                (audit! p audit-ref :ready {:audit/files files
                                            :audit/manifest-sha256 (sha marker-text)})
                (let [pending (io/file (:to dest) (str "." manifest-name ".pending"))]
                  (write-new! pending marker-text (= :private (:audience dest)))
                  (Files/move (path pending) (path marker)
                              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                  (reset! marker-written? true))
                (let [record? (and provenance (prov/recording-enabled?))]
                  (when record?
                    (prov/record-projection-run!
                      {:id (UUID/fromString audit-ref) :status :succeeded
                       :started-at (java.util.Date.) :ended-at (java.util.Date.)
                       :name "Guarded project export"
                       :used (mapv :entity included) :generated (mapv :entity included)}))
                  (assoc summary :status :complete :complete? true
                         :exported-count (count @written) :provenance-recorded? (boolean record?))))
              (catch Exception _
                ;; Only our own freshly created completion marker may be removed.
                (when @marker-written? (Files/deleteIfExists (path marker)))
                (try (audit! p audit-ref :incomplete {:audit/written @written})
                     (catch Exception _ nil))
                (assoc summary :status :incomplete :complete? false
                       :exported-count (count @written) :reason :write-or-audit-failed)))))
        (catch Exception _
          (assoc summary :status :incomplete :complete? false :exported-count 0
                 :reason :prewrite-or-audit-failed))))
    (catch clojure.lang.ExceptionInfo ex
      (if (= :export-refusal (:sandbar/error (ex-data ex))) (throw ex)
          (refuse! :invalid-export-request)))
    (catch Exception _ (refuse! :invalid-export-request))))
