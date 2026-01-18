(ns sandbag.db.rules
  "Database logical query rules")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rules
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:dynamic *rule-base*   (atom []))

(defn set-rulebase
  "Sets current rule base."
  [rules]
  (reset! *rule-base* rules))

(defn all-rules
  "Gets current rule base."
  []
  @*rule-base*)

(defn clear-rulebase!
  "Resets current rulenbase to empty."
  []
  (set-rulebase []))

(defn add-rule-to-rulebase
  [rule]
  (swap! *rule-base* conj rule)
  rule)

(defn read-rulebase
  "Read all forms in f, where f is any resource that can
   be opened by io/reader."
  [f]
  (reset! *rule-base* (slurp f)))

(defn save-rulebase
  "Opens f with writer, writes current-ruleset to f, then
   closes f. Options passed to clojure.java.io/writer."
  [f & options]
  (apply spit f (all-rules) options))

(defn save-rules
  "Opens f with writer, writes rules to f, then
   closes f. Options passed to clojure.java.io/writer."
  [f rules & options]
  (apply spit f rules options))

(defn build-rule [name vars clauses]
  (apply vector
        (apply vector name vars)
        clauses))

(defn new-rule [name vars clauses]
  (add-rule-to-rulebase (build-rule name vars clauses)))

(defmacro defrule [name vars & clauses]
  `(new-rule '~name '~vars '~clauses))

(defmacro defrules [& rules]
   `(doseq [rule# '~rules]
      (println rule#)
      (if (and (or (list? rule#)
                     (vector? rule#))
                 (> (count rule#) 2))
        (new-rule (first rule#) (second rule#) (nnext rule#))
        (println (str "Rule '" rule# "' ignored, wrong syntax." )))))
