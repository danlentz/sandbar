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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; First-class :mm/Fn memorial emission — Stage D D2 of first-class :dt/Fn arc
;;
;; Per decisions/dt_fn_existing_state_reconciliation_dual_emit_defdbfn_legacy_
;; migration_2026_05_23.md — defdbfn macro dual-emits BOTH the :db/fn schema
;; entity (legacy *fn-base*) AND a :mm/Fn memorial entity carrying the Stage C
;; :dt.fn/* slot vocabulary (parameters / body / lang / purpose / purity /
;; cost-class / source-ns / source-var / version / installed-as).
;;
;; Source-of-truth discipline (gen-fn pattern; per libraries/clojure/gen_fn_
;; 2026_05_23.md): the Clojure namespace var is canonical; both schema entity
;; + memorial are projections from the same source.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:dynamic *mm-fn-memorial-base* (atom []))

(defn set-mm-fn-base
  "Destructively sets the current base of :mm/Fn memorial maps."
  [memorials]
  (reset! *mm-fn-memorial-base* memorials))

(defn all-mm-fn-memorials
  "Returns all currently-queued :mm/Fn memorial maps."
  []
  @*mm-fn-memorial-base*)

(defn clear-mm-fn-memorial-base!
  "Resets the memorial base to empty."
  []
  (set-mm-fn-base []))

(defn new-mm-fn-memorial
  "Add a :mm/Fn memorial map to *mm-fn-memorial-base* for later install.
   Returns the memorial map for caller convenience."
  [memorial-map]
  (swap! *mm-fn-memorial-base* conj memorial-map)
  memorial-map)

(defn load-all-mm-fn-memorials
  "Transact all queued :mm/Fn memorials into Datomic.  Called from
   sandbar.core/start after schema install + load-all-dbfn.
   Idempotent: re-loading the same memorials is safe (Datomic upserts
   on :db/ident)."
  [uri]
  (let [memorials (all-mm-fn-memorials)]
    (log/info :MM-FN "Loading" (count memorials) ":mm/Fn memorials")
    (when (seq memorials)
      (d/transact (d/connect uri) memorials))))

(defn- build-mm-fn-memorial
  "Construct a :mm/Fn memorial entity-map from a defdbfn declaration.

   Captures Stage C :dt.fn/* metadata from the optional attr-map between
   params and code, with sensible defaults.  The source-ns + source-var
   are captured at macro-expansion time (gen-fn pattern bridge between
   source-of-truth Clojure namespace and schema-entity + memorial-entity
   projections).

   Inputs:
     name-keyword  — :db/ident for the fn (e.g. :run-state-advance)
     ns-symbol     — namespace symbol where defdbfn was expanded
     name-symbol   — var symbol within ns
     params        — quoted param vector (e.g. '[db run-eid expected-state])
     body-form     — quoted body form (e.g. '(do ...))
     attr-map      — optional metadata map (extracted Stage C :dt.fn/* slots)"
  [name-keyword ns-symbol name-symbol params body-form attr-map]
  (let [defaults    {:dt.fn/purpose      :transform
                     :dt.fn/purity       :pure-total
                     :dt.fn/cost-class   :cheap
                     :dt.fn/installed-as :db-fn
                     :dt.fn/lang         :clojure
                     :dt.fn/status       :draft
                     :dt.fn/version      "1.0.0"}
        merged      (merge defaults attr-map)]
    {:db/ident             name-keyword
     :dt/type              :mm/Fn
     :dt.fn/source-ns      (str ns-symbol)
     :dt.fn/source-var     (str name-symbol)
     :dt.fn/body           (pr-str body-form)
     :dt.fn/lang           (:dt.fn/lang merged)
     :dt.fn/description    (or (:dt.fn/description merged) (str name-symbol))
     :dt.fn/purpose        (:dt.fn/purpose merged)
     :dt.fn/purity         (:dt.fn/purity merged)
     :dt.fn/cost-class     (:dt.fn/cost-class merged)
     :dt.fn/status         (:dt.fn/status merged)
     :dt.fn/version        (:dt.fn/version merged)
     :dt.fn/installed-as   (:dt.fn/installed-as merged)
     :mm.memory/rel-path   (str "fns/" name-symbol ".md")
     :mm.memory/name       (str name-symbol)
     :mm.memory/memory-type "fn"
     :mm.memory/scope      "project"}))

(defn new-dbfn+memorial
  "Dual-emit helper: add a :mm/Fn memorial entity (always) PLUS a Datomic
   :db/fn schema entity (CONDITIONALLY — only when :dt.fn/installed-as is
   :db-fn, the transactor-side install surface).

   For :classpath-fn / :ion / :entity-pred / :attr-pred install surfaces,
   ONLY the memorial is emitted — the actual fn impl lives in the peer
   JVM classpath (resolved via :dt.fn/source-ns + :dt.fn/source-var).

   Per Stage C ADR (SHACL arc 2026-05-23) — walker fns are :classpath-fn
   peer-side fns; substrate-mutation fns (run-state-advance) are :db-fn
   transactor-side fns.  Same macro `defdbfn` handles both via the
   :dt.fn/installed-as discriminator.

   Called from the macro-expansion; not typically called directly."
  [name-keyword ns-symbol name-symbol params body-form attr-map]
  (let [installed-as (get attr-map :dt.fn/installed-as :db-fn)]
    ;; Always: emit the :mm/Fn memorial (substrate-level record)
    (new-mm-fn-memorial
      (build-mm-fn-memorial name-keyword ns-symbol name-symbol params body-form attr-map))
    ;; Conditional: emit the Datomic :db/fn schema entity only for transactor-side fns
    (when (= installed-as :db-fn)
      (new-dbfn name-symbol params body-form))
    name-symbol))


(defmacro defdbfn
  "Defines a function normally; emits BOTH a Datomic :db/fn schema entity (legacy
   *fn-base*) AND a :mm/Fn memorial entity (*mm-fn-memorial-base*) per Stage D
   D2 of the first-class :dt/Fn integration arc 2026-05-23.

   Optional attr-map between params and body specifies Stage C metadata:
     :dt.fn/purpose      — :derive / :assert / :retract / :transform / :validate / :infer
                            (default :transform)
     :dt.fn/purity       — :pure-total / :pure-partial / :side-effecting
                            (default :pure-total)
     :dt.fn/cost-class   — :cheap / :moderate / :expensive
                            (default :cheap)
     :dt.fn/description  — human-readable description (default: stringified name)
     :dt.fn/version      — semver string (default \"1.0.0\")
     :dt.fn/installed-as — :db-fn / :classpath-fn / :ion / :entity-pred / :attr-pred
                            (default :db-fn)
     :dt.fn/status       — :draft / :validated / :installed / :retired
                            (default :draft)

   Source-of-truth is the Clojure namespace; :dt.fn/source-ns + :dt.fn/source-var
   are captured at macro-expansion time (gen-fn pattern).

   Usage (backwards-compatible):

     ;; Old form (no attr-map) — still works:
     (defdbfn my-fn [db arg]
       (do-something db arg))

     ;; New form (with Stage C metadata):
     (defdbfn run-state-advance [db run-eid expected-state new-state]
       {:dt.fn/purpose     :transform
        :dt.fn/purity      :pure-total
        :dt.fn/cost-class  :cheap
        :dt.fn/description \"Atomic compare-and-set on :run/state\"
        :dt.fn/version     \"1.0.0\"}
       (let [current-state (:run/state (datomic.api/entity db run-eid))]
         (if (= current-state expected-state)
           [[:db/add run-eid :run/state new-state]]
           (throw (ex-info \":run/state CAS failed\"
                           {:expected expected-state :actual current-state})))))"
  [name-symbol params & body]
  (let [[attr-map code] (if (and (map? (first body))
                                 (every? keyword? (keys (first body))))
                          [(first body) (rest body)]
                          [{} body])
        body-form       (cons 'do `~code)]
    ;; Capture (ns-name *ns*) AT MACRO-EXPANSION TIME — emitting the literal
    ;; ns-name symbol via '~(ns-name *ns*) so the runtime let-binding sees the
    ;; namespace where defdbfn was syntactically written (the gen-fn discipline
    ;; per decisions/dt_fn_existing_state_reconciliation_dual_emit_defdbfn_legacy_migration_2026_05_23.md).
    ;; Prior form `(ns-name *ns*)` evaluated at runtime in the caller's dynamic
    ;; context (typically `user` in test/REPL runners), which broke source-ns
    ;; capture for `defdbfn-captures-source-ns-and-var` (Wave 0 W.0.3 fix).
    `(let [~'fn-name-keyword (keyword '~name-symbol)
           ~'ns-symbol       '~(ns-name *ns*)]
       (util/returning '~name-symbol
         (defn ~name-symbol ~params ~body-form)
         (new-dbfn+memorial ~'fn-name-keyword ~'ns-symbol '~name-symbol
                            '~params '~body-form ~attr-map)))))

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

;; NOTE: post-schema-reload handler registrations for `clear-fnbase!` +
;; `clear-mm-fn-memorial-base!` live in sandbar.db.datomic (which already
;; requires this ns; adding the inverse here would create a load cycle).
;; See `sandbar.db.datomic` end-of-namespace registrations.
