(ns sandbar.mcp.introspection-contract-test
  "Entry-point contract for the metamodel introspection family — the six
   class verbs, the three property verbs and codec.list — through
   `tools/handle-call` by their WIRE names.

   Before 2026-09-20 no test reached these verbs through the dispatcher; the
   helpers beneath them were tested, the catalog named them, and an unknown
   class ident escaped the Datalog helpers as a JSON-RPC internal error while
   an unknown property answered a silent `nil`.  This suite pins the family
   contract the onboarding wave decided (Astra, 2026-09-20): known inputs
   answer their documented shapes; meaningful empty and nil answers stay;
   unknown, non-namespaced, malformed and wrong-kind inputs are user errors
   (`isError`), never internal errors; the registry stays introspectable for
   every namespace while instance enumeration of a firewalled class is
   refused; a read-only principal may introspect; and the codec spelling
   `codec.list` returns composes with `entity.create`.

   Order is asserted only where a card promises it; otherwise sets.
   Per codex/reviews/onboarding-wave-2026-09-20/opus-introspection-coverage.md
   and opus-introspection-contract.md."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "introspection-contract"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — the wire, not the handler vars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call
  "tools/call by WIRE name (`sandbar_class_describe`), optionally as
   `principal`; the in-process nil principal otherwise."
  ([wire args] (call wire args nil))
  ([wire args principal]
   (tools/handle-call 1 {:name wire :arguments args} principal)))

(defn- payload
  "The successful tool payload, parsed from its JSON text with keyword keys."
  [response]
  (-> response :result :content first :text (json/parse-string true)))

(defn- user-error?
  "True for the `isError` envelope — a refusal the client can act on."
  [response]
  (true? (get-in response [:result :isError])))

(defn- rpc-error?
  "True for a JSON-RPC error envelope — unknown verb, gate denial, or an
   exception that escaped the handler (the internal-error shape)."
  [response]
  (some? (:error response)))

(defn- error-text
  [response]
  (str (or (get-in response [:result :content 0 :text])
           (get-in response [:error :message]))))

(defn- ok?
  [response]
  (and (not (rpc-error? response)) (not (user-error? response))))

(def ^:private class-verbs
  ["sandbar_class_describe" "sandbar_class_slots" "sandbar_class_direct-slots"
   "sandbar_class_required-slots" "sandbar_class_subclasses" "sandbar_class_parents"])

(def ^:private property-verbs
  ["sandbar_property_domain" "sandbar_property_range" "sandbar_property_cardinality"])

(defn- seed-memorial!
  "A memorial to pass where a class or property is expected."
  []
  (dt/make :mm/Observation {:db/ident           :memory.observations/introspection_probe
                            :mm.memory/name     "Introspection probe"
                            :mm.memory/rel-path "observations/introspection_probe.md"})
  :memory.observations/introspection_probe)

(defn- read-only-principal!
  "Seed + return an active service account carrying only the :read-only role
   (the `read_only_token_test` shape; validation off — a fixture role needs
   no permissions of its own)."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture read-only role"}
                      {:validate? false})
        sa   (dt/make :auth/ServiceAccount
                      {:auth/service-name :introspection-read-only-fixture
                       :auth/api-key-hash "fixture-not-a-real-key"
                       :auth/roles        [(:db/id role)]
                       :auth/active?      true}
                      {:validate? false})]
    (db/entity (:db/id sa))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-01 — known inputs answer their documented shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest known-inputs-answer-through-the-wire-names
  (testing "class.describe — the descriptor keys; subclasses agree with class.subclasses as a set"
    (let [r (call "sandbar_class_describe" {"class" ":mm/Observation"})
          p (payload r)]
      (is (ok? r) (error-text r))
      (is (= #{:class :abstract? :parents :ancestors :subclasses :slots} (set (keys p))))
      (is (= ":mm/Observation" (:class p)))
      (is (boolean? (:abstract? p)))
      (is (= [":mm/Signal"] (:parents p)))
      (is (contains? (set (:ancestors p)) ":mm/Memory"))
      (is (contains? (set (:ancestors p)) ":dt/Resource"))
      (is (set/subset? #{":mm.memory/name" ":mm.memory/rel-path"} (set (:slots p))))
      (is (= (:slots p) (vec (sort (:slots p)))) "slots are sorted, as the card promises")
      (is (= (set (:subclasses p))
             (set (:subclasses (payload (call "sandbar_class_subclasses" {"class" ":mm/Observation"})))))
          "describe's subclasses (unordered) equal class.subclasses (sorted) as a set")))
  (testing "class.slots — inherited plus direct, sorted"
    (let [p (payload (call "sandbar_class_slots" {"class" ":mm/Inbox"}))]
      (is (= ":mm/Inbox" (:class p)))
      (is (= (:slots p) (vec (sort (:slots p)))))
      (is (set/subset? #{":mm.inbox/importance" ":mm.memory/name"} (set (:slots p))))))
  (testing "class.direct-slots — only the class's own contribution"
    (let [p (payload (call "sandbar_class_direct-slots" {"class" ":mm/Inbox"}))]
      (is (= #{":mm.inbox/importance" ":mm.inbox/suggested-type"} (set (:slots p))))))
  (testing "class.required-slots — property-level requiredness: Project declares four"
    (let [p (payload (call "sandbar_class_required-slots" {"class" ":mm/Project"}))]
      (is (= [":mm.project/corpus-repo" ":mm.project/default-visibility"
              ":mm.project/ident" ":mm.project/runs-in-context"]
             (:slots p)))))
  (testing "class.subclasses — sorted, transitive"
    (let [p (payload (call "sandbar_class_subclasses" {"class" ":mm/Signal"}))]
      (is (= #{":mm/Bug" ":mm/Idea" ":mm/Observation" ":mm/Question"} (set (:subclasses p))))
      (is (= (:subclasses p) (vec (sort (:subclasses p)))))))
  (testing "class.parents — direct parents and the ancestor set"
    (let [p (payload (call "sandbar_class_parents" {"class" ":mm/Observation"}))]
      (is (= [":mm/Signal"] (:parents p)))
      (is (= #{":mm/Signal" ":mm/Memory" ":dt/Resource"} (set (:ancestors p))))))
  (testing "property.domain / range / cardinality — the triad on real properties"
    (is (= ":mm/Inbox" (:domain (payload (call "sandbar_property_domain" {"property" ":mm.inbox/importance"})))))
    (is (= ":dt/Resource" (:range (payload (call "sandbar_property_range" {"property" ":mm.memory/cites"})))))
    (is (= ":db.type/string" (:range (payload (call "sandbar_property_range" {"property" ":mm.memory/name"})))))
    (is (= ":db.cardinality/many" (:cardinality (payload (call "sandbar_property_cardinality" {"property" ":mm.memory/tags"})))))
    (is (= ":db.cardinality/one" (:cardinality (payload (call "sandbar_property_cardinality" {"property" ":mm.memory/name"}))))))
  (testing "codec.list — one entry per registered codec, format and MIME types"
    (md/register!)
    (let [p      (payload (call "sandbar_codec_list" {}))
          codecs (:codecs p)
          md-entry (first (filter #(= "markdown" (:format %)) codecs))]
      (is (vector? codecs))
      (is (some? md-entry) "the markdown codec is registered")
      (is (contains? (set (:mime-types md-entry)) "text/markdown"))
      (is (= #{:format :mime-types} (set (keys md-entry)))
          "the entry carries a format and MIME types and nothing else — no class list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-01b — meaningful empty and nil answers are unchanged
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest meaningful-empty-and-nil-answers-stay
  (testing "a memorial class declares no property-level required slots"
    (let [r (call "sandbar_class_required-slots" {"class" ":mm/Inbox"})]
      (is (ok? r) (error-text r))
      (is (= [] (:slots (payload r))))))
  (testing "a leaf class has no subclasses"
    (let [r (call "sandbar_class_subclasses" {"class" ":mm/Bug"})]
      (is (ok? r) (error-text r))
      (is (= [] (:subclasses (payload r))))))
  (testing "the root class has no parents"
    (let [r (call "sandbar_class_parents" {"class" ":dt/Resource"})]
      (is (ok? r) (error-text r))
      (is (= [] (:parents (payload r))))
      (is (= [] (:ancestors (payload r))))))
  (testing "a real property with no declared domain answers nil, not an error"
    (let [r (call "sandbar_property_domain" {"property" ":twit/name"})]
      (is (ok? r) (error-text r))
      (is (= ":twit/name" (:property (payload r))))
      (is (nil? (:domain (payload r)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-02 — an unknown ident is a user error naming the ident, on every verb
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unknown-ident-is-a-user-error-not-an-internal-error
  (doseq [wire class-verbs]
    (testing (str wire " on an unknown class")
      (let [r (call wire {"class" ":nonexistent/Class"})]
        (is (not (rpc-error? r)) (str wire " must not escape as a JSON-RPC error: " (pr-str r)))
        (is (user-error? r) (str wire " must refuse with isError: " (pr-str r)))
        (is (str/includes? (error-text r) "nonexistent/Class") "the refusal names the ident"))))
  (doseq [wire property-verbs]
    (testing (str wire " on an unknown property")
      (let [r (call wire {"property" ":nonexistent/prop"})]
        (is (not (rpc-error? r)) (pr-str r))
        (is (user-error? r) (pr-str r))
        (is (str/includes? (error-text r) "nonexistent/prop"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-03 — argument shape: missing, non-namespaced, not a string
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest argument-shape-refusals-are-user-errors
  (testing "missing argument"
    (let [r (call "sandbar_class_describe" {})]
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "Missing required argument: class")))
    (let [r (call "sandbar_property_domain" {})]
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "Missing required argument: property"))))
  (testing "an ident without a namespace"
    (let [r (call "sandbar_class_describe" {"class" "Memory"})]
      (is (not (rpc-error? r)) (pr-str r))
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "namespaced")))
    (let [r (call "sandbar_property_range" {"property" "name"})]
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "namespaced"))))
  (testing "an argument that is not an ident string"
    (let [r (call "sandbar_class_slots" {"class" 42})]
      (is (not (rpc-error? r)) (pr-str r))
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "ident string")))
    (let [r (call "sandbar_property_cardinality" {"property" ["mm.memory/name"]})]
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "ident string")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-03b — wrong kind: the ident exists but names the wrong sort of thing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest wrong-kind-is-refused-not-answered-empty
  (let [memorial (seed-memorial!)]
    (doseq [wire class-verbs]
      (testing (str wire " on a memorial")
        (let [r (call wire {"class" (str memorial)})]
          (is (not (rpc-error? r)) (pr-str r))
          (is (user-error? r) (str wire " must refuse a memorial as a class: " (pr-str r)))
          (is (str/includes? (error-text r) "not a class")))))
    (testing "a property passed as a class"
      (let [r (call "sandbar_class_slots" {"class" ":mm.memory/name"})]
        (is (user-error? r) (pr-str r))
        (is (str/includes? (error-text r) "not a class"))))
    (doseq [wire property-verbs]
      (testing (str wire " on a class")
        (let [r (call wire {"property" ":mm/Memory"})]
          (is (not (rpc-error? r)) (pr-str r))
          (is (user-error? r) (str wire " must refuse a class as a property: " (pr-str r)))
          (is (str/includes? (error-text r) "not a property"))))
      (testing (str wire " on a memorial")
        (let [r (call wire {"property" (str memorial)})]
          (is (user-error? r) (pr-str r))
          (is (str/includes? (error-text r) "not a property")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-04 — the registry stays introspectable; instances of a firewalled
;; class do not
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest registry-definitions-answer-while-instances-are-refused
  (testing "class.describe on a firewalled-namespace class answers its definition — idents and booleans only"
    (let [r (call "sandbar_class_describe" {"class" ":auth/User"})
          p (payload r)]
      (is (ok? r) (error-text r))
      (is (= ":auth/User" (:class p)))
      (is (contains? (set (:slots p)) ":auth/password-hash")
          "a slot NAME is a definition, not instance data")
      (is (every? #(and (string? %) (str/starts-with? % ":")) (:slots p)))
      (is (every? #(and (string? %) (str/starts-with? % ":")) (concat (:parents p) (:ancestors p) (:subclasses p))))
      (is (boolean? (:abstract? p)))))
  (testing "property.domain on a firewalled-namespace property answers its definition"
    (let [r (call "sandbar_property_domain" {"property" ":auth/password-hash"})]
      (is (ok? r) (error-text r))
      (is (string? (:domain (payload r))))))
  (testing "class.instances on the same class is refused by the read-plane input guard"
    (let [r (call "sandbar_class_instances" {"class" ":auth/User"})]
      (is (not (rpc-error? r)) (pr-str r))
      (is (user-error? r) (pr-str r))
      (is (str/includes? (error-text r) "read-plane")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-05 — a read-only principal may introspect the family
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest read-only-principal-may-introspect
  (md/register!)
  (let [principal (read-only-principal!)]
    (doseq [[wire args] [["sandbar_class_describe"        {"class" ":mm/Observation"}]
                         ["sandbar_class_required-slots"  {"class" ":mm/Project"}]
                         ["sandbar_property_range"        {"property" ":mm.memory/name"}]
                         ["sandbar_codec_list"            {}]]]
      (testing (str wire " is served to a read-only principal")
        (let [r (call wire args principal)]
          (is (ok? r) (str wire ": " (error-text r)))
          (is (some? (payload r))))))
    (testing "and its refusals are the same user errors"
      (let [r (call "sandbar_class_describe" {"class" ":nonexistent/Class"} principal)]
        (is (user-error? r) (pr-str r))
        (is (not (rpc-error? r)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTRO-06 — the codec spelling codec.list returns is the one create accepts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest codec-list-spelling-composes-with-entity-create
  (md/register!)
  (let [fmt    (->> (payload (call "sandbar_codec_list" {})) :codecs (map :format) (some #{"markdown"}))
        source "---\nname: Codec spelling probe\ntype: observation\nscope: global\n---\n\nBody.\n"
        create (fn [format rel-path]
                 (call "sandbar_entity_create"
                       {"class"  ":mm/Observation"
                        "slots"  {"mm.memory/rel-path" rel-path}
                        "format" format
                        "source" source}))]
    (is (= "markdown" fmt) "codec.list names the markdown codec by its format")
    (testing "the listed spelling is accepted as-is"
      (let [r (create fmt "observations/codec_spelling_plain.md")]
        (is (ok? r) (error-text r))
        (is (= "observations/codec_spelling_plain.md"
               (get-in (payload r) [:entity :mm.memory/rel-path])))))
    (testing "the colon-prefixed spelling the card shows is accepted too"
      (let [r (create (str ":" fmt) "observations/codec_spelling_colon.md")]
        (is (ok? r) (error-text r))))
    (testing "the namespace the old card advertised is refused with the known formats named"
      (let [r (create ":codec/markdown" "observations/codec_spelling_namespaced.md")]
        (is (user-error? r) (pr-str r))
        (is (re-find #"No codec registered for format :codec/markdown" (error-text r))
            "the old spelling is an unregistered format, refused by name")))))
