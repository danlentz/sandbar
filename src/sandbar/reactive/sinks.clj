(ns sandbar.reactive.sinks
  "Reactive-projection sinks — Stage B.1+ of the SSE-reactive-corpus-
   projection arc.

   ## What this exists for

   The reactive-projection pipeline (Stages A.5 + A.6) provides the
   hook + opt-out + bounded queue + worker.  This namespace ships the
   actual SINKS that get registered with `sandbar.reactive.queue/register-sink!`
   so the worker invokes them per drained task:

   - `fs-projection-sink`  — codec.emit → atomic fs write at
                              `:mm.memory/rel-path` under the corpus root
                              (closes gap #2 — entity.create :format :markdown
                              one-way ingest)
   - `sse-emit-sink`       — invokes `resources/entity-updated!` so
                              subscribed MCP clients receive notifications

   Both sinks ship structured logging per the `:REACTIVE/<event-name>`
   vocabulary (decision eid 17592186094433).

   ## Decision lineage

   - `plans/sse_reactive_corpus_projection_arc_2026_05_23.md`
     (eid 17592186094359) — parent arc; Stage B.1 = forward direction
     DB→FS reactive projection; Stage B.3 = SSE event emit
   - `decisions/reactive_projection_hook_at_dt_make_boundary_with_opt_out_2026_05_23.md`
     (eid 17592186094347) — hook attaches at dt/* substrate primitive
   - `decisions/sandbar_project_graph_boundary_layer_primitive_per_anderson_de_setf_resource_2026_05_12.md`
     (eid 17592186047059) — codec.emit is the boundary primitive these
     sinks compose with

   ## Corpus-root resolution

   Reads `SANDBAR_CORPUS_ROOT` env-var; falls back to
   `$HOME/claude` (matching `bin/sandbar`'s start auto-init logic).
   Override per-process via env-var; future evolution could route
   through config/registry."
  (:require [clojure.java.io       :as io]
            [clojure.tools.logging :as log]
            [sandbar.codec         :as codec]
            [sandbar.db.datatype   :as dt]
            [sandbar.mcp.resources :as resources]
            [sandbar.projection    :as pg]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Corpus-root resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn corpus-root
  "Resolve the corpus filesystem root.  Reads SANDBAR_CORPUS_ROOT env-var;
   falls back to $HOME/claude.  Matches `bin/sandbar`'s start auto-init
   convention."
  []
  (or (System/getenv "SANDBAR_CORPUS_ROOT")
      (str (System/getProperty "user.home") "/claude")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FS-projection sink — codec.emit + atomic fs write
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- atomic-write!
  "Write `content` to `target-path` atomically (write to .tmp; rename).
   The rename is an OS-level atomic operation on the same filesystem —
   readers either see the previous content or the new content, never a
   partial write.

   Creates parent directories if absent.

   Consults the pre-write registry guard
   (`sandbar.projection/guard-registry-critical-write!`, IP-3 call-site 1)
   BEFORE writing the `.tmp` file: if this write would strip a
   registry-critical frontmatter key from an existing on-disk file the
   guard throws a `:registry-strip-refusal` ex-info, so neither the target
   nor the `.tmp` sibling is ever written.

   The `.renameTo` boolean is CHECKED: a false return (rename failed) is
   no longer a silent no-write — it throws an `:atomic-rename-failed`
   ex-info (an ordinary IO failure, caught+warn-logged by
   `fs-projection-sink`'s catch, NOT rethrown as a fidelity refusal).

   Returns nil.  Raises on failure."
  [^String target-path ^String content]
  (let [target ^java.io.File (io/file target-path)
        parent (.getParentFile target)
        tmp    ^java.io.File (io/file (str target-path ".tmp"))]
    (when parent (.mkdirs parent))
    (pg/guard-registry-critical-write! target-path content)
    (spit tmp content)
    (when-not (.renameTo tmp target)
      (throw (ex-info "atomic write failed: File.renameTo returned false"
                      {:sandbar/error :atomic-rename-failed
                       :target-path   target-path
                       :tmp-path      (str target-path ".tmp")})))
    nil))

(defn fs-projection-sink
  "Per-drain sink: reactive forward projection (DB → FS).

   For classes with `:dt/memorial-policy :first-class` (declared on the
   class or inherited) AND a `:mm.memory/rel-path` slot value:
   - Resolves the entity's class native codec
   - Invokes `(sandbar.projection/realize-and-emit-entity entity)` which
     handles section-tree walking + emit-document via the codec mediator
   - Atomically writes the emitted markdown to `<corpus-root>/<rel-path>`
   - Emits structured logs at `:REACTIVE/fs-write` (debug, start/done) +
     `:REACTIVE/fs-write-failed` (warn, on exception) +
     `:REACTIVE/fs-write-skipped` (debug, no-action cases)

   Skips:
   - Classes with `:db-only` / `:inline` policy (or policy-undeclared,
     conservatively treated as `:db-only` per Stage B.3 MVP)
   - Entities without `:mm.memory/rel-path`

   Failure semantics: per-entity try/catch.  A failed write doesn't
   propagate; the entity stays drained (will re-enqueue on next
   mutation).  Stage D will refine retry semantics.

   ONE exception to the swallow: a registry-strip refusal from the
   pre-write guard (`:sandbar/error :registry-strip-refusal`) is
   error-logged `:REACTIVE/registry-strip-refused` and RETHROWN, so it
   escapes to `dispatch-sinks!` → increments `:sink-error-total` +
   `:REACTIVE/sink-failed` + `:REACTIVE/projection-partial` and becomes
   visible in `sandbar_reactive_health` (a swallowed refusal would leave
   the counter at 0 and the drain logging `projection-success`).  Ordinary
   IO failures — including a false `.renameTo` (`:atomic-rename-failed`) —
   keep today's warn+swallow (widening that is Stage-D territory, out of
   the S2 scope, per AP-S2-4)."
  [eid post-tx-slots]
  (let [ident       (:db/ident post-tx-slots)
        class-ident (:dt/type post-tx-slots)
        rel-path    (:mm.memory/rel-path post-tx-slots)
        ;; Stage B.3 of first-class-memorialization arc — consult
        ;; :dt/memorial-policy via the dt/* substrate primitive
        ;; (composes with dt/ancestors-of).  Per
        ;; interaction/build_on_type_system_reflectively_and_prospectively_dont_reinvent_in_parallel_due_to_tactical_concerns_2026_05_23.md
        ;; this lookup lives at the dt/* layer rather than as a private
        ;; hierarchy-walking helper here.  Policy-undeclared classes
        ;; are conservatively treated as :db-only (skip projection)
        ;; until Stage G makes nil a class-registration loud-fail.
        policy      (when class-ident
                      (try (dt/effective-memorial-policy-of class-ident)
                           (catch Throwable _ nil)))]
    (cond
      (not= policy :first-class)
      (log/debug :REACTIVE/fs-write-skipped
                 {:ident ident :eid eid :class class-ident
                  :reason :memorial-policy-not-first-class
                  :policy (or policy :undeclared)})

      (nil? rel-path)
      (log/debug :REACTIVE/fs-write-skipped
                 {:ident ident :eid eid :class class-ident :reason :no-rel-path})

      :else
      (try
        (let [start-ms (System/currentTimeMillis)
              ;; realize-and-emit-entity handles section-tree walking +
              ;; codec selection via :dt/native-codec
              content  (pg/realize-and-emit-entity post-tx-slots)]
          (if (nil? content)
            (log/debug :REACTIVE/fs-write-skipped
                       {:ident ident :eid eid :class class-ident :reason :no-native-codec})
            (let [target-path (str (corpus-root) "/memory/" rel-path)
                  _           (log/debug :REACTIVE/fs-write
                                         {:ident ident :eid eid :class class-ident
                                          :rel-path rel-path :phase :start})
                  _           (atomic-write! target-path content)
                  done-ms     (- (System/currentTimeMillis) start-ms)
                  bytes       (count content)]
              (log/info :REACTIVE/fs-write-done
                        {:ident ident :eid eid :class class-ident :rel-path rel-path
                         :duration-ms done-ms :bytes bytes}))))
        (catch Throwable t
          ;; Companion rethrow: a registry-strip refusal must ESCAPE the
          ;; sink so `dispatch-sinks!` increments :sink-error-total and the
          ;; refusal is visible in sandbar_reactive_health.  Everything
          ;; else (ordinary IO, :atomic-rename-failed, emit-path throws)
          ;; keeps today's warn+swallow.
          (if (= :registry-strip-refusal (:sandbar/error (ex-data t)))
            (do (log/error t :REACTIVE/registry-strip-refused
                           {:ident ident :eid eid :class class-ident :rel-path rel-path
                            :error (.getMessage t) :ex-data (ex-data t)})
                (throw t))
            (log/warn t :REACTIVE/fs-write-failed
                      {:ident ident :eid eid :class class-ident :rel-path rel-path
                       :error (.getMessage t)})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SSE-emit sink — fire MCP resource-update notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sse-emit-sink
  "Per-drain sink: emit MCP resource-update notification.

   Invokes `sandbar.mcp.resources/entity-updated!` which routes the
   notification per F-S-001 resolution semantics (subscriber-id-targeted
   when bound; broadcast for ::broadcast subscribers).

   Per `decisions/reactive_projection_structured_logging_required_...` §2.1:
   - `:REACTIVE/sse-emit`        (debug)
   - `:REACTIVE/sse-emit-failed` (warn) on exception

   No-op when no subscribers exist for the entity's URI (the
   `entity-updated!` body handles the no-subscriber branch internally).

   Failure semantics: per-entity try/catch.  A failed emit doesn't
   propagate; future mutations re-trigger."
  [eid post-tx-slots]
  (let [ident       (:db/ident post-tx-slots)
        class-ident (:dt/type post-tx-slots)]
    (try
      (let [start-ms (System/currentTimeMillis)
            ;; entity-updated! takes a Datomic Entity (or entity-shaped map);
            ;; uses (entity->uri ...) internally to derive the URI
            _ (resources/entity-updated! post-tx-slots)
            done-ms (- (System/currentTimeMillis) start-ms)]
        (log/debug :REACTIVE/sse-emit
                   {:ident ident :eid eid :class class-ident
                    :duration-ms done-ms}))
      (catch Throwable t
        (log/warn t :REACTIVE/sse-emit-failed
                  {:ident ident :eid eid :class class-ident
                   :error (.getMessage t)})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience: register both sinks at startup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-all!
  "Register both `fs-projection-sink` + `sse-emit-sink` with the queue.

   Called from `sandbar.core/start` after `sandbar.reactive.queue/start!`
   completes.

   Idempotent: clears prior registrations first to avoid duplicate sinks
   on hot-reload of this namespace.

   Returns nil."
  []
  (let [q (requiring-resolve 'sandbar.reactive.queue/clear-sinks!)
        r (requiring-resolve 'sandbar.reactive.queue/register-sink!)]
    (when q (q))
    (when r
      (r fs-projection-sink)
      (r sse-emit-sink))
    (log/info :REACTIVE/sinks-registered
              {:sinks [:fs-projection-sink :sse-emit-sink]
               :corpus-root (corpus-root)}))
  nil)
