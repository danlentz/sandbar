(ns sandbar.mcp.resource-representation-test
  "Resource descriptions advertise class defaults; reads label the bytes that
   were actually produced. These handler tests use a disposable memory store,
   not an authenticated HTTP connection or a live corpus."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.codec :as codec]
            [sandbar.codec.json :as json-codec]
            [sandbar.codec.markdown :as md]
            [sandbar.codec.protocol :as codec-proto]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.resources :as resources]
            [sandbar.test-util :as tu])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn- with-fixture-codecs [f]
  (let [saved (into {} (map (fn [{:keys [format]}] [format (codec/codec-for format)])
                          (codec/list-codecs)))]
    (try
      (codec/clear-all!)
      (md/register!)
      (json-codec/register!)
      (f)
      (finally
        (codec/clear-all!)
        (doseq [[format implementation] saved]
          (codec/register! format implementation))))))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "resource-representation" :auth? false
                            :extra-schema [:auth]})
  with-fixture-codecs)

(defn- seed-memory!
  [class-ident ident visibility sectioned?]
  (let [section-ident (keyword (namespace ident) (str (name ident) "__evidence"))
        uuid (UUID/nameUUIDFromBytes (.getBytes (str ident) StandardCharsets/UTF_8))
        root (cond-> {:db/id "document" :db/ident ident :dt/type class-ident
                      :mm/id uuid :mm.memory/name "Resource representation fixture"
                      :mm.memory/rel-path (str "observations/" (name ident) ".md")
                      :mm.memory/visibility visibility
                      :mm.memory/body-raw "An exact body paragraph.\n"}
               sectioned? (assoc :mm.memory/first-section "section"))
        tx (cond-> [root]
             sectioned? (conj {:db/id "section" :db/ident section-ident :dt/type :mm/Section
                                :mm.section/parent "document"
                                :mm.section/heading "Evidence" :mm.section/heading-level 2
                                :mm.section/body "The section body survives.\n"}))]
    @(d/transact (db/conn) tx)
    (db/entity ident)))

(defn- listed [principal]
  (let [response (resources/handle-list 41 {} principal)]
    (is (nil? (:error response)) (pr-str response))
    (into {} (map (juxt :uri identity) (get-in response [:result :resources])))))

(defn- read-content [entity]
  (let [uri (resources/entity->uri entity)
        response (resources/handle-read 42 {:uri uri})
        contents (get-in response [:result :contents])]
    (is (nil? (:error response)) (pr-str response))
    (is (= 1 (count contents)))
    (is (= uri (:uri (first contents))))
    (first contents)))

(defn- not-found [uri]
  {:jsonrpc "2.0" :id 43
   :error {:code -32602 :message (str "Resource not found: " uri) :data {:uri uri}}})

(defn- fixture-codec [declared-mimes text]
  (reify codec-proto/Codec
    (parse [_ _input _opts] (throw (UnsupportedOperationException. "Emission-only fixture")))
    (emit [_ _entity _opts] text)
    (mime-types [_] declared-mimes)
    (supports? [_ _class-ident] true)
    (round-trip-test [_ _entity] (throw (UnsupportedOperationException. "Emission-only fixture")))))

(defn- seed-custom-resource! [format]
  @(d/transact (db/conn)
     [[:db/add :dt/Resource :dt/native-codec format]
      {:db/ident :memory.resources/custom_codec :dt/type :dt/Resource
       :db/doc "Custom resource fallback content."}])
  (db/entity :memory.resources/custom_codec))

(deftest memory-descendant-markdown-with-and-without-sections
  (is (dt/type-isa? :mm/Memory :mm/Observation))
  ;; Observation declares its codec directly while sharing the Memory walker.
  (is (= :markdown (dt/native-codec-of-class :mm/Observation)))
  (doseq [[ident sectioned?] [[:memory.observations/resource_flat false]
                              [:memory.observations/resource_sections true]]]
    (testing (str ident)
      (let [entity (seed-memory! :mm/Observation ident :public sectioned?)
            uri (resources/entity->uri entity)
            content (read-content entity)]
        (is (= "text/markdown" (get-in (listed nil) [uri :mimeType])))
        (is (= "text/markdown" (:mimeType content)))
        (is (str/starts-with? (:text content) "---\n"))
        (is (str/includes? (:text content) (str (:mm/id entity))))
        (is (str/includes? (:text content) "An exact body paragraph."))
        (is (= sectioned? (str/includes? (:text content) "## Evidence")))
        (when sectioned?
          (is (str/includes? (:text content) "The section body survives.")))))))

(deftest read-mime-follows-the-rendered-branch-instead-of-class-default
  ;; A section tree takes md/emit-document, even if the class's no-section
  ;; default is another codec. List metadata remains that declared default.
  @(d/transact (db/conn) [[:db/add :mm/Observation :dt/native-codec :json]])
  (let [sectioned (seed-memory! :mm/Observation :memory.observations/resource_json_sections :public true)
        flat (seed-memory! :mm/Observation :memory.observations/resource_json_flat :public false)
        catalog (listed nil)
        section-content (read-content sectioned)
        flat-content (read-content flat)]
    (is (= :json (dt/native-codec-of-class :mm/Observation)))
    (doseq [entity [sectioned flat]]
      (is (= "application/json" (get-in catalog [(resources/entity->uri entity) :mimeType]))))
    (is (= "text/markdown" (:mimeType section-content)))
    (is (str/starts-with? (:text section-content) "---\n"))
    (is (str/includes? (:text section-content) "## Evidence"))
    (is (str/includes? (:text section-content) "The section body survives."))
    (is (= "application/json" (:mimeType flat-content)))
    (is (= "mm/Observation" (:_class (json/parse-string (:text flat-content) true))))))

(deftest ordinary-resource-without-native-codec-returns-edn
  @(d/transact (db/conn)
     [{:db/ident :memory.resources/plain_edn :dt/type :dt/Resource
       :dt/label "Ordinary resource" :db/doc "Exact plain resource content."}])
  (let [entity (db/entity :memory.resources/plain_edn)
        uri (resources/entity->uri entity)
        content (read-content entity)]
    (is (nil? (dt/native-codec-of-class :dt/Resource)))
    (is (= "application/edn" (get-in (listed nil) [uri :mimeType])))
    (is (= "application/edn" (:mimeType content)))
    (is (= (into {} entity) (edn/read-string (:text content))))))

(deftest registered-custom-codec-supplies-declared-mime
  (codec/register! :resource-fixture (fixture-codec ["text/x-resource-fixture"] "Custom bytes.\n"))
  (let [entity (seed-custom-resource! :resource-fixture)
        uri (resources/entity->uri entity)
        content (read-content entity)]
    (is (= "text/x-resource-fixture" (get-in (listed nil) [uri :mimeType])))
    (is (= "text/x-resource-fixture" (:mimeType content)))
    (is (= "Custom bytes.\n" (:text content)))))

(deftest custom-codec-without-mime-keeps-text-and-omits-optional-label
  (codec/register! :resource-fixture-no-mime (fixture-codec [] "Unlabelled custom bytes.\n"))
  (let [entity (seed-custom-resource! :resource-fixture-no-mime)
        uri (resources/entity->uri entity)
        catalog (listed nil)
        content (read-content entity)]
    (is (contains? catalog uri) "Missing MIME metadata must not hide the resource")
    (is (not (contains? (get catalog uri) :mimeType)))
    (is (not (contains? content :mimeType)))
    (is (= "Unlabelled custom bytes.\n" (:text content)))))

(deftest unregistered-native-codec-has-no-guessed-label-and-reads-as-edn
  (let [entity (seed-custom-resource! :resource-fixture-unregistered)
        uri (resources/entity->uri entity)
        catalog (listed nil)
        content (read-content entity)]
    (is (nil? (codec/codec-for :resource-fixture-unregistered)))
    (is (contains? catalog uri))
    (is (not (contains? (get catalog uri) :mimeType)))
    (is (= "application/edn" (:mimeType content)))
    (is (= (into {} entity) (edn/read-string (:text content))))))

(deftest renderer-exception-falls-back-in-both-text-and-mime
  ;; The base class made the old split-label path return text/markdown around
  ;; its EDN fallback. Throw inside the actual section renderer, not the handler.
  (let [entity (seed-memory! :mm/Memory :memory.observations/resource_renderer_error :public true)
        uri (resources/entity->uri entity)
        calls (atom 0)]
    (is (= "text/markdown" (get-in (listed nil) [uri :mimeType])))
    (with-redefs [md/emit-document
                  (fn [_]
                    (swap! calls inc)
                    (throw (ex-info "Synthetic renderer failure" {})))]
      (let [content (read-content entity)]
        (is (= 1 @calls) "The intended renderer branch was actually attempted")
        (is (= "application/edn" (:mimeType content)))
        (is (= (into {} entity) (edn/read-string (:text content))))
        (is (not (str/includes? (:text content) "Synthetic renderer failure")))))))

(deftest spoofed-absent-and-hidden-resources-share-not-found-contract
  (let [public (seed-memory! :mm/Observation :memory.observations/resource_public :public false)
        private (seed-memory! :mm/Observation :memory.observations/resource_private :private false)]
    @(d/transact (db/conn)
       [{:db/ident :auth/resource_forbidden :dt/type :auth/ServiceAccount
         :db/doc "SYNTHETIC-FORBIDDEN-RESOURCE"}])
    (let [public-uri (resources/entity->uri public)
          private-uri (resources/entity->uri private)
          forbidden-uri (resources/entity->uri (db/entity :auth/resource_forbidden))
          wrong-parent (str/replace public-uri "/mm/Observation/" "/mm/Memory/")
          wrong-kind (str/replace public-uri "/mm/Observation/" "/dt/Class/")
          absent "mcp://sandbar/mm/Observation/memory.observations/resource_absent"
          denied [wrong-parent wrong-kind absent private-uri forbidden-uri]
          renderer-calls (atom 0)]
      (doseq [principal [nil {}]]
        (let [catalog (listed principal)]
          (is (contains? catalog public-uri))
          (is (not (contains? catalog private-uri)))
          (is (not (contains? catalog forbidden-uri)))))
      (is (= "text/markdown" (:mimeType (read-content public))))
      ;; An explicit fixture principal checks the existing clearance predicate;
      ;; it is not evidence of account provisioning or authenticated dispatch.
      (is (contains? (listed {:auth/full-clearance? true}) private-uri))
      (is (= "text/markdown"
             (get-in (resources/handle-read 44 {:uri private-uri} {:auth/full-clearance? true})
                     [:result :contents 0 :mimeType])))
      (with-redefs [codec/emit (fn [& _] (swap! renderer-calls inc) "UNEXPECTED RENDER")
                    md/emit-document (fn [& _] (swap! renderer-calls inc) "UNEXPECTED RENDER")]
        (doseq [principal [nil {}]
                uri denied]
          (testing (str principal " " uri)
            (is (= (not-found uri) (resources/handle-read 43 {:uri uri} principal)))))
        (is (zero? @renderer-calls) "Refusals happen before rendering")))))
