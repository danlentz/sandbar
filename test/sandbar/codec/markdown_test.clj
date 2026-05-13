(ns sandbar.codec.markdown-test
  "Tests for sandbar.codec.markdown — Stage B.2 minimum-viable scope:
   frontmatter ↔ slot map + body verbatim round-trip.  No section-tree
   decomposition tests yet (those land at Stage B.3)."
  (:require [clojure.test            :refer :all]
            [clojure.string          :as str]
            [sandbar.codec           :as codec]
            [sandbar.codec.protocol  :as proto]
            [sandbar.codec.markdown  :as md]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — fresh codec registry per test

(use-fixtures :each
  (fn [t]
    (codec/clear-all!)
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter split helpers

(deftest split-frontmatter-with-fence
  (let [src "---\nname: Foo\ntype: decision\n---\n# Heading\n\nBody."]
    (let [[fm body] (md/split-frontmatter src)]
      (is (= "name: Foo\ntype: decision\n" fm))
      (is (= "# Heading\n\nBody." body)))))

(deftest split-frontmatter-without-fence
  (let [src "# Heading\n\nNo frontmatter here."]
    (let [[fm body] (md/split-frontmatter src)]
      (is (nil? fm))
      (is (= "# Heading\n\nNo frontmatter here." body)))))

(deftest split-frontmatter-unterminated-fence
  ;; Source starts with `---` but never closes — treat as body
  (let [src "---\nname: Foo\nno closing fence"
        [fm body] (md/split-frontmatter src)]
    (is (nil? fm))
    (is (= "---\nname: Foo\nno closing fence" body))))

(deftest split-frontmatter-normalizes-crlf
  (let [src "---\r\nname: Foo\r\n---\r\n# Body\r\n"
        [fm body] (md/split-frontmatter src)]
    (is (= "name: Foo\n" fm))
    (is (= "# Body\n" body))))

(deftest split-frontmatter-empty-frontmatter
  ;; --- followed immediately by --- = empty frontmatter
  (let [src "---\n---\n# Body\n"
        [fm body] (md/split-frontmatter src)]
    (is (= "" fm))
    (is (= "# Body\n" body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Whitespace normalization

(deftest normalize-body-strips-non-hardbreak-trailing-whitespace
  ;; Lines with EXACTLY 1 trailing space (or trailing tab) are NOT a
  ;; markdown hard-break and must be stripped.
  (is (= "line one\nline two\nline three"
         (md/normalize-body "line one \nline two\t\nline three"))))

(deftest normalize-body-preserves-markdown-hardbreaks
  ;; Two trailing spaces = markdown hard-break per CommonMark §4.2.6
  (is (= "line one  \nline two"
         (md/normalize-body "line one  \nline two")))
  (is (= "line one   \nline two"
         (md/normalize-body "line one   \nline two"))
      "Three trailing spaces still preserves hard-break (2+ spaces)"))

(deftest normalize-body-collapses-multi-blank-lines
  (is (= "para one\n\npara two"
         (md/normalize-body "para one\n\n\n\npara two"))))

(deftest normalize-body-handles-crlf
  (is (= "line one\nline two\n"
         (md/normalize-body "line one\r\nline two\r\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter ↔ slot mapping

(deftest frontmatter-key-to-slot-default-rule
  (is (= :mm.memory/name        (md/frontmatter-key->slot :mm/Memory :name)))
  (is (= :mm.memory/description (md/frontmatter-key->slot :mm/Memory :description)))
  (is (= :mm.memory/scope       (md/frontmatter-key->slot :mm/Memory :scope))))

(deftest frontmatter-key-to-slot-respects-aliases
  ;; mm/Memory's `type` → `memory-type` to avoid Clojure reserved-word
  ;; collision per known-class-slot-aliases
  (is (= :mm.memory/memory-type (md/frontmatter-key->slot :mm/Memory :type))))

(deftest frontmatter-key-to-slot-for-section
  (is (= :mm.section/heading       (md/frontmatter-key->slot :mm/Section :heading)))
  (is (= :mm.section/heading-level (md/frontmatter-key->slot :mm/Section :heading-level))))

(deftest slot-to-frontmatter-key-inverse-of-aliases
  (is (= :type (md/slot->frontmatter-key :mm/Memory :mm.memory/memory-type)))
  (is (= :name (md/slot->frontmatter-key :mm/Memory :mm.memory/name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codec parse — direct

(deftest parse-mm-memory-with-frontmatter
  (let [c (md/make-codec)
        src "---\nname: Foo\ntype: decision\nscope: global\n---\n# Heading\n\nBody.\n"
        result (proto/parse c src {:class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo"     (:mm.memory/name result)))
    (is (= :decision (:mm.memory/memory-type result)))
    (is (= :global   (:mm.memory/scope result)))
    (is (= "# Heading\n\nBody.\n" (:mm.memory/body-raw result)))))

(deftest parse-without-frontmatter
  (let [c (md/make-codec)
        src "# Heading\n\nJust body."
        result (proto/parse c src {:class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "# Heading\n\nJust body." (:mm.memory/body-raw result)))
    (is (nil? (:mm.memory/name result)))))

(deftest parse-rejects-without-class-opt
  (let [c (md/make-codec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :class"
          (proto/parse c "# Body" {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codec emit — direct

(deftest emit-mm-memory-with-frontmatter
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/body-raw "# Heading\n\nBody."}
        emitted (proto/emit c entity {})]
    (is (str/starts-with? emitted "---\n"))
    (is (str/includes? emitted "name: Foo"))
    (is (str/includes? emitted "type: decision")
        "memory-type slot emits back as `type` frontmatter key")
    (is (str/includes? emitted "# Heading"))
    (is (str/ends-with? emitted "\n"))))

(deftest emit-without-frontmatter-when-empty
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "# Just body\n"}
        emitted (proto/emit c entity {})]
    (is (not (str/starts-with? emitted "---")))
    (is (= "# Just body\n" emitted))))

(deftest emit-rejects-without-dt-type
  (let [c (md/make-codec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :dt/type"
          (proto/emit c {:body "..."} {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip — parse(emit(x)) = x

(deftest round-trip-mm-memory-frontmatter-plus-body
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/scope :global
                :mm.memory/body-raw "# Heading\n\nBody text here.\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "round-trip mismatch — diff: " (pr-str (:diff result))))))

(deftest round-trip-body-only
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "# Just body\n\nNo frontmatter.\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "round-trip mismatch — diff: " (pr-str (:diff result))))))

(deftest round-trip-with-markdown-hardbreak
  ;; Two trailing spaces should survive normalization-on-parse + round-trip
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "line one  \nline two\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "hard-break preservation mismatch — diff: " (pr-str (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mediator integration — register! + dispatch by :format and :dt/native-codec

(deftest register-and-parse-via-mediator
  (md/register!)
  (let [src "---\nname: Foo\n---\n# Body\n"
        result (codec/parse src {:format :markdown :class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo"      (:mm.memory/name result)))))

(deftest mime-types-include-text-markdown
  (let [c (md/make-codec)
        mts (proto/mime-types c)]
    (is (some #{"text/markdown"} mts))))

(deftest mediator-dispatches-via-mime
  (md/register!)
  (let [src "---\nname: Bar\n---\n# Body\n"
        [fmt _codec] (codec/codec-for-mime "text/markdown")]
    (is (= :markdown fmt))))
