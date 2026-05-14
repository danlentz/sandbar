(ns sandbar.service.endpoint
  (:require
            [clojure.set              :as set]
            [clojure.tools.logging    :as log]
            ;;            [geheimtur.util.auth      :as auth]
            [sandbar.util.http-status :as http-status]
            [io.pedestal.interceptor  :as interceptor]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reified Return Value
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord ReturnValue [headers status body] ;; MAYBE: content-type
  clojure.lang.IFn ; Ring interceptors call return as function to retrieve data
  (invoke [this arg]
    (get this arg)))

(defn return-value? [x]
  (instance? ReturnValue x))

(defn return
  ([x] (if (return-value? x)
           x
           (return http-status/success x)))
  ([status body] (return nil status body))
  ([headers status body] (->ReturnValue headers status body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Responses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: refactor (?)

(defn- maybe-assoc [m k v]
  (cond-> m v (assoc k v)))

(defn no-content []
  (return http-status/no-content nil))

(defn forbidden [reason]
  (return http-status/forbidden reason))

(defn not-found [message]
  (return http-status/not-found message))

(defn not-authorized [user & [reason]]
  (return http-status/not-authorized
          (-> {:user user} (maybe-assoc :reason reason))))

(defn not-implemented [feature & [reason]]
  (return http-status/not-implemented
          (-> {:feature feature} (maybe-assoc :reason reason))))

(defn bad-request [error]
  (return http-status/bad-request error))

(defn not-acceptable [& [accept-param]]
  (return http-status/not-acceptable
          (str "Not Acceptable " accept-param)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Endpoint Mechanics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn standard-error-handler [{:keys [request route] :as context} info]
  (let [route-name      (:route-name route)
        valid-params    (:valid-params request)
        request-method  (:request-method request)]
    (log/errorf ":ERROR in %s %s\n  Params:\n%s\n\n  Exception Data:\n%s\n"
                route-name request-method
                (pr-str valid-params)
                (pr-str (ex-data info)))
    (throw info)))

(defn- entity-ref-reasons
  "Extract the :reasons set from an ex-info, walking the (:exception ...)
   chain Pedestal wraps caught throwables in."
  [info]
  (or (:reasons (ex-data info))
      (some-> info ex-cause ex-data :reasons)
      #{}))

(defn entity-ref-error-handler
  "Pedestal :error phase projecting `sandbar.entity-ref/*` ex-info reasons
   to structured HTTP responses:
     - `:entity-ref/not-found` → HTTP 404
     - `:entity-ref/malformed-input` /
       `:entity-ref/lookup-vector-unsupported` /
       `:entity-ref/no-ident` → HTTP 400

   Non-entity-ref ex-info is re-thrown for upstream handling.
   Per `decisions/sandbar_entity_ref_abstraction_2026_05_14.md` §D-3.4."
  [context info]
  (let [reasons (entity-ref-reasons info)
        message (some-> info .getMessage)
        details (ex-data info)]
    (cond
      (contains? reasons :entity-ref/not-found)
      (assoc context :response
             (return http-status/not-found
                     {:error    "Entity not found"
                      :reasons  reasons
                      :message  message
                      :details  details}))

      (some #{:entity-ref/malformed-input
              :entity-ref/lookup-vector-unsupported
              :entity-ref/no-ident} reasons)
      (assoc context :response
             (return http-status/bad-request
                     {:error    "Invalid entity reference"
                      :reasons  reasons
                      :message  message
                      :details  details}))

      :else
      (throw info))))

(def entity-ref-error-interceptor
  "Pedestal interceptor projecting `sandbar.entity-ref/*` ex-info reasons
   to structured HTTP 400 / 404 responses.  Hooked into the `/api`
   interceptor stack so REST handlers calling `eref/resolve` /
   `eref/resolve-ident` (throw-on-error) get clean HTTP-error projection
   without per-handler try/catch boilerplate.

   Per `decisions/sandbar_entity_ref_abstraction_2026_05_14.md` §D-3.4."
  (interceptor/interceptor
    {:name  ::entity-ref-error-interceptor
     :error entity-ref-error-handler}))

(defn standard-endpoint [{:keys [route request] :as context}
                         {:keys [handler] :as opts}]
  (let [{:keys [route-name]} route
        {:keys [db-conn valid-params request-method]} request]
    (assoc context :response (-> request (handler db-conn valid-params) return))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Interceptor Definers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private def-simple-interceptor-definer
  "simple pedestal definer macros to provide compatibility with AOT compilation
  https://github.com/pedestal/pedestal/issues/308"
  [helper phase]
  `(defmacro ~helper [sym# & fdecl#]
     (let [m#     (if (string? (first fdecl#))
                   {:doc (first fdecl#)}
                   {})
           fdecl# (if (string? (first fdecl#))
                   (next fdecl#)
                   fdecl#)]
       `(do (def ~sym# (interceptor/interceptor
                         {:name (keyword (str (ns-name *ns*)) (name '~sym#))
                          ~~phase (fn ~@fdecl#)}))
            (alter-meta! #'~sym# merge ~m#)
            #'~sym#))))

(def-simple-interceptor-definer defbefore :enter)

(def-simple-interceptor-definer defafter  :leave)

(defmacro defon-response [sym & fdecl]
  `(let [f# (fn ~@fdecl)]
     (defafter ~sym [context#]
       (update context# :response f#))))

(defmacro defendpoint [interceptor-name & more]
  (let [impl-name (symbol (str (name interceptor-name) "-impl"))
        [docstring bindings body] (if (-> more first string?)
                                    [(first more) (second more) (-> more rest rest)]
                                    [nil (first more) (rest more)])]
    `(do ~(if docstring
           `(defn ~impl-name ~docstring ~bindings ~@body)
           `(defn ~impl-name ~bindings ~@body))
        (defbefore ~interceptor-name ~bindings (@(var ~impl-name) ~@bindings))))) ; @(var <name>for reloadability reasons

(defmacro defhandler
  "Define a standard endpoint handler that accepts the REQUEST, ENT-STORE,
   and validated DATA.
    ex:  (defhandler abc [request db-conn {:keys [filters] :as data}]
           (println :filters filters :data data))"
  [interceptor-name & fdecl]
  (let [impl-name (symbol (str (name interceptor-name) "-impl"))]
    `(do
       (defn ~impl-name ~@fdecl)
       (def ~interceptor-name
         {:name  ~(->> `~interceptor-name str (keyword (namespace ``~interceptor-name)))
          :error standard-error-handler
          :enter (fn [context#]
                   (standard-endpoint context# {:handler #'~impl-name}))}))))
