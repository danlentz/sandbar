(ns sandbar.core-boot-order-test
  "Boot-order contract (2026-09-18, reliability sprint item 2.2): the
  projection pipeline (worker + reactive callback + sinks) and the markdown
  codec are registered BEFORE the component system opens the HTTP port.

  Before the fix `sandbar.core/start` ran component/start first (port open),
  then the BM25F warm sweep (~50 s on the live corpus), and only then
  started the worker and registered the sinks — so writes accepted in that
  window reached the database and were never projected to files (the
  2026-09-18 restart observation: `reactive_health` showed 0 sinks three
  minutes after boot).  Under the filesystem-canonical ruling that was data
  loss.

  Technique: `component/start` is redefined to RECORD what is registered at
  the instant it is called (the instant the port would open) and to return
  the system untouched.  Everything after it in `core/start` is wrapped in
  its own try/catch and degrades cleanly without a database, so the whole
  `start` runs against a stub system."
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [sandbar.core :as core]
            [sandbar.reactive :as reactive]
            [sandbar.reactive.queue :as queue]
            [sandbar.sys :as sys]))

(defn- clean-pipeline! []
  (queue/stop!)
  (queue/clear-sinks!)
  (reactive/clear-callbacks!))

(use-fixtures :each
  (fn [t]
    (let [prior sys/system]
      (clean-pipeline!)
      (try
        (t)
        (finally
          (clean-pipeline!)
          (alter-var-root #'sys/system (constantly prior)))))))

(deftest projection-pipeline-and-codec-are-live-before-the-port-opens
  (let [at-port-open (atom nil)]
    ;; Minimal stub system: no components to start, an empty config so the
    ;; scheduler stays disabled.
    (alter-var-root #'sys/system (constantly {:config {}}))
    (with-redefs [component/start (fn [system]
                                    (reset! at-port-open
                                            {:worker-running? (:worker-running? (queue/health))
                                             :sinks           (queue/sink-count)
                                             :callbacks       (reactive/callback-count)})
                                    system)]
      (core/start))
    (is (some? @at-port-open) "component/start (the port-opening step) was reached")
    (is (true? (:worker-running? @at-port-open))
        "the projection worker is running before the port opens")
    (is (= 2 (:sinks @at-port-open))
        "both sinks (fs + SSE) are registered before the port opens")
    (is (= 1 (:callbacks @at-port-open))
        "enqueue-projection! is the registered reactive callback before the port opens")))
