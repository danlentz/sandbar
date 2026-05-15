(ns sandbar.mcp.namespace-load-smoke-test
  "Minimum-viable release-gate smoke per F-M-005 (codex review finding) —
   `every sandbar.mcp.* namespace loads cleanly`.

   Per observations/mcp_test_surface_defers_stateful_release_risks_2026_05_12.md:
   the systemic gap that let F-M-001 (cyclic require + nonexistent var)
   slip to the release gate was that no test invoked any `sandbar.mcp.*`
   namespace at all.  A 5-second `lein test :only ...` of this namespace
   catches that class of regression before deeper DB-backed tests run.

   Per ideas/protocol_layer_needs_namespace_load_and_black_box_gate_2026_05_12.md
   (F-I-003): this smoke is the minimum-viable form; the broader black-box
   gate (DB-backed dispatch + transport/SSE conformance) lands at codec arc
   Stage H.2 of plans/sandbar_codec_layer_arc_2026-05-12.md."
  (:require [clojure.test :refer :all]))

(def required-mcp-namespaces
  "Every sandbar.mcp.* namespace + adjacent substrate namespaces the
   protocol layer depends on at runtime.  Adding a new namespace here
   when one is introduced is part of the F-M-005 substage acceptance
   discipline.

   Extended at fulltext arc (2026-05-14) to cover the comprehensive
   four-axis retrieval surface namespaces — these are public-API
   namespaces that ship at 0.1.0; a compile failure here is a
   release blocker."
  '[;; Codec layer (Stage F.x of codec arc)
    sandbar.codec
    sandbar.codec.json
    sandbar.codec.markdown
    sandbar.codec.protocol

    ;; Projection (Anderson boundary-layer primitive; renamed from project-graph 2026-05-14)
    sandbar.projection

    ;; Four-axis retrieval surface (fulltext arc, 2026-05-13/14)
    sandbar.search
    sandbar.search.analysis
    sandbar.search.bm25f
    sandbar.aggregate
    sandbar.navigate.edges
    sandbar.navigate.walk
    sandbar.navigate.siblings
    sandbar.navigate.path
    sandbar.navigate.path.ast
    sandbar.navigate.path.ir
    sandbar.navigate.path.datomic
    sandbar.navigate.path.value
    sandbar.orient

    ;; REST API namespaces for the new retrieval surface
    sandbar.api.aggregate
    sandbar.api.navigate
    sandbar.api.orient

    ;; MCP server
    sandbar.mcp.auth
    sandbar.mcp.envelope
    sandbar.mcp.notifications
    sandbar.mcp.prompts
    sandbar.mcp.protocol
    sandbar.mcp.resources
    sandbar.mcp.tasks
    sandbar.mcp.tools
    sandbar.mcp.transport])

(deftest every-mcp-namespace-loads-cleanly
  (testing "Each sandbar.mcp.* namespace can be required without compile error"
    (doseq [ns-sym required-mcp-namespaces]
      (testing (str ns-sym)
        (is (try
              (require ns-sym)
              (some? (find-ns ns-sym))
              (catch Throwable t
                (println "FAILED to load" ns-sym "—" (.getMessage t))
                false))
            (str "Namespace " ns-sym " must load cleanly — compile errors or "
                 "cycles caught here block release per F-M-005 / F-I-003."))))))

(deftest dispatch-table-references-every-handler-namespace
  (testing "protocol/method-handlers binds handlers from every sub-namespace"
    (require 'sandbar.mcp.protocol)
    (let [handlers @(resolve 'sandbar.mcp.protocol/method-handlers)
          method-set (set (keys handlers))]
      (is (contains? method-set "initialize"))
      (is (contains? method-set "tools/list"))
      (is (contains? method-set "tools/call"))
      (is (contains? method-set "resources/list"))
      (is (contains? method-set "resources/read"))
      (is (contains? method-set "resources/subscribe"))
      (is (contains? method-set "resources/unsubscribe"))
      (is (contains? method-set "prompts/list"))
      (is (contains? method-set "prompts/get"))
      (is (contains? method-set "tasks/get"))
      (is (contains? method-set "tasks/cancel")))))
