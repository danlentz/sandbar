(ns sandbar.util.http-status)

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
(def temporary-redirect  307)
(def permanent-redirect  308)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 4xx Client Error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def bad-request         400)
(def not-authorized      401)
(def payment-required    402)
(def forbidden           403)
(def not-found           404)
(def method-not-allowed  405)
(def not-acceptable      406)
(def proxy-auth-required 407)
(def request-timeout     408)
(def conflict            409)
(def gone                410)
(def length-required     411)
(def precondition-failed 412)
(def too-large           413)
(def uri-too-long        414)
(def unsupported-media-type 415)
(def range-not-satisfiable 416)
(def expectation-failed  417)
(def im-a-teapot         418)
(def misdirected-request 421)
(def unprocessable-entity 422)
(def locked              423)
(def failed-dependency   424)
(def upgrade-required    426)
(def precondition-required 428)
(def too-many-requests   429)
(def header-fields-too-large 431)
(def unavailable-for-legal 451)

;; Backwards compatibility alias
(def split-failed        unprocessable-entity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 5xx Server Error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server-error        500)
(def not-implemented     501)
(def bad-gateway         502)
(def not-available       503)
(def gateway-timeout     504)
(def http-version-not-supported 505)
(def insufficient-storage 507)
(def loop-detected       508)
(def network-auth-required 511)
