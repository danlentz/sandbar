(ns sandbar.util.jsonrpc-status
  "Named JSON-RPC/MCP error-code constants.
   Use semantic names such as internal-error and invalid-params instead of
   opaque integer literals in adapters. JSON-RPC reserves -32700 for parse
   error, -32600 invalid request, -32601 missing method, -32602 invalid
   parameters and -32603 internal error; server-error? tests its server-error
   range. See https://www.jsonrpc.org/specification#error_object.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pre-defined error codes (JSON-RPC 2.0 §5.1)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def parse-error      -32700) ; Invalid JSON received by the server
(def invalid-request  -32600) ; JSON sent is not a valid Request object
(def method-not-found -32601) ; The method does not exist / is not available
(def invalid-params   -32602) ; Invalid method parameter(s)
(def internal-error   -32603) ; Internal JSON-RPC error

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server error range bounds (implementation-defined)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server-error-min -32099) ; Inclusive lower bound of server-error range
(def server-error-max -32000) ; Inclusive upper bound of server-error range

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reserved?
  "True if `code` is in the JSON-RPC 2.0 reserved range
   (`-32768` to `-32000` inclusive per the spec).  Reserved codes are
   pre-defined or server-error use only; application-defined errors
   must use codes outside this range."
  [code]
  (and (integer? code) (<= -32768 code -32000)))

(defn server-error?
  "True if `code` is in the implementation-defined server-error range
   (`-32099` to `-32000` inclusive)."
  [code]
  (and (integer? code) (<= server-error-min code server-error-max)))

(defn pre-defined?
  "True if `code` is one of the five JSON-RPC 2.0 pre-defined error
   codes (`parse-error`, `invalid-request`, `method-not-found`,
   `invalid-params`, `internal-error`)."
  [code]
  (contains? #{parse-error invalid-request method-not-found invalid-params internal-error}
             code))
