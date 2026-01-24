(ns sandbar.api.status
  (:require [sandbar.service.endpoint :as endpoint :refer [defhandler]]
            [sandbar.service.params   :as params   :refer [defvalidator]]))

(defvalidator ::status-handler [_]
  identity)

(defhandler status-handler [_ _ _]
  {:time (java.util.Date.)
   :clojure *clojure-version*})
