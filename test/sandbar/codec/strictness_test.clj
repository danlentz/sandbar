(ns sandbar.codec.strictness-test
  "D6, codec strictness (2026-09-19): the interactive create refuses malformed
   or lossy front matter with a verdict before anything is built; the bulk
   path keeps the carrier; the emitter's block scalars parse back; a Tag
   document imports; the D6 schema declarations are visible.

   Each case names the contract it pins: RT-01 (Astra: an invalid strict write
   leaves no committed facts, no projection enqueue and no notification),
   REP-04 (block scalars), REP-05 (Tag body), RT-11 (wrong primitive types
   refused at the boundary), and the 2026-09-19 14:59Z opaque failure."
  (:require [cheshire.core          :as json]
            [clojure.string         :as str]
            [clojure.test           :refer :all]
            [datomic.api            :as d]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.mcp.tools      :as tools]
            [sandbar.reactive.queue :as reactive-queue]
            [sandbar.test-util      :as tu]))

(defn- with-markdown-codec
  "Production registers the markdown codec at boot (sandbar.core); the test
   process registers it itself, as the other codec suites do."
  [t]
  (md/register!)
  (t))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "codec-strictness-test"})
  with-markdown-codec)

(defn- call [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- payload [resp]
  (some-> resp (get-in [:result :content 0 :text]) (json/parse-string true)))

(defn- error? [resp] (true? (get-in resp [:result :isError])))

(def ^:private clean-source
  "---\nname: strictness probe\ndescription: a clean document\ntype: observation\nscope: project\n---\n\n## Body\n\nText.\n")

(def ^:private unknown-key-source
  "---\nname: strictness probe\ndescription: with an unmapped key\ntype: observation\nscope: project\nsuggested-type: log\n---\n\n## Body\n\nText.\n")

(defn- create-args [source & [extra]]
  (merge {"class" ":mm/Observation" "format" "markdown" "source" source
          "slots" {"mm.memory/rel-path" "observations/strictness_probe.md"}}
         extra))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interactive strictness + the RT-01 boundary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unknown-key-is-refused-with-a-verdict-and-leaves-nothing-behind
  (let [basis-before   (d/basis-t (d/db (db/conn)))
        enqueue-before (:enqueue-total (reactive-queue/health))
        published      (atom 0)
        resp           (with-redefs [notifications/publish! (fn [& _] (swap! published inc) nil)]
                         (call "sandbar.entity.create" (create-args unknown-key-source)))
        body           (payload resp)]
    (testing "the verdict names the key, the class and the accepted keys"
      (is (error? resp) (pr-str resp))
      (is (str/includes? (:message body) "suggested-type") (pr-str body))
      (is (str/includes? (:message body) "Accepted keys"))
      (is (str/includes? (:message body) "allow-unknown-keys"))
      (is (= "frontmatter-refused" (get-in body [:details :type])))
      (is (some #(= "suggested-type" (:key %)) (get-in body [:details :refused]))))
    (testing "RT-01: no committed facts, no projection enqueue, no notification"
      (is (= basis-before (d/basis-t (d/db (db/conn)))) "the basis did not move")
      (is (= enqueue-before (:enqueue-total (reactive-queue/health))) "nothing was enqueued")
      (is (zero? @published) "nothing was published")
      (is (nil? (dt/find-by-ident :memory.observations/strictness_probe))))))

(deftest allow-unknown-keys-carries-the-key-instead
  (let [resp (call "sandbar.entity.create" (create-args unknown-key-source {"allow-unknown-keys" true}))
        body (payload resp)]
    (is (not (error? resp)) (pr-str body))
    (let [e       (dt/find-by-ident :memory.observations/strictness_probe)
          carrier (:mm.memory/frontmatter e)]
      (is (some? e))
      (is (some? carrier) "the carrier is attached")
      (is (str/includes? (str (:mm.frontmatter/extra carrier)) "suggested-type")))))

(deftest a-clean-document-creates-under-strictness
  (let [resp (call "sandbar.entity.create" (create-args clean-source))]
    (is (not (error? resp)) (pr-str (payload resp)))
    (is (some? (dt/find-by-ident :memory.observations/strictness_probe)))))

(deftest an-empty-value-is-refused-with-its-reason
  (let [resp (call "sandbar.entity.create"
                   (create-args "---\nname: strictness probe\ndescription: empty importance\ntype: observation\nimportance: \"\"\n---\n\nText.\n"))
        body (payload resp)]
    (is (error? resp) (pr-str body))
    (is (some #(and (= "importance" (:key %)) (= "empty" (:reason %)))
              (get-in body [:details :refused]))
        (pr-str (get-in body [:details :refused])))))

(deftest a-wrong-primitive-type-is-refused-at-the-boundary
  ;; RT-11 at the codec boundary: :mm.pattern/confidence is a double (D6);
  ;; a word where a number is declared is refused with the slot named.
  (let [resp (call "sandbar.entity.create"
                   {"class" ":mm/Pattern" "format" "markdown"
                    "source" "---\nname: typed probe\ndescription: wrong type\ntype: pattern\nconfidence: high\n---\n\nText.\n"
                    "slots" {"mm.memory/rel-path" "patterns/typed_probe.md"}})
        body (payload resp)]
    (is (error? resp) (pr-str body))
    (is (some #(and (= "confidence" (:key %)) (= "wrong-type" (:reason %)))
              (get-in body [:details :refused]))
        (pr-str (get-in body [:details :refused])))
    (is (nil? (dt/find-by-ident :memory.patterns/typed_probe)))))

(deftest a-numeric-confidence-lands-as-a-double
  (let [resp (call "sandbar.entity.create"
                   {"class" ":mm/Pattern" "format" "markdown"
                    "source" "---\nname: typed probe\ndescription: right type\ntype: pattern\nconfidence: 0.93\n---\n\nText.\n"
                    "slots" {"mm.memory/rel-path" "patterns/typed_probe.md"}})]
    (is (not (error? resp)) (pr-str (payload resp)))
    (is (= 0.93 (:mm.pattern/confidence (dt/find-by-ident :memory.patterns/typed_probe))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The lenient path is unchanged (bulk import keeps the carrier)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest the-lenient-parse-still-carries-unknown-keys
  (let [specs (md/parse-document unknown-key-source "observations/strictness_probe.md")
        memory (first specs)]
    (is (= :mm/Observation (:dt/type memory)))
    (is (some? (:mm.memory/frontmatter memory)) "the carrier is built, not refused")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REP-04 — the emitter's block scalars parse back
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest block-scalars-round-trip
  (testing "the emitter's literal block scalar comes back as the same string"
    (let [entity  {:dt/type :mm/Observation
                   :mm.memory/name "Alpha\nBeta"
                   :mm.memory/description "two lines"
                   :mm.memory/body-raw "Body.\n"}
          text    (codec/emit entity {:format :markdown})
          parsed  (codec/parse text {:format :markdown :class :mm/Observation})]
      (is (str/includes? text "|") (str "the emitter wrote a block scalar:\n" text))
      (is (= "Alpha\nBeta" (:mm.memory/name parsed)) text)))
  (testing "the bounded grammar: literal, folded, chomping"
    (let [fm (md/parse-frontmatter-text "name: |-\n  one\n  two\ndescription: >\n  folded\n  text\n\n  next\ntype: observation\n")]
      (is (= "one\ntwo" (:name fm)))
      (is (= "folded text\nnext\n" (:description fm)))
      (is (= "observation" (:type fm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REP-05 — a Tag document has no body slot and imports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-tag-document-imports-without-a-constructed-body-slot
  (let [specs (md/parse-document "---\ntype: tag\nvalue: strictness-probe\ndefinition: a synthetic tag\n---\n" "tags/strictness_probe.md")
        tag   (first specs)]
    (is (= :mm/Tag (:dt/type tag)) (pr-str tag))
    (is (not (contains? tag :mm.tag/body)) "no undeclared body slot is minted")
    (dt/make-all* (md/entity-specs->tx-data specs))
    (is (some? (d/q '[:find ?e . :where [?e :mm.tag/value "strictness-probe"]] (d/db (db/conn))))
        "the tag persisted")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The D6 schema declarations are visible through the lattice
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest the-declarations-are-listed
  (is (contains? (dt/slots-of :mm/Example) :mm.memory/demonstrates))
  (is (contains? (dt/slots-of :mm/Memory) :mm.memory/demonstrated-by))
  (is (contains? (dt/slots-of :mm/Memory) :mm/id))
  (is (contains? (dt/slots-of :mm/Run) :mm.run/job))
  (is (contains? (dt/slots-of :mm/Shape) :mm.shape/xor-constraints))
  (is (= :db.type/double (dt/range-of :mm.pattern/confidence)))
  (is (= :db.type/double (dt/range-of :mm.idea/confidence)))
  (is (= :db.type/double (dt/range-of :mm.bug/confidence)))
  (is (true? (:db/isComponent (d/entity (d/db (db/conn)) :mm.memory/frontmatter)))))

(deftest a-demonstrates-edge-lands-on-an-example
  (let [target (call "sandbar.entity.create" (create-args clean-source))
        _      (is (not (error? target)))
        resp   (call "sandbar.entity.create"
                     {"class" ":mm/Example" "format" "markdown"
                      "source" "---\nname: an example\ndescription: demonstrates the probe\ntype: example\ndemonstrates:\n- observations/strictness_probe.md\n---\n\nShown.\n"
                      "slots" {"mm.memory/rel-path" "examples/strictness_example.md"}})
        body   (payload resp)]
    (is (not (error? resp)) (pr-str body))
    (let [ex (dt/find-by-ident :memory.examples/strictness_example)]
      (is (some? ex))
      (is (seq (:mm.memory/demonstrates ex)) "the edge landed"))))
