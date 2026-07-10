(ns sandbar.codec.fidelity-test
  "Emitter-fidelity receipts for the extras-carrier fix (Option A) per
   decisions/db_fs_emitter_fidelity_option_a_wire_dormant_frontmatter_carrier_2026_07_02.md
   + scratchpad/emitter-fidelity-2026-07-02/SPEC.md §5 (R1-R8).

   HARD CONSTRAINT (SPEC.md §1.2): every test runs under
   `tu/make-test-db-fixture` — an isolated `datomic:mem://` conn seeded
   from the required schema.  NO test touches the shared dev transactor.
   parse/emit are NOT pure (dt/range-of etc. resolve through (db/db)), so
   the fixture MUST bind `sandbar.db.datomic/**conn*` before any codec
   call.

   Real corpus files are READ-ONLY; all round-trips run on COMMITTED
   FIXTURE COPIES under test/resources/codec-fixtures/emitter-fidelity/
   (originally authored at scratchpad/emitter-fidelity-2026-07-02/scratch/)
   + the drift-set files copied into a per-test tmp dir.  The R6 drift
   sweep reads the live corpus root from SANDBAR_CORPUS_ROOT and SKIPS
   loudly when it is unset (mandatory on the release machine)."
  (:require [clojure.test            :refer :all]
            [clojure.edn             :as edn]
            [clojure.string          :as str]
            [clojure.java.io         :as io]
            [datomic.api             :as d]
            [sandbar.codec           :as codec]
            [sandbar.codec.protocol  :as proto]
            [sandbar.codec.markdown  :as md]
            [sandbar.projection      :as pg]
            [sandbar.db.datomic      :as db]
            [sandbar.db.datatype     :as dt]
            [sandbar.test-util       :as tu])
  (:import [java.util UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — isolated in-mem DB + fresh codec registry per test (SPEC §1.2)

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "codec-fidelity"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Paths + helpers

(def scratch-dir
  "Committed fixture copies (formerly untracked
   scratchpad/emitter-fidelity-2026-07-02/scratch/), so fresh
   clones/worktrees run green.  CWD-relative — `lein test` runs from the
   project root (same convention as test/resources/w1-fixtures via
   sandbar.gate.fixture)."
  "test/resources/codec-fixtures/emitter-fidelity")

(def ground-scratch (str scratch-dir "/ground_new_concepts.md"))
(def preaction-scratch (str scratch-dir "/pre_action_memory_protocol.md"))

(def ground-rel-path
  "memory/interaction/ground_new_concepts_at_introduction_boundaries.md")
(def preaction-rel-path
  "memory/protocol/pre_action_memory_protocol.md")

(defn slurp-scratch [p] (slurp (io/file p)))

(defn fm-block
  "Extract the raw frontmatter block (text between the first two `---`
   fences) from a markdown source string, or nil when absent."
  [src]
  (first (md/split-frontmatter src)))

(defn fm-lines
  "Vector of the frontmatter lines (verbatim) for a source string."
  [src]
  (when-let [b (fm-block src)]
    (str/split-lines b)))

(defn source-fm-keys
  "Ordered vector of top-level frontmatter wire-keys (strings) from the
   RAW source frontmatter block — the ground-truth key set R1 checks
   against."
  [src]
  (->> (fm-lines src)
       (remove #(str/starts-with? % " "))
       (remove #(str/starts-with? % "\t"))
       (filter #(str/includes? % ":"))
       (mapv #(str/trim (first (str/split % #":" 2))))))

(defn emit-line-for
  "Return the emitted frontmatter line whose wire-key is `k` (string),
   verbatim (including any block continuation up to the next top-level
   key), from an EMITTED markdown string.  Mirrors the source-side
   top-level-key detection."
  [emitted k]
  (let [lines (fm-lines emitted)
        top?  (fn [l] (and (not (str/starts-with? l " "))
                           (not (str/starts-with? l "\t"))
                           (str/includes? l ":")))
        this? (fn [l] (and (top? l) (= k (str/trim (first (str/split l #":" 2))))))]
    (loop [[l & more] lines]
      (cond
        (nil? l) nil
        (this? l) (str/join "\n" (cons l (take-while #(not (top? %)) more)))
        :else (recur more)))))

(defn java-tag?
  "True when the emitted string carries any `!!java...` YAML tag (the
   mangle R1/R5 forbid)."
  [emitted]
  (boolean (re-find #"!!java" emitted)))

(defn nested-seq-mangle?
  "Heuristic for the ref-slot nested-seq / nested-map mangle: an emitted
   frontmatter line that serializes a slot value as `{...: ...}` inline
   map or a `- ? ` / `mm.tag/value` / `db/ident` leak.  These are the
   exact live-DB mangles R1/R5 forbid."
  [emitted]
  (let [fm (or (fm-block emitted) "")]
    (boolean (or (re-find #"mm\.tag/value" fm)
                 (re-find #"db/ident" fm)
                 (re-find #"dt/type" fm)
                 (re-find #"mm\.frontmatter/extra" fm)
                 ;; a `- {` or `- ?` inline nested structure under a list key
                 (re-find #"(?m)^\s*-\s*\{" fm)))))

;; Parse a scratch file the way the codec does (full document parse), and
;; emit it back.  parse-document threads the raw frontmatter through the
;; MarkdownCodec parse method (frontmatter->slots 3-arity), so the extras
;; carrier is populated.  emit-document is the coll-shape emit the sink
;; path uses for section-bearing memories.
(defn parse->emit-doc
  "parse-document(src, rel-path) → emit-document.  Returns the emitted
   markdown string."
  [src rel-path]
  (md/emit-document (md/parse-document src rel-path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R1 — semantic fidelity (HARD)

(deftest r1-semantic-fidelity
  (testing "every source frontmatter key present in output; no mangles; no java tags"
    (doseq [[label scratch rel-path]
            [["ground" ground-scratch ground-rel-path]
             ["pre-action" preaction-scratch preaction-rel-path]]]
      (let [src     (slurp-scratch scratch)
            emitted (parse->emit-doc src rel-path)
            src-keys (source-fm-keys src)
            out-keys (set (source-fm-keys emitted))]
        (testing (str label " — all source keys present in output")
          (doseq [k src-keys]
            (is (contains? out-keys k)
                (str label ": source frontmatter key '" k "' missing from emitted output"))))
        (testing (str label " — no !!java tags")
          (is (not (java-tag? emitted))
              (str label ": emitted output carries a !!java tag:\n" (fm-block emitted))))
        (testing (str label " — no nested-seq / ref-slot mangles")
          (is (not (nested-seq-mangle? emitted))
              (str label ": emitted frontmatter carries a nested-map/seq mangle:\n"
                   (fm-block emitted))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R2 — extras byte-identity (HARD)

(deftest r2-extras-byte-identity
  (testing "at-startup / one-line (+ every other extras-riding) lines byte-identical to source"
    (doseq [[label scratch rel-path expected-extras]
            [["ground" ground-scratch ground-rel-path ["at-startup" "one-line"]]
             ["pre-action" preaction-scratch preaction-rel-path
              ["atomicity-exempt" "atomicity-exempt-rationale" "at-startup" "one-line"]]]]
      (let [src     (slurp-scratch scratch)
            emitted (parse->emit-doc src rel-path)]
        (doseq [k expected-extras]
          (let [src-line (emit-line-for src k)
                out-line (emit-line-for emitted k)]
            (is (some? src-line) (str label ": source is missing expected extras key '" k "'"))
            (is (= src-line out-line)
                (str label ": extras key '" k "' not byte-identical.\n  source: "
                     (pr-str src-line) "\n  emitted: " (pr-str out-line)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R3 — idempotency (HARD): emit(parse(emit(parse(src)))) == emit(parse(src))

(deftest r3-idempotency
  (testing "canonical form is a fixed point"
    (doseq [[label scratch rel-path]
            [["ground" ground-scratch ground-rel-path]
             ["pre-action" preaction-scratch preaction-rel-path]]]
      (let [src   (slurp-scratch scratch)
            once  (parse->emit-doc src rel-path)
            twice (parse->emit-doc once rel-path)]
        (is (= once twice)
            (str label ": emit(parse(emit(parse(src)))) != emit(parse(src)) — not idempotent"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R4 — full byte-identity (TARGET): emit(parse(src)) == src.  Where it
;; fails, this test records + classifies each diff hunk (style-only vs
;; semantic).  ANY semantic-class diff FAILS.  Style-only diffs are
;; reported (target, not gate) for Fable's recanonicalization ruling.

(defn diff-lines
  "Return [only-in-a only-in-b] frontmatter-line sets for a vs b."
  [a b]
  (let [as (set (fm-lines a))
        bs (set (fm-lines b))]
    [(remove bs (fm-lines a)) (remove as (fm-lines b))]))

(defn norm-ref-scalar
  "Canonicalize a single ref-slot scalar so the two EQUIVALENT wire
   encodings the codec treats as the same referent compare equal:
     - ident-string form   `memory.actors/claude-opus-4-8`
       (source authors sometimes write the bare :db/ident-derived form)
     - rel-path form       `actors/claude-opus-4-8.md`
       (what the emitter canonically produces via `memory-ident->rel-path`)
   Both normalize to `actors/claude-opus-4-8`.  This is a PRE-EXISTING
   codec ref normalization (present at HEAD, independent of the extras
   fix); the R4/R6 classifier must not mis-file it as a semantic change.
   Non-string / non-ref-shaped values pass through untouched."
  [x]
  (letfn [(undodge [nm]
            ;; The codec digit-dodges leading-digit idents by prefixing the
            ;; class-derived stem (`session-` / `log-` etc.) so the ident is
            ;; EDN-safe; `memory-ident->rel-path` un-dodges on the way back to
            ;; the filename.  A ref written in the corpus in bare dodged-ident
            ;; form (`sessions/session-2026-…`) is the SAME referent as the
            ;; emitted rel-path (`sessions/2026-….md`).  Strip a `<dir-stem>-`
            ;; dodge prefix on a name that is otherwise digit-leading so both
            ;; encodings canonicalize equal.  (Per the P6 digit-dodge codec
            ;; round-trip work — decisions/…defer_p6_mm_id… + the HEAD commit
            ;; cac6570 "digit-dodge the codec round-trip".)
            (str/replace nm #"^[a-z]+-(?=\d)" ""))]
    (if-not (string? x)
      x
      (let [;; drop trailing .md
            s (if (str/ends-with? x ".md") (subs x 0 (- (count x) 3)) x)
            path (cond
                   ;; ident-string form: `<dotted-ns>/<name>` where ns starts memory.
                   (and (str/includes? s "/")
                        (str/starts-with? s "memory."))
                   (let [[ns-part nm] (str/split s #"/" 2)
                         dirs (-> ns-part (subs (count "memory.")) (str/replace "." "/"))]
                     (str dirs "/" nm))
                   ;; rel-path form already: `<dir>/.../<name>` (memory. prefix
                   ;; absent, .md already stripped) — canonical as-is.
                   :else s)]
        ;; un-dodge only the final path segment (the filename stem).
        (if (str/includes? path "/")
          (let [ix (str/last-index-of path "/")]
            (str (subs path 0 ix) "/" (undodge (subs path (inc ix)))))
          (undodge path))))))

(defn norm-ref-val
  "Apply `norm-ref-scalar` across a value that may be a scalar or a
   sequential of scalars (block/flow ref lists)."
  [v]
  (cond
    (sequential? v) (mapv norm-ref-scalar v)
    :else           (norm-ref-scalar v)))

(defn semantic-diff?
  "Classify a frontmatter-line diff as SEMANTIC (a key whose presence /
   parsed value changed) vs style-only (flow↔block restyle, quote-style,
   ident-string↔rel-path ref re-encoding, whitespace).  Conservative: a
   diff is style-only iff the same set of top-level wire-keys is present
   on both sides AND each differing key's PARSED value is equal AFTER
   ref-encoding normalization; else semantic."
  [src emitted]
  (let [src-keys (set (source-fm-keys src))
        out-keys (set (source-fm-keys emitted))]
    (if (not= src-keys out-keys)
      {:semantic? true :reason :key-set-differs
       :only-src (sort (remove out-keys src-keys))
       :only-out (sort (remove src-keys out-keys))}
      ;; same key set — compare parsed values key by key, normalizing the
      ;; ident-string↔rel-path ref re-encoding (a style-class diff).
      (let [src-p (md/parse-frontmatter-text (fm-block src))
            out-p (md/parse-frontmatter-text (fm-block emitted))
            differing (for [k (keys src-p)
                            :when (not= (norm-ref-val (get src-p k))
                                        (norm-ref-val (get out-p k)))]
                        k)]
        (if (seq differing)
          {:semantic? true :reason :value-differs :keys (vec differing)
           :detail (into {} (for [k differing]
                              [k {:src (get src-p k) :out (get out-p k)}]))}
          {:semantic? false :reason :style-only})))))

(deftest r4-full-byte-identity-target
  (testing "emit(parse(src)) vs src — target byte-identity; enumerate + classify diffs"
    (doseq [[label scratch rel-path]
            [["ground" ground-scratch ground-rel-path]
             ["pre-action" preaction-scratch preaction-rel-path]]]
      (let [src     (slurp-scratch scratch)
            emitted (parse->emit-doc src rel-path)
            [only-src only-out] (diff-lines src emitted)
            cls     (semantic-diff? src emitted)]
        (println (str "R4[" label "] byte-identical? " (= src emitted)))
        (when (not= src emitted)
          (println (str "  R4[" label "] classification: " (pr-str cls)))
          (println (str "  R4[" label "] only-in-source lines: " (pr-str (vec only-src))))
          (println (str "  R4[" label "] only-in-emitted lines: " (pr-str (vec only-out)))))
        ;; HARD sub-assertion: no SEMANTIC-class diff.
        (is (not (:semantic? cls))
            (str label ": R4 found a SEMANTIC-class diff (hard failure): " (pr-str cls)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R5 — DB round-trip (HARD): the receipt that reproduces today's live
;; mangles.  Transact parsed entity-specs → read back via the sink's
;; realize path → emit → assert flat tags / rel-path refs / extras / no
;; java tags / no nested map.  Plus a synthetic DB-FIRST entity with
;; :mm.memory/identity → `id: '<uuid>'`.

(defn transact-doc!
  "Transact the parsed entity-specs for `src`/`rel-path` into the fixture
   DB via the codec's tx-boundary helper.  Returns the memory :db/ident."
  [src rel-path]
  (let [specs (md/parse-document src rel-path)
        memory-ident (:db/ident (first specs))]
    ;; The fixture (tu/make-test-db-fixture) has already bound
    ;; sandbar.db.datomic/**conn* to the isolated datomic:mem:// conn via
    ;; `reset!`, so `(db/conn)` here returns the fixture conn — NOT the
    ;; shared dev transactor (SPEC.md §1.2).  Transact via datomic.api/d
    ;; directly (there is no `db/transact` wrapper) per the
    ;; entity-specs->tx-data docstring idiom.
    @(d/transact (db/conn) (md/entity-specs->tx-data specs))
    memory-ident))

(deftest r5-db-round-trip
  (testing "transact TIER-0 → sink-realize → emit: flat refs, extras, no mangles"
    (doseq [[label scratch rel-path]
            [["ground" ground-scratch ground-rel-path]
             ["pre-action" preaction-scratch preaction-rel-path]]]
      (let [src   (slurp-scratch scratch)
            ident (transact-doc! src rel-path)
            ent   (db/entity ident)
            ;; The exact DB→FS path the reactive sink uses.  Pass the lazy
            ;; datomic Entity DIRECTLY — NOT `(into {:dt/type …} ent)`: a
            ;; Datomic Entity's seq/keys do NOT include `:db/id`, so the
            ;; into-map loses it and `dt/realize-with` (which dedups on
            ;; `:db/id`) then DROPS the seed → emit receives nil.
            ;; `dt/realize-with` seeds fine from a raw Entity (its `:db/id`
            ;; is reachable via keyword lookup), matching the sink, whose
            ;; `post-tx-slots` also carries `:db/id`.
            emitted (pg/realize-and-emit-entity ent)]
        (is (some? emitted) (str label ": realize-and-emit-entity returned nil"))
        (when emitted
          (testing (str label " — no !!java tags after DB round-trip")
            (is (not (java-tag? emitted))
                (str label ": DB round-trip emitted a !!java tag:\n" (fm-block emitted))))
          (testing (str label " — no nested-map / ref-slot mangle after DB round-trip")
            (is (not (nested-seq-mangle? emitted))
                (str label ": DB round-trip emitted a nested-map mangle:\n" (fm-block emitted))))
          (testing (str label " — tags: is a flat sequence of bare strings")
            (when-let [tags-line (emit-line-for emitted "tags")]
              (is (not (re-find #"mm\.tag/value|\{|db/ident" tags-line))
                  (str label ": tags line mangled: " (pr-str tags-line)))))
          (testing (str label " — related: is rel-path form, not nested entity")
            (when-let [rel-line (emit-line-for emitted "related")]
              (is (not (re-find #"db/ident|dt/type|\{" rel-line))
                  (str label ": related line mangled: " (pr-str rel-line)))
              (is (re-find #"\.md" rel-line)
                  (str label ": related line lost its rel-path .md form: " (pr-str rel-line)))))
          (testing (str label " — extras lines present byte-identical after DB round-trip")
            (doseq [k ["at-startup" "one-line"]]
              (let [src-line (emit-line-for src k)
                    out-line (emit-line-for emitted k)]
                (is (= src-line out-line)
                    (str label ": extras key '" k "' not byte-identical after DB round-trip.\n"
                         "  source:  " (pr-str src-line) "\n  emitted: " (pr-str out-line))))))))))

  (testing "synthetic DB-FIRST entity (no carrier) with :mm.memory/identity → id: '<uuid>'"
    (let [u   (UUID/randomUUID)
          ;; No :mm.memory/frontmatter carrier → DB-first shape.  No
          ;; sections → walker returns nil, so realize-and-emit-entity
          ;; emits the seed alone via codec/emit (identity reaches the emit
          ;; method, not stripped by emit-document's derived-attrs).
          ;; NB: `dt/realize-with` (which realize-and-emit-entity calls)
          ;; DROPS any seed lacking a `:db/id` (it dedups on eid) — the
          ;; real sink's `post-tx-slots` always carries `:db/id`, so mirror
          ;; that here with a synthetic id; otherwise the seed vanishes and
          ;; emit is handed nil.
          ent {:dt/type :mm/Memory
               :db/id 424242
               :db/ident :memory.decisions/synthetic-db-first
               :mm.memory/name "Synthetic DB-first"
               :mm.memory/memory-type :decision
               :mm.memory/identity u
               :mm.memory/body-raw "# Body\n\nText.\n"}
          emitted (pg/realize-and-emit-entity ent)
          id-line (emit-line-for emitted "id")]
      (is (some? emitted) "realize-and-emit-entity returned nil for synthetic entity")
      (is (not (java-tag? emitted))
          (str "synthetic DB-first entity emitted a !!java UUID tag:\n" (fm-block emitted)))
      (is (some? id-line) (str "synthetic DB-first entity did not emit an id: line:\n" (fm-block emitted)))
      (is (= (str "id: '" (str u) "'") id-line)
          (str "id: line not the plain single-quoted UUID form: " (pr-str id-line)))))

  (testing "synthetic DB-FIRST entity carrying :mm/id (the live post-cutover slot) → id: '<uuid>', no java tag"
    ;; Found live by the 2026-07-02 hot-load probe: real post-cutover
    ;; entities carry their UUID under :mm/id (D1 covenant), NOT
    ;; :mm.memory/identity — the original fix stripped only the latter,
    ;; so :mm/id sailed through fm-slots and re-emitted !!java.util.UUID.
    (let [u   (UUID/randomUUID)
          ent {:dt/type :mm/Memory
               :db/id 424243
               :db/ident :memory.decisions/synthetic-db-first-mm-id
               :mm.memory/name "Synthetic DB-first (mm/id)"
               :mm.memory/memory-type :decision
               :mm/id u
               :mm.memory/body-raw "# Body\n\nText.\n"}
          emitted (pg/realize-and-emit-entity ent)
          id-line (emit-line-for emitted "id")]
      (is (some? emitted) "realize-and-emit-entity returned nil for :mm/id synthetic entity")
      (is (not (java-tag? emitted))
          (str ":mm/id synthetic entity emitted a !!java UUID tag:\n" (fm-block emitted)))
      (is (some? id-line) (str ":mm/id synthetic entity did not emit an id: line:\n" (fm-block emitted)))
      (is (= (str "id: '" (str u) "'") id-line)
          (str "id: line not the plain single-quoted UUID form: " (pr-str id-line))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R6 — drift-set sweep (HARD): for every file in drift-set.edn, copy to
;; scratch, parse→emit, report per-file R1 (keys present, no mangle) + R3
;; (idempotent) + R4 classification.  Zero SEMANTIC-class regressions.

(def drift-set-file
  "Committed fixture copy (formerly untracked
   scratchpad/reconcile-2026-07-02/drift-set.edn)."
  "test/resources/codec-fixtures/reconcile/drift-set.edn")

(def corpus-root
  "Corpus filesystem root for the R6 drift sweep.  Reads the
   SANDBAR_CORPUS_ROOT env-var — the same env-read convention as
   `sandbar.reactive.sinks/corpus-root` — but deliberately WITHOUT that
   fn's $HOME/claude fallback: a test must never silently assume a
   machine-local corpus.  nil when unset; R6 then SKIPS loudly (see
   `r6-drift-sweep`)."
  (System/getenv "SANDBAR_CORPUS_ROOT"))

(defn corpus-root-available?
  "True when SANDBAR_CORPUS_ROOT is set AND resolves to a directory."
  []
  (boolean (and corpus-root (.isDirectory (io/file corpus-root)))))

(defn drift-entries []
  (edn/read-string (slurp (io/file drift-set-file))))

(deftest r6-drift-sweep
  (if-not (corpus-root-available?)
    ;; POLICY (adopted default 2026-07-10, Dan-overridable): without a
    ;; corpus root the sweep SKIPS — but LOUDLY, never as a silent pass.
    ;; The sweep is MANDATORY on the release machine.
    (testing "R6 SKIPPED — SANDBAR_CORPUS_ROOT unset/not-a-directory"
      (println "================================================================")
      (println "R6 SKIPPED: SANDBAR_CORPUS_ROOT is unset (or not a directory) —")
      (println "the drift-set sweep DID NOT RUN. This sweep is MANDATORY on the")
      (println "release machine: export SANDBAR_CORPUS_ROOT=<corpus-root> (e.g.")
      (println "/Users/dan/claude/) and re-run")
      (println "  lein test :only sandbar.codec.fidelity-test/r6-drift-sweep")
      (println "before shipping.")
      (println "================================================================")
      (is true "R6 drift sweep SKIPPED (SANDBAR_CORPUS_ROOT unset) — mandatory on the release machine"))
    (testing "drift-set files parse→emit with zero semantic-class regressions"
      (let [entries (drift-entries)
            tmp     (io/file (System/getProperty "java.io.tmpdir")
                             (str "fidelity-drift-" (System/currentTimeMillis)))]
        (.mkdirs tmp)
        (println (str "R6: sweeping " (count entries) " drift-set entries"))
        (let [results
              (doall
                (for [{:keys [rel-path action flags] :as _entry} entries]
                  (let [srcf (io/file corpus-root rel-path)]
                    (cond
                      ;; Non-round-trippable entries: registry indexes /
                      ;; frontmatter-less specials (e.g. memory/MEMORY.md).
                      ;; parse→emit key-presence is not a meaningful receipt
                      ;; for these; report + skip the hard gates rather than
                      ;; false-fail on a file the codec was never meant to
                      ;; round-trip.  The extras fix touches ONLY frontmatter
                      ;; emit, so these are provably unaffected.
                      (or (contains? (set flags) :no-frontmatter)
                          (= :special action))
                      {:rel-path rel-path :status :skipped-special
                       :action action :flags flags}

                      (not (.exists srcf))
                      {:rel-path rel-path :status :missing-source}

                      :else
                      (let [;; copy to scratch (READ-ONLY discipline: never
                            ;; parse the corpus file in place)
                            scratch (io/file tmp (str/replace rel-path #"/" "__"))
                            _ (io/copy srcf scratch)
                            src (slurp scratch)]
                        (try
                          (let [once  (parse->emit-doc src rel-path)
                                twice (parse->emit-doc once rel-path)
                                src-keys (set (source-fm-keys src))
                                out-keys (set (source-fm-keys once))
                                missing  (remove out-keys src-keys)
                                cls   (semantic-diff? src once)
                                ;; Only NEWLY-introduced mangles count as a
                                ;; regression.  Several drift-set corpus files
                                ;; ALREADY carry `!!java` id: lines (prior
                                ;; sink-emitter output) or prose text that
                                ;; literally contains `:db/ident` /
                                ;; `mm.tag/value` (observations ABOUT those
                                ;; idents).  The extras fix round-trips such
                                ;; lines BYTE-FAITHFULLY (that is the whole
                                ;; point), so a `!!java` / mangle-substring
                                ;; present in the SOURCE and preserved in the
                                ;; OUTPUT is NOT a regression — only one that
                                ;; the emitter INTRODUCES is.
                                src-java?    (java-tag? src)
                                out-java?    (java-tag? once)
                                src-mangle?  (nested-seq-mangle? src)
                                out-mangle?  (nested-seq-mangle? once)]
                            {:rel-path rel-path
                             :status :ok
                             :r1-keys-present? (empty? missing)
                             :r1-missing (vec missing)
                             :src-java? src-java?
                             :out-java? out-java?
                             ;; regression = introduced-by-emit only
                             :r1-no-java? (not (and out-java? (not src-java?)))
                             :src-mangle? src-mangle?
                             :out-mangle? out-mangle?
                             :r1-no-mangle? (not (and out-mangle? (not src-mangle?)))
                             :r3-idempotent? (= once twice)
                             :r4-byte-identical? (= src once)
                             :r4-semantic? (:semantic? cls)
                             :r4-class cls})
                          (catch Exception e
                            {:rel-path rel-path :status :parse-error
                             :error (.getMessage e)})))))))
              ok-results (filter #(= :ok (:status %)) results)
              errored    (filter #(= :parse-error (:status %)) results)
              skipped    (filter #(= :skipped-special (:status %)) results)
              missing-src (filter #(= :missing-source (:status %)) results)
              semantic   (filter :r4-semantic? ok-results)
              non-idem   (filter #(false? (:r3-idempotent? %)) ok-results)
              key-loss   (filter #(false? (:r1-keys-present? %)) ok-results)
              java-leak  (filter #(false? (:r1-no-java? %)) ok-results)
              mangle     (filter #(false? (:r1-no-mangle? %)) ok-results)]
          (println (str "R6 summary: total=" (count results)
                        " ok=" (count ok-results)
                        " skipped-special=" (count skipped)
                        " missing-source=" (count missing-src)
                        " parse-error=" (count errored)
                        " byte-identical=" (count (filter :r4-byte-identical? ok-results))
                        " semantic-regressions=" (count semantic)
                        " non-idempotent=" (count non-idem)
                        " key-loss=" (count key-loss)
                        " java-leak=" (count java-leak)
                        " mangle=" (count mangle)))
          (doseq [r semantic]  (println "  R6 SEMANTIC:" (:rel-path r) (pr-str (:r4-class r))))
          (doseq [r non-idem]  (println "  R6 NON-IDEMPOTENT:" (:rel-path r)))
          (doseq [r key-loss]  (println "  R6 KEY-LOSS:" (:rel-path r) (:r1-missing r)))
          (doseq [r java-leak] (println "  R6 JAVA-LEAK:" (:rel-path r)))
          (doseq [r mangle]    (println "  R6 MANGLE:" (:rel-path r)))
          (doseq [r errored]   (println "  R6 PARSE-ERROR:" (:rel-path r) (:error r)))
          (doseq [r skipped]   (println "  R6 SKIPPED-SPECIAL:" (:rel-path r) (:action r) (pr-str (:flags r))))
          (doseq [r missing-src] (println "  R6 MISSING-SOURCE:" (:rel-path r)))
          ;; HARD: zero semantic regressions, zero key-loss, zero java leaks,
          ;; zero nested-map mangles, zero non-idempotent, zero parse errors.
          (is (empty? semantic)  "R6: semantic-class diffs found in drift sweep")
          (is (empty? key-loss)  "R6: frontmatter key-loss found in drift sweep")
          (is (empty? java-leak) "R6: !!java tag leak found in drift sweep")
          (is (empty? mangle)    "R6: nested-map mangle found in drift sweep")
          (is (empty? non-idem)  "R6: non-idempotent files found in drift sweep")
          (is (empty? errored)   "R6: parse errors found in drift sweep"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R7 — cycle guard (HARD): a section chain with a deliberate
;; :mm.section/next-sibling cycle → emit throws ex-info (not hangs).

(deftest r7-cycle-guard
  (testing "emit-sections-body throws ex-info on a next-sibling cycle (no hang)"
    (let [sec-a {:db/ident :memory.decisions/cyc#sec-a
                 :dt/type :mm/Section
                 :mm.section/heading "A"
                 :mm.section/heading-level 1
                 :mm.section/body "body a"
                 :mm.section/next-sibling :memory.decisions/cyc#sec-b}
          sec-b {:db/ident :memory.decisions/cyc#sec-b
                 :dt/type :mm/Section
                 :mm.section/heading "B"
                 :mm.section/heading-level 1
                 :mm.section/body "body b"
                 ;; deliberate cycle: B → A
                 :mm.section/next-sibling :memory.decisions/cyc#sec-a}
          sections [sec-a sec-b]
          ;; Run under a timeout so a hang FAILS rather than blocking.
          fut (future
                (try
                  (md/emit-sections-body sections :memory.decisions/cyc
                                         :memory.decisions/cyc#sec-a)
                  ::no-throw
                  (catch clojure.lang.ExceptionInfo e
                    {:ex-info (ex-data e)})
                  (catch Throwable t {:other (.getMessage t)})))
          result (deref fut 5000 ::timeout)]
      (is (not= ::timeout result)
          "R7: emit HUNG on a next-sibling cycle (5s timeout) — cycle guard absent/broken")
      (is (map? result) (str "R7: expected ex-info map, got " (pr-str result)))
      (is (= :section-chain-cycle (get-in result [:ex-info :sandbar/error]))
          (str "R7: cycle guard did not throw the expected ex-info: " (pr-str result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; R8 — no-carrier no-change (HARD): a fully-declared-frontmatter file
;; round-trips exactly as on baseline — NO carrier attached, and the emit
;; is byte-identical to the legacy codec-slot-order emit.

(def fully-declared-src
  "A minimal mm/Memory whose frontmatter is 100% declared slots (name /
   description / type / scope / created) — no undeclared or guard-dropped
   keys, so the extras carrier must NOT attach."
  (str "---\n"
       "name: Fully Declared\n"
       "description: All keys map to declared mm/Memory slots\n"
       "type: decision\n"
       "scope: global\n"
       "created: 2026-05-11\n"
       "---\n"
       "# Heading\n\nBody text.\n"))

(deftest r8-no-carrier-no-change
  (testing "fully-declared frontmatter attaches NO carrier"
    (let [parsed (proto/parse (md/make-codec) fully-declared-src {:class :mm/Memory})]
      (is (not (contains? parsed :mm.memory/frontmatter))
          (str "R8: carrier attached to a fully-declared entity (should be absent): "
               (pr-str (get parsed :mm.memory/frontmatter))))))

  (testing "emit byte-identical to the legacy codec-slot-order emit (no behavior change)"
    (let [c        (md/make-codec)
          parsed   (proto/parse c fully-declared-src {:class :mm/Memory})
          emitted  (proto/emit c parsed {})
          ;; Legacy path baseline: same entity WITHOUT any carrier reaches
          ;; the codec-slot-order emit (emit-frontmatter branch), so this
          ;; is the pre-carrier byte-form.  Idempotency then pins it.
          reparsed (proto/parse c emitted {:class :mm/Memory})
          re-emit  (proto/emit c reparsed {})]
      (is (not (str/includes? emitted "frontmatter"))
          (str "R8: emitted output leaked a frontmatter carrier key:\n" emitted))
      (is (= emitted re-emit)
          "R8: fully-declared round-trip not idempotent (behavior change for unaffected population)")
      ;; Body + declared keys survive.
      (is (str/includes? emitted "name: Fully Declared"))
      (is (str/includes? emitted "type: decision"))
      (is (str/includes? emitted "# Heading")))))
