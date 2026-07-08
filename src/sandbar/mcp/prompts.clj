(ns sandbar.mcp.prompts
  "MCP `prompts/list` + `prompts/get` handlers — exposes Sandbar's
   workflow definitions as MCP prompts.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.6:
   - Each `workflow/define-workflow!` definition is queryable as a named
     MCP prompt
   - Clients receive workflow spec (states + transitions + initial +
     terminals) as structured guidance
   - Self-documenting: the workflow IS its own MCP prompt; no parallel
     registry

   Discipline per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
   uses `dt/all-named-instances-of :mm/Workflow` + the
   `sandbar.util.workflow/*` abstraction (find-workflow + get-workflow-
   states + get-workflow-transitions + get-initial-state + get-terminal-
   states) — NEVER raw datomic.api.

   Stage C.6:
   - prompts/list walks dt/all-named-instances-of :mm/Workflow
   - prompts/get returns workflow specification structured for MCP

   Subsequent stages:
   - C.6.1 Workflow argument schemas (each prompt has parameters per
     :mm/Workflow's input slots)
   - C.6.2 Workflow output projection (terminal-state outputs surfaced
     as the prompt's expected response shape)"
  (:require [clojure.string         :as str]
            [clojure.tools.logging  :as log]
            [sandbar.db.datatype    :as dt]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]
            [sandbar.util.workflow  :as workflow]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prompt naming convention
;;
;; Per B.1.6: prompts are named after workflows.
;;   :validation/resource → "sandbar.workflow.validation.resource"

(defn workflow-ident->prompt-name
  "Convert a workflow's :db/ident to a prompt name.
   `:validation/resource` → `\"sandbar.workflow.validation.resource\"`.
   `:mm/Memory.export`    → `\"sandbar.workflow.mm.Memory.export\"`.

   Sandbar workflow idents follow a single-segment-namespace convention:
   the namespace part contains no dots.  The name part MAY contain
   dots, supporting class-namespaced workflows like :mm/Memory.export
   where the name encodes a Class.Verb pair.  The encoding pairs with
   first-dot decoding in `prompt-name->workflow-ident`."
  [ident]
  {:pre [(some? (namespace ident))
         (not (str/includes? (namespace ident) "."))]}
  (str "sandbar.workflow." (namespace ident) "." (name ident)))

(defn prompt-name->workflow-ident
  "Inverse of workflow-ident->prompt-name. Returns nil if name doesn't
   match the convention.

   Splits on the FIRST dot after the `sandbar.workflow.` prefix —
   matches the encoder's single-segment-namespace invariant.  Any
   trailing dots are preserved in the name part (so :mm/Memory.export
   round-trips losslessly, unlike a last-dot split which would
   misparse it as :mm.Memory/export)."
  [prompt-name]
  (when (str/starts-with? prompt-name "sandbar.workflow.")
    (let [suffix (subs prompt-name (count "sandbar.workflow."))
          dot    (str/index-of suffix ".")]
      (when (and dot (pos? dot))
        (keyword (subs suffix 0 dot) (subs suffix (inc dot)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Wire-name projection (dots→underscores) — mirrors the tool-name rename so
;; prompt names also pass the Anthropic API name pattern ^[a-zA-Z0-9_-]{1,64}$
;; for every client (per the 2026-07-04 underscore ruling, which covers
;; tool/prompt names).  prompts/list advertises ONLY the underscore names;
;; prompts/get accepts BOTH the underscore name and the deprecated dotted alias
;; for one release.  The INTERNAL encoder/decoder above stay dotted (the
;; substrate identity), so this is a pure boundary projection.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn prompt-wire-name
  "Project an internal dotted prompt name (\"sandbar.workflow.validation.resource\")
   to its MCP WIRE name (\"sandbar_workflow_validation_resource\").  ALL dots
   collapse to underscores — including any inside a Class.Verb workflow
   name-part — so the wire form is always dot-free and pattern-conformant; the
   inverse is resolved by ENUMERATION (see `prompt-name->workflow-ident*`) so it
   stays correct even when the workflow name-part itself contained dots."
  [prompt-name]
  (str/replace (str prompt-name) "." "_"))

(defn prompt-name->workflow-ident*
  "Resolve an incoming prompts/get `:name` — the NEW underscore wire form OR the
   DEPRECATED dotted form — to a workflow `:db/ident`.  Returns
   {:ident <kw-or-nil> :deprecated? <bool>}.  The dotted form uses the direct
   decoder; the underscore form is resolved by enumerating the live `:mm/Workflow`
   set and matching the wire projection (correct for any name-part), so no
   lossy underscore→dot string-split is needed.  A non-matching name yields nil."
  [prompt-name]
  (let [s (str prompt-name)]
    (cond
      (str/starts-with? s "sandbar.workflow.")
      {:ident (prompt-name->workflow-ident s) :deprecated? true}

      (str/starts-with? s "sandbar_workflow_")
      {:ident (some (fn [wf]
                      (let [ident (:db/ident wf)]
                        (when (= s (prompt-wire-name (workflow-ident->prompt-name ident)))
                          ident)))
                    (dt/named-entities-of :mm/Workflow))
       :deprecated? false}

      :else {:ident nil :deprecated? false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow → MCP prompt description

(defn- workflow->prompt-description
  "Build the MCP prompt description map for a single workflow definition.

   Used in prompts/list responses. Each workflow's name + description +
   the structural metadata (state count, transition count) helps Claude
   choose which prompt to fetch."
  [workflow-def]
  (let [ident       (:db/ident workflow-def)
        states      (workflow/get-workflow-states workflow-def)
        transitions (workflow/get-workflow-transitions workflow-def)
        initial     (workflow/get-initial-state workflow-def)
        terminals   (workflow/get-terminal-states workflow-def)]
    {:name        (prompt-wire-name (workflow-ident->prompt-name ident))
     :title       (or (:dt/name workflow-def)
                      (str (name ident) " workflow"))
     :description (or (:dt/description workflow-def)
                      (str "Sandbar workflow: " ident
                           " — " (count states) " states, "
                           (count transitions) " transitions, "
                           "initial: " (:db/ident initial) ", "
                           (count terminals) " terminal state(s)"))
     :arguments   []})) ;; Stage C.6.1 derives from workflow input slots

(defn all-workflow-prompts
  "Walk every named `:mm/Workflow` entity + emit MCP prompt
   descriptions.

   Uses `dt/named-entities-of` (returns entity maps, per Q1=B Stage A
   helpers) — `workflow->prompt-description` reads `:db/ident`,
   `:dt/name`, `:dt/description`, etc. off each entity.  The prior
   `dt/all-named-instances-of` returned idents, so the description
   helper silently produced nil-everywhere descriptions (codex
   MUST-FIX #2 at prompts.clj:86)."
  []
  (->> (dt/named-entities-of :mm/Workflow)
       (map workflow->prompt-description)
       (sort-by :name)
       vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; prompts/list handler

(defn handle-list
  "MCP `prompts/list` — returns all defined Sandbar workflows as MCP
   prompts. Stage C.6 returns only workflow-derived prompts;
   subsequent stages can add cross-cutting prompts (e.g., domain-
   specific guidance not tied to a workflow)."
  [id _params]
  (try
    {:jsonrpc "2.0"
     :id      id
     :result  {:prompts (all-workflow-prompts)}}
    (catch Exception e
      (log/error e :MCP/prompts-list-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/internal-error
                 :message "Prompts list failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; prompts/get handler
;;
;; Per MCP spec: prompts/get returns the prompt's text (template) + any
;; binding values. For Sandbar workflows, the prompt content is a
;; structured description of the workflow's state machine — Claude reads
;; it to understand which tool-calls compose for a multi-step operation.

(defn- format-workflow-as-prompt-text
  "Render a workflow definition as a markdown-shaped text suitable for
   MCP prompt consumption. Includes the workflow's structural shape +
   how to invoke it via tool-calls."
  [workflow-def]
  (let [ident       (:db/ident workflow-def)
        states      (workflow/get-workflow-states workflow-def)
        transitions (workflow/get-workflow-transitions workflow-def)
        initial     (workflow/get-initial-state workflow-def)
        terminals   (workflow/get-terminal-states workflow-def)]
    (str "# Workflow: " ident "\n\n"
         (when-let [doc (:dt/description workflow-def)]
           (str doc "\n\n"))
         "## States (" (count states) ")\n\n"
         (str/join "\n" (for [s states]
                          (let [s-ident (:db/ident s)]
                            (str "- `" s-ident "`"
                                 (cond
                                   (= s-ident (:db/ident initial)) " (initial)"
                                   (some #(= s-ident (:db/ident %)) terminals) " (terminal)"
                                   :else "")))))
         "\n\n"
         "## Transitions (" (count transitions) ")\n\n"
         (str/join "\n" (for [t transitions]
                          (str "- `" (:db/ident t) "`")))
         "\n\n"
         "## Invocation\n\n"
         "To start a process running this workflow:\n\n"
         "```\n"
         "sandbar.workflow.start-process tool with:\n"
         "  workflow: " ident "\n"
         "  subject: <entity-uri-to-track>\n"
         "  data: { ... initial process data ... }\n"
         "```\n\n"
         "To advance the process:\n\n"
         "```\n"
         "sandbar.workflow.transition tool with:\n"
         "  process-id: <returned-from-start-process>\n"
         "  transition: <one of the transitions above>\n"
         "```\n")))

(defn handle-get
  "MCP `prompts/get` — returns the prompt content for a named workflow.
   Stage C.6 returns the workflow's structural shape as a markdown
   description; subsequent stages can add binding-specific arguments."
  [id params]
  (try
    (let [prompt-name      (:name params)
          {:keys [ident deprecated?]} (prompt-name->workflow-ident* prompt-name)
          workflow-ident   ident]
      ;; One-release dotted-alias: the deprecated dotted prompt name still
      ;; resolves, but WARN so callers migrate to the underscore wire name.
      (when (and deprecated? workflow-ident)
        (log/warn :MCP/deprecated-dotted-prompt-name
                  {:received prompt-name
                   :use      (prompt-wire-name prompt-name)
                   :note     "sandbar MCP prompt names are now underscore-form; the dotted alias is deprecated and will be removed after one release"}))
      (cond
        (nil? workflow-ident)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    jsonrpc-status/invalid-params
                   :message (str "Invalid prompt name: " prompt-name)
                   :data    {:received-name prompt-name}}}

        :else
        (if-let [workflow-def (workflow/find-workflow workflow-ident)]
          (let [content (format-workflow-as-prompt-text workflow-def)]
            {:jsonrpc "2.0"
             :id      id
             :result  {:description (or (:dt/description workflow-def)
                                        (str "Workflow " workflow-ident))
                       :messages    [{:role    "user"
                                      :content {:type "text"
                                                :text content}}]}})
          {:jsonrpc "2.0"
           :id      id
           :error   {:code    jsonrpc-status/invalid-params
                     :message (str "Workflow not found: " workflow-ident)}})))
    (catch Exception e
      (log/error e :MCP/prompts-get-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/internal-error
                 :message "Prompt get failed"
                 :data    {:exception-message (.getMessage e)}}})))
