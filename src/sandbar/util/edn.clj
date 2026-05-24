(ns sandbar.util.edn
  "generic convenience routines for access to static resources"
  (:refer-clojure :exclude [cat])
  (:require [clojure.java.io       :as io]
            [clojure.pprint        :as pp]
            [clojure.edn           :as edn]
            [clojure.java.io       :as io]
            [clojure.string        :as str])
  (:require [sandbar.util.common   :as util])
  (:import  (java.net  URL))
  (:import  (java.util UUID))
  (:import  (java.io   ByteArrayInputStream
                       FileInputStream
                       File)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN Resources
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol StringDesignator
  (as-string [x] "Stringified name of object"))

(extend-type clojure.lang.Keyword StringDesignator
             (as-string [k]
               (name k)))

(extend-type clojure.lang.Var StringDesignator
             (as-string [v]
               (util/symbolic-name-from-var v)))

(extend-type java.lang.String StringDesignator
             (as-string [s]
               s))

(extend-type java.lang.Object StringDesignator
             (as-string [s]
               (.toString s)))

(defn- maybe-append-extension [ext base]
  (or (re-matches (re-pattern (str ".*\\." (as-string ext) "$")) base)
    (str base "." (as-string ext))))

(defn edn-resource [designator]
  (io/resource
    (maybe-append-extension :edn
      (as-string designator))))

(defn edn-file [designator]
  (io/file
    (edn-resource designator)))

(defmulti resource-value (fn [_ arg] (type arg)))

(defmethod resource-value nil [designator _]
                                        ; (util/ignore-exceptions
    (read-string
     (slurp (edn-file designator))))   ;)

(defmethod resource-value clojure.lang.Keyword [designator config-key]
  (get (resource-value designator nil) config-key))

(defmethod resource-value clojure.lang.PersistentVector [designator config-path]
  (util/ignore-exceptions
   (get-in (resource-value designator nil) config-path)))

;; (edn-file :config)
;; (edn-file :literal)
;; (slurp (edn-file :literal))

;; (resource-value :config nil)
;; (resource-value :config db)
;; (resource-value :config [:db :sid])
;; (resource-value :literal nil)
;; (resource-value :fn nil)

;(type :literal)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;
;; Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
;; — config-value is now a thin facade over the 3-layer loader in
;; sandbar.config (bundled defaults + client `.sandbar/config.edn` +
;; env vars).  Existing callers see no API change; they get the layered
;; resolved config automatically.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Lazy require to avoid circular dep + ns load-order issues — sandbar.config
;; itself only depends on java + clojure.{edn,io,string}.
(defn- resolve-config-fn []
  (require 'sandbar.config)
  (resolve 'sandbar.config/config))

(defn config-value
  ([]
   (config-value nil))
  ([& path]
   (let [conf ((resolve-config-fn))]
     (cond
       (or (nil? path) (empty? path)) conf
       (= 1 (count path))             (get conf (first path))
       :else                          (get-in conf (vec path))))))

(defn config-keys []
  (keys ((resolve-config-fn))))

;; (config-value :required-schema)
;; (config-value :db :sid)
;; (config-value [:db :sid])
;; (config-keys)
