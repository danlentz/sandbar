(ns sandbar.project.export-import-roundtrip-test
  "THE live-store export→import end-to-end round-trip (board-ruled shape,
   fleet cycle 2026-07-21).

   The gap this closes: no live-store export→import round-trip had ever
   run green —
     - `sandbar.projection-test` hands `project-graph` PLAIN entity-spec
       maps (nothing is ever transacted into a store);
     - the W1.J gate (`sandbar.gate.roundtrip`) starts from COMMITTED FS
       fixtures and mirrors the verb internals, never dispatching the
       verbs themselves.
   Neither exercises the live-store scenario: entities BORN IN THE DB
   (transacted, not ingested), exported through the REAL
   `sandbar.project.export` MCP verb, re-imported through the REAL
   `sandbar.project.import` MCP verb, and compared for semantic equality.
   That DB-born export path is exactly where sections were silently
   dropped once before (Codex MUST-FIX #4 — `project-export-handler` now
   realizes the section tree via `pg/mm-walker`); this test pins the fix
   at the DISPATCHED-VERB level against a transacted store.

   Board-ruled test shape:
     1. transact a SECTIONED memorial into a test store
     2. `project.export`  (real verb, via `tools/handle-call`)
     3. assert the sections are PRESENT IN THE EXPORT (file content)
     4. re-import (`project.import :persist? true`, real verb, fresh store)
     5. compare semantic equality via the W1.J harness's §D.5 8-query
        contract helpers (`sandbar.gate.roundtrip-contract`)

   Settle discipline (mirrors `sandbar.gate.roundtrip`'s SETTLE pass, at
   the TEXT level): the authored markdown is normalized ONCE through
   parse→emit before it seeds the source store, so the comparison
   measures round-trip FIDELITY of the store→export→import pipeline, not
   fixture-authoring whitespace normalization — keeping Q6 (per-file
   body SHA) an exact assertion.

   Store lifecycle via `sandbar.gate.db/with-fresh-db*` (ephemeral
   datomic:mem; ambient conn saved/restored) — the live store is never
   touched, per HARD-CONSTRAINT-e.  No `make-test-db-fixture` here, same
   as `sandbar.gate.release-gate-test`."
  (:require [cheshire.core   :as json]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [sandbar.codec.markdown          :as md]
            [sandbar.db.datatype             :as dt]
            [sandbar.gate.db                 :as gdb]
            [sandbar.gate.roundtrip-contract :as contract]
            [sandbar.mcp.tools               :as tools]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fresh-tmp-dir ^java.io.File [stem]
  (let [f (java.io.File/createTempFile (str "e2e-" stem "-") "")]
    (.delete f) (.mkdirs f) f))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- call-verb
  "Dispatch the REAL MCP verb by its wire name via `tools/handle-call`
   (nil-principal in-process path) and return the parsed result payload.
   Asserts the response is NOT an error envelope before parsing."
  [wire-name arguments]
  (let [response (tools/handle-call 1 {:name wire-name :arguments arguments})
        result   (:result response)]
    (is (map? result) (str wire-name " returned no :result — " (pr-str response)))
    (is (not (:isError result))
        (str wire-name " returned isError — " (-> result :content first :text)))
    (some-> result :content first :text (json/parse-string true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the sectioned memorial (authored form)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private rel-path "decisions/live_store_roundtrip_probe.md")

(def ^:private section-headings ["Context" "Decision" "Consequences"])

(def ^:private authored-markdown
  (str "---\n"
       "name: Live Store Roundtrip Probe\n"
       "type: decision\n"
       "scope: project\n"
       "tags: [e2e-roundtrip, live-store]\n"
       "---\n"
       "A memorial BORN IN THE DB (transacted, never ingested from FS),\n"
       "exercising the live-store export→import round-trip.\n"
       "\n"
       "## Context\n"
       "\n"
       "The projection suite hands project-graph plain spec maps; the W1.J\n"
       "gate starts from committed FS fixtures.  Nothing round-trips a\n"
       "transacted store through the dispatched verbs.\n"
       "\n"
       "## Decision\n"
       "\n"
       "Round-trip through sandbar.project.export → sandbar.project.import\n"
       "and assert the §D.5 8-query semantic contract.\n"
       "\n"
       "## Consequences\n"
       "\n"
       "Sections must survive the DB→FS→DB round trip; a green run is the\n"
       "first live-store export→import proof.\n"))

(defn- settled-markdown
  "Normalize the authored source ONCE through the codec (parse→emit) so
   the store is seeded with the at-rest emitted form — the text-level
   analog of the W1.J gate's SETTLE pass.

   NB must run with an ambient schema-loaded DB bound: the markdown codec
   introspects the metamodel at parse time (`dt/range-of` /
   `dt/effective-codec-aliases-of` — see the `sandbar.projection-test`
   docstring), so callers invoke this INSIDE `gdb/with-fresh-db*`."
  []
  (md/emit-document (md/parse-document authored-markdown rel-path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest live-store-export-import-round-trip
  (let [export-dir (fresh-tmp-dir "export")]
    (try
      (let [;; ---- SOURCE STORE — settle, transact, snapshot, export via the verb.
            ;; (The settle parse/emit ALSO needs the ambient schema-loaded DB —
            ;; the codec introspects the metamodel — so it runs inside the same
            ;; fresh-store scope that receives the transact.)
            {:keys [settled source-snapshot export-result]}
            (gdb/with-fresh-db* {:name "e2e-rt-source"}
              (fn []
                (let [settled (settled-markdown)
                      _       (testing "settle sanity — normalization preserves the sections"
                                (doseq [h section-headings]
                                  (is (str/includes? settled (str "## " h))
                                      (str "settled source lost section " h))))
                      specs   (md/parse-document settled rel-path)
                      _       (testing "parse sanity — one memory + three sections"
                                (is (= 4 (count specs)))
                                (is (= :mm/Decision (:dt/type (first specs))))
                                (is (= 3 (count (filter #(= :mm/Section (:dt/type %)) specs)))))]
                  ;; Baseline BEFORE the transact: a schema-loaded store is not
                  ;; empty of file-backed memorials — schema/workflow-session.edn
                  ;; seeds the `:workflow/session` lifecycle definition, which
                  ;; carries `:mm.memory/rel-path "workflows/session.md"` and
                  ;; therefore participates in export/import alongside the probe.
                  ;; All store-level counts below are DELTAS against this
                  ;; baseline, so the test asserts the probe's contribution
                  ;; without hardcoding the schema's resident population.
                  (let [baseline (contract/capture)]
                    ;; (1) transact the sectioned memorial — the canonical
                    ;; sectioned persist boundary (tempid translation via
                    ;; `entity-specs->tx-data`, atomic per-file tx via
                    ;; `make-all*`, F#17), i.e. the memorial is DB-BORN here.
                    (dt/make-all* (md/entity-specs->tx-data specs))
                    (let [snapshot (contract/capture)]
                      (testing "the memorial is IN the store, sectioned"
                        (is (not (contains? (:by-rel-path baseline) rel-path))
                            "probe rel-path must be NEW (not schema-seeded)")
                        (is (contains? (:by-rel-path snapshot) rel-path))
                        (is (= (inc (:population baseline)) (:population snapshot))
                            "exactly the probe joins the file-backed population")
                        (is (= (+ 3 (:section-count baseline)) (:section-count snapshot))
                            "all three probe :mm/Section entities must be transacted"))
                      ;; (2) export through the REAL dispatched verb.
                      {:settled         settled
                       :source-snapshot snapshot
                       :export-result   (call-verb "sandbar_project_export"
                                                   {"to" (str export-dir)})})))))

            exported-file (io/file export-dir rel-path)]

        ;; ---- (3) sections present in the export (the MUST-FIX #4 pin).
        (testing "project.export verb response"
          (is (= (:population source-snapshot) (:exported export-result))
              (str "every file-backed memorial exports (probe + schema-seeded "
                   "residents like workflows/session.md) — got "
                   (pr-str export-result)))
          (is (some #{rel-path} (:files export-result))
              (str "probe file missing from export — " (pr-str (:files export-result)))))
        (testing "sections are present in the exported file"
          (is (.isFile exported-file) (str rel-path " must exist under :to"))
          (let [content (slurp exported-file)]
            (doseq [h section-headings]
              (is (str/includes? content (str "## " h))
                  (str "exported markdown lost section heading " h)))
            (is (str/includes? content "name: Live Store Roundtrip Probe"))
            (is (str/includes? content "A memorial BORN IN THE DB")
                "prologue (pre-first-heading body) must survive export")
            (is (str/includes? content "the §D.5 8-query semantic contract")
                "section body content must survive export")
            (is (= content settled)
                "export of the DB-born memorial reproduces the settled at-rest form byte-for-byte")))

        ;; ---- (4) re-import through the REAL verb into a FRESH store.
        (let [{:keys [import-result reimport-snapshot]}
              (gdb/with-fresh-db* {:name "e2e-rt-reimport"}
                (fn []
                  (let [result (call-verb "sandbar_project_import"
                                          {"from" (str export-dir) "persist?" true})]
                    {:import-result     result
                     :reimport-snapshot (contract/capture)})))]
          (testing "project.import verb response — persisted clean"
            (is (true? (:persist? import-result)))
            (is (= (count (:files export-result)) (:persisted-count import-result))
                (str "every exported file persists as one atomic group — got "
                     (pr-str import-result)))
            (is (zero? (:failed-count import-result))
                (str "failed: " (pr-str (:failed import-result))))
            (is (zero? (:refused-count import-result))
                (str "refused: " (pr-str (:refused import-result)))))

          ;; ---- (5) §D.5 semantic equality, source store vs re-imported store.
          (testing "8-query §D.5 semantic equivalence (zero allowed drift)"
            (let [cmp (contract/compare-contracts source-snapshot reimport-snapshot)]
              (doseq [chk (:checks cmp)]
                (is (:equivalent? chk)
                    (str (name (:query chk)) " diverged: " (pr-str (:detail chk)))))
              (is (:equivalent? cmp)
                  (str "round-trip divergences: " (pr-str (:divergences cmp))))
              (is (empty? (:divergences cmp)))))
          (testing "the probe's per-file fingerprint survives the round trip"
            (is (= (get-in source-snapshot [:by-rel-path rel-path])
                   (get-in reimport-snapshot [:by-rel-path rel-path]))
                "name / type / scope / body-sha / cites must be identical for the probe"))
          (testing "sections survive INTO the re-imported store"
            (is (= (:section-count source-snapshot)
                   (:section-count reimport-snapshot))))))
      (finally
        (rm-rf! export-dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the nested cross-path case (Astra, D7 2026-09-20): two nested levels,
;; sibling order and body text through the dispatched export and a dispatched
;; import into a SECOND fresh store, then that store's own export
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private nested-rel-path "decisions/nested_roundtrip_probe.md")

(def ^:private nested-headings
  ["## Parent" "### Child A" "#### Grandchild" "### Child B" "## Second"])

(def ^:private nested-bodies
  ["Parent body." "Child A body." "Grandchild body." "Child B body." "Second body."])

(def ^:private nested-markdown
  (str "---\n"
       "name: Nested Roundtrip Probe\n"
       "type: decision\n"
       "scope: project\n"
       "---\n"
       "A memorial born in the store with two nested levels.\n"
       "\n"
       "## Parent\n\nParent body.\n\n"
       "### Child A\n\nChild A body.\n\n"
       "#### Grandchild\n\nGrandchild body.\n\n"
       "### Child B\n\nChild B body.\n\n"
       "## Second\n\nSecond body.\n"))

(defn- heading-lines [text]
  (->> (str/split-lines text) (filter #(re-find #"^#{1,6} " %)) vec))

(deftest nested-sections-survive-a-dispatched-export-and-import-into-a-second-fresh-store
  ;; Astra's remaining cross-path acceptance case for REP-01 / REP-02 (her
  ;; D7 increment 1 review, 2026-09-20): the shipped section-tree suite
  ;; exercises project-graph and reparse; this runs the REAL dispatched verbs
  ;; end to end — a store-born memorial with two nested levels exported by
  ;; `project.export`, imported by `project.import` into a SECOND fresh
  ;; store, and exported again from there — asserting heading depth, sibling
  ;; order and body text at every hop.
  (let [export-dir   (fresh-tmp-dir "nested-export")
        reexport-dir (fresh-tmp-dir "nested-reexport")]
    (try
      (let [{:keys [settled source-sections]}
            (gdb/with-fresh-db* {:name "e2e-nested-source"}
              (fn []
                (let [settled (md/emit-document (md/parse-document nested-markdown nested-rel-path))
                      specs   (md/parse-document settled nested-rel-path)]
                  (is (= nested-headings (heading-lines settled))
                      "the settled form keeps every heading at its depth, in order")
                  (is (= 5 (count (filter #(= :mm/Section (:dt/type %)) specs))))
                  (dt/make-all* (md/entity-specs->tx-data specs))
                  (call-verb "sandbar_project_export" {"to" (str export-dir)})
                  {:settled         settled
                   :source-sections (:section-count (contract/capture))})))
            exported (slurp (io/file export-dir nested-rel-path))]
        (testing "the dispatched export keeps two nested levels, sibling order and body text"
          (is (= nested-headings (heading-lines exported)))
          (doseq [b nested-bodies]
            (is (str/includes? exported b) (str "the exported text lost " b)))
          (is (= settled exported) "the export reproduces the settled form byte for byte"))
        (let [{:keys [import-result reimported-sections reexported]}
              (gdb/with-fresh-db* {:name "e2e-nested-reimport"}
                (fn []
                  (let [result (call-verb "sandbar_project_import" {"from" (str export-dir) "persist" true})]
                    (call-verb "sandbar_project_export" {"to" (str reexport-dir)})
                    {:import-result       result
                     :reimported-sections (:section-count (contract/capture))
                     :reexported          (slurp (io/file reexport-dir nested-rel-path))})))]
          (testing "the dispatched import into the second store persists the unit"
            (is (zero? (:failed-count import-result)) (pr-str (:failed import-result)))
            (is (zero? (:conflict-count import-result)) (pr-str (:conflicts import-result)))
            (is (some #(= nested-rel-path (:source %)) (:persisted import-result))
                (pr-str (:persisted import-result))))
          (testing "the second store holds the whole tree and renders it back identically"
            (is (= source-sections reimported-sections)
                "every section, nested or not, is in the second store")
            (is (= nested-headings (heading-lines reexported)))
            (doseq [b nested-bodies]
              (is (str/includes? reexported b) (str "the second store's export lost " b)))
            (is (= settled reexported) "the second store's export equals the first's"))))
      (finally
        (rm-rf! export-dir)
        (rm-rf! reexport-dir)))))
