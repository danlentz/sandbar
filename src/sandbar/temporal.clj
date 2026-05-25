(ns sandbar.temporal
  "Polymorphic consumer-helpers for the Phase D Temporal Tier-2 substrate.

  Per the Option ε Paired-Property Pattern (Q.D.2 resolution per the
  pre-0.2.0 β.0.5 Stage 3 ADR), substrate slots on `:mm/Interval` are
  monomorphic + paired:

  - `:mm.interval/begins-at-instant` — ObjectProperty form (ref to `:mm/Instant`)
  - `:mm.interval/begins-at-time`    — DatatypeProperty form (primitive `:db.type/instant`)

  A `:mm/Shape` XOR constraint (`:memory.shapes/interval-begins-at-xor`)
  enforces exactly-one-of-the-pair-populated per `:mm/Interval` instance.
  The same pattern applies to the `ends-at` pair.

  This namespace provides the POLYMORPHIC-READER ILLUSION at the consumer
  boundary — callers see a single `interval-beginning` / `interval-end`
  function and don't need to discriminate between the ref form + literal
  form themselves.

  Allen-relation convenience predicates wrap the substrate's typed-edge
  predicates (`:mm.interval/before` etc.) in reader-friendly Clojure
  function form for the 13 relations from Allen's interval algebra
  (per the W3C OWL-Time `time:interval*` family + Allen 1983)."
  (:require [datomic.api :as d]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Endpoint resolution — polymorphic read across Option ε paired slots
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn interval-beginning
  "Returns the beginning wall-clock instant of an :mm/Interval entity,
   regardless of whether stored as first-class :mm/Instant ref form
   (:mm.interval/begins-at-instant → :mm.instant/at-time) or primitive
   :db.type/instant literal form (:mm.interval/begins-at-time).  The Option ε
   XOR Shape guarantees exactly one form is populated per Interval instance."
  [entity]
  (or (:mm.instant/at-time (:mm.interval/begins-at-instant entity))
      (:mm.interval/begins-at-time entity)))

(defn interval-end
  "Mirror of interval-beginning for the ends-at pair.  Returns the ending
   wall-clock instant regardless of which Option ε form is populated."
  [entity]
  (or (:mm.instant/at-time (:mm.interval/ends-at-instant entity))
      (:mm.interval/ends-at-time entity)))

(defn interval-duration-ms
  "Returns the interval duration in milliseconds, computed as
   `(- (inst-ms end) (inst-ms beginning))`.  Returns nil if either endpoint
   is absent (handles the partially-populated case gracefully — callers
   should validate via the XOR Shape before assuming both endpoints exist)."
  [entity]
  (when-let [beg (interval-beginning entity)]
    (when-let [end (interval-end entity)]
      (- (inst-ms end) (inst-ms beg)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Allen-relation convenience predicates — reader-friendly wrappers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The substrate's typed-edge predicates (:mm.interval/before etc.) are
;; the canonical query surface — these Clojure-function wrappers exist
;; for ergonomic in-process predicate testing without needing to fish
;; out the cardinality-many ref-set + walk it manually.
;;
;; Each predicate takes two :mm/Interval entities + returns true iff the
;; first stands in that Allen-relation to the second.  Implementation
;; checks membership in the cardinality-many slot via :db/id comparison.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- allen-related?
  "Internal: does interval-a hold the given Allen relation to interval-b?
   Walks the cardinality-many ref-set on interval-a under `slot-kw` looking
   for an entity whose :db/id matches interval-b."
  [slot-kw interval-a interval-b]
  (boolean (some #(= (:db/id interval-b) (:db/id %))
                 (slot-kw interval-a))))

(defn allen-before?
  "Allen's :before — interval-a entirely precedes interval-b
   (end(a) < start(b); no overlap)."
  [interval-a interval-b]
  (allen-related? :mm.interval/before interval-a interval-b))

(defn allen-after?
  "Allen's :after — interval-a entirely follows interval-b
   (start(a) > end(b); no overlap).  Inverse-of :before; derived via prp-inv1."
  [interval-a interval-b]
  (allen-related? :mm.interval/after interval-a interval-b))

(defn allen-meets?
  "Allen's :meets — end(a) coincides with start(b) (touching but no overlap)."
  [interval-a interval-b]
  (allen-related? :mm.interval/meets interval-a interval-b))

(defn allen-met-by?
  "Allen's :met-by — start(a) coincides with end(b)."
  [interval-a interval-b]
  (allen-related? :mm.interval/met-by interval-a interval-b))

(defn allen-overlaps?
  "Allen's :overlaps — a starts before b starts AND a ends during b."
  [interval-a interval-b]
  (allen-related? :mm.interval/overlaps interval-a interval-b))

(defn allen-overlapped-by?
  "Allen's :overlapped-by — a starts during b AND a ends after b ends."
  [interval-a interval-b]
  (allen-related? :mm.interval/overlapped-by interval-a interval-b))

(defn allen-during?
  "Allen's :during — a is strictly contained within b
   (start(b) < start(a) AND end(a) < end(b); no boundary coincidence)."
  [interval-a interval-b]
  (allen-related? :mm.interval/during interval-a interval-b))

(defn allen-contains?
  "Allen's :contains — a strictly contains b
   (start(a) < start(b) AND end(b) < end(a))."
  [interval-a interval-b]
  (allen-related? :mm.interval/contains interval-a interval-b))

(defn allen-starts?
  "Allen's :starts — a shares a starting point with b AND a ends before b
   (start(a) = start(b) AND end(a) < end(b))."
  [interval-a interval-b]
  (allen-related? :mm.interval/starts interval-a interval-b))

(defn allen-started-by?
  "Allen's :started-by — a shares a starting point with b AND a ends after b
   (start(a) = start(b) AND end(b) < end(a))."
  [interval-a interval-b]
  (allen-related? :mm.interval/started-by interval-a interval-b))

(defn allen-finishes?
  "Allen's :finishes — a shares an ending point with b AND a starts after b
   (end(a) = end(b) AND start(b) < start(a))."
  [interval-a interval-b]
  (allen-related? :mm.interval/finishes interval-a interval-b))

(defn allen-finished-by?
  "Allen's :finished-by — a shares an ending point with b AND a starts before b
   (end(a) = end(b) AND start(a) < start(b))."
  [interval-a interval-b]
  (allen-related? :mm.interval/finished-by interval-a interval-b))

(defn allen-equals?
  "Allen's :equals — a and b coincide exactly
   (start(a) = start(b) AND end(a) = end(b)).  Symmetric (the only Allen
   relation that's self-inverse; derived via prp-symp on the
   :dt/EquivalenceRelationProperty intersection class)."
  [interval-a interval-b]
  (allen-related? :mm.interval/equals interval-a interval-b))
