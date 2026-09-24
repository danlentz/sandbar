(ns sandbar.projection.section-tree-test
  "Nested sections survive every path that renders a memorial (REP-01 of
   Astra's 0.2.0 representation review) and mixed heading depths chain in
   document order (REP-02) — folded into D7 on 2026-09-20.

   Her reproductions at 7fd481f: `# Parent` / `## Child` parsed into three
   entities, direct emission kept Child, but after a transaction the
   realized rendering and the dispatched export wrote only Parent while
   reporting success; `project-graph` over the plain vector dropped Child
   with and without a class filter.  Three seams: the walker read a reverse
   attribute Datomic does not name (`:_mm.section/parent`), the export
   grouping followed only the top chain, the export filter kept only
   sections whose IMMEDIATE parent is a passing memory.  Separately (REP-02)
   `# A`, `### B`, `## C` gave B and C the same parent but no sibling link,
   and emission kept B and silently dropped C.

   What this pins, per her acceptance: direct codec emission, persisted
   rendering, unfiltered and filtered export, a fresh-store re-parse of the
   exported file, and the resource rendering all preserve two nested levels,
   sibling order and body text, compared as the semantic tree before the
   first normalization; generated heading-level sequences (skipped depths,
   a leading level-2 heading, repeated titles, empty bodies, several
   same-parent levels) keep every accepted heading reachable exactly once;
   documents whose links were recorded before D7 (disconnected chains) still
   emit every section; and the re-emission oracle D7's repairs rest on: the
   emitted body of an imported memorial equals its normalized body text."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [clojure.test           :refer [deftest is testing use-fixtures]]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.mcp.resources  :as resources]
            [sandbar.projection     :as pg]
            [sandbar.test-util      :as tu]))

(use-fixtures :each
  (fn [f]
    (md/register!)
    ((tu/make-test-db-fixture {:test-name "section-tree-test" :auth? false}) f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fresh-tmp-dir ^java.io.File [stem]
  (let [f (java.io.File/createTempFile (str "section-tree-" stem "-") "")]
    (.delete f) (.mkdirs f) f))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- headings
  "The heading lines of a markdown text, in order, front matter excluded."
  [text]
  (let [body (if (str/starts-with? text "---")
               (let [parts (str/split text #"(?m)^---\s*$" 3)] (nth parts 2 ""))
               text)]
    (->> (str/split-lines body)
         (filter #(re-find #"^#{1,6} " %))
         vec)))

(defn- body-of
  "The body of an emitted document (after the front matter)."
  [text]
  (if (str/starts-with? text "---")
    (let [parts (str/split text #"(?m)^---\s*$" 3)]
      (str/replace-first (nth parts 2 "") #"^\n" ""))
    text))

(defn- semantic-tree
  "The section graph of a parse as data: [heading level parent-heading body]
   per section in the order given, with parents resolved to headings so two
   parses of the same document compare equal whatever their idents."
  [specs]
  (let [sections (rest specs)
        by-ident (into {} (map (fn [s] [(:db/ident s) s])) sections)]
    (mapv (fn [s]
            [(:mm.section/heading s)
             (:mm.section/heading-level s)
             (some-> (get by-ident (:mm.section/parent s)) :mm.section/heading)
             (:mm.section/body s)])
          sections)))

(defn- persist! [specs]
  (dt/make-all* (md/entity-specs->tx-data specs)))

(def ^:private nested-doc
  "---\ntype: decision\nname: Nested probe\ndescription: two nested levels with bodies and a prologue\n---\nA prologue before the first heading.\n\n# Parent\n\nParent body.\n\n## Child\n\nChild body.\n\n### Grandchild\n\nGrandchild body.\n\n## Child Two\n\nChild two body.\n")

(def ^:private nested-headings
  ["# Parent" "## Child" "### Grandchild" "## Child Two"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REP-01 — nested sections survive every rendering path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest nested-sections-survive-every-rendering-path
  (let [rel-path "decisions/nested_probe.md"
        specs    (md/parse-document nested-doc rel-path)
        ident    (:db/ident (first specs))
        tree     (semantic-tree specs)
        dir      (fresh-tmp-dir "export")]
    (try
      (is (= 5 (count specs)) "the memory and four sections")
      (is (= nested-headings (headings (md/emit-document specs))) "direct codec emission keeps every heading in order")
      (testing "the shared tree walk"
        (is (= ["Parent" "Child" "Grandchild" "Child Two"]
               (mapv :mm.section/heading (md/section-tree (rest specs) ident (:mm.memory/first-section (first specs)))))))
      (persist! specs)
      (testing "persisted rendering realizes and emits the whole tree"
        (let [text (pg/realize-and-emit-entity (db/entity ident))]
          (is (= nested-headings (headings text)) text)
          (is (str/includes? text "Grandchild body."))
          (is (str/starts-with? (body-of text) "A prologue before the first heading.") "the prologue survives")
          (testing "the re-emission oracle: the emitted body equals the normalized body text"
            (is (= (md/normalize-body (body-of nested-doc)) (body-of text))))))
      (testing "the resource rendering uses the same walk"
        (let [uri (resources/entity->uri (db/entity ident))
              response (resources/handle-read 1 {:uri uri} {:auth/full-clearance? true})
              content (get-in response [:result :contents 0])]
          (is (nil? (:error response)) (pr-str response))
          (is (= "text/markdown" (:mimeType content)))
          (is (= nested-headings (headings (:text content))))))
      (testing "the export over plain entity maps, unfiltered and filtered by class"
        (doseq [opts [{:to (.getPath dir)} {:to (.getPath dir) :filter {:class :mm/Decision}}]]
          (let [written (pg/project-graph specs opts)
                text    (slurp (io/file dir rel-path))]
            (is (= 1 (count written)) (pr-str opts))
            (is (= nested-headings (headings text)) (pr-str opts))
            (testing "a fresh parse of the exported file has the same semantic tree"
              (is (= tree (semantic-tree (md/parse-document text rel-path))))))))
      (testing "the export over realized database entities"
        (let [realized (dt/realize-with (db/entity ident) pg/mm-walker)
                _      (pg/project-graph realized {:to (.getPath dir)})
              text     (slurp (io/file dir rel-path))]
          (is (= nested-headings (headings text)))))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REP-02 — mixed heading depths chain in document order
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mixed-heading-depths-chain-in-document-order
  (let [doc   "---\ntype: decision\nname: Mixed depths\ndescription: a level-3 heading before a level-2 heading under one parent\n---\n# A\n\nA body.\n\n### B\n\nB body.\n\n## C\n\nC body.\n"
        specs (md/parse-document doc "decisions/mixed_depths.md")
        [a b c] (rest specs)]
    (is (= ["A" "B" "C"] (mapv :mm.section/heading [a b c])))
    (is (= (:db/ident a) (:mm.section/parent b)) "B is A's child")
    (is (= (:db/ident a) (:mm.section/parent c)) "C is A's child too, its level notwithstanding")
    (is (= (:db/ident c) (:mm.section/next-sibling b)) "B chains to C")
    (is (= (:db/ident b) (:mm.section/previous-sibling c)) "C chains back to B")
    (let [text (md/emit-document specs)]
      (is (= ["# A" "### B" "## C"] (headings text)) "every heading emitted once, in document order")
      (is (= (md/normalize-body (body-of doc)) (body-of text)) "the re-emission oracle holds"))))

(defn- doc-for
  "A document from heading levels and titles; bodies are numbered, or empty
   when `empty-bodies?`."
  [levels titles empty-bodies?]
  (str "---\ntype: decision\nname: generated\ndescription: generated heading sequence\n---\n"
       (str/join "\n"
                 (map-indexed (fn [i [level title]]
                                (str (apply str (repeat level "#")) " " title "\n"
                                     (when-not empty-bodies? (str "\nBody " i ".\n"))))
                              (map vector levels titles)))))

(deftest generated-heading-sequences-keep-every-heading-reachable-once
  (doseq [[levels titles empty?] [[[1 3 2] ["H1" "H2" "H3"] false]
                                  [[2 1 2] ["H1" "H2" "H3"] false]
                                  [[1 2 2 3 1] ["H1" "H2" "H3" "H4" "H5"] false]
                                  [[2 4 3 2] ["H1" "H2" "H3" "H4"] false]
                                  [[1 1 1] ["H1" "H2" "H3"] true]
                                  [[3 2 1] ["H1" "H2" "H3"] false]
                                  [[1 2 3 2 3] ["H1" "H2" "H3" "H4" "H5"] false]
                                  [[1 2 2] ["Same" "Same" "Same"] false]
                                  [[1 2 3 2 3 4] ["Top" "Mid" "Low" "Mid" "Low" "Deep"] true]]]
    (let [doc     (doc-for levels titles empty?)
          specs   (md/parse-document doc "decisions/generated.md")
          text    (md/emit-document specs)
          heads   (headings text)
          re-tree (semantic-tree (md/parse-document text "decisions/generated.md"))]
      (testing (pr-str levels titles)
        (is (= (count levels) (count (rest specs))) "every heading parsed")
        (is (= (count levels) (count heads)) (str "every heading emitted exactly once: " (pr-str heads)))
        (is (= (map vector levels titles)
               (map (fn [h] [(count (re-find #"^#+" h)) (str/trim (subs h (inc (count (re-find #"^#+" h)))))]) heads))
            "in document order with their levels")
        (is (= (semantic-tree specs) re-tree) "a re-parse of the emission is the same tree")
        (when-not empty?
          (is (= (md/normalize-body (body-of doc)) (body-of text)) "the re-emission oracle holds"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; documents recorded before D7 — disconnected chains still emit everything
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest disconnected-legacy-chains-still-emit-every-section
  ;; The shape the old parser recorded for `# A`, `### B`, `## C`: B and C both
  ;; children of A, neither linked to the other, each a chain head.  The eids
  ;; stand in for document order.
  (let [memory {:db/ident :memory.decisions/legacy :dt/type :mm/Decision
                :mm.memory/rel-path "decisions/legacy.md" :mm.memory/name "legacy"
                :mm.memory/memory-type :decision :mm.memory/body-raw ""
                :mm.memory/first-section :memory.decisions/legacy__a}
        a {:db/id 1 :db/ident :memory.decisions/legacy__a :dt/type :mm/Section
           :mm.section/heading "A" :mm.section/heading-level 1 :mm.section/parent :memory.decisions/legacy :mm.section/body "A body."}
        b {:db/id 2 :db/ident :memory.decisions/legacy__b :dt/type :mm/Section
           :mm.section/heading "B" :mm.section/heading-level 3 :mm.section/parent :memory.decisions/legacy__a :mm.section/body "B body."}
        c {:db/id 3 :db/ident :memory.decisions/legacy__c :dt/type :mm/Section
           :mm.section/heading "C" :mm.section/heading-level 2 :mm.section/parent :memory.decisions/legacy__a :mm.section/body "C body."}
        text (md/emit-document [memory a c b])]
    (is (= ["# A" "### B" "## C"] (headings text)) text)
    (is (= [:memory.decisions/legacy__a :memory.decisions/legacy__b :memory.decisions/legacy__c]
           (mapv :db/ident (md/section-tree [c b a] :memory.decisions/legacy :memory.decisions/legacy__a)))
        "the tree walk orders the heads by their eids when no link joins them")))

(deftest a-broken-first-section-link-loses-nothing
  (let [specs  (md/parse-document nested-doc "decisions/broken_link.md")
        memory (assoc (first specs) :mm.memory/first-section :memory.decisions/no_such_section)
        text   (md/emit-document (into [memory] (rest specs)))]
    (is (= nested-headings (headings text)) "the chain heads are found without the link")))
