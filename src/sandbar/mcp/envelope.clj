(ns sandbar.mcp.envelope
  "JSON-RPC 2.0 envelope construction and validation.

   This leaf namespace has no dependencies inside sandbar.mcp, allowing
   protocol dispatch and notification delivery to share envelopes without
   a require cycle.

   Standard error codes: -32700 parse error, -32600 invalid request,
   -32601 method not found, -32602 invalid params, -32603 internal error;
   -32000 through -32099 are implementation-defined server errors.
   Specification: https://www.jsonrpc.org/specification")

(defn jsonrpc-result
  "Construct a JSON-RPC 2.0 success response."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn jsonrpc-error
  "Construct a JSON-RPC 2.0 error response."
  [id code message & [data]]
  {:jsonrpc "2.0"
   :id      id
   :error   (cond-> {:code    code
                     :message message}
              data (assoc :data data))})

(defn jsonrpc-notification
  "Construct a JSON-RPC 2.0 notification (no id; server → client push)."
  [method params]
  {:jsonrpc "2.0"
   :method  method
   :params  params})

(defn valid-envelope?
  "Quick structural validation of an inbound JSON-RPC 2.0 message.
   Requires `:jsonrpc` field equal to '2.0' and either `:method`
   (request or notification) or `:result`/`:error` (response).
   Returns boolean."
  [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (or (contains? msg :method)
           (contains? msg :result)
           (contains? msg :error))))
