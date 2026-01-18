(ns socialnet.model.twit
  (:refer-clojure :exclude [cat])
  (:require [datomic.api        :as    d]
            [datomic.db         :as  ddb]
            [datomic.common     :as  dcm]
            [clojure.pprint     :as   pp]
            [socialnet.util     :as util]
            [socialnet.state    :as state]
            [socialnet.db.datatype :as dt]
            [socialnet.db.rules :refer [defrule clear-rulebase! all-rules] :as rule]
            [socialnet.db.fn    :refer [defdbfn dbfn clear-fnbase! all-dbfn] :as fn]
            [socialnet.db.ops   :refer [entity describe] :as db]
            [socialnet.api.twitter :as twitter]
            [clojure.tools.logging :as log]))

(def me  7623072)

(defn ensure-twit-ids [ids]
  (when (seq ids)
    (vals (:tempids @(d/transact (db/conn)
                                 (for [id ids]
                                   {:db/id (d/tempid :db.part/user)
                                    :dt/dt :twit
                                    :twit/id id}))))))

(defn ensure-twit-id [id]
  (first (ensure-twit-ids [id])))

(defn- non-nil-values [m]
  (reduce-kv (fn [acc k v] (if (nil? v) acc (assoc acc k v))) {} m))


(defn- upsert-twit! [{id         :id
                     suspended   :suspended
                     name        :screen_name
                     namestring  :name
                     lang        :lang
                     description :description
                     image-url   :profile_image_url
                     location    :location
                     url         :url
                     created     :created_at
                     geo-enabled :geo-enabled
                      :as         user}
                     follower-eids]
  (let [twit-eid (first
                  (vals
                   (:tempids @(d/transact (db/conn)
                                          [(non-nil-values
                                            {:db/id #db/id[:db.part/user]
                                             :twit/id id
                                             :dt/dt :twit
                                             :twit/suspended suspended
                                             :twit/description description
                                             :twit/geo-enabled geo-enabled
                                             :twit/lang lang
                                             :twit/location location
                                             :twit/name name
                                             :twit/namestring namestring
                                             :twit/image-url (java.net.URI. (or image-url ""))
                                             :twit/url (java.net.URI. (or url ""))})]))))
        _         @(d/transact (db/conn)
                               (for [eid follower-eids]
                                 {:db/id eid
                                  :dt/dt :twit
                                  :twit/follows twit-eid}))]
    twit-eid))

(defn add-twit! [screen-name-or-twitter-id]
  (let [twit
        (cond
          (number? screen-name-or-twitter-id) (twitter/fetch-user-by-id screen-name-or-twitter-id)
          (string? screen-name-or-twitter-id) (twitter/fetch-user screen-name-or-twitter-id)
          true                                (util/exception IllegalArgumentException :BAD-IDENTITY
                                                              screen-name-or-twitter-id))
        followers (twitter/fetch-follower-ids (:screen_name twit))
        eid       (upsert-twit! twit (ensure-twit-ids followers))]
    (println :TWIT-CREATED eid (:screen_name twit))))

;; (ensure-twit-ids (twitter/fetch-follower-ids "vseloved"))
;; (add-twit! "vseloved")
