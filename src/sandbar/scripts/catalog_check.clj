(ns sandbar.scripts.catalog-check
  "The catalog single-source DRIFT GATE — regenerate every projection from the
   ONE source (the `verb-catalog` def in sandbar.mcp.tools) and diff each against
   its committed copy, exiting non-zero on ANY mismatch.

   This is the landed JVM form of the F6 staging `drift_gate.bb`.  It shares the
   EXACT generator code the `lein affordance-map` / `verb-edges-map` /
   `memory-open-affordance` aliases use (it calls their `render` fns), so the
   check can never diverge from what `lein catalog-regen` produces.  Because the
   catalog-model is DB-free, this runs in seconds with no Datomic spin-up — fit
   for a pre-commit hook or a CI job that need not wait on the test matrix.

   The gate compares RENDERED BYTES, not counts: a verb rename with no count
   change, a safety-class flip, a reordered axis, a changed combines-with edge
   all produce a non-empty diff and fail.  A count-only check would pass while
   wrong.  Plus the §5.8 classifier/override completeness invariant fails the
   build the instant a rename flips a verb's leaf safety class — de-risking the
   consolidation that renames verbs (this gate lands BEFORE any collapse, per
   the w45_rescope amendment-5 consolidation-first ruling).

   SCOPE (design open-question #3 / PROPOSED-CI-WIRING option b): by default the
   gate checks only the sandbar-LOCAL projections (doc/mcp-affordance-map.md +
   the :mm/Verb parity + §5.8 completeness) so `lein catalog-check` is
   self-contained — green with no sibling corpus checkout.  The two CORPUS
   projections (etc/verb-edges.edn, .claude/commands/memory-open.md) are checked
   only when their paths are supplied (--verb-edges / --memory-open or the
   F6_VERB_EDGES / F6_MEMORY_OPEN env vars), so the corpus repo can run the same
   gate over its own files.

   P4 fail-closed: a projection that cannot be generated (parse error, missing
   editorial row, orphaned eager verb) FAILS rather than skipping.

   Usage:
     lein catalog-check
       [--affordance PATH]   (default: doc/mcp-affordance-map.md, cwd-relative)
       [--verb-edges PATH]   (opt-in; F6_VERB_EDGES)
       [--memory-open PATH]  (opt-in; F6_MEMORY_OPEN)
       [--editorial PATH]    (default: vendored resources/catalog/affordance-editorial.edn)
       [--eager-core PATH]   (default: vendored resources/catalog/eager-core.edn)
       [--report-only]       (print the report but always exit 0 — advisory mode)"
  (:require [clojure.string                     :as str]
            [sandbar.mcp.tools                  :as tools]
            [sandbar.mcp.catalog-model          :as model]
            [sandbar.scripts.affordance-map     :as aff]
            [sandbar.scripts.verb-edges-map     :as edges]
            [sandbar.scripts.memory-open-affordance :as moa]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Arg / path resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-args [args]
  (loop [a args, m {}]
    (if (empty? a)
      m
      (let [[k & more] a]
        (if (str/starts-with? (str k) "--")
          (if (= k "--report-only")
            (recur more (assoc m :report-only true))
            (recur (rest more) (assoc m (keyword (subs k 2)) (first more))))
          (recur more m))))))

(defn- resolve-path [cli cli-key env-key default]
  (or (get cli cli-key) (System/getenv env-key) default))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Diff helper — first differing line + bounded added/removed line-sets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- line-diff
  "nil if equal, else a report string naming the first divergence + the
   added/removed line-sets (bounded to 12 each)."
  [committed regen]
  (if (= committed regen)
    nil
    (let [cl (str/split-lines committed)
          rl (str/split-lines regen)
          first-diff (->> (map vector (range) cl rl)
                          (some (fn [[i c r]] (when (not= c r) i))))
          added   (remove (set cl) rl)
          removed (remove (set rl) cl)
          cap     (fn [xs] (let [v (vec xs)]
                             (if (> (count v) 12)
                               (conj (subvec v 0 12) (str "… (+" (- (count v) 12) " more)"))
                               v)))]
      (str "  first divergence at line " (if first-diff (inc first-diff) "?")
           " (committed " (count cl) " lines / regen " (count rl) " lines)\n"
           (when (seq removed)
             (str "  --- only in committed (" (count removed) "):\n"
                  (str/join "\n" (map #(str "      - " %) (cap removed))) "\n"))
           (when (seq added)
             (str "  +++ only in regenerated (" (count added) "):\n"
                  (str/join "\n" (map #(str "      + " %) (cap added))) "\n"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; memory-open.md region extraction (pre-sentinel live file: extract by structure)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-eager-line [mo-text]
  (->> (str/split-lines mo-text)
       (filter #(str/starts-with? % "ToolSearch select:"))
       first))

(defn- extract-table [mo-text]
  (let [lines (str/split-lines mo-text)
        start (->> lines (map-indexed vector)
                   (some (fn [[i l]] (when (str/starts-with? l "| Axis | n |") i))))]
    (when start
      (let [rows (->> (drop start lines) (take-while #(str/starts-with? % "|")))]
        (str (str/join "\n" rows) "\n")))))

(defn- extract-count-sentences [mo-text]
  (->> (re-seq #"\d+ verbs / \d+ axes" mo-text) distinct vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §5.8 classifier / override completeness (catalog-coupled invariants)
;;
;; Reads the AUTHORITATIVE private classifier sets from sandbar.mcp.tools via
;; var-quote — NOT a re-copy (copying would reintroduce the very drift this gate
;; exists to catch, and F6 must not touch tools.clj, the ceremony collision
;; magnet).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private destructive-verb-leaves  @#'tools/destructive-verb-leaves)
(def ^:private idempotent-write-leaves  @#'tools/idempotent-write-leaves)
(def ^:private read-only-denied-overrides @#'tools/read-only-denied-overrides)
(def ^:private wire->canonical          @#'tools/wire->canonical)

(defn- completeness-report
  "Return {:fail? :report}.  FAILS if a read-only-denied-override no longer
   names an existing read-only verb (a stale override = latent authz bug), OR
   if the dots→underscores WIRE rename regresses: a wire name that still carries
   a dot / breaks the Anthropic pattern, a non-injective wire projection, or a
   broken wire↔canonical round-trip (any of which could flip a verb's authz
   class at dispatch, per the 2026-07-04 underscore ruling).  Reports (INFO,
   non-failing) verbs whose leaf falls through to deny-by-default mutating — a
   genuinely-new SAFE leaf would be accidentally locked out and should surface
   for review."
  [model]
  (let [verbs (:verbs model)
        catalog-names  (set (map :name verbs))
        known-mutating (into destructive-verb-leaves idempotent-write-leaves)
        fallthrough (for [v verbs
                          :let [leaf (last (str/split (:name v) #"\."))]
                          :when (and (not (:read-only? v))
                                     (not (contains? known-mutating leaf)))]
                      (:name v))
        bad-overrides (for [o read-only-denied-overrides
                            :let [hints (tools/verb-behavioral-hints o)]
                            :when (or (not (contains? catalog-names o))
                                      (not (:read-only? hints)))]
                        {:override o
                         :exists? (contains? catalog-names o)
                         :read-only? (:read-only? hints)})
        ;; --- wire-name rename invariants (dots→underscores; 2026-07-04) ---
        wire-pattern    #"^[a-zA-Z0-9_-]{1,64}$"
        bad-wire        (for [n (sort catalog-names)
                              :let [w (tools/wire-name n)]
                              :when (or (str/includes? w ".")
                                        (not (re-matches wire-pattern w)))]
                          {:verb n :wire w})
        bad-roundtrip   (for [n (sort catalog-names)
                              :let [w    (tools/wire-name n)
                                    back (get wire->canonical w)]
                              :when (not= n back)]
                          {:verb n :wire w :resolves-to back})
        wire-collision? (not= (count wire->canonical) (count catalog-names))]
    {:fail? (boolean (or (seq bad-overrides) (seq bad-wire)
                         (seq bad-roundtrip) wire-collision?))
     :report
     (str
      (when (seq fallthrough)
        (str "  INFO deny-by-default (unknown mutating leaf — expected for new mutating verbs, "
             "but confirm none is a genuinely-safe leaf that should be added to read-only-verb-leaves):\n"
             (str/join "\n" (map #(str "      · " %) fallthrough)) "\n"))
      (when (seq bad-overrides)
        (str "  FAIL stale read-only-denied-override(s) — no longer load-bearing:\n"
             (str/join "\n" (map #(str "      x " (pr-str %)) bad-overrides)) "\n"))
      (when (seq bad-wire)
        (str "  FAIL wire name(s) not pattern-conformant (^[a-zA-Z0-9_-]{1,64}$; no dots):\n"
             (str/join "\n" (map #(str "      x " (pr-str %)) bad-wire)) "\n"))
      (when (seq bad-roundtrip)
        (str "  FAIL wire↔canonical round-trip broken (dispatch could flip authz class):\n"
             (str/join "\n" (map #(str "      x " (pr-str %)) bad-roundtrip)) "\n"))
      (when wire-collision?
        (str "  FAIL wire-name collision — " (count wire->canonical) " wire names for "
             (count catalog-names) " verbs (dots→underscores not injective here)\n")))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Run the gate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- exists? [p] (and p (.exists (java.io.File. ^String p))))

(defn run
  "Run the gate. Returns the process exit code (0 sync / 1 drift / 2 error)."
  [args]
  (let [cli (parse-args args)
        affordance-path (resolve-path cli :affordance "F6_AFFORDANCE" "doc/mcp-affordance-map.md")
        verb-edges-path (resolve-path cli :verb-edges "F6_VERB_EDGES" nil)
        memory-open-path (resolve-path cli :memory-open "F6_MEMORY_OPEN" nil)
        editorial-path  (resolve-path cli :editorial "F6_EDITORIAL" nil)  ; nil -> vendored resource
        eager-core-path (resolve-path cli :eager-core "F6_EAGER_CORE" nil)
        m       (model/build-catalog-model)
        summary (model/catalog-summary m)
        results (atom [])
        record! (fn [nm status detail] (swap! results conj {:name nm :status status :detail detail}))]

    (println (str "catalog drift gate — source: sandbar.mcp.tools/verb-catalog (in-process, DB-free)"))
    (println (str "  model: " (:verb-count summary) " verbs / " (:axis-count summary)
                  " axes  (entity=" (get-in summary [:by-axis :entity]) ")"))
    (println)

    ;; ---- projection: affordance doc (sandbar-local, always) ----
    (if (exists? affordance-path)
      (let [d (line-diff (slurp affordance-path) (aff/render m))]
        (record! "doc/mcp-affordance-map.md" (if d :DRIFT :OK) d))
      (record! "doc/mcp-affordance-map.md" :MISSING (str "  file not found: " affordance-path)))

    ;; ---- projection: verb-edges.edn (corpus, opt-in) ----
    (when verb-edges-path
      (if (exists? verb-edges-path)
        (let [d (line-diff (slurp verb-edges-path) (edges/render m))]
          (record! "etc/verb-edges.edn" (if d :DRIFT :OK) d))
        (record! "etc/verb-edges.edn" :MISSING (str "  file not found: " verb-edges-path))))

    ;; ---- projection: memory-open.md region (corpus, opt-in) ----
    (when memory-open-path
      (if (exists? memory-open-path)
        (let [mo        (slurp memory-open-path)
              editorial (moa/load-editorial editorial-path)
              policy    (moa/load-eager-core eager-core-path)]
          ;; eager-core block
          (let [derived (try (moa/render-eager-core-block m policy)
                             (catch Exception e (str "GENFAIL " (.getMessage e))))
                live    (extract-eager-line mo)
                d (cond (str/starts-with? (str derived) "GENFAIL") (str "  " derived)
                        (nil? live) "  no ToolSearch select: line found in memory-open.md"
                        (not= derived live) (str "  eager-core block drift\n" (line-diff (str live) (str derived)))
                        :else nil)]
            (record! "memory-open.md :: eager-core block" (if d :DRIFT :OK) d))
          ;; affordance table
          (let [regen-table (try (moa/render-affordance-table m editorial)
                                 (catch Exception e (str "GENFAIL " (.getMessage e))))
                live-table  (extract-table mo)
                d (cond (str/starts-with? (str regen-table) "GENFAIL") (str "  " regen-table)
                        (nil? live-table) "  no affordance table found in memory-open.md"
                        :else (line-diff (str live-table) (str regen-table)))]
            (record! "memory-open.md :: affordance table" (if d :DRIFT :OK) d))
          ;; count sentences
          (let [want  (moa/count-sentence m)
                lives (extract-count-sentences mo)
                stale (remove #(= % want) lives)
                d (when (seq stale)
                    (str "  memory-open.md says " (pr-str lives)
                         "; single-source truth is " (pr-str want) "\n"))]
            (record! "memory-open.md :: 'N verbs / M axes' count" (if d :DRIFT :OK) d)))
        (record! "memory-open.md" :MISSING (str "  file not found: " memory-open-path))))

    ;; ---- projection: :mm/Verb seed parity (pure, no DB) ----
    (let [verbs (:verbs m)
          bad   (remove #(and (:name %) (:axis %) (:safety %)) verbs)
          d (cond (seq bad) (str "  " (count bad) " verb(s) missing name/axis/safety: " (pr-str (map :name bad)))
                  (not= (count verbs) (:verb-count summary)) "  count mismatch model vs summary"
                  :else nil)]
      (record! ":mm/Verb seed parity (model well-formed; no DB)" (if d :DRIFT :OK) d))

    ;; ---- §5.8 classifier / override completeness ----
    (let [{:keys [fail? report]} (completeness-report m)]
      (record! "classifier/override completeness (§5.8)"
               (if fail? :DRIFT :OK)
               (when (seq (str/trim (str report))) report)))

    ;; ---- report ----
    (println "projection checks:")
    (doseq [{:keys [name status detail]} @results]
      (println (format "  [%-7s] %s" (clojure.core/name status) name))
      (when (and detail (not= status :OK))
        (println detail)))
    (println)

    (let [statuses (map :status @results)
          fails    (filter #{:DRIFT :MISSING} statuses)]
      (if (seq fails)
        (do (println (str "DRIFT GATE: FAIL — " (count fails) " projection(s) out of sync.  "
                          "Run 'lein catalog-regen' and re-stage the projections."))
            (if (:report-only cli) (do (println "(--report-only: exiting 0)") 0) 1))
        (do (println "DRIFT GATE: PASS — all projections in sync with the source catalog.")
            0)))))

(defn -main [& args]
  (let [code (try (run args)
                  (catch Exception e
                    (println "DRIFT GATE: ERROR (fail-closed) —" (.getMessage e))
                    (println (pr-str (ex-data e)))
                    2))]
    (flush)
    (shutdown-agents)
    (System/exit code)))
