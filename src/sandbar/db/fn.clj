(ns sandbar.db.fn
  "Database and Transaction Functions"
  (:require [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.util.common :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fn, Fn, Fn.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:dynamic *fn-base* (atom []))

(defn set-fnbase
  "Destructively sets current base of :db/fn definitions."
  [dbfns]
  (reset! *fn-base* dbfns))

(defn- distinct-most-recent [coll]
  (loop [c coll m (sorted-map)]
    (if (seq c)
      (recur (rest c) (assoc m (:db/ident (first c)) (first c)))
      (vals m))))

(defn all-dbfn
  "Returns all current :db/fn definitions."
  []
  (distinct-most-recent @*fn-base*))

(defn clear-fnbase!
  "Resets current rulenbase to empty."
  []
  (set-fnbase []))

(defn add-fn-to-fnbase
  [dbfn]
  (swap! *fn-base* conj dbfn)
  dbfn)

(defn load-all-dbfn [uri]
  (log/info :DB/FN "Loading" (count (all-dbfn)) "db/fn" )
  (d/transact (d/connect uri) (all-dbfn)))

(defn read-fnbase [f]
  (set-fnbase (slurp f)))

(defn save-fnbase [f & options]
  (apply spit f (all-dbfn) options))

(defn- maybe-prepend-db-arg [args]
  (if (= (first args) 'db)
        args
        (vec (cons 'db args))))

(defn- maybe-remove-db-arg [args]
  (if (= (first args) 'db)
        (vec (rest args))
        args))

(defmacro dbfn [args & body]
  `(d/function
     {:lang :clojure
      :params '~args
      :code (cons 'do '~body)}))

;; (dbfn [x y] (+ x (inc y)))


(defn build-dbfn [name args body]
  (into {}
    [[:db/id (d/tempid :db.part/db)]
    [:db/ident (keyword name)]
    [:dt/dt :fn]
    [:db/fn (d/function
              {:lang :clojure
               :params args
;;               :requires []
;;               :imports []
               :code body})]]))

;; (build-dbfn :set-doc! '[-db- e doc]
;;   '[[:db/add e :db/doc doc]])

;; {:db/id #db/id[:db.part/db -1000008],
;;  :db/ident :set-doc!,
;;  :dt/dt :fn,
;;  :db/fn #db/fn{:code "[[:db/add e :db/doc doc]]",
;;                :params [-db- e doc],
;;                :requires [],
;;                :imports [],
;;                :lang :clojure}}


(defn new-dbfn [name args body]
  (add-fn-to-fnbase
    (build-dbfn name args body)))


(defmacro defdbfn
  "Defines a function normally, and also adds a datomic db function
  transaction (that can be installed in a datomic db) to atom
  '*fn-base*'."
  [name-symbol params & code]
  `(let [~'fn-name-keyword (keyword '~name-symbol)]
     (util/returning '~name-symbol
       (defn ~name-symbol ~params ~(cons 'do `~code))
       (new-dbfn '~name-symbol '~params '~(cons 'do `~code)))))

;; NOTE: possibly '[~@params] ?




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Example:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; (defdbfn f0 [x y]
;;  (+ x (inc y)))

;; Macro expansion:
;;
;; (let*
;;  [fn-name-keyword (clojure.core/keyword 'f0)]
;;  (if (clojure.core/some #{fn-name-keyword}
;;        (clojure.core/map :db/ident (datomodel.db.fn/get-fnbase)))
;;    (throw
;;      (datomodel.util/exception
;;        (clojure.core/str
;;          "Refusing to overwrite the already existing database function "
;;          fn-name-keyword ".")))
;;    (datomodel.util/returning 'f0
;;      (clojure.core/defn f0 [x y] (do (+ x (inc y))))
;;      (datomodel.db.fn/new-dbfn 'f0 '[x y] '(do (+ x (inc y)))))))
;;

;; Result:
;;
;; datomodel.user> (fn/all-dbfn)
;;
;; => [{:db/id #db/id[:db.part/db -1000159],
;;      :db/ident :f0
;;      :dt/dt :fn,
;;      :db/fn #db/fn{:code "(do (+ x (inc y)))",
;;                    :params [x y],
;;                    :requires [],
;;                    :imports [],
;;                    :lang :clojure}}]


;; (defdbfn f1 [x y]
;;   (+ x (inc y)))


;; (defdbfn f1 [x y]
;;   (+ x (dec y)))


;; Result: Only the most recent definition returned:
;;
;; datomodel.user> (fn/all-dbfn)
;;
;; => ({:db/id #db/id[:db.part/db -1001011],
;;      :db/ident :f0,
;;      :dt/dt :fn,
;;      :db/fn #db/fn{:code "(do (+ x (inc y)))",
;;                    :params [x y],
;;                    :requires [],
;;                    :imports [],
;;                    :lang :clojure}}
;;     {:db/id #db/id[:db.part/db -1001013],
;;      :db/ident :f1,
;;      :dt/dt :fn,
;;      :db/fn #db/fn{:code "(do (+ x (dec y)))",
;;                    :params [x y],
;;                    :requires [],
;;                    :imports [],
;;                    :lang :clojure}})
