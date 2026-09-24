(ns sandbar.project.onboarding-privacy-test
  "Manual public/private enrollment composed with native MCP and physical routing.
   Synthetic fixtures only. Full-clearance writer and public-only reader use the
   shipped account schema; no fixture-only per-project clearance is installed."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.project.destination :as dest]
            [sandbar.reactive :as reactive]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.shape :as shape]
            [sandbar.store :as store]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *base* nil)
(def ^:dynamic *tokens* nil)
(def private-marker "PrivateOnboardingCanary93671")
(def public-project :memory.projects/onboard_public)
(def private-project :memory.projects/onboard_private)

(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- projected-file [tree path] (io/file (root tree) "memory" path))
(defn- remove-tree! [f]
  (when (.isDirectory f) (doseq [child (.listFiles f)] (remove-tree! child)))
  (.delete f))

(def strict-enrollment {:pre-commit #(shape/validate %1 %2 :strict)})

(defn- enroll! [posture]
  (let [public? (= posture :public)
        slug (str "onboard_" (name posture))
        context (keyword "memory.contexts" slug)
        project (keyword "memory.projects" slug)
        firewall (if public? :public-bottom :project-isolated)]
    (store/create-memory! :mm/Context
      {:db/ident context :mm.memory/rel-path (str "contexts/" slug ".md")
       :mm.memory/name slug :mm.memory/memory-type :context :mm.context/context-type :context
       :mm.memory/visibility posture :mm.context/firewall-class firewall}
      strict-enrollment)
    (store/create-memory! :mm/Project
      {:db/ident project :mm.memory/rel-path (str "projects/" slug ".md")
       :mm.memory/name slug :mm.memory/memory-type :project
       :mm.memory/visibility posture :mm.project/default-visibility posture
       :mm.project/firewall-class firewall :mm.project/runs-in-context context
       :mm.project/ident (keyword "project" slug)
       ;; This fixture has no repository publication handle. Physical paths
       ;; belong exclusively to the operator map installed below.
       :mm.project/corpus-repo "UNASSIGNED"}
      strict-enrollment)
    (dt/update-entity! context {:mm.context/visible-projects [project]} strict-enrollment)))

(defn- account! [service role full?]
  (let [key "synthetic-onboarding-privacy-only"]
    (dt/make :auth/ServiceAccount
      (cond-> {:auth/service-name service
               :auth/api-key-hash (auth/hash-password key)
               :auth/roles [(tu/ensure-role! role)] :auth/active? true}
        full? (assoc :auth/full-clearance? true)))
    (str (name service) ":" key)))

(defn- fixture [f]
  (let [base (.toFile (Files/createTempDirectory "onboarding-privacy-" (make-array FileAttribute 0)))
        roots (atom {}) original config/value]
    (binding [*base* base]
      (doseq [tree ["global" "public" "private"]]
        (.mkdirs (projected-file tree "")))
      (with-redefs [config/value (fn [k & args]
                                  (if (= k :project-roots) @roots (apply original k args)))
                    dest/global-root #(root "global")]
        (try
          (md/register!)
          (binding [reactive/*reactive-projection-enabled?* false]
            (enroll! :public)
            (enroll! :private))
          (reset! roots {:project/onboard_public (root "public")
                         :project/onboard_private (root "private")})
          (dest/validate-roots! (db/db) (root "global"))
          (binding [*tokens* {:writer (account! :onboarding-writer :read-write true)
                             :reader (account! :onboarding-reader :read-only false)}]
            (f))
          (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture
                      {:test-name "onboarding-privacy" :auth? false :extra-schema [:auth :event]})
              fixture)

(defn- call [principal verb args]
  (let [response (response-for tu/service :post "/mcp"
                   :headers {"Content-Type" "application/json"
                             "Accept" "application/json, text/event-stream"
                             "Authorization" (str "Bearer " (get *tokens* principal))}
                   :body (json/generate-string
                           {:jsonrpc "2.0" :id 1 :method "tools/call"
                            :params {:name verb :arguments args}}))]
    (assert (= 200 (:status response)) (pr-str response))
    (json/parse-string (:body response) true)))

(defn- payload [envelope]
  (assert (nil? (:error envelope)) (pr-str envelope))
  (assert (map? (:result envelope)) (pr-str envelope))
  (assert (not (get-in envelope [:result :isError])) (pr-str envelope))
  (let [p (or (get-in envelope [:result :structuredContent])
              (json/parse-string (get-in envelope [:result :content 0 :text]) true))]
    (assert (nil? (:error p)) (pr-str p))
    p))

(defn- slots [slug posture cites]
  (cond-> {:db/ident (str ":memory.observations/" slug)
           :mm.memory/rel-path (str "observations/" slug ".md")
           :mm.memory/name slug :mm.memory/memory-type "observation"
           :mm.memory/scope "project" :mm.memory/visibility (name posture)
           :mm.memory/owning-project (str (if (= posture :public) public-project private-project))
           :mm.memory/body-raw (if (= posture :private) private-marker "Public reusable knowledge.")}
    cites (assoc :mm.memory/cites [(str cites)])))

(defn- create! [slug posture cites]
  (:entity (payload (call :writer "sandbar_entity_create"
                     {:class ":mm/Observation" :validation-mode "strict"
                      :slots (slots slug posture cites)}))))
(defn- find! [principal ident]
  (payload (call principal "sandbar_entity_find" {:ident (str ident) :projection "full"})))
(defn- emit! [entity]
  (let [eid (:db/id entity)]
    (sinks/fs-projection-sink eid (into {:db/id eid} (db/entity eid)))))
(defn- refuses-firewall? [envelope]
  (and (or (:error envelope) (get-in envelope [:result :isError]))
       (boolean (re-find #"(?i)firewall" (pr-str envelope)))))

(deftest private-project-reuses-public-memory-without-publishing-its-capture
  (let [pub (create! "onboard_source" :public nil)
        priv (create! "onboard_capture" :private :memory.observations/onboard_source)
        ident :memory.observations/onboard_capture
        path "observations/onboard_capture.md"]
    (emit! pub) (emit! priv)
    (is (= "public" (get-in (find! :writer public-project) [:entity :mm.project/default-visibility])))
    (is (= "private" (get-in (find! :writer private-project) [:entity :mm.project/default-visibility])))
    (is (= private-marker (get-in (find! :writer ident) [:entity :mm.memory/body-raw])))
    (let [walk (payload (call :writer "sandbar_navigate_outbound-edges"
                         {:entity (str ident) :predicate ":mm.memory/cites" :projection "full"}))]
      (is (= 1 (:total walk)))
      (is (= (:db/id pub) (get-in walk [:edges 0 :target :db/id]))))
    (is (.isFile (projected-file "private" path)))
    (is (str/includes? (slurp (projected-file "private" path)) private-marker))
    (is (not (.exists (projected-file "public" path))))
    (is (not (.exists (projected-file "global" path))))
    (let [hidden (find! :reader ident)]
      (is (nil? (:entity hidden)))
      (is (true? (:missing? hidden)))
      (is (not (str/includes? (pr-str hidden) private-marker))))
    (is (= (:db/id pub) (get-in (find! :reader :memory.observations/onboard_source) [:entity :db/id])))
    (let [args {:class ":mm/Observation" :query private-marker :limit 10
                :include ["snippets" "field-scores"] :projection "full"}
          visible (payload (call :writer "sandbar_search_bm25f" args))
          hidden (payload (call :reader "sandbar_search_bm25f" args))]
      (is (= #{(:db/id priv)} (set (map #(get-in % [:entity :db/id]) (:hits visible))))
          "The same query finds the private record for an authorized principal")
      (is (= 1 (:total visible)))
      (is (= [] (:hits hidden)) "Metadata must be hidden along with the body")
      (is (= 0 (:total hidden)))
      (is (not (str/includes? (pr-str hidden) private-marker))))))

(deftest public-memory-links-public-but-refuses-private-on-create-and-update
  (let [pub (create! "onboard_public_target" :public nil)
        _ (create! "onboard_private_target" :private nil)
        public-ref :memory.observations/onboard_public_target
        private-ref :memory.observations/onboard_private_target
        clean (create! "onboard_public_citer" :public public-ref)
        path "observations/onboard_public_citer.md"]
    (emit! clean)
    (let [before (d/basis-t (db/db)) bytes (slurp (projected-file "public" path))
          creation (call :writer "sandbar_entity_create"
                     {:class ":mm/Observation" :validation-mode "strict"
                      :slots (slots "onboard_refused" :public private-ref)})
          update (call :writer "sandbar_entity_update"
                   {:entity ":memory.observations/onboard_public_citer" :validation-mode "strict"
                    :slots {:mm.memory/cites [(str private-ref)]}})]
      (is (refuses-firewall? creation) (pr-str creation))
      (is (refuses-firewall? update) (pr-str update))
      (is (= before (d/basis-t (db/db))) "Refused attempts transact nothing")
      (is (= bytes (slurp (projected-file "public" path))))
      (is (nil? (d/entid (db/db) :memory.observations/onboard_refused)))
      (is (not (.exists (projected-file "public" "observations/onboard_refused.md"))))
      (let [walk (payload (call :reader "sandbar_navigate_outbound-edges"
                           {:entity ":memory.observations/onboard_public_citer"
                            :predicate ":mm.memory/cites" :projection "full"}))]
        (is (= 1 (:total walk)))
        (is (= (:db/id pub) (get-in walk [:edges 0 :target :db/id])))))))

(deftest changing-project-default-does-not-publish-existing-private-memory
  (let [priv (create! "onboard_kept_private" :private nil)
        ident :memory.observations/onboard_kept_private
        path "observations/onboard_kept_private.md"]
    (emit! priv)
    (payload (call :writer "sandbar_entity_update"
               {:entity (str private-project) :validation-mode "strict"
                :slots {:mm.project/default-visibility "public"}}))
    (is (= "public" (get-in (find! :writer private-project) [:entity :mm.project/default-visibility])))
    (is (= "private" (get-in (find! :writer ident) [:entity :mm.memory/visibility])))
    (is (nil? (:entity (find! :reader ident))))
    (emit! priv)
    (is (.isFile (projected-file "private" path)))
    (is (not (.exists (projected-file "public" path))))))

(deftest search-filters-before-limits-enrichment-and-scalar-facets
  (let [word "SharedOnboardingQuery82417"
        pub (create! "onboard_search_public" :public nil)
        priv (create! "onboard_search_private" :private nil)]
    (doseq [[entity posture] [[pub :public] [priv :private]]]
      (payload (call :writer "sandbar_entity_update"
                 {:entity (str (:db/id entity)) :validation-mode "strict"
                  :slots {:mm.memory/name (if (= posture :private) word "Public search result")
                          :mm.memory/body-raw word
                          :mm.memory/description (if (= posture :private) private-marker "Public facet")}})))
    (doseq [class [":mm/Observation" ":mm/Memory" [":mm/Observation" ":mm/Decision"]]]
      (let [args {:class class :query word :limit 1 :projection "full"
                  :rank-by ":relevance" :include ["snippets" "field-scores"]
                  :facet-by [":mm.memory/description"]}
            visible (payload (call :writer "sandbar_search_bm25f" args))
            public (payload (call :reader "sandbar_search_bm25f" args))]
        (is (= 2 (:total visible)) "Authorized control sees both matches")
        (is (= (:db/id priv) (get-in visible [:hits 0 :eid]))
            "The hidden hit would consume the limited result without pre-limit filtering")
        (is (= [(:db/id pub)] (mapv :eid (:hits public))))
        (is (= 1 (:total public)))
        (is (= 1 (:returned public)))
        (is (= {(keyword "Public facet") 1}
               (get-in public [:facets :mm.memory/description])))
        (is (not (str/includes? (pr-str public) private-marker)))))))

(deftest cached-public-label-does-not-authorize-a-now-private-hit
  (let [pub (create! "onboard_reclassified" :public nil)
        word "ReclassifiedOnboardingQuery52731"
        args {:class ":mm/Observation" :query word :limit 0
              :projection "full" :include ["snippets" "field-scores"]}]
    (payload (call :writer "sandbar_entity_update"
               {:entity (str (:db/id pub)) :validation-mode "strict"
                :slots {:mm.memory/body-raw word}}))
    (is (= [(:db/id pub)]
           (mapv :eid (:hits (payload (call :reader "sandbar_search_bm25f" args))))))
    ;; Simulate an accepted label change before an asynchronous index refresh.
    ;; Do not invalidate the warmed cache: the current store must govern access.
    @(d/transact (db/conn) [[:db/add (:db/id pub) :mm.memory/visibility :private]])
    (let [hidden (payload (call :reader "sandbar_search_bm25f" args))]
      (is (= [] (:hits hidden)))
      (is (= 0 (:total hidden)))
      (is (not (str/includes? (pr-str hidden) word))))
    (is (= [(:db/id pub)]
           (mapv :eid (:hits (payload (call :writer "sandbar_search_bm25f" args))))))))

(deftest attribute-search-removes-private-matches-before-counting-and-limiting
  (let [pub (create! "onboard_attribute_public" :public nil)
        priv (create! "onboard_attribute_private" :private nil)
        word "AttributeOnboardingQuery92841"
        public-body (str "## Details\n\n" word " " (str/join " " (repeat 150 "ordinary")))
        private-body (str "## Details\n\n" private-marker " "
                          (str/join " " (repeat 25 word)))]
    (doseq [[entity body] [[pub public-body] [priv private-body]]]
      (payload (call :writer "sandbar_entity_update"
                 {:entity (str (:db/id entity)) :validation-mode "strict"
                  :slots {:mm.memory/body-raw body}})))
    (doseq [attribute [":mm.memory/body-raw" ":mm.section/body"]]
      (let [args {:attribute attribute :query private-marker :limit 0 :projection "full"}
            visible (payload (call :writer "sandbar_search_attribute" args))
            hidden (payload (call :reader "sandbar_search_attribute" args))]
        (is (= 1 (:total visible)) "Authorized control finds the private document or its owned section")
        (is (= [] (:hits hidden)))
        (is (= 0 (:total hidden)))
        (is (= 0 (:returned hidden))))
      (let [args {:attribute attribute :query word :limit 1 :projection "full"}
            visible (payload (call :writer "sandbar_search_attribute" args))
            public (payload (call :reader "sandbar_search_attribute" args))]
        (is (= 2 (:total visible)))
        (is (str/includes? (pr-str (:hits visible)) private-marker)
            "The hidden stronger hit would consume the first result")
        (is (= 1 (:total public)))
        (is (= 1 (:returned public)))
        (is (str/includes? (pr-str (:hits public)) "ordinary"))
        (is (not (str/includes? (pr-str public) private-marker)))))))

(deftest bm25f-payload-and-facets-use-the-current-authorized-store-value
  (let [pub (create! "onboard_redacted_payload" :public nil)
        word "CurrentPayloadQuery62183"
        public-body (str word " Reviewed public summary.")
        args {:class ":mm/Observation" :query word :limit 0 :projection "full"
              :include ["snippets" "field-scores"]
              :facet-by [":mm.memory/description"]}]
    ;; Warm a legitimately private cached representation on a public project.
    (payload (call :writer "sandbar_entity_update"
               {:entity (str (:db/id pub)) :validation-mode "strict"
                :slots {:mm.memory/visibility "private"
                        :mm.memory/body-raw (str word " " private-marker)
                        :mm.memory/description private-marker}}))
    (let [warm (payload (call :writer "sandbar_search_bm25f" args))]
      (is (= [(:db/id pub)] (mapv :eid (:hits warm))))
      (is (str/includes? (pr-str warm) private-marker)))
    (is (= [] (:hits (payload (call :reader "sandbar_search_bm25f" args)))))
    ;; Model accepted in-process changes without an analyzed-cache refresh.
    ;; Exact current reads establish the public, redacted value independently.
    @(d/transact (db/conn)
       [[:db/add (:db/id pub) :mm.memory/body-raw public-body]
        [:db/add (:db/id pub) :mm.memory/description "Reviewed public facet"]
        [:db/add (:db/id pub) :mm.memory/visibility :public]])
    (is (= public-body
           (get-in (find! :reader :memory.observations/onboard_redacted_payload)
                   [:entity :mm.memory/body-raw])))
    (let [result (payload (call :reader "sandbar_search_bm25f" args))]
      (is (= [(:db/id pub)] (mapv :eid (:hits result))))
      (is (= public-body (get-in result [:hits 0 :entity :mm.memory/body-raw])))
      (is (= {(keyword "Reviewed public facet") 1}
             (get-in result [:facets :mm.memory/description])))
      (is (not (str/includes? (pr-str result) private-marker))))))
