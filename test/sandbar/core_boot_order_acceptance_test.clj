(ns sandbar.core-boot-order-acceptance-test
  "Acceptance test for the production boot order (reliability sprint 2.2 and
   D1, 2026-09-18 / 2026-09-19): on the PRODUCTION system graph, at the first
   instant the HTTP port answers, the database component has started and the
   projection pipeline is live, and a real first-ready HTTP write reaches a
   real filesystem sink — on a fresh store and on a pre-initialized one.

   ## History

   `sandbar.core-boot-order-test` pins the registration ORDER inside
   `core/start` by replacing `component/start` with a recorder.  The first
   acceptance test (fleet 239e050) booted the real components but built a
   MIRROR of the system map here, with `:pedestal` declared `using
   [:datomic]` — a dependency production's `make-system` did not declare.
   Astra's trajectory review (2026-09-19T103821Z) named that gap: the test
   proved the fixture's graph, not production's.  `component/system-map` is
   insertion-ordered and `start-system` keeps that order for components with
   no declared dependency, so production started `:pedestal` BEFORE
   `:datomic`: the port answered before `initialize-db!` had loaded schema
   and before `db/**conn*` was set.  On an existing store the connection
   fallback hid it; on a fresh store the first requests failed.

   D1 closes it in production: `make-system` now declares `:pedestal` using
   `:datomic`, and takes an overrides map so THIS test boots the graph
   `make-system` builds — same component types, same declarations — on a
   free port and an in-memory store, through the unmodified `core/start`.

   ## What is real and what is substituted

   REAL: `core/make-system` (the production builder, with overrides for the
   config map, the database spec, the port and nREPL); `core/start` itself
   (logging foundation, codec registration, projection worker + callback +
   both sinks, `component/start`, the BM25F warm sweep, the scheduler gate);
   the `DatomicPeer` component and its `initialize-db!`; the `Pedestal`
   component over `create-connector-map` (the full sandbar interceptor stack
   + `service.routes/routes`) and Jetty; the whole `/mcp` chain (bearer auth,
   dispatch gate, `entity-create-handler`, `store/create-memory!`, `dt/make`
   → reactive hook → dirty-map queue → worker thread → `fs-projection-sink`
   → `atomic-write!`).

   SUBSTITUTED (seams only; nothing on the boot path is stubbed): the config
   map (`{:scheduler {:enabled? false}}`), a `datomic:mem://` spec, a free
   port, nREPL omitted (a plain nREPL server on a port; nothing on the boot
   path depends on it); `sinks/corpus-root` → a scratch directory;
   `logging-init/log-file-path` → a scratch log; the private
   `core/warm-bm25f-caches!` WRAPPED, not replaced: it records entry, blocks
   on a gate, then runs the real sweep, and the gate opens only after the
   projected file is observed — so the write is provably issued before any
   warm sweep could have finished, the post-boot window in which the
   2026-09-18 restart lost writes.

   ## What this proves / does not prove

   PROVES, on the graph production builds: `make-system` declares the
   port's dependency on the database component; at the first instant the
   port answers, `db/**conn*` is set and the schema is loaded (fresh store:
   by the component during this very boot), the markdown codec, the
   projection worker, the reactive callback and both sinks are live, and
   `start` has not returned; a real authenticated MCP write accepted at that
   instant, while the warm sweep is still held, is committed and projected
   to a corpus file by the real fs sink within the bound; `start` then
   completes with zero sink errors.

   DOES NOT PROVE: the nREPL component (omitted); the real warm-sweep
   DURATION on the live corpus (the sweep runs on a tiny store after the
   gate opens); anything about `bin/sandbar`, config layering, or the live
   server.

   Deterministic: readiness is observed, the sweep is gated, every wait is
   bounded and polled.  Runs in isolation:
   `lein test sandbar.core-boot-order-acceptance-test`."
  (:require [cheshire.core              :as json]
            [clojure.java.io            :as io]
            [clojure.test               :refer :all]
            [com.stuartsierra.component :as component]
            [datomic.api                :as d]
            [sandbar.core               :as core]
            [sandbar.db.datatype        :as dt]
            [sandbar.db.datomic         :as db]
            [sandbar.logging.init       :as logging-init]
            [sandbar.reactive           :as reactive]
            [sandbar.reactive.queue     :as queue]
            [sandbar.reactive.sinks     :as sinks]
            [sandbar.sys                :as sys]
            [sandbar.util.auth          :as auth]
            [taoensso.telemere          :as tel])
  (:import [java.io File IOException]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bounds + fixture constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +ready-timeout-ms+
  "Bound on waiting for the port to answer.  Covers the component boot,
   which runs `initialize-db!` (schema load + entailment validation + dbfn
   load) on the in-memory store — on the fresh case for the first time."
  180000)

(def ^:private +projection-timeout-ms+
  "Bound on waiting for the projected file after the write is accepted.  The
   worker wakes on enqueue; a drain is milliseconds.  Generous on purpose so
   a slow CI box cannot produce a false failure."
  10000)

(def ^:private +gate-timeout-ms+
  "Backstop so a failing test can never hang the boot thread on the gate."
  60000)

(def ^:private +poll-ms+ 20)

(def ^:private rel-path     "decisions/boot_order_acceptance_first_ready_write.md")
(def ^:private memory-name  "boot order acceptance first ready write")
(def ^:private service-name :boot-order-acceptance-writer)
(def ^:private api-key      "boot-order-acceptance-not-a-real-key-2e7b")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scratch filesystem + free port
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- delete-tree! [^File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn- scratch-root
  "`<tmpdir>/boot-order-acceptance-<ts>-<rand>/{corpus/memory,logs}`."
  ^File []
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "boot-order-acceptance-" (System/currentTimeMillis)
                           "-" (rand-int 1000000)))]
    (.mkdirs (io/file root "corpus" "memory"))
    (.mkdirs (io/file root "logs"))
    root))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The system under test — the graph production builds, on test resources
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- production-system
  "`core/make-system` with overrides: the scheduler disabled by config, an
   in-memory spec, a free port, no nREPL.  The component types and the
   dependency declarations are the builder's own."
  [port db-spec]
  (core/make-system {:config  {:scheduler {:enabled? false}}
                     :db-spec db-spec
                     :port    port
                     :nrepl?  false}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP client — the write travels over a real socket, not response-for
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 2))
             (.build))))

(defn- http-get-status
  "Status code of `GET url`, or nil while the port does not answer."
  [url]
  (try
    (let [req (-> (HttpRequest/newBuilder (URI. url))
                  (.timeout (Duration/ofSeconds 5))
                  (.GET)
                  (.build))]
      (.statusCode (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString))))
    (catch IOException _ nil)))

(defn- mcp-post
  "POST a JSON-RPC message to `/mcp` on `port` with a Bearer token; returns
   `{:status :body}` with the JSON body parsed (keyword keys)."
  [port bearer body]
  (let [req  (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mcp")))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "Content-Type"  "application/json")
                 (.header "Accept"        "application/json")
                 (.header "Authorization" (str "Bearer " bearer))
                 (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                 (.build))
        resp (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (try (json/parse-string (.body resp) true)
                  (catch Exception _ (.body resp)))}))

(defn- entity-create-call
  "`tools/call sandbar_entity_create` for an :mm/Memory at `rel-path` — the
   wire-canonical underscore tool name, string-keyed slots as an MCP client
   sends them."
  []
  {:jsonrpc "2.0" :id 1 :method "tools/call"
   :params  {:name      "sandbar_entity_create"
             :arguments {:class ":mm/Memory"
                         :slots {"mm.memory/rel-path"    rel-path
                                 "mm.memory/name"        memory-name
                                 "mm.memory/memory-type" ":decision"
                                 "mm.memory/body-raw"    (str "Written at first readiness, before the "
                                                              "BM25F warm sweep finished.")}}}})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Principal seed — minted the way the wire tests mint one
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-writer-principal!
  "Seed a role that is NOT `auth/read-only-role` plus a ServiceAccount whose
   api-key hashes to `api-key` (mirrors `read_only_token_wire_test`).  A
   scoped, non-read-only principal may call mutating verbs over `/mcp`
   (`sandbar.mcp.authz/method-scope-decision` + `tools/handle-call`).
   Writes through `db/conn`, i.e. whatever `db/**conn*` holds.  Returns the
   Bearer token `<service>:<key>`."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  :boot-order-acceptance-writer
                       :auth/role-label "Fixture writer (boot-order acceptance)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name service-name
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name service-name) ":" api-key)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cleanup helpers — leave a shared test JVM as found
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- clean-pipeline!
  "Stop the worker (synchronous: drains what is dirty on this thread),
   drop sinks + callbacks.  Same reset the boot-order test uses."
  []
  (queue/stop!)
  (queue/clear-sinks!)
  (reactive/clear-callbacks!))

(defn- uninstall-boot-handlers!
  "Remove the Telemere handlers `core/start` installs and put the default
   console handler back.  The `:sandbar/file` handler points at this test's
   scratch log and `:sandbar/memorial-projection` writes memorial entities
   through `db/conn` — neither may outlive the test database."
  []
  (doseq [hid [:sandbar/console :sandbar/file :sandbar/memorial-projection]]
    (try (tel/remove-handler! hid) (catch Throwable _ nil)))
  (when-not (contains? (tel/get-handlers) :default/console)
    (tel/add-handler! :default/console (tel/handler:console))))

(defn- await-true
  "Poll `pred` every `+poll-ms+` until truthy or `timeout-ms` elapse; returns
   the truthy value or nil.  `abort!` (optional, no-arg) is called on every
   iteration and may throw to fail fast."
  ([pred timeout-ms] (await-true pred timeout-ms nil))
  ([pred timeout-ms abort!]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (when abort! (abort!))
       (or (pred)
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep (long +poll-ms+))
             (recur)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The acceptance boot
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- boot-and-verify!
  "Boot the production graph on a scratch store and verify the first-ready
   contract.  `fresh?` true boots on a store nothing has touched: the
   component's `initialize-db!` loads the schema during THIS boot and the
   principal can only be seeded after the port answers, through the
   connection the component set.  `fresh?` false pre-initializes the store
   and seeds the principal first, as every production boot finds an
   existing store."
  [{:keys [fresh?]}]
  (let [root         (scratch-root)
        corpus-root  (.getPath (io/file root "corpus"))
        log-path     (.getPath (io/file root "logs" "sandbar.log"))
        target-file  (io/file corpus-root "memory" rel-path)
        port         (free-port)
        db-spec      {:url "datomic:mem://"
                      :sid (str "boot-order-acceptance-" (if fresh? "fresh-" "existing-")
                                (System/currentTimeMillis))}
        db-uri       (db/db-uri db-spec)
        prior-system sys/system
        prior-conn   @db/**conn*
        ;; The warm-sweep gate.  `real-warm` is captured BEFORE the redef.
        warm-entered (promise)
        warm-release (promise)
        real-warm    @#'core/warm-bm25f-caches!
        gated-warm   (fn []
                       (deliver warm-entered (System/currentTimeMillis))
                       (deref warm-release +gate-timeout-ms+ :released-by-timeout)
                       (real-warm))]
    (clean-pipeline!)
    (reset! db/**conn* nil)
    (try
      (with-redefs [sinks/corpus-root          (constantly corpus-root)
                    logging-init/log-file-path (constantly log-path)
                    core/warm-bm25f-caches!    gated-warm]
        ;; 1. The store: untouched (fresh), or initialized by the very fn the
        ;;    DatomicPeer component runs, plus the principal (existing).
        (let [seeded-bearer (when-not fresh?
                              (db/initialize-db! db-uri)
                              (reset! db/**conn* (d/connect db-uri))
                              (try (seed-writer-principal!)
                                   (finally (reset! db/**conn* nil))))
              system        (production-system port db-spec)
              _             (is (= {:datomic :datomic}
                                   (component/dependencies (:pedestal system)))
                                "production's make-system declares that the port depends on the database component")
              _             (is (not (contains? system :nrepl))
                                "nREPL omitted from the test graph (:nrepl? false)")
              _             (alter-var-root #'sys/system (constantly system))
              ;; 2. The UNMODIFIED boot, on its own thread.
              boot          (future (core/start) :started)
              ;; Fail fast if the boot thread has already died.
              abort!        (fn [] (when (realized? boot) @boot))]
          (try
            ;; 3. Readiness observed from OUTSIDE: the first HTTP answer.
            (let [ready-status  (await-true #(http-get-status (str "http://localhost:" port "/"))
                                            +ready-timeout-ms+ abort!)
                  conn-at-ready @db/**conn*
                  at-ready      {:worker-running? (:worker-running? (queue/health))
                                 :sinks           (queue/sink-count)
                                 :callbacks       (reactive/callback-count)
                                 :boot-returned?  (realized? boot)
                                 :conn?           (some? conn-at-ready)
                                 :schema?         (some? (when conn-at-ready
                                                           (d/entid (d/db conn-at-ready) :mm/Memory)))}]
              (is (some? ready-status)
                  (str "the HTTP port answered within " +ready-timeout-ms+ " ms"))
              (is (true? (:conn? at-ready))
                  "the database component had started (db/**conn* set) when the port first answered")
              (is (true? (:schema? at-ready))
                  (str "the schema was loaded before the port answered"
                       (when fresh? " — by the component, during this boot, on a fresh store")))
              (is (true? (:worker-running? at-ready))
                  "the projection worker is running at first readiness")
              (is (= 2 (:sinks at-ready))
                  "both sinks (fs + SSE) are registered at first readiness")
              (is (= 1 (:callbacks at-ready))
                  "enqueue-projection! is the registered reactive callback at first readiness")
              (is (false? (:boot-returned? at-ready))
                  "core/start had NOT returned when the port first answered (still inside the boot)")
              (when (and ready-status (:schema? at-ready))
                ;; 4. The write — at first readiness, gate still closed, so
                ;;    the warm sweep cannot have finished.  On the fresh store
                ;;    the principal is seeded now, through the component's
                ;;    connection: the first write the store ever accepts.
                (let [bearer (or seeded-bearer (seed-writer-principal!))
                      {:keys [status body]} (mcp-post port bearer (entity-create-call))
                      result-text (get-in body [:result :content 0 :text])
                      entity      (some-> result-text (json/parse-string true) :entity)]
                  (is (= 200 status)
                      (str "MCP write accepted at first readiness; body: " (pr-str body)))
                  (is (nil? (:error body))
                      (str "no JSON-RPC error: " (pr-str (:error body))))
                  (is (not (true? (get-in body [:result :isError])))
                      (str "no tool error: " result-text))
                  (is (= rel-path (:mm.memory/rel-path entity))
                      "the committed entity carries the rel-path the sink projects to")
                  (is (false? (realized? warm-release))
                      "the warm-sweep gate was still closed when the write was accepted")
                  ;; 5. The projected file, within a bounded wait, while the
                  ;;    warm sweep is still held open.
                  (let [appeared? (await-true #(.exists target-file) +projection-timeout-ms+ abort!)]
                    (is appeared?
                        (str "the projected file appeared within " +projection-timeout-ms+
                             " ms at " target-file))
                    (when appeared?
                      (is (.contains ^String (slurp target-file) memory-name)
                          "the projected file carries the entity's name (real codec emit)")
                      (is (not (.exists (io/file (str (.getPath target-file)
                                                      sinks/atomic-write-tmp-suffix))))
                          "no .tmp sibling left behind (atomic rename completed)"))
                    (is (false? (realized? warm-release))
                        "the file was observed BEFORE the warm sweep was released")))))
            (finally
              ;; 6. Open the gate, let the boot finish, verify it did — all
              ;;    inside the redef scope.
              (deliver warm-release :go)
              (let [outcome (try (deref boot 120000 :boot-did-not-return)
                                 (catch Throwable t t))]
                (is (= :started outcome)
                    (str "core/start completed after the warm sweep was released: " (pr-str outcome))))
              (is (realized? warm-entered)
                  "the real BM25F warm sweep ran (it was gated, not skipped)")
              (let [h (queue/health)]
                (is (zero? (:sink-error-total h)) (str "no sink errors; health: " (pr-str h)))
                (is (pos? (:drain-total h)) "the worker drained at least the write"))
              ;; 7. Teardown INSIDE the redef scope so nothing can drain
              ;;    against the real corpus root or a configured transactor:
              ;;    memorial handler out first, then a synchronous worker stop
              ;;    (drains while the database is still up), then the system.
              (uninstall-boot-handlers!)
              (clean-pipeline!)
              (core/stop)))))
      (finally
        (deliver warm-release :go)
        (alter-var-root #'sys/system (constantly prior-system))
        (reset! db/**conn* prior-conn)
        (uninstall-boot-handlers!)
        (clean-pipeline!)
        (try (d/delete-database db-uri) (catch Throwable _ nil))
        (delete-tree! root)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The two cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest first-ready-http-write-reaches-the-fs-sink-on-an-existing-store
  (boot-and-verify! {:fresh? false}))

(deftest first-ready-http-write-reaches-the-fs-sink-on-a-fresh-store
  (boot-and-verify! {:fresh? true}))
