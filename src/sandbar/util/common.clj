(ns sandbar.util.common
  (:require [clojure.pprint :as pp]
            [clojure.repl]
            [sandbar.util.codec]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Control Flow
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro returning
  "Compute a return value, then execute other forms for side effects.
  Like prog1 in common lisp, or a (do) that returns the first form."
  [value & forms]
  `(let [value# ~value]
     ~@forms
     value#))

(defmacro returning-bind
  "Compute a return value, bind that value to provided sym, then
  execute other forms for side effects within the lexical scope of
  that binding.  The return value of a returning-bind block will be
  the value computed by retn-form.  Similar in concept to Paul
  Graham's APROG1, or what is commonly found in CL libraries as
  PROG1-BIND.  This macro is especially handy when one needs to
  interact with stateful resources such as io.

  Example:

    (returning-bind [x (inc 41)]
      (println :returning x)
      (println 3.141592654))

  PRINTS:   :returning 42
            3.141592654
  RETURNS:  42"
  [[sym retn-form] & body]
      `(let [val# ~retn-form
             ~sym val#]
         ~@body
         val#))

(defn rmerge
  "Recursive merge of the provided maps."
  [& maps]
  (if (every? map? maps)
    (apply merge-with rmerge maps)
    (last maps)))

(defn symbolic-name-from-var
  [var]
  (clojure.string/join "/" ((juxt (comp str :ns) :name) (meta var))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debugging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro wrap-fn [name args & body]
  `(let [old-fn# (var-get (var ~name))
         new-fn# (fn [& p#]
                   (let [~args p#]
                     (do ~@body)))
         wrapper# (fn [& params#]
                    (if (= ~(count args) (count params#))
                      (apply new-fn# params#)
                      (apply old-fn# params#)))]
     (alter-var-root (var ~name) (constantly wrapper#))))

(defmacro ppmx [form]
  `(do
     (pp/cl-format *out*  ";;; Macroexpansion:~%~% ~S~%~%;;; First Step~%~%"
       '~form)
     (pp/pprint (macroexpand-1 '~form))
     (pp/cl-format *out*  "~%;;; Full expansion:~%~%")
     (pp/pprint (macroexpand '~form))
     (println "")))

(defmacro ignore-exceptions [& body]
  `(try
     ~@body
     (catch Exception e# nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn lines-of-file [file-name]
  (line-seq
    (java.io.BufferedReader.
      (java.io.InputStreamReader.
        (java.io.FileInputStream. file-name)))))









;; (defn to-byte-array [x]
;;   (let [baos (ByteArrayOutputStream.)
;;         oos (ObjectOutputStream. baos)]
;;     (pr oos x)
;;     (.close oos)
;;     (.toByteArray baos)))
