(ns sandbar.codec.readable-keyword-test
  "Markdown authoring must not mint values that break its EDN representation.
   These are new-authoring refusals, not repairs to historical stored values."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "readable-keyword" :auth? false})
  (fn [f] (md/register!) (f)))

(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def invalid-paths
  ["decisions/ghost.md  # retained author comment"
   "briefings/old.md → briefings/new.md"
   "logs/two words.md"
   "decisions/closing]bracket.md"
   "decisions/a;b.md"
   "decisions/a,b.md"
   "decisions/a\tb.md"
   "decisions/a\"b.md"
   "decisions/a\\b.md"
   "decisions/a(b).md"
   "decisions/a[b].md"
   "decisions/a{b}.md"
   "decisions/a`b.md"
   "decisions/a^b.md"
   "decisions/a@b.md"
   "decisions/a~b.md"
   "decisions/a:.md"
   "created:"])

(deftest invalid-derived-reference-identities-are-refused
  (doseq [path invalid-paths]
    (let [error (error-data #(md/rel-path->memory-ident path))]
      (is (= :markdown/unreadable-keyword (:type error)) path)
      (is (= path (:value error)) "The exact supplied value remains in diagnostics")
      (is (string? (:keyword-text error)) "An invalid keyword must not corrupt the error data itself"))))

(deftest valid-names-and-existing-digit-prefixes-are-preserved
  (doseq [[path expected]
          [["decisions/known.md" :memory.decisions/known]
           ["memory/decisions/known.md" :memory.decisions/known]
           ["memory.decisions/known" :memory.decisions/known]
           ["logs/2026-09-21_example.md" :memory.logs/log-2026-09-21_example]
           ["decisions/naïve→ok.md" :memory.decisions/naïve→ok]
           ["decisions/why?.md" :memory.decisions/why?]
           ["decisions/a#b.md" :memory.decisions/a#b]
           ["decisions/a'b.md" :memory.decisions/a'b]
           ["decisions/a%b?c*d+e.md" :memory.decisions/a%b?c*d+e]
           ["decisions/.hidden.md" :memory.decisions/.hidden]
           ["foo" :memory/foo]]]
    (let [actual (md/rel-path->memory-ident path)]
      (is (= expected actual))
      (is (= [actual] (edn/read-string (pr-str [actual]))))))
  (is (nil? (md/rel-path->memory-ident ""))))

(deftest both-frontmatter-modes-refuse-with-field-context
  (doseq [strict? [false true]
          [class key slot value]
          [[:mm/Idea :timeframe :mm.idea/timeframe "when public library emerges"]
           [:mm/Idea :timeframe :mm.idea/timeframe (keyword "future — gated on metrics")]
           [:mm/Idea :timeframe :mm.idea/timeframe "soon;ish"]
           [:mm/Idea :timeframe :mm.idea/timeframe "q3/2026"]
           [:mm/Log :touches :mm.memory/touches ["decisions/valid.md" (first invalid-paths)]]]]
    (let [error (error-data #(md/frontmatter->slots class {key value} nil nil {:strict? strict?}))]
      (is (= :markdown/unreadable-keyword (:type error)) (str key " strict=" strict?))
      (is (= (name key) (:key error)))
      (is (= slot (:slot error)))
      (is (= error (edn/read-string (pr-str error))) "Diagnostic itself must round-trip")))
  (doseq [v ["near-term" "future→current" "future—gated" "2026-Q3" "horizon/near" :unscheduled]]
    (is (= (keyword v) (:mm.idea/timeframe (md/frontmatter->slots :mm/Idea {:timeframe v}))))))

(deftest long-spellings-are-bounded-in-messages-but-retained-in-error-data
  (let [value (str "later " (apply str (repeat 1000 "x")))
        error (try (md/frontmatter->slots :mm/Idea {:timeframe value}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= value (:value (ex-data error))))
    (is (str/includes? (.getMessage error) "timeframe"))
    (is (str/includes? (.getMessage error) "supplied value"))
    (is (< (count (.getMessage error)) 520))))

(deftest path-lookup-distinguishes-an-unreadable-argument-from-a-legitimate-miss
  (doseq [[path error?] [["decisions/a;b.md" true] ["decisions/not-present.md" false]]]
    (let [r (:result (tools/handle-call 1 {:name "sandbar.entity.find-by-rel-path"
                                          :arguments {"rel-path" path}}))
          payload (json/parse-string (get-in r [:content 0 :text]) true)]
      (is (= error? (true? (:isError r))) (pr-str r))
      (if error?
        (is (str/includes? (:message payload) path))
        (is (true? (:missing? payload)))))))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "readable-keyword-" (make-array FileAttribute 0))))
(defn- remove-tree! [^java.io.File f]
  (when (.isDirectory f) (doseq [child (.listFiles f)] (remove-tree! child)))
  (.delete f))
(defn- write! [root path text]
  (let [file (io/file root path)] (io/make-parents file) (spit file text)))
(defn- import! [root persist?]
  (let [r (:result (tools/handle-call 1 {:name "sandbar.project.import"
                                       :arguments {"from" (.getPath root) "persist" persist?}}))]
    (is (map? r))
    (is (not (:isError r)) (pr-str r))
    (json/parse-string (-> r :content first :text) true)))
(defn- doc [type name header body]
  (str "---\nname: " name "\ntype: " type "\n" header "---\n# " name "\n\n" body "\n"))

(deftest interactive-codec-create-refuses-before-any-transaction-in-both-modes
  (doseq [allow-unknown? [false true]
          [class type header field value]
          [[":mm/Log" "log" "touches:\n  - decisions/valid.md\n  - decisions/a;b.md\n" "touches" "decisions/a;b.md"]
           [":mm/Idea" "idea" "timeframe: later this year\n" "timeframe" "later this year"]]]
    (let [before (d/basis-t (d/db (db/conn)))
          response (tools/handle-call 1 {:name "sandbar.entity.create"
                                         :arguments {"class" class "format" "markdown"
                                                     "slots" {"mm.memory/rel-path" (str type "s/refused.md")}
                                                     "allow-unknown-keys" allow-unknown?
                                                     "source" (doc type "Refused" header "Do not save.")}})
          payload (json/parse-string (get-in response [:result :content 0 :text]) true)]
      (is (true? (get-in response [:result :isError])) (pr-str response))
      (is (str/includes? (:message payload) field))
      (is (str/includes? (:message payload) value))
      (is (= before (d/basis-t (d/db (db/conn))))))))

(deftest real-import-refuses-entire-bad-units-and-keeps-valid-neighbor
  (let [root (temp-root)
        files {"logs/bad-reference.md" (doc "log" "Bad reference" (str "touches:\n  - decisions/valid-neighbor.md\n  - " (first invalid-paths) "\n") "Untouched source.")
               "ideas/bad-value.md" (doc "idea" "Bad value" "timeframe: future — gated on metrics\n" "Untouched source.")
               "observations/good.md" (doc "observation" "Good" "" "The accepted neighbor.")}]
    (try
      (doseq [[path text] files] (write! root path text))
      (doseq [persist? [false true]]
        (let [report (import! root persist?)]
          (is (= 3 (:attempted report)))
          (is (= 2 (:parse-failed-count report)) (pr-str report))
          (is (= #{"logs/bad-reference.md" "ideas/bad-value.md"}
                 (set (map :source (:parse-failed report)))))
          (doseq [{:keys [source error]} (:parse-failed report)]
            (is (str/includes? error (if (= source "logs/bad-reference.md") "touches" "timeframe")))
            (is (str/includes? error "supplied value")))
          (is (nil? (d/entid (d/db (db/conn)) :memory.logs/bad-reference)))
          (is (nil? (d/entid (d/db (db/conn)) :memory.ideas/bad-value)))
          (is (nil? (d/entid (d/db (db/conn)) :memory.decisions/valid-neighbor))
              "Even the valid item in a refused document must not create a placeholder")
          (is (nil? (d/entid (d/db (db/conn))
                            (keyword "memory.decisions" "ghost.md  # retained author comment"))))
          (when persist?
            (is (= 1 (:persisted-count report)))
            (is (true? (:reconciled? report)))
            (is (some? (d/entid (d/db (db/conn)) :memory.observations/good))))))
      (doseq [[path text] files] (is (= text (slurp (io/file root path)))))
      (finally (remove-tree! root)))))

(deftest refused-reimport-preserves-existing-identity-content-and-reference
  (let [root (temp-root) path "observations/existing.md"
        ident :memory.observations/existing
        projection '[:db/id :mm/id :mm.memory/body-raw {:mm.memory/cites [:db/ident]}]]
    (try
      (write! root path (doc "observation" "Existing" "cites: decisions/retained.md\n" "Keep this body."))
      (is (= 1 (:persisted-count (import! root true))))
      (let [before (d/pull (d/db (db/conn)) projection ident)
            bad-source (doc "observation" "Existing" (str "cites: " (first invalid-paths) "\n") "Must not replace the body.")]
        (is (some? (:mm/id before)))
        (write! root path bad-source)
        (let [report (import! root true)]
          (is (= 1 (:parse-failed-count report)))
          (is (zero? (:persisted-count report)))
          (is (= before (d/pull (d/db (db/conn)) projection ident)))
          (is (= bad-source (slurp (io/file root path))))))
      (finally (remove-tree! root)))))
