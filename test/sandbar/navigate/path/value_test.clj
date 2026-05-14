(ns sandbar.navigate.path.value-test
  "Tests for sandbar.navigate.path.value — Stage P-5 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Pure tests on the path-value abstraction: shape validation,
  accessors, composition (extend / concat / reverse), and predicates
  (prefix? / suffix? / subpath?)."
  (:require [clojure.test :refer :all]
            [sandbar.navigate.path.value :as path]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Construction + validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest singleton-path-shape
  (testing "singleton path has one node and no edges"
    (let [p (path/singleton :alpha)]
      (is (= [:alpha] (path/nodes p)))
      (is (= [] (path/edges p)))
      (is (zero? (path/length p)))
      (is (path/empty-path? p))
      (is (path/valid? p)))))

(deftest make-validates-invariant
  (testing "make rejects mismatched node/edge counts"
    (is (thrown? clojure.lang.ExceptionInfo
                 (path/make [:a :b :c] [(path/make-edge :cites)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (path/make [:a] [(path/make-edge :cites)])))))

(deftest make-builds-valid-path
  (testing "make builds valid path with N+1 nodes + N edges"
    (let [p (path/make [:a :b :c]
                       [(path/make-edge :cites)
                        (path/make-edge :evidences)])]
      (is (path/valid? p))
      (is (= 2 (path/length p)))
      (is (= :a (path/start-node p)))
      (is (= :c (path/end-node p))))))

(deftest make-edge-defaults-forward
  (testing "make-edge defaults to :direction :forward"
    (is (= {:predicate :cites :direction :forward}
           (path/make-edge :cites)))))

(deftest make-edge-explicit-direction
  (is (= {:predicate :cites :direction :inverse}
         (path/make-edge :cites :inverse))))

(deftest make-edge-rejects-bad-direction
  (is (thrown? AssertionError
               (path/make-edge :cites :sideways))))

(deftest valid-rejects-malformed
  (is (not (path/valid? {})))
  (is (not (path/valid? {:nodes [:a] :edges [(path/make-edge :p)]})))
  (is (not (path/valid? {:nodes [:a :b]
                         :edges [{:no-predicate-key 1}]}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest accessors-via-positional
  (let [p (path/make [:a :b :c]
                     [(path/make-edge :cites)
                      (path/make-edge :evidences)])]
    (is (= :a (path/node-at p 0)))
    (is (= :b (path/node-at p 1)))
    (is (= :c (path/node-at p 2)))
    (is (nil? (path/node-at p 3)))
    (is (= :cites (:predicate (path/edge-at p 0))))
    (is (= :evidences (:predicate (path/edge-at p 1))))
    (is (nil? (path/edge-at p 2)))))

(deftest predicates-and-directions-projection
  (let [p (path/make [:a :b :c]
                     [(path/make-edge :cites)
                      (path/make-edge :evidences :inverse)])]
    (is (= [:cites :evidences] (path/predicates p)))
    (is (= [:forward :inverse] (path/directions p)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Composition — extend / concat / reverse
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest extend-singleton
  (testing "extend grows a singleton into a 1-edge path"
    (let [p0 (path/singleton :a)
          p1 (path/extend-path p0 (path/make-edge :cites) :b)]
      (is (= [:a :b] (path/nodes p1)))
      (is (= 1 (path/length p1)))
      (is (path/valid? p1)))))

(deftest extend-twice
  (testing "repeated extend builds longer paths"
    (let [p (-> (path/singleton :a)
                (path/extend-path (path/make-edge :p1) :b)
                (path/extend-path (path/make-edge :p2) :c))]
      (is (= [:a :b :c] (path/nodes p)))
      (is (= 2 (path/length p))))))

(deftest concat-paths-joins
  (testing "concat-paths joins where end-of-p1 = start-of-p2"
    (let [p1 (path/make [:a :b] [(path/make-edge :p1)])
          p2 (path/make [:b :c] [(path/make-edge :p2)])
          j  (path/concat-paths p1 p2)]
      (is (= [:a :b :c] (path/nodes j)))
      (is (= 2 (path/length j))))))

(deftest concat-paths-rejects-mismatch
  (testing "concat-paths refuses when join nodes differ"
    (let [p1 (path/make [:a :b] [(path/make-edge :p1)])
          p2 (path/make [:x :c] [(path/make-edge :p2)])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (path/concat-paths p1 p2))))))

(deftest reverse-flips-direction-and-order
  (let [p (path/make [:a :b :c]
                     [(path/make-edge :p1)
                      (path/make-edge :p2)])
        r (path/reverse p)]
    (is (= [:c :b :a] (path/nodes r)))
    (is (= [{:predicate :p2 :direction :inverse}
            {:predicate :p1 :direction :inverse}]
           (path/edges r)))))

(deftest reverse-is-involutive
  (testing "(reverse (reverse p)) = p"
    (let [p (path/make [:a :b :c]
                       [(path/make-edge :p1 :forward)
                        (path/make-edge :p2 :inverse)])]
      (is (= p (path/reverse (path/reverse p)))))))

(deftest reverse-singleton
  (let [p (path/singleton :a)]
    (is (= p (path/reverse p)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; prefix? / suffix? / subpath?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest prefix?-positive
  (let [short-p (path/make [:a :b] [(path/make-edge :p1)])
        long-p  (path/make [:a :b :c]
                           [(path/make-edge :p1)
                            (path/make-edge :p2)])]
    (is (path/prefix? short-p long-p))))

(deftest prefix?-self
  (testing "a path is a prefix of itself"
    (let [p (path/make [:a :b] [(path/make-edge :p)])]
      (is (path/prefix? p p)))))

(deftest prefix?-singleton-prefix
  (testing "singleton at start is a prefix"
    (let [s (path/singleton :a)
          p (path/make [:a :b] [(path/make-edge :p)])]
      (is (path/prefix? s p)))))

(deftest prefix?-rejects-non-prefix
  (let [p1 (path/make [:b :c] [(path/make-edge :p2)])
        p2 (path/make [:a :b :c]
                      [(path/make-edge :p1)
                       (path/make-edge :p2)])]
    (is (not (path/prefix? p1 p2))
        "p1 starts in the middle of p2, not the start")))

(deftest suffix?-positive
  (let [short-p (path/make [:b :c] [(path/make-edge :p2)])
        long-p  (path/make [:a :b :c]
                           [(path/make-edge :p1)
                            (path/make-edge :p2)])]
    (is (path/suffix? short-p long-p))))

(deftest suffix?-self
  (let [p (path/make [:a :b] [(path/make-edge :p)])]
    (is (path/suffix? p p))))

(deftest subpath?-prefix-and-suffix
  (testing "every prefix and suffix is also a subpath"
    (let [long-p (path/make [:a :b :c :d]
                            [(path/make-edge :p1)
                             (path/make-edge :p2)
                             (path/make-edge :p3)])
          prefix (path/make [:a :b] [(path/make-edge :p1)])
          suffix (path/make [:c :d] [(path/make-edge :p3)])
          middle (path/make [:b :c] [(path/make-edge :p2)])]
      (is (path/subpath? prefix long-p))
      (is (path/subpath? suffix long-p))
      (is (path/subpath? middle long-p)))))

(deftest subpath?-singleton-in-path
  (testing "singleton at any node-position of p is a subpath of p"
    (let [p (path/make [:a :b :c]
                       [(path/make-edge :p1)
                        (path/make-edge :p2)])]
      (is (path/subpath? (path/singleton :a) p))
      (is (path/subpath? (path/singleton :b) p))
      (is (path/subpath? (path/singleton :c) p))
      (is (not (path/subpath? (path/singleton :z) p))))))

(deftest subpath?-rejects-non-contiguous
  (testing "non-contiguous matching is NOT a subpath"
    (let [p (path/make [:a :b :c]
                       [(path/make-edge :p1)
                        (path/make-edge :p2)])
          ;; [:a :c] with edge :p1 doesn't appear contiguously
          nope (path/make [:a :c] [(path/make-edge :p1)])]
      (is (not (path/subpath? nope p))))))

(deftest subpath?-rejects-longer-than-target
  (let [short-p (path/make [:a :b] [(path/make-edge :p)])
        long-p  (path/make [:a :b :c :d]
                           [(path/make-edge :p1)
                            (path/make-edge :p2)
                            (path/make-edge :p3)])]
    ;; long-p is not a subpath of short-p (wrong direction)
    (is (not (path/subpath? long-p short-p)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Length-based tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest length-of-empty-path-is-zero
  (is (zero? (path/length (path/singleton :a)))))

(deftest length-counts-edges
  (let [p (path/make [:a :b :c :d]
                     [(path/make-edge :p1)
                      (path/make-edge :p2)
                      (path/make-edge :p3)])]
    (is (= 3 (path/length p)))))
