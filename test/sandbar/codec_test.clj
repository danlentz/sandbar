(ns sandbar.codec-test
  "Tests for the sandbar.codec mediator + sandbar.codec.protocol.  Uses
   a stub TestCodec record for in-test concrete-codec behavior; concrete
   codec round-trip tests for markdown / JSON / TTL / EDN-TTL-hybrid land
   in their respective Stage B–E test namespaces per
   plans/sandbar_codec_layer_arc_2026-05-12.md."
  (:require [clojure.test           :refer :all]
            [sandbar.codec          :as codec]
            [sandbar.codec.protocol :as proto]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stub codecs for mediator-level testing

(defrecord IdentityCodec [format-key mime supported-classes]
  proto/Codec
  (parse [_ input _opts]
    ;; Identity codec: input is already an entity-spec map; pass through.
    (read-string input))
  (emit [_ entity _opts]
    (pr-str entity))
  (mime-types [_] (vec (if (sequential? mime) mime [mime])))
  (supports? [_ class-ident]
    (or (= :all supported-classes)
        (contains? (set supported-classes) class-ident)))
  (round-trip-test [self entity]
    (let [emitted  (proto/emit self entity {})
          reparsed (proto/parse self emitted {})]
      {:ok?      (= entity reparsed)
       :emitted  emitted
       :reparsed reparsed
       :diff     (when (not= entity reparsed)
                   {:original entity :reparsed reparsed})})))

(defn- make-codec
  ([format-key] (make-codec format-key (str "application/" (name format-key)) :all))
  ([format-key mime] (make-codec format-key mime :all))
  ([format-key mime supported-classes]
   (->IdentityCodec format-key mime supported-classes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — clear registry per test

(use-fixtures :each
  (fn [t]
    (codec/clear-all!)
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A.2 Mediator — registry + lookup

(deftest register-and-list-codecs
  (let [edn-codec    (make-codec :edn "application/edn")
        json-codec   (make-codec :json "application/json")]
    (codec/register! :edn edn-codec)
    (codec/register! :json json-codec)
    (let [listing (codec/list-codecs)]
      (is (= 2 (count listing)))
      (is (= [:edn :json] (map :format listing)))
      (is (= ["application/edn"] (-> listing first :mime-types))))))

(deftest register-rejects-non-keyword-format
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword"
        (codec/register! "json" (make-codec :json)))))

(deftest register-rejects-non-codec
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Codec"
        (codec/register! :foo {:not-a-codec true}))))

(deftest unregister-is-idempotent
  (codec/register! :edn (make-codec :edn))
  (codec/unregister! :edn)
  (is (nil? (codec/unregister! :edn)))
  (is (nil? (codec/codec-for :edn))))

(deftest codec-for-returns-registered-instance
  (let [c (make-codec :edn)]
    (codec/register! :edn c)
    (is (identical? c (codec/codec-for :edn)))))

(deftest codec-for-mime-dispatch
  (let [edn-codec  (make-codec :edn "application/edn")
        json-codec (make-codec :json "application/json")]
    (codec/register! :edn edn-codec)
    (codec/register! :json json-codec)
    (let [[fmt c] (codec/codec-for-mime "application/edn")]
      (is (= :edn fmt))
      (is (identical? edn-codec c)))
    (is (nil? (codec/codec-for-mime "application/yaml")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parse / emit / parse-mime dispatch

(deftest parse-with-explicit-format
  (codec/register! :edn (make-codec :edn))
  (let [entity {:dt/type :foo/Bar :slots {:name "x"}}]
    (is (= entity (codec/parse (pr-str entity) {:format :edn})))))

(deftest parse-throws-when-no-format-resolvable
  (codec/register! :edn (make-codec :edn))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No codec format"
        (codec/parse "{:dt/type :foo/Bar}" {}))))

(deftest parse-throws-when-format-unregistered
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No codec registered for format"
        (codec/parse "x" {:format :unknown}))))

(deftest parse-mime-dispatches-by-mime
  (codec/register! :edn (make-codec :edn "application/edn"))
  (let [entity {:dt/type :foo/Bar}]
    (is (= entity (codec/parse-mime "application/edn" (pr-str entity))))))

(deftest parse-mime-throws-on-unknown-mime
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No codec registered for MIME"
        (codec/parse-mime "text/x-unknown" "x"))))

(deftest emit-with-explicit-format
  (codec/register! :edn (make-codec :edn))
  (let [entity {:dt/type :foo/Bar :slots {:name "x"}}]
    (is (= (pr-str entity)
           (codec/emit entity {:format :edn})))))

(deftest emit-throws-without-format-or-native-codec
  (codec/register! :edn (make-codec :edn))
  ;; Entity carries :dt/type but the class has no :dt/native-codec
  ;; declared + no DB to read it from in this pure-test context.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No codec format"
        (codec/emit {:dt/type :foo/Unmodeled} {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A.4 Round-trip-test scaffolding

(deftest round-trip-test-via-mediator
  (codec/register! :edn (make-codec :edn))
  (let [entity {:dt/type :foo/Bar :slots {:name "x"}}
        result (codec/round-trip-test :edn entity)]
    (is (true? (:ok? result)))
    (is (nil? (:diff result)))
    (is (= entity (:reparsed result)))))

(deftest round-trip-test-detects-lossy-codec
  ;; A codec that drops :slots on parse should fail round-trip
  (let [lossy (reify proto/Codec
                (parse [_ input _opts]
                  (dissoc (read-string input) :slots))
                (emit  [_ entity _opts] (pr-str entity))
                (mime-types [_] ["application/lossy"])
                (supports? [_ _] true)
                (round-trip-test [self entity]
                  (let [emitted  (proto/emit self entity {})
                        reparsed (proto/parse self emitted {})]
                    {:ok?      (= entity reparsed)
                     :emitted  emitted
                     :reparsed reparsed
                     :diff     (when (not= entity reparsed)
                                 {:original entity :reparsed reparsed})})))]
    (codec/register! :lossy lossy)
    (let [result (codec/round-trip-test :lossy {:dt/type :foo/Bar :slots {:k 1}})]
      (is (false? (:ok? result)))
      (is (some? (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A.3 Per-class default codec — native-codec-for-class
;;
;; Pure-test scope: `native-codec-for-class` reads via dt/*; with no
;; live DB the function returns nil (graceful degradation).  DB-backed
;; tests exercising the full mm/Memory → markdown default-resolution
;; path land at Stage B when the markdown codec exists + corpus
;; mm/Memory schema can declare :dt/native-codec :markdown.

(deftest native-codec-for-class-returns-nil-when-undeclared-or-no-db
  (is (nil? (codec/native-codec-for-class :foo/Unmodeled))
      "No DB / no declaration → nil (mediator falls back to explicit :format)"))
