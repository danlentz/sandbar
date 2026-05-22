(ns sandbar.bootstrap.render-test
  "Tests for sandbar.bootstrap.render — the substrate-to-corpus
   markdown renderer.

   Per plans/sandbar_memorial_type_substrate_arc_2026_05_21.md Stage 3:
   confirms render-class works for the 38+ new substrate classes added
   during the memorial-type substrate arc (Decision, Pattern, Observation,
   ... + abstract supertypes Artifact / Signal / Guidance / Meta + sub-
   abstracts Actor / MetaType)."
  (:require [clojure.string           :as str]
            [clojure.test             :refer [deftest is testing use-fixtures]]
            [sandbar.bootstrap.render :as render]
            [sandbar.codec.markdown   :as codec-md]
            [sandbar.db.datatype      :as dt]
            [sandbar.db.datomic       :as db]
            [sandbar.test-util        :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "render-test"
                                              :auth?     false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Smoke tests — render-class on new substrate classes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest render-class-on-mm-decision
  (testing "render-class :mm/Decision produces a valid memorial"
    (let [r (render/render-class :mm/Decision)]
      (is (= "memory/types/decision.md" (:rel-path r)))
      (is (string? (:content r)))
      (is (pos? (count (:content r))))
      (is (re-find #":mm/Decision" (:content r)) "content names the class ident"))))

(deftest render-class-on-mm-pattern
  (testing "render-class :mm/Pattern produces a valid memorial"
    (let [r (render/render-class :mm/Pattern)]
      (is (= "memory/types/pattern.md" (:rel-path r)))
      (is (re-find #":mm/Pattern" (:content r))))))

(deftest render-class-on-mm-observation
  (testing "render-class :mm/Observation produces a valid memorial"
    (let [r (render/render-class :mm/Observation)]
      (is (= "memory/types/observation.md" (:rel-path r)))
      (is (re-find #":mm/Observation" (:content r))))))

(deftest render-class-on-abstract-supertypes
  (testing "abstract supertypes (Artifact / Signal / Guidance / Meta) render"
    (doseq [[class-ident expected-path]
            [[:mm/Artifact "memory/types/artifact.md"]
             [:mm/Signal   "memory/types/signal.md"]
             [:mm/Guidance "memory/types/guidance.md"]
             [:mm/Meta     "memory/types/meta.md"]]]
      (let [r (render/render-class class-ident)]
        (is (= expected-path (:rel-path r))
            (str "rel-path for " class-ident))
        (is (pos? (count (:content r)))
            (str "non-empty content for " class-ident))))))

(deftest render-class-on-sub-abstracts
  (testing "sub-abstract types (Actor / MetaType) render"
    (doseq [[class-ident expected-path]
            [[:mm/Actor    "memory/types/actor.md"]
             [:mm/MetaType "memory/types/meta-type.md"]]]
      (let [r (render/render-class class-ident)]
        (is (= expected-path (:rel-path r))
            (str "rel-path for " class-ident))
        (is (pos? (count (:content r)))
            (str "non-empty content for " class-ident))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Kebab-case file naming — covers AIActor / MarkdownMetaType / etc.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest render-class-kebab-case-naming
  (testing "kebab-case file naming handles acronym prefixes + multi-word camel"
    (doseq [[class-ident expected-rel-path]
            [[:mm/AIActor          "memory/types/ai-actor.md"]
             [:mm/HumanActor       "memory/types/human-actor.md"]
             [:mm/SystemActor      "memory/types/system-actor.md"]
             [:mm/MarkdownMetaType "memory/types/markdown-meta-type.md"]
             [:mm/CodeMetaType     "memory/types/code-meta-type.md"]
             [:mm/AntiPattern      "memory/types/anti-pattern.md"]
             [:mm/ContentType      "memory/types/content-type.md"]
             [:mm/MetaType         "memory/types/meta-type.md"]
             [:mm/Memory           "memory/types/memory.md"]
             [:mm/BootstrapSource  "memory/types/bootstrap-source.md"]]]
      (let [r (render/render-class class-ident)]
        (is (= expected-rel-path (:rel-path r))
            (str "kebab-case for " class-ident))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parent class introspection — surfaces inheritance chain in rendered body
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest render-class-shows-parent-class-in-body
  (testing "rendered body names the direct parent class (introspection-only path)"
    ;; :mm/Decision extends :mm/Artifact
    (let [r (render/render-class :mm/Decision)]
      (is (re-find #":mm/Artifact" (:content r))
          ":mm/Decision rendered output should name :mm/Artifact as parent"))
    ;; :mm/Pattern extends :mm/Guidance
    (let [r (render/render-class :mm/Pattern)]
      (is (re-find #":mm/Guidance" (:content r))
          ":mm/Pattern rendered output should name :mm/Guidance as parent"))
    ;; :mm/Observation extends :mm/Signal
    (let [r (render/render-class :mm/Observation)]
      (is (re-find #":mm/Signal" (:content r))
          ":mm/Observation rendered output should name :mm/Signal as parent"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; render-all-bootstrap — picks up the new substrate classes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest render-all-bootstrap-includes-new-classes
  (testing "render-all-bootstrap returns memorials for all :mm/* substrate classes"
    (let [all  (render/render-all-bootstrap)
          paths (set (map :rel-path all))]
      (is (contains? paths "memory/types/decision.md"))
      (is (contains? paths "memory/types/pattern.md"))
      (is (contains? paths "memory/types/observation.md"))
      (is (contains? paths "memory/types/artifact.md"))
      (is (contains? paths "memory/types/signal.md"))
      (is (contains? paths "memory/types/guidance.md"))
      (is (contains? paths "memory/types/meta.md"))
      (is (contains? paths "memory/types/ai-actor.md")
          "AIActor kebab-cased correctly")
      ;; Existing infrastructure classes still present
      (is (contains? paths "memory/types/memory.md"))
      (is (contains? paths "memory/types/section.md"))
      (is (contains? paths "memory/types/tag.md"))
      ;; Lower-bound expectation — we expect ~50+ type memorials
      (let [type-paths (filter #(str/starts-with? % "memory/types/") paths)]
        (is (>= (count type-paths) 40)
            (str "expected ≥40 type memorials rendered, got " (count type-paths)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip — rendered output parses back to a valid memorial entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest render-class-output-round-trips-through-parser
  (testing "rendered class memorial parses back via codec-md/parse-document"
    (let [r (render/render-class :mm/Decision)
          content (:content r)
          parsed  (codec-md/parse-document content (:rel-path r))]
      (is (sequential? parsed))
      (is (some? (first parsed))
          "first entity in parse result is the memorial"))))
