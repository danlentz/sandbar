(ns sandbag.api.status
  (:require [sandbag.service.endpoint :as endpoint :refer [defhandler]]
            [sandbag.service.params   :as params   :refer [defvalidator]]))

(defvalidator ::status-handler [_]
  identity)

(defhandler status-handler [_ _ _]
  {:time (java.util.Date.)
   :clojure *clojure-version*})
