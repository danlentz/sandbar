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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Byte-offset primitives — the whole point is NEVER to round-trip the file
;; through str/split-lines + rejoin (which silently DROPS trailing blank lines
;; and NORMALIZES CRLF->LF).  We splice on raw character offsets so every byte
;; outside the one id: line is preserved verbatim.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn scan-lines
  "Split `content` into physical-line records preserving EXACT byte offsets and
   line terminators.  Returns a vector of
   `{:text <line-without-terminator> :start <int> :end <int> :term <str>}`
   where the line's full span in `content` is `[:start, (+ :end (count :term)))`,
   `:text` is `content[:start :end)` (terminator excluded), and `:term` is the
   terminator (\"\\n\", \"\\r\\n\", or \"\" for an unterminated final line).

   Round-trip law (byte-exact):
     (= content (apply str (mapcat (juxt :text :term) (scan-lines content))))
   This is the property str/split-lines VIOLATES — it discards trailing empties
   and collapses \\r\\n — which is the whole class of corruption we are fixing."
  [^String content]
  (let [n (count content)]
    (loop [i 0, acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [nl (.indexOf content "\n" (int i))]
          (if (neg? nl)
            (persistent! (conj! acc {:text (subs content i n) :start i :end n :term ""}))
            (let [cr?      (and (> nl i) (= \return (.charAt content (dec nl))))
                  text-end (if cr? (dec nl) nl)
                  term     (if cr? "\r\n" "\n")]
              (recur (inc nl)
                     (conj! acc {:text (subs content i text-end) :start i :end text-end :term term})))))))))

(defn fence-indices
  "Indices `[open close]` into `(scan-lines content)` of the opening and closing
   `---` fences, or `[nil nil]` when there is no leading fenced frontmatter block
   (no opening `---`, or an unterminated fence).  Matches `split-frontmatter`'s
   notion of a fence: a line whose trimmed text is exactly `---`."
  [recs]
  (if (and (seq recs) (= frontmatter-delim (str/trim (:text (first recs)))))
    (if-let [close (first (keep-indexed
                           (fn [i r] (when (and (pos? i)
                                                (= frontmatter-delim (str/trim (:text r))))
                                       i))
                           recs))]
      [0 close]
      [nil nil])
    [nil nil]))

(defn splice-plan
  "Pure splice COORDINATES for an actionable `plan` over `content`, or nil when
   the plan is non-actionable / there is no usable frontmatter block.  Both
   `apply-plan` and `verify-surgical` derive from this single byte-exact anchor
   computation, so the write and its check agree on WHERE the edit lands while
   the check independently proves the RESULT changed nothing else.
     :insert  -> {:op :insert  :at <offset-of-closing-fence> :eol <str> :text <new-line>}
     :rewrite -> {:op :replace :start <s> :end <e> :text <new-line>}
   For :replace, `[s,e)` is the old id: line's TEXT span (terminator excluded),
   so the terminator after it is never touched."
  [content {:keys [action new-line]}]
  (let [recs (scan-lines content)
        [open close] (fence-indices recs)]
    (when (and open close)
      (case action
        :insert
        (let [close-rec (nth recs close)
              ;; reuse the terminator of the line just before the closing fence
              ;; (always present — a fence follows it), so an all-CRLF block gets
              ;; a CRLF-terminated inserted line and an all-LF block gets LF.
              eol (or (not-empty (:term (nth recs (dec close)))) "\n")]
          {:op :insert :at (:start close-rec) :eol eol :text new-line})

        (:rewrite :conflict)
        (when-let [rec (first (filter #(re-find #"^id:\s" (:text %))
                                      (subvec recs (inc open) close)))]
          {:op :replace :start (:start rec) :end (:end rec) :text new-line})

        nil))))

(defn apply-plan
  "Produce the new file content for an actionable `plan` over `content`, PURELY
   and BYTE-EXACTLY.  Splices on raw character offsets (via `splice-plan`) — it
   NEVER splits+rejoins the whole file — so multiple trailing newlines, a missing
   final newline, and CRLF endings are all preserved verbatim; only the one id:
   line is inserted (`:insert`, before the closing fence) or replaced in place
   (`:rewrite`/`:conflict`).  Non-actionable plans return `content` unchanged."
  [content plan]
  (if-let [{:keys [op at eol start end text]} (splice-plan content plan)]
    (case op
      :insert  (str (subs content 0 at) text eol (subs content at))
      :replace (str (subs content 0 start) text (subs content end)))
    content))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Byte-fidelity invariant — the falsifier the dry-run runs on EVERY file.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn common-prefix-length
  "Length of the maximal common prefix of strings `a` and `b`."
  [^String a ^String b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))))

(defn common-suffix-length
  "Length of the maximal common suffix of strings `a` and `b`."
  [^String a ^String b]
  (let [na (count a) nb (count b) n (min na nb)]
    (loop [i 0]
      (if (and (< i n) (= (.charAt a (- na 1 i)) (.charAt b (- nb 1 i)))) (recur (inc i)) i))))

(defn diff-region
  "Byte-level single-region diff of original `a` vs modified `b`.  Returns
   `{:prefix <int> :suffix <int> :a-mid <str> :b-mid <str>}` — the maximal common
   prefix/suffix lengths (clamped so they never overlap in EITHER string) and the
   sole differing spans.  Round-trip law:
     a == (str (subs a 0 prefix) a-mid (subs a (- (count a) suffix)))
     b == (str (subs b 0 prefix) b-mid (subs b (- (count b) suffix)))
   Because every non-mid byte is proven identical, any drift OUTSIDE the intended
   id: line (a lost trailing newline, a flipped CRLF) is forced INTO a-mid/b-mid,
   where the caller's exact-match assertion catches it."
  [^String a ^String b]
  (let [cp  (common-prefix-length a b)
        cap (- (min (count a) (count b)) cp)         ; suffix cannot cross prefix
        cs  (min (common-suffix-length a b) cap)]
    {:prefix cp :suffix cs
     :a-mid (subs a cp (- (count a) cs))
     :b-mid (subs b cp (- (count b) cs))}))

(defn verify-surgical
  "The plan-level fidelity invariant: assert `applied` differs from `content` by
   EXACTLY the one intended id: line, byte for byte.  Anchors on `splice-plan`
   (byte-exact) and then proves the RESULT preserved every other byte by direct
   `subs`-equality of the unchanged prefix and suffix — independent of HOW
   `apply-plan` produced `applied`, so a lost EOF newline or a CRLF flip fails it.
   Returns `{:ok? bool :op <kw> :reason str ...}`; `run` aggregates it and
   `run-apply!` REFUSES to write when `:ok?` is false."
  [content applied plan]
  (if-let [{:keys [op at eol start end text]} (splice-plan content plan)]
    (case op
      :insert
      (let [inj    (str text eol)
            la     (count applied)
            pre-ok (= (subs content 0 at) (subs applied 0 (min at la)))
            len-ok (= la (+ (count content) (count inj)))
            mid    (subs applied (min at la) (min la (+ at (count inj))))
            suf-ok (= (subs content at) (subs applied (min la (+ at (count inj)))))]
        (if (and pre-ok suf-ok len-ok (= mid inj))
          {:ok? true  :op op :reason "surgical single-line insert; all other bytes byte-identical"}
          {:ok? false :op op :reason (format "NON-SURGICAL insert (pre=%s suf=%s len=%s)" pre-ok suf-ok len-ok)
           :expected-mid inj :actual-mid mid}))

      :replace
      (let [tail   (- (count content) end)
            la     (count applied)
            hi     (max 0 (- la tail))
            lo     (min start la hi)
            pre-ok (= (subs content 0 start) (subs applied 0 (min start la)))
            suf-ok (= (subs content end) (subs applied (max 0 (- la tail))))
            mid    (subs applied lo hi)]
        (if (and pre-ok suf-ok (= mid text))
          {:ok? true  :op op :reason "surgical in-place id: rewrite; all other bytes byte-identical"}
          {:ok? false :op op :reason (format "NON-SURGICAL rewrite (pre=%s suf=%s)" pre-ok suf-ok)
           :expected-mid text :actual-mid mid})))
    {:ok? true :op :noop :reason "non-actionable; content returned unchanged"}))

(defn unified-diff
  "Unified-diff string for `rel`, DERIVED FROM THE ACTUAL before/after bytes (via
   `diff-region`) so it can NEVER conceal non-target drift — the removed/added
   spans are `pr-str`-escaped, making any trailing-newline or CRLF delta visible,
   and a loud NON-SURGICAL DRIFT banner is prepended whenever `verify-surgical`
   fails.  Takes the real `content` and `applied`, not just the planned line."
  [rel content applied plan]
  (let [{:keys [ok? reason]} (verify-surgical content applied plan)
        {:keys [a-mid b-mid]} (diff-region content applied)
        hdr (str "--- a/" rel "\n+++ b/" rel "\n")]
    (str hdr
         (when-not ok? (str "!!! NON-SURGICAL DRIFT — " reason "\n"))
         "@@ id: line (byte-delta, escaped) @@\n"
         "-" (pr-str a-mid) "\n"
         "+" (pr-str b-mid) "\n")))

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
  "Plan the id: backfill — DRY-RUN ONLY.  `run` writes NOTHING; the WRITE arm is
   the separate, Dan-gated `run-apply!`.  Returns
   `{:stats {...} :plans [...] :diffs [...]}` where every actionable plan is
   additionally byte-verified in memory (`apply-plan` + `verify-surgical`, no
   write) so a non-surgical result is surfaced BEFORE any ceremony.  Options:
     :corpus-root      absolute path whose `memory/` subtree is scanned
     :id-map           `{rel-path -> uuid-string}` (from `id-map-from-db`, the
                       EDN dump, or a mem fixture) — the authoritative id source
     :files            explicit seq of absolute file paths (the caller globs
                       memory/**.md; this fn takes files to stay IO-light)
     :non-dogfood-dirs override the excluded top-level dirs

   `run` has NO `:apply?` or `:guard!` option — passing either throws, so a
   write request can never silently degrade into a dry-run no-op.  The caller
   supplies `:files` + `:corpus-root`; rel-path is the path relative to
   `<corpus-root>/`.  Actionable = :insert/:rewrite (NOT :conflict — conflicts
   are reported, never auto-applied).

   `run` is READ-ONLY by construction — it slurps + plans + diffs and returns;
   no code path in `run` can mutate the corpus.  `:stats` carries `:byte-clean`
   / `:byte-drift` counts; a non-zero `:byte-drift` is a hard failure signal."
  [{:keys [corpus-root id-map files non-dogfood-dirs]
    :or   {non-dogfood-dirs default-non-dogfood-dirs}
    :as   opts}]
  (when (or (contains? opts :apply?) (contains? opts :guard!))
    (throw (ex-info (str "`run` is DRY-RUN ONLY and has no :apply?/:guard! option — "
                         "the write arm is `run-apply!` (Dan-gated ceremony). "
                         "Pass the dry-run plans to `run-apply!`; do not ask `run` to write.")
                    {:offending-options (select-keys opts [:apply? :guard!])
                     :write-arm 'sandbar.migrate.id-backfill/run-apply!})))
  (let [root-prefix (str corpus-root "/")
        rel-of (fn [f] (if (str/starts-with? f root-prefix) (subs f (count root-prefix)) f))
        actionable? #(contains? #{:insert :rewrite} (:action %))
        entries (for [f files
                      :let [rel (rel-of f)]
                      :when (and (str/starts-with? rel "memory/")
                                 (dogfood? (subs rel (count "memory/")) non-dogfood-dirs))
                      :let [mem-rel  (subs rel (count "memory/"))
                            content  (slurp f)
                            plan     (assoc (plan-file content (get id-map mem-rel))
                                            :file f :rel mem-rel)
                            applied  (when (actionable? plan) (apply-plan content plan))
                            verify   (when applied (verify-surgical content applied plan))]]
                  {:plan plan :content content :applied applied :verify verify})
        plans (mapv (fn [{:keys [plan verify]}]
                      (cond-> plan
                        verify (assoc :byte-ok? (:ok? verify) :byte-check (:reason verify))))
                    entries)
        act-entries (filterv (comp actionable? :plan) entries)
        stats (-> (frequencies (map (comp :action :plan) entries))
                  (assoc :total (count entries)
                         :actionable (count act-entries)
                         :byte-clean (count (filter (comp :ok? :verify) act-entries))
                         :byte-drift (count (remove (comp :ok? :verify) act-entries))))]
    {:stats stats
     :plans plans
     :diffs (mapv (fn [{:keys [plan content applied]}]
                    (unified-diff (:rel plan) content applied plan))
                  act-entries)}))

(defn run-apply!
  "The Dan-gated WRITE arm, factored out so the dry-run `run` never touches the
   filesystem.  For each actionable plan: recompute the new content, REFUSE (throw)
   if `verify-surgical` reports non-surgical drift — the write cannot corrupt EOF
   bytes or line endings even under ceremony — then run the optional `guard!`
   (throws on registry-critical strip) and spit.  Returns a per-file outcome
   vector.  Invoked ONLY under the human-supervised ceremony — NEVER from a test
   or an unattended run."
  [{:keys [plans guard!]}]
  (vec
   (for [{:keys [file action] :as p} plans
         :when (contains? #{:insert :rewrite} action)]
     (let [content     (slurp file)
           new-content (apply-plan content p)
           v           (verify-surgical content new-content p)]
       (when-not (:ok? v)
         (throw (ex-info (str "REFUSING to write non-surgical change to " file " — " (:reason v))
                         {:file file :verify v})))
       (when guard! (guard! file new-content))
       (spit file new-content)
       {:file file :action action :bytes (count new-content) :byte-ok? true}))))
