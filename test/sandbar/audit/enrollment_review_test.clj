(ns sandbar.audit.enrollment-review-test
  "Independent review cases for enrollment clearance, using the delivery's fixture."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sandbar.audit.fs-substrate-drift :as drift]
            [sandbar.audit.fs-substrate-drift-preflight-test :as fixture]
            [sandbar.codec.markdown :as md]
            [sandbar.projection :as pg]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "enrollment-review" :auth? false})
  #'fixture/fixture)

(deftest every-claimant-at-one-stored-path-prevents-clearance
  (let [first-ident (#'fixture/document! {:slug "a_claimant" :rel "observations/shared.md" :owned? true})
        _ (#'fixture/document! {:slug "z_claimant" :rel "observations/shared.md" :owned? true})
        _ (#'fixture/emit! first-ident "a")
        report (drift/audit-all {:from (#'fixture/root "a")})]
    (is (= 1 (get-in report [:summary :substrate-rel-path-collision-count])))
    (is (false? (get-in report [:enrollment-preflight :clear-for-enrollment?]))
        "a matching representative does not settle the other stored claimant")
    (is (pos? (get-in report [:enrollment-preflight :unresolved-count])))))

(deftest parser-failure-with-readable-uuid-cannot-clear-enrollment
  (let [ident (#'fixture/document! {:slug "parse_failure" :owned? true})
        _ (#'fixture/emit! ident "a")
        original md/parse-document]
    ;; Inject the existing parser's documented failure path. The file and
    ;; ownership parser still succeed, isolating propagation of unit errors.
    (with-redefs [md/parse-document
                  (fn [text source & more]
                    (if (= source "observations/parse_failure.md")
                      (throw (ex-info "review parser failure" {}))
                      (apply original text source more)))]
      (let [units (pg/ingest-units (#'fixture/memory-dir "a"))
            report (drift/audit-all {:from (#'fixture/root "a")})]
        (is (some #(= :parse-failed (:status %)) units))
        (is (false? (get-in report [:enrollment-preflight :clear-for-enrollment?]))
            "parse errors must not collapse into an empty successful population")
        (is (pos? (get-in report [:enrollment-preflight :error-count])))))))
