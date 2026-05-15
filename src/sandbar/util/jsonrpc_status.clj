(ns sandbar.util.jsonrpc-status
  "Semantic named constants for JSON-RPC 2.0 + MCP error codes.

   Parallel to `sandbar.util.http-status`.  Eliminates opaque
   negative-integer literals (`-32603`, `-32602`, etc.) at MCP
   boundary surfaces in favor of named constants like
   `jsonrpc-status/internal-error` and `jsonrpc-status/invalid-params`.

   Per `decisions/sandbar_jsonrpc_status_semantic_constants_namespace_2026_05_14.md`.

   ## JSON-RPC 2.0 reserved error code range

   Per JSON-RPC 2.0 §5.1 (https://www.jsonrpc.org/specification#error_object):

   - `-32700` Parse error          — invalid JSON received by the server
   - `-32600` Invalid Request      — JSON sent is not a valid Request object
   - `-32601` Method not found     — the method does not exist / is not available
   - `-32602` Invalid params       — invalid method parameter(s)
   - `-32603` Internal error       — internal JSON-RPC error
   - `-32099` to `-32000`          — server error (implementation-defined range)
   - `0` and positive              — application-defined errors

   ## Usage

     (require '[sandbar.util.jsonrpc-status :as jsonrpc-status])

     (envelope/jsonrpc-error id jsonrpc-status/internal-error
                             \"Tool execution failed\"
                             {:tool tool-name})

     (when (server-error? code)
       ...)")

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
