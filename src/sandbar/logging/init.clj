(ns sandbar.logging.init
  "Canonical Telemere reconfiguration site for sandbar.

   `start!` runs BEFORE any other startup work in `sandbar.core/start`
   so callsites produce output instead of dispatching to a no-handler
   void.

   ## Handler design (Phase 1 easy-wins; per Dan-directive 2026-05-23)

   - **`:default/console` retained** — Telemere's kiwi formatter (verbose
     but informative) writes to *out*, captured by `bin/sandbar`'s
     nohup redirect into `.sandbar/sandbar.log`.
   - **`:sandbar/file` added** — same kiwi format mirrored into
     `.sandbar/logs/sandbar.log` (rolling+gzip; machine-parseable
     longitudinal archive).
   - **Middleware `:xfn` installed** — `sandbar.logging.format/middleware`
     strips the noisy `ctx: {pid X}` footer from every line AND drops
     Datomic `:MetricsReport` periodic floods.  Other Datomic events
     pass through.

   Phase 2 (custom formatter with source-attribution + shorter timestamp
   + content curation) is deferred per the
   memory/authorizations/logging_curation_sequence_easy_wins_then_deep_telemere_study_then_arc_alignment_dan_directive_2026_05_23.md
   sequence."
  (:require [clojure.tools.logging   :as log]
            [sandbar.logging.config  :as logging-config]
            [sandbar.logging.format  :as fmt]
            [sandbar.logging.handlers :as handlers]
            [taoensso.telemere       :as tel]))

(defn log-file-path
  "Resolve the durable structured-log file path for the `:sandbar/file`
   handler.  Distinct from bin/sandbar's stdout-redirect file.

   Reads `SANDBAR_LOG_FILE` env-var; falls back to
   `<SANDBAR_CLIENT_DIR or $HOME/claude>/.sandbar/logs/sandbar.log`."
  []
  (or (System/getenv "SANDBAR_LOG_FILE")
      (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                           (str (System/getProperty "user.home") "/claude"))]
        (str client-dir "/.sandbar/logs/sandbar.log"))))

(defn- short-formatter
  "Build a Telemere `format-signal-fn` that uses our short
   `HH:mm:ss.SSS` timestamp instead of the verbose full-ISO default."
  []
  (tel/format-signal-fn
    {:preamble-fn
     ((requiring-resolve 'taoensso.telemere.utils/signal-preamble-fn)
      {:format-inst-fn fmt/short-inst-fn})}))

(defn install-handlers!
  "Install sandbar's canonical Telemere handler set.  Idempotent.

   Three handlers:
   - `:sandbar/console`             — formatted to *out* (stdout-captured
                                      to .sandbar/sandbar.log)
   - `:sandbar/file`                — rolling+gzip durable archive
   - `:sandbar/memorial-projection` — Stage D handler bridging signals
                                      flagged `:memorial <policy>` into
                                      the substrate as `:mm/EventLog`
                                      (`:first-class`) or
                                      `:event/SystemEvent` (`:db-only`)
                                      memorial entities"
  []
  (doseq [hid [:default/console :sandbar/console :sandbar/file
               :sandbar/memorial-projection]]
    (try (tel/remove-handler! hid) (catch Throwable _ nil)))
  (let [out-fn (short-formatter)]
    (tel/add-handler! :sandbar/console
      (tel/handler:console {:output-fn out-fn})
      {:async     nil
       :min-level :info})
    (let [path (log-file-path)]
      (tel/add-handler! :sandbar/file
        (tel/handler:file
          {:path              path
           :interval          :daily
           :max-file-size     (* 4 1024 1024)
           :max-num-parts     8
           :max-num-intervals 6
           :gzip-archives?    true
           :output-fn         out-fn})
        {:async     {:mode :dropping :buffer-size 4096}
         :min-level :info})
      ;; Stage D — memorial-projection-handler
      ;; Sync dispatch (sandbar.reactive owns its own async pipeline).
      ;; Per-handler :when filter restricts to memorial-flagged signals
      ;; — the handler itself also checks; double-defense.
      (tel/add-handler! :sandbar/memorial-projection
        handlers/memorial-projection-handler
        {:async     nil
         :min-level :info})
      path)))

(defn install-noise-filter!
  "Install sandbar.logging.format/middleware as Telemere's :xfn.

   The middleware:
   - Drops signals carrying `:data {:MetricsReport ...}` (Datomic
     periodic flood per Dan-directive 2026-05-23).
   - Strips `:ctx` from every other signal — the kiwi formatter then
     has nothing to render for the per-line ctx-footer that was
     contributing noise without value."
  []
  (tel/set-xfn! fmt/middleware))

(defn start!
  "Configure Telemere with sandbar's standard handler set + EDN config.
   Idempotent.  Returns the resolved log file path."
  []
  (logging-config/load!)
  (install-noise-filter!)
  (let [path (install-handlers!)]
    (log/info :SANDBAR.LOGGING/STARTED
              {:log-file  path
               :handlers  (vec (keys (tel/get-handlers)))
               :substrate "telemere v1.2.1 + slf4j-telemere v1.0.0-beta21"})
    path))
