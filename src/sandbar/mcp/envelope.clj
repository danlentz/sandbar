(ns sandbar.mcp.envelope
  "JSON-RPC 2.0 envelope construction + validation.

   Leaf namespace — depends on nothing inside `sandbar.mcp.*`.
   Lives below `sandbar.mcp.protocol` and `sandbar.mcp.notifications`
   in the require graph so both can construct + inspect envelopes
   without inducing a cycle (per the F-M-001 cycle-break correction
   from `audit-results/codex_sandbar_as_mcp_server_2026_05_12.md`
   + `memory/decisions/sandbar_mcp_tool_surface_resolution_operational_verb_catalog_per_adr_b13_2026_05_12.md`).

   JSON-RPC 2.0 specification: https://www.jsonrpc.org/specification

   Standard error codes:
   -32700 Parse error
   -32600 Invalid Request
   -32601 Method not found
   -32602 Invalid params
   -32603 Internal error
   -32000 to -32099 Server error (implementation-defined)")

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
