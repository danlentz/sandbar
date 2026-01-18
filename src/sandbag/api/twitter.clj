(ns socialnet.api.twitter
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [socialnet.edn  :as dedn]
            [socialnet.api.protocols  :as p]
            [socialnet.util :as util])
  (:require [twitter.oauth :as oauth :refer :all]
            [twitter.callbacks :as cb :refer :all]
            [twitter.callbacks.handlers :as handler :refer :all]
            [twitter.api.restful :as api :refer :all])
  (:import [twitter.callbacks.protocols SyncSingleCallback]))


(defn- make-creds []
  ((juxt :consumer-key :consumer-secret :access-token :access-secret)
   (dedn/config-value :auth :twitter)))

(defn- creds []
  (apply make-oauth-creds (make-creds)))

(defn oauth-credential [config]
  (apply make-oauth-creds
         ((juxt :consumer-key :consumer-secret :access-token :access-secret)
          (-> config :auth :twitter))))

(defrecord TwitterEndpoint [auth state cache]
  component/Lifecycle
  (start [self]
    (util/returning (assoc self
                           :state (atom {:idx 0})
                           :cache (atom {}))
      (log/info :TWITTER-ENDPOINT {:event :started}))
    )
  (stop [self]
    nil)
  p/EndPoint
  (make-request [self op lambda args]
    (p/->Request (java.util.UUID/randomUUID) op lambda args))
  (execute [self request]
    )
  (decode [self response]
    )
  )

(defn make-twitter-endpoint [config]
  (map->TwitterEndpoint {:auth (oauth-credential config)}))


(defn fetch-user* [screen-name]
  (log/info :API-FETCH-USER-BY-NAME screen-name)
  (:body (users-show :oauth-creds (creds)
                     :params {:screen-name screen-name})))

(def fetch-user (memoize fetch-user*))

(defn fetch-user-by-id* [id]
  (log/info :API-FETCH-USER-BY-ID id)
  (first (:body (users-lookup :oauth-creds (creds)
                       :params {:user-id id}))))

(def fetch-user-by-id (memoize fetch-user-by-id*))

(defn fetch-follower-ids* [screen-name]
  (log/info :API-FETCH-FOLLOWERS screen-name)
  (:ids (followers-ids    :oauth-creds (creds)
                          :callbacks (SyncSingleCallback. response-return-body
                                                          response-throw-error
                                                          exception-rethrow)
                          :params {:target-screen-name screen-name})))

(def fetch-follower-ids (memoize fetch-follower-ids*))


(comment

  (fetch-user "danlentz")
  (repeatedly 100   #(fetch-user "danlentz"))

#_
(users-show :oauth-creds my-creds :params {:screen-name "bobdc"})
#_
(followers-list :oauth-creds my-creds
                :params {:target-screen-name "danlentz"})

; shows the users friends

; use a custom callback function that only returns the body of the response
(friendships-show :oauth-creds (creds)
                  :callbacks (SyncSingleCallback. response-return-body
                                                  response-throw-error
                                                  exception-rethrow)
          :params {:target-screen-name "bobdc"})

; post a text status, using the default sync-single callback
(statuses-update :oauth-creds *creds*
                 :params {:status "hello world"})

; upload a picture tweet with a text status attached, using the default sync-single callback
(statuses-update-with-media :oauth-creds *creds*
                            :body [(file-body-part "/pics/test.jpg")
                                  (status-body-part "testing")])
)
