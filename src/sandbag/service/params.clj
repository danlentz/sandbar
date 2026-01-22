(ns sandbag.service.params
  (:require [clojure.spec.alpha         :as spec]
            [clojure.tools.logging      :as log]
            [io.pedestal.http.route     :as route]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [sandbag.db.datomic         :as db]
            [sandbag.service.endpoint   :as endpoint :refer [defbefore defafter defhandler]]
            [sandbag.util.http-status   :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validator Dispatch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Example:
;;
;; (defmethod validate :foo [_]
;;   (fn [& [ent-store]]
;;     (vc/valid-int "must be valid int data")))
;;
;; (((validate :foo) user/es) 7)
;;
;; (defvalidator ::int [_]
;;   (vc/valid-int "must be integer value"))
;;
;; (((validate ::int) nil) 7)

(defmulti validate
  "For a given API route, generate a function appropriate for that specific
  route that will return a function that can be called to generate
  a corresponding validation function that ensures the semantic validity
  of data submitted."
  (fn [kw & _] kw))

(defmacro defvalidator [kw & fdecl]
  `(defmethod validate ~kw [x#]
     (fn ~@fdecl)))

(defvalidator :default [_]
  identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path Params
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defbefore url-decode-path-params [context]
  (update-in context [:request :path-params]
             #(reduce-kv (fn [m k v] (assoc m k (route/decode-query-part v))) {} %)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parameter Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def method-params
  {:post   [:json-params :csv-params :edn-params]
   :delete [:json-params :csv-params :edn-params]
   :get    [:query-params]
   :put    [:json-params :csv-params :edn-params]})

(defn preferred-params [{:keys [request-method] :as request}]
  ((apply some-fn (method-params request-method)) request))

(spec/def ::row*
  (spec/nilable
    (spec/or
      :map map?
      :maps (spec/coll-of map? :kind sequential?))))

(defn- consolidate-params [{:keys [request] :as context}]
  (let [params         (preferred-params request)]
    (if (spec/valid? ::row* params)
      (let [path-params (:path-params request)
            all-params  (if-not (sequential? params)
                          (merge params path-params)
                          (map merge params (repeat path-params)))]
        (log/debug :PARAMS/REQUEST all-params)
        (assoc-in context [:request :parsed-params] all-params))
      (-> context
          (assoc :response (endpoint/bad-request (with-out-str (spec/explain ::row* params)))
          terminate)))))

(def parsed-params
  {:name  ::parsed-params
   :enter consolidate-params})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parameter Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-params [{:keys [request route] :as context}]
  (log/info :PARAMS/VALIDATE {:route-name (:route-name route)})
  (let [ent-store  (:ent-store request)
        validation ((validate (:route-name route)) (db/conn) )
        params     (->> request :parsed-params)]
    (log/info :PARAMS/VALIDATING params)
    (if (spec/valid? validation params)
      (assoc-in context [:request :valid-params] params)
      (-> context
          (assoc :response (endpoint/bad-request (with-out-str (spec/explain validation params))))
          terminate))))

(def validated-params
  {:name ::validated-params
   :enter validate-params})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging Params
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defafter log-params
  [{:keys [request] :as context}]
  (if (:suppress-logging? context)
    (log/info :PARAMS :REDACTED)
    (log/info :PARAMS (:valid-params request)))
  context)




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coercion Dispatch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Example:
;;
;; (defmethod coercion :foo "text/csv" [_]
;;   (fn [& [ent-store]]
;;     (vc/valid-int-str "must be valid integer string")))
;;
;; (((coersion :foo "text/csv") user/es) "7")

;; (defmulti coercion
;;   (fn [route content-type & _] [route content-type])
;;   :default [:default nil])

;; (defmacro defcoercion [route content-type & fdecl]
;;   `(defmethod coercion [~route ~content-type] [x# y#]
;;      (fn ~@fdecl)))

;; (defcoercion :default nil [_]
;;   vc/valid)

;; (defn- coerce-params [{:keys [request route] :as context}]
;;   (log/info :COERCE-PARAMS {:route-name (:route-name route) :content-type (:content-type request)})
;;   (let [ent-store  (:ent-store request)
;;         coerce     ((coercion (:route-name route) (:content-type request)) ent-store)
;;         params     (->> request :parsed-params coerce)]
;;     (log/debug :COERCED-PARAMS params)
;;     (if (vc/valid? params)
;;       (assoc-in context [:request :coerced-params] (vc/data params))
;;       (-> context (assoc :response (endpoint/bad-request (vc/error params)))
;;           terminate))))

;; (def coerced-params
;;   {:name ::coerced-params
;;    :enter coerce-params})
