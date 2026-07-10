(ns sandbar.migrate.id-backfill
  "One-shot DB->FS backfill of the `id: '<uuid>'` frontmatter line into corpus
   files that lack it — the step-2 `backfill` of the ratified fidelity pipeline
   (fidelity -> BACKFILL -> routing).

   WHY this exists.  The DB->FS emitter now emits a clean single-quoted
   `id: '<uuid>'` line for any entity carrying `:mm/id` (markdown.clj
   `uuid->id-line` + emit id: policy, landed fc6ae69/7f3a788).  But most corpus
   files were hand-authored or ingested before that emit path ever wrote them,
   so ~86% carry no id: line and ~2.5% carry the legacy `!!java.util.UUID`
   java-tag form.  This tool materializes the DB's authoritative `:mm/id` into
   those files SURGICALLY (one line touched; every other byte preserved), so a
   later full-corpus re-emit — or a fresh restore — round-trips the identity
   without re-deriving it.

   WHY it is safe to run (and safe to defer).  `:mm/id` is a DETERMINISTIC
   clj-uuid v5 value: `:mm/id = ident-uuid(:db/ident)` under the frozen
   per-deployment authority (sandbar.identifier).  So this backfill materializes
   REDISCOVERABLE data, not unrecoverable data (contrast the extras-carrier
   backfill, which captured genuinely-dropped keys).  Losing the id: line never
   loses the id.  That makes the backfill a durability/round-trip-cleanliness
   convenience, not a correctness gate.

   Design contract:
     - DB-driven: the id comes from the entity's stored `:mm/id`, read directly
       (never re-derived from rel-path — the naive rel-path->ident derivation
       drops the digit-dodge that the ingest path applies, so re-derivation is
       wrong for digit-leading slugs; reading :mm/id sidesteps that entirely).
     - Dry-run FIRST: `run` defaults to dry-run and writes NOTHING; it returns
       per-file plans + a stats map + unified diffs.  `:apply? true` is the
       Dan-gated ceremony step.
     - Surgical: only the id: line is inserted/rewritten.  The whole-frontmatter
       serializer is deliberately NOT invoked, so no unrelated emit drift can
       ride along.
     - Guarded: every write goes through `guard-registry-critical-write!`
       (the projection registry guard) — belt-and-suspenders; an id: insert
       cannot drop keys, but the guard is the standing write-time contract.
     - Idempotent: a second run is all no-ops (clean-sq lines already match).
     - Scoped: dogfood :mm/Memory classes only; the junk-twin memory/memory/
       and working-artifact trees are excluded.

   Test law (per the fidelity decision): the PURE core (`classify-id-form`,
   `plan-file`, `apply-plan`) has NO DB or IO dependency and is unit-testable
   directly.  The DB-driven `id-map-from-db` is the only slot that needs a conn,
   and acceptance runs it against a `datomic:mem` fixture (never the shared
   transactor).

   Provenance:
     - decisions/db_fs_emitter_fidelity_option_a_wire_dormant_frontmatter_carrier_2026_07_02.md (the pipeline + sequencing)
     - decisions/zeta_scope_b_stable_identifier_substrate_primitive_...2026_05_26.md (:mm/id = v5 UUID; compute-at-migration-time)
     - sandbar.identifier / sandbar.codec.markdown (uuid->id-line, memory-ident-from-rel-path)"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure core — frontmatter id: classification + surgical transform
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private uuid-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(def frontmatter-delim "---")

(defn split-frontmatter
  "Return `[fm-lines body-lines]` for `content`, or `[nil <all-lines>]` when
   there is no leading `---`-fenced frontmatter block.  `fm-lines` is the vector
   of raw lines strictly BETWEEN the opening and closing `---` fences."
  [content]
  (let [lines (vec (str/split-lines content))]
    (if (and (seq lines) (= frontmatter-delim (str/trim (first lines))))
      (let [after (subvec lines 1)
            end   (first (keep-indexed (fn [i l] (when (= frontmatter-delim (str/trim l)) i)) after))]
        (if end
          [(subvec after 0 end) (subvec after (inc end))]
          [nil lines]))              ; unterminated fence -> treat as no-fm
      [nil lines])))

(defn classify-id-form
  "Classify the `id:` line inside `fm-lines`, returning
   `{:form <kw> :line <str-or-nil> :idx <int-or-nil> :value <uuid-str-or-nil>}`.

   `:form` is one of :clean-sq (`id: '<uuid>'`, the canonical emitter form),
   :java-tag (`id: !!java.util.UUID '<uuid>'`), :double-q, :bare, :other-id
   (an id: line whose value is not a UUID), or :missing (no id: line)."
  [fm-lines]
  (if-let [[idx line] (first (keep-indexed
                              (fn [i l] (when (re-find #"^id:\s" l) [i l]))
                              (or fm-lines [])))]
    (let [val (some-> (re-find uuid-re line) str)
          form (cond
                 (re-find #"^id:\s*!!java\.util\.UUID\s" line) :java-tag
                 (re-find (re-pattern (str "^id:\\s*'" uuid-re "'\\s*$")) line) :clean-sq
                 (re-find (re-pattern (str "^id:\\s*\"" uuid-re "\"\\s*$")) line) :double-q
                 (re-find (re-pattern (str "^id:\\s*" uuid-re "\\s*$")) line) :bare
                 :else :other-id)]
      {:form form :line line :idx idx :value val})
    {:form :missing :line nil :idx nil :value nil}))

(defn id-line
  "The canonical clean id: line for `uuid-str` — mirrors markdown.clj
   `uuid->id-line` byte-for-byte (`id: '<uuid>'`)."
  [uuid-str]
  (str "id: '" uuid-str "'"))

(defn plan-file
  "Decide the backfill action for one file, PURELY.  `content` is the file's
   current text; `db-id` is the entity's stored `:mm/id` as a string, or nil
   when the DB has no id for this file.

   Returns `{:action <kw> :reason <str> :db-id <str-or-nil> :current <classify>
             :new-line <str-or-nil>}` where `:action` is:
     :skip-clean   already `id: '<db-id>'` — nothing to do (idempotent no-op)
     :insert       no id: line — insert `id: '<db-id>'` as the trailing fm line
     :rewrite      java-tag/double-q/bare id: present — normalize to clean form
     :conflict     a clean id: is present but DIFFERS from the DB id (needs a
                   human — a moved file, a rel-path collision, or a hand id)
     :no-db-id     DB carries no :mm/id for this file — cannot backfill; SKIP
                   (mint-candidate; never fabricate an id here)
     :no-frontmatter  file has no `---` block (index/README) — SKIP"
  [content db-id]
  (let [[fm-lines _] (split-frontmatter content)
        cur (classify-id-form fm-lines)]
    (cond
      (nil? fm-lines)
      {:action :no-frontmatter :reason "no --- frontmatter block" :db-id db-id :current cur :new-line nil}

      (nil? db-id)
      {:action :no-db-id :reason "DB has no :mm/id for this rel-path" :db-id nil :current cur :new-line nil}

      (and (= :clean-sq (:form cur)) (= (:value cur) db-id))
      {:action :skip-clean :reason "already clean + matches DB" :db-id db-id :current cur :new-line nil}

      (= :clean-sq (:form cur))         ; clean but value != db-id
      {:action :conflict :reason (str "on-disk id " (:value cur) " != DB :mm/id " db-id)
       :db-id db-id :current cur :new-line (id-line db-id)}

      (contains? #{:java-tag :double-q :bare :other-id} (:form cur))
      {:action :rewrite
       :reason (str "normalize " (name (:form cur)) " id: line to clean single-quoted form"
                    (when (and (:value cur) (not= (:value cur) db-id))
                      (str " (WARN: on-disk " (:value cur) " != DB " db-id ")")))
       :db-id db-id :current cur :new-line (id-line db-id)}

      :else                             ; :missing
      {:action :insert :reason "insert trailing id: line" :db-id db-id :current cur :new-line (id-line db-id)})))

(defn apply-plan
  "Produce the new file content for an actionable `plan` over `content`,
   PURELY.  For :rewrite the id: line is replaced IN PLACE (position preserved,
   minimal diff); for :insert the clean id: line is appended as the LAST
   frontmatter line before the closing `---` (the corpus convention — id:
   trails, matching the emit path).  Non-actionable plans return `content`
   unchanged.  Every non-id byte is preserved."
  [content {:keys [action new-line current] :as _plan}]
  (let [lines (vec (str/split-lines content))
        trailing-nl? (or (str/ends-with? content "\n") (str/blank? content))
        rejoin (fn [ls] (cond-> (str/join "\n" ls) trailing-nl? (str "\n")))]
    (case action
      (:rewrite :conflict)
      ;; fm block starts at line 1 (line 0 is the opening ---); the id: idx is
      ;; relative to fm-lines, so the absolute line is (inc idx).
      (rejoin (assoc lines (inc (:idx current)) new-line))

      :insert
      ;; find the closing --- (first delim after line 0) and inject before it.
      (let [close (first (keep-indexed
                          (fn [i l] (when (and (pos? i) (= frontmatter-delim (str/trim l))) i))
                          lines))]
        (if close
          (rejoin (vec (concat (subvec lines 0 close) [new-line] (subvec lines close))))
          content))

      content)))

(defn unified-diff
  "Minimal unified-diff string for a single-line frontmatter change on `rel`.
   Dry-run artifact only — shows the id: context, not a full-file diff."
  [rel {:keys [action current new-line]}]
  (let [hdr (str "--- a/" rel "\n+++ b/" rel "\n")]
    (case action
      (:rewrite :conflict)
      (str hdr "@@ frontmatter id: line @@\n-" (:line current) "\n+" new-line "\n")
      :insert
      (str hdr "@@ frontmatter (append trailing id:) @@\n+" new-line "\n")
      (str hdr "(no change: " (name action) ")\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB-driven orchestration — dry-run FIRST
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn id-map-from-db
  "Read the authoritative `{rel-path -> :mm/id-string}` map from a Datomic `db`
   value, READ-ONLY (a single `d/q`; no transaction).  `d/q` is passed in to
   keep this ns free of a static datomic edge — call as
   `(id-map-from-db (requiring-resolve 'datomic.api/q) db)`.  Acceptance feeds
   a `datomic:mem` fixture db here; production feeds the live db value."
  [dq db]
  (into {} (map (fn [[rel id]] [rel (str id)]))
        (dq '[:find ?rel ?id
              :where
              [?e :mm.memory/rel-path ?rel]
              [?e :mm/id ?id]]
            db)))

(def default-non-dogfood-dirs
  "Top-level memory/ subdirs that are NOT dogfood :mm/Memory classes — working
   artifacts + the accidental junk-twin.  Backfill is bounded away from these."
  #{"audit-results" "memory"})

(defn- dogfood?
  [rel non-dogfood-dirs]
  (let [top (first (str/split rel #"/"))]
    (not (contains? non-dogfood-dirs top))))

(defn run
  "Plan (and, only when `:apply? true`, perform) the id: backfill.  DRY-RUN by
   default — returns `{:stats {...} :plans [...] :diffs [...]}` and writes
   NOTHING.  Options:
     :corpus-root      absolute path whose `memory/` subtree is scanned
     :id-map           `{rel-path -> uuid-string}` (from `id-map-from-db`, the
                       EDN dump, or a mem fixture) — the authoritative id source
     :files            explicit seq of absolute file paths (else glob memory/**.md
                       via the caller; this fn takes files to stay IO-light)
     :non-dogfood-dirs override the excluded top-level dirs
     :apply?           when true, WRITE each actionable change (guarded); the
                       Dan-gated ceremony step.  Default false.
     :guard!           optional 2-arg (path, content) pre-write guard; when
                       supplied it is called before every spit (wire
                       `sandbar.projection/guard-registry-critical-write!`).

   The caller supplies `:files` + `:corpus-root`; rel-path is the file path
   relative to `<corpus-root>/`.  Actionable = :insert/:rewrite (NOT :conflict —
   conflicts are reported, never auto-applied).

   `run` is READ-ONLY by construction — it slurps + plans + diffs and returns.
   The WRITE arm is the separate, Dan-gated `run-apply!`; there is no `:apply?`
   branch here, so no code path in `run` can mutate the corpus."
  [{:keys [corpus-root id-map files non-dogfood-dirs]
    :or   {non-dogfood-dirs default-non-dogfood-dirs}}]
  (let [root-prefix (str corpus-root "/")
        rel-of (fn [f] (if (str/starts-with? f root-prefix) (subs f (count root-prefix)) f))
        plans (for [f files
                    :let [rel (rel-of f)]
                    :when (and (str/starts-with? rel "memory/")
                               (dogfood? (subs rel (count "memory/")) non-dogfood-dirs))
                    :let [mem-rel (subs rel (count "memory/"))
                          content (slurp f)
                          plan (plan-file content (get id-map mem-rel))]]
                (assoc plan :file f :rel mem-rel))
        actionable? #(contains? #{:insert :rewrite} (:action %))
        stats (-> (frequencies (map :action plans))
                  (assoc :total (count plans)
                         :actionable (count (filter actionable? plans))))]
    {:stats stats
     :plans (vec plans)
     :diffs (mapv (fn [p] (unified-diff (:rel p) p)) (filter actionable? plans))}))

(defn run-apply!
  "The Dan-gated WRITE arm, factored out so the dry-run `run` never touches the
   filesystem.  For each actionable plan, computes the new content, runs the
   optional `guard!` (throws on registry-critical strip), then spits.  Returns a
   per-file outcome vector.  Invoked ONLY under the human-supervised ceremony —
   NEVER from a test or an unattended run."
  [{:keys [plans guard!]}]
  (vec
   (for [{:keys [file action] :as p} plans
         :when (contains? #{:insert :rewrite} action)]
     (let [content (slurp file)
           new-content (apply-plan content p)]
       (when guard! (guard! file new-content))
       (spit file new-content)
       {:file file :action action :bytes (count new-content)}))))
