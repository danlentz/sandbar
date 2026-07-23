(ns sandbar.logging-test
  "Tests for sandbar.logging — the single public observability API namespace.

   Per memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md
   the ratified shape is SIX macros (info/warn/error/debug/trace/profile)
   with optional final-positional memorial-flag keyword + human-string
   shorthand with auto-derived synthetic event-id.

   Per memory/interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md
   — verification is tests + memorialization, NOT REPL verification."
  (:require [clojure.test           :refer :all]
            [sandbar.logging        :as sb-log]
            [sandbar.logging.config :as logging-config]
            [sandbar.logging.init   :as logging-init]
            [taoensso.telemere      :as tel]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; info macro — all five arities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest info-1-arg-keyword-bare-marker
  (let [form (macroexpand-1 '(sandbar.logging/info ::test))]
    (is (= 'taoensso.telemere/log! (first form)))
    (is (= {:level :info :id ::test} (second form)))))

(deftest info-1-arg-string-synthesizes-event-id
  (testing "human shorthand: synthetic event-id with calling ns + line + msg hash"
    (binding [*ns* (find-ns 'sandbar.logging-test)]
      (let [form (macroexpand-1 '(sandbar.logging/info "hello world"))
            opts (second form)]
        (is (= 'taoensso.telemere/log! (first form)))
        (is (= :info (:level opts)))
        (is (= "hello world" (:msg opts)))
        (is (keyword? (:id opts)))
        (is (some? (namespace (:id opts)))
            "Synthetic id is namespace-qualified (specific ns depends on caller)")
        (is (re-find #"^line-\d+-[0-9a-f]+$" (name (:id opts)))
            "Synthetic id name follows the line-<n>-<hash> pattern")))))

(deftest info-2-arg-id-plus-data-map
  (let [form (macroexpand-1 '(sandbar.logging/info ::test {:k :v}))]
    (is (= 'taoensso.telemere/log! (first form)))
    (is (= {:level :info :id ::test :data {:k :v}} (second form)))))

(deftest info-2-arg-id-plus-string-message
  (let [form (macroexpand-1 '(sandbar.logging/info ::test "msg"))]
    (is (= {:level :info :id ::test :msg "msg"} (second form)))))

(deftest info-2-arg-id-plus-memorial-flag
  (testing "(info ::id :db-only) — flag with no other payload"
    (let [form (macroexpand-1 '(sandbar.logging/info ::test :db-only))]
      (is (= {:level :info :id ::test :data {:memorial :db-only}}
             (second form))))))

(deftest info-2-arg-string-plus-data
  (testing "human shorthand + data: synthetic id, msg, data"
    (let [form (macroexpand-1 '(sandbar.logging/info "hello" {:k :v}))
          opts (second form)]
      (is (= :info (:level opts)))
      (is (= "hello" (:msg opts)))
      (is (= {:k :v} (:data opts)))
      (is (keyword? (:id opts))))))

(deftest info-3-arg-id-data-flag
  (testing "(info ::id data :first-class) — data with memorial flag"
    (let [form (macroexpand-1 '(sandbar.logging/info ::test {:k :v} :first-class))
          opts (second form)]
      (is (= :info (:level opts)))
      (is (= ::test (:id opts)))
      ;; :data is (clojure.core/assoc {:k :v} :memorial :first-class)
      (let [data-form (:data opts)]
        (is (= 'clojure.core/assoc (first data-form)))
        (is (= {:k :v} (second data-form)))
        (is (= :memorial (nth data-form 2)))
        (is (= :first-class (nth data-form 3)))))))

(deftest info-3-arg-id-msg-data
  (let [form (macroexpand-1 '(sandbar.logging/info ::test "msg" {:k :v}))]
    (is (= {:level :info :id ::test :msg "msg" :data {:k :v}}
           (second form)))))

(deftest info-4-arg-id-msg-data-flag
  (let [form (macroexpand-1 '(sandbar.logging/info ::test "msg" {:k :v} :inline))
        opts (second form)]
    (is (= :info (:level opts)))
    (is (= ::test (:id opts)))
    (is (= "msg" (:msg opts)))
    (let [data-form (:data opts)]
      (is (= 'clojure.core/assoc (first data-form)))
      (is (= {:k :v} (second data-form)))
      (is (= :inline (nth data-form 3))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; warn / debug / trace mirror info — spot-check :level dispatch + the
;; memorial-flag arity to confirm shape parity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest warn-emits-warn-level
  (let [form (macroexpand-1 '(sandbar.logging/warn ::test {:k :v}))]
    (is (= :warn (:level (second form))))))

(deftest warn-memorial-flag
  (let [form (macroexpand-1 '(sandbar.logging/warn ::test {:k :v} :db-only))
        data-form (:data (second form))]
    (is (= :db-only (nth data-form 3)))))

(deftest debug-emits-debug-level
  (let [form (macroexpand-1 '(sandbar.logging/debug ::test {:k :v}))]
    (is (= :debug (:level (second form))))))

(deftest trace-emits-trace-level
  (let [form (macroexpand-1 '(sandbar.logging/trace ::test {:k :v}))]
    (is (= :trace (:level (second form))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest error-1-arg-keyword-bare-marker
  (let [form (macroexpand-1 '(sandbar.logging/error ::test))]
    (is (= 'taoensso.telemere/log! (first form)))
    (is (= :error (:level (second form))))))

(deftest error-1-arg-string-shorthand
  (let [form (macroexpand-1 '(sandbar.logging/error "boom"))
        opts (second form)]
    (is (= :error (:level opts)))
    (is (= "boom" (:msg opts)))
    (is (keyword? (:id opts)))))

(deftest error-2-arg-id-plus-string-msg
  (let [form (macroexpand-1 '(sandbar.logging/error ::test "boom"))]
    (is (= 'taoensso.telemere/log! (first form)))
    (is (= "boom" (:msg (second form))))))

(deftest error-2-arg-id-plus-throwable-routes-to-error-kind
  (testing "Non-string non-flag literal in slot 2 → tel/error! (typed error kind)"
    (let [form (macroexpand-1 '(sandbar.logging/error ::test ex))]
      (is (= 'taoensso.telemere/error! (first form))
          "Expands to tel/error! when payload is a throwable (variable)")
      (is (= 'ex (:error (second form)))))))

(deftest error-2-arg-id-plus-memorial-flag
  (let [form (macroexpand-1 '(sandbar.logging/error ::test :db-only))]
    (is (= 'taoensso.telemere/log! (first form)))
    (is (= {:memorial :db-only} (:data (second form))))))

(deftest error-3-arg-id-throwable-data
  (let [form (macroexpand-1 '(sandbar.logging/error ::test ex {:k :v}))]
    (is (= 'taoensso.telemere/error! (first form)))
    (is (= 'ex (:error (second form))))
    (is (= {:k :v} (:data (second form))))))

(deftest error-3-arg-id-throwable-with-flag
  (let [form (macroexpand-1 '(sandbar.logging/error ::test ex :db-only))]
    (is (= 'taoensso.telemere/error! (first form)))
    (is (= {:memorial :db-only} (:data (second form))))))

(deftest error-4-arg-id-throwable-data-flag
  (let [form (macroexpand-1 '(sandbar.logging/error ::test ex {:k :v} :first-class))
        data-form (:data (second form))]
    (is (= 'taoensso.telemere/error! (first form)))
    (is (= 'ex (:error (second form))))
    (is (= 'clojure.core/assoc (first data-form)))
    (is (= :first-class (nth data-form 3)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Profile macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest profile-expands-to-tufte-p
  (let [form (macroexpand-1 '(sandbar.logging/profile :hot-path (do-stuff)))]
    (is (= 'taoensso.tufte/p (first form)))
    (is (= :hot-path (second form)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ratified-shape discipline — these macros do NOT exist
;;
;; Per ADR Alt-B (rejected): audit / memorialize sugar macros are NOT
;; in MVP.  Lock that discipline so future drift can't reintroduce them
;; silently.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest no-audit-sugar-macro
  (is (nil? (resolve 'sandbar.logging/audit))
      "sandbar.logging/audit is NOT present in the ratified 6-macro MVP shape"))

(deftest no-memorialize-sugar-macro
  (is (nil? (resolve 'sandbar.logging/memorialize))
      "sandbar.logging/memorialize is NOT present in the ratified 6-macro MVP shape"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; logging.config — parse + apply roundtrip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-config-returns-resources-edn-when-no-override
  (let [spec (logging-config/parse-config)]
    (is (some? spec) "Default resource logging.edn is on classpath")
    (is (= :info (:min-level spec)))))

(deftest apply-config!-returns-spec
  (let [spec {:min-level :warn}
        ret  (logging-config/apply-config! spec)]
    (is (= spec ret))
    (logging-config/apply-config! {:min-level :info})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; logging.init — log-file-path + handler install
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest log-file-path-uses-logs-subdir
  (let [path (logging-init/log-file-path)]
    (is (.endsWith ^String path "/sandbar.log"))
    (is (re-find #"\.sandbar/logs/sandbar\.log$" path)
        "Path under .sandbar/logs/ (distinct from bin/sandbar's stdout-capture)")))

(deftest install-handlers!-is-idempotent
  (logging-init/install-handlers!)
  (let [count-after-first (count (tel/get-handlers))]
    (logging-init/install-handlers!)
    (is (= count-after-first (count (tel/get-handlers))))))

(deftest install-handlers!-registers-sandbar-file
  (logging-init/install-handlers!)
  (is (contains? (set (keys (tel/get-handlers))) :sandbar/file)))

(deftest start!-returns-log-file-path
  (let [path (logging-init/start!)]
    (is (string? path))
    (is (.endsWith ^String path ".log"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage D — memorial-projection-handler signal-spec bridges
;;
;; Tests for sandbar.logging.handlers — the Telemere handler that
;; bridges flagged signals into the substrate as :mm/EventLog
;; (:first-class) or :event/SystemEvent (:db-only) entities.  Pure-fn
;; tests (signal->* spec builders) don't touch the DB; the dispatch
;; tests use with-redefs to stub dt/make.
;;
;; Per memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(require '[sandbar.logging.handlers :as handlers]
         '[sandbar.db.datatype      :as dt])

(deftest signal->event-log-spec-shape
  (let [signal {:level :info
                :id    :test/event-id
                :ns    "sandbar.test.callsite"
                :msg   "thing happened"
                :data  {:k :v :memorial :first-class}
                :inst  #inst "2026-05-24T00:00:00.000Z"}
        spec   (handlers/signal->event-log-spec signal)]
    (testing "PROV-O activity slots populated"
      (is (= #inst "2026-05-24T00:00:00.000Z" (:mm.activity/started-at spec)))
      (is (= #inst "2026-05-24T00:00:00.000Z" (:mm.activity/ended-at spec)))
      (is (= :succeeded (:mm.activity/status spec))))
    (testing ":mm.event-log/* slots populated"
      (is (= :info (:mm.event-log/level spec)))
      (is (= :test/event-id (:mm.event-log/signal-id spec)))
      (is (= "sandbar.test.callsite" (:mm.event-log/source-ns spec)))
      (is (= "thing happened" (:mm.event-log/msg spec))))
    (testing ":memorial flag stripped from stored data"
      (let [data-str (:mm.event-log/data spec)]
        (is (string? data-str))
        (is (not (re-find #":memorial" data-str))
            ":memorial flag should NOT appear in stored :data string")
        (is (re-find #":k :v" data-str)
            "Other data keys should be preserved")))))

(deftest signal->event-log-spec-prefers-location-ns-for-slf4j-bridged
  (testing "SLF4J-bridged signals have :ns = bridge ns; :location :ns = real logger name"
    (let [signal {:level    :info
                  :id       :datomic.peer/transact
                  :ns       "taoensso.telemere.slf4j"        ; bridge ns (wrong source)
                  :location {:ns "datomic.peer"}            ; real source
                  :data     {:memorial :first-class}}
          spec   (handlers/signal->event-log-spec signal)]
      (is (= "datomic.peer" (:mm.event-log/source-ns spec))
          ":location :ns preferred over top-level :ns"))))

(deftest signal->event-log-spec-falls-back-to-data-event-for-id
  (testing "When :id is missing, :data :event is used"
    (let [signal {:level :info
                  :ns    "sandbar.reactive"
                  :data  {:event :peer/transact :phase :start :memorial :first-class}}
          spec   (handlers/signal->event-log-spec signal)]
      (is (= :peer/transact (:mm.event-log/signal-id spec))))))

(deftest signal->event-log-spec-forces-msg-delay
  (testing "Telemere :msg_ delay is forced to resolve to the string"
    (let [signal {:level :info
                  :id    :test/event
                  :ns    "sandbar.test"
                  :msg_  (delay "delayed message")
                  :data  {:memorial :first-class}}
          spec   (handlers/signal->event-log-spec signal)]
      (is (= "delayed message" (:mm.event-log/msg spec))))))

(deftest signal->server-event-spec-shape
  (let [signal {:level :info
                :id    :test/db-only-event
                :ns    "sandbar.test"
                :msg   "operational audit"
                :inst  #inst "2026-05-24T00:00:00.000Z"
                :data  {:memorial :db-only}}
        spec   (handlers/signal->server-event-spec signal)]
    (testing ":event/* slots populated (compatible with sandbar.util.event)"
      (is (= #inst "2026-05-24T00:00:00.000Z" (:event/timestamp spec)))
      (is (= :info                            (:event/level spec)))
      (is (= ":test/db-only-event"            (:event/name spec)))
      (is (= "sandbar.test"                   (:event/namespace spec)))
      (is (= :memorial/db-only                (:event/kind spec))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; memorial-projection-handler dispatch — uses with-redefs to stub dt/make
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest handler-shutdown-arity-is-noop
  (testing "0-arity shutdown drain returns nil; does not throw"
    (is (nil? (handlers/memorial-projection-handler)))))

(deftest handler-skips-when-reentry-guard-active
  (testing "Reentry guard prevents handler re-execution"
    (let [calls (atom 0)]
      (binding [handlers/*log-substrate-active?* true]
        (with-redefs [dt/make (fn [& _] (swap! calls inc) nil)]
          (handlers/memorial-projection-handler
            {:level :info :id :test/x :data {:memorial :first-class}})))
      (is (zero? @calls)
          "Handler MUST NOT invoke dt/make while reentry-guard is bound true"))))

(deftest handler-skips-when-no-memorial-flag
  (testing "Signals without :data :memorial flag are noop"
    (let [calls (atom 0)]
      (with-redefs [dt/make (fn [& _] (swap! calls inc) nil)]
        (handlers/memorial-projection-handler
          {:level :info :id :test/transient :data {:k :v}}))
      (is (zero? @calls)
          "Handler MUST NOT invoke dt/make when no :memorial flag is set"))))

(deftest handler-routes-first-class-to-mm-event-log
  (testing ":memorial :first-class signals route to :mm/EventLog via dt/make"
    (let [target-class (atom nil)
          target-spec  (atom nil)]
      (with-redefs [dt/make (fn [class spec & _]
                              (reset! target-class class)
                              (reset! target-spec  spec))]
        (handlers/memorial-projection-handler
          {:level :info :id :test/first-class
           :ns "sandbar.test"
           :data {:k :v :memorial :first-class}}))
      (is (= :mm/EventLog @target-class))
      (is (= :test/first-class (:mm.event-log/signal-id @target-spec))))))

(deftest handler-routes-db-only-to-event-system-event
  (testing ":memorial :db-only signals route to :event/SystemEvent via dt/make"
    (let [target-class (atom nil)]
      (with-redefs [dt/make (fn [class _spec & _]
                              (reset! target-class class))]
        (handlers/memorial-projection-handler
          {:level :info :id :test/db-only
           :ns "sandbar.test"
           :data {:memorial :db-only}}))
      (is (= :event/SystemEvent @target-class)))))

(deftest handler-inline-is-deferred-noop
  (testing ":memorial :inline is deferred per Stage D MVP; noop"
    (let [calls (atom 0)]
      (with-redefs [dt/make (fn [& _] (swap! calls inc) nil)]
        (handlers/memorial-projection-handler
          {:level :info :id :test/inline :data {:memorial :inline}}))
      (is (zero? @calls)
          "Handler MUST NOT call dt/make for :inline policy (deferred Stage D MVP)"))))

(deftest handler-swallows-dt-make-failures
  (testing "Handler catches dt/make exceptions; does not throw"
    (with-redefs [dt/make (fn [& _] (throw (ex-info "simulated" {})))]
      ;; Should not throw — handler catches + reports to *err*
      (is (nil? (handlers/memorial-projection-handler
                  {:level :info :id :test/will-fail
                   :data {:memorial :first-class}}))))))
