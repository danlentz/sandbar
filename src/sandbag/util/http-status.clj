(ns sandbag.util.http-status)

(defn error? [status] (>= status 400))

;; TODO: docstrings
;; TODO: -> pershing.util.http.status (!)

(def null                000) ; pseudo-status

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 1xx Informational
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def continue            100)
(def switching-protocols 101)
(def processing          102)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 2xx Success
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def success             200)
(def created             201)
(def accepted            202)
(def non-authoritative   203)
(def no-content          204)
(def reset-content       205)
(def partial-content     206)
(def multi-status        207)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 3xx Redirect
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def multiple-choices    300)
(def moved-permanantly   301)
(def found               302)
(def see-other           303)
(def not-modified        304)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 4xx Client Error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def bad-request         400)
(def not-authorized      401)
(def forbidden           403)
(def not-found           404)
(def method-not-allowed  405)
(def not-acceptable      406)
(def conflict            409)
(def gone                410)
(def too-large           413)
(def cube-error          418) ;; TODO: this is a server error :(
(def split-failed        422)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 5xx Server Error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server-error        500)
(def not-implemented     501)
(def not-available       503)
(def gateway-timeout     504)
