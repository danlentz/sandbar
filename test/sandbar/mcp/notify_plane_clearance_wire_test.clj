(ns sandbar.mcp.notify-plane-clearance-wire-test
  "AP-S4-2 notify-plane wire contract: a `:private` entity update fans out to a
   CLEARED (same-compartment) subscriber and NOT to an UNCLEARED
   (out-of-compartment) one — the confidentiality-scoped delivery gate the
   ceremony-#4 resolution left OPEN.

   ## Why this test exists (the DEP-S5→S9 rider)

   `bugs/resource_subscriptions_are_broadcast_only_and_unwired_2026_05_12` is
   `status:resolved` for CORRECTNESS, but its resolution wired the mutation path
   and made the private→any notify leak LIVE with NO clearance predicate: a
   `::broadcast`-bound subscriber received EVERY entity's update, regardless of
   compartment.  S5's EP-N2 delivery filter (`resources/entity-updated!` calling
   `clearance/subscriber-cleared-for-entity?`) closes that leak; this test pins
   it over the REAL subscriber registry + delivery seam.

   ## The shape (D3 §8, ratified CI shape — AP-4 / OF-D3-8)

   The notify plane is SSE, not request/response, so a full live-socket harness
   is heavy (deferred to S9's compile-guarded live-SSE probe, OF-D3-8).  The
   buildable-today shape drives the DELIVERY SEAM directly: register two
   subscribers in the REAL `sandbar.mcp.notifications` registry (the exact
   registry the wire's `sse-stream-ready` populates), each with a DISTINCT
   principal + a `send!` closure that captures delivered frames into an atom;
   fire `resources/entity-updated!` for a `:private` entity; assert only the
   cleared subscriber's closure received the frame.

   ## No DB, no S6 schema (INERT-UNTIL-S6, HARD RULE 5)

   The S6 compartment slots (`:mm.memory/visibility`,
   `:mm.memory/owning-project`, `:auth/cleared-projects`, `:auth/full-clearance?`)
   do NOT exist in the schema yet (grep-proven absent, D3 §7).  The clearance
   predicates are PURE keyword-as-fn lookups over already-loaded maps
   (`sandbar.mcp.clearance`), and `resources/entity->uri` reads a map's
   `:dt/type` directly (`db/entity` returns an associative arg as-is), so this
   test feeds BARE maps carrying those keys — the exact projection shape the
   wire hands the delivery path once S6 mints the slots.  This needs no DB
   fixture and no `:firewall` schema; it is the same map-driven idiom the pure
   companion `clearance_test.clj` uses, but exercised through the real registry
   + the real `entity-updated!` seam.

   Per S5-PLAN.md §2.2 item 8 + §2.4 (criterion-3 CI shape) + DESIGN-D3 §8."
  (:require [clojure.test              :refer :all]
            [sandbar.mcp.clearance     :as clearance]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.mcp.resources     :as resources]))

;; Registry state is process-global (defonce atoms survive between deftests and
;; across reloads — R4).  Clear before AND after every test so a stray
;; subscriber from another suite cannot receive (and thus mask) a leak here.
(use-fixtures :each
  (fn [t]
    (notifications/clear-all!)
    (resources/clear-all-subscriptions!)
    (try (t)
         (finally
           (notifications/clear-all!)
           (resources/clear-all-subscriptions!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bare-map fixtures — the wire projection shape (no DB; S6 slots as plain keys)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private proj-a-eid 100)
(def ^:private proj-b-eid 200)

(defn- principal-clearing
  "A ServiceAccount-shaped principal cleared for `project-eids` (the S6
   `:auth/cleared-projects` multi-ref, here a plain seq of `{:db/id …}` refs)."
  [service-name & project-eids]
  {:db/id             (hash service-name)
   :auth/service-name service-name
   :auth/cleared-projects (mapv (fn [eid] {:db/id eid}) project-eids)})

(defn- private-entity
  "A `:private` `:mm/Memory`-shaped entity owned by `project-eid`.  `:dt/type`
   + `:db/id` are present so `resources/entity->uri` resolves the URI purely
   (no DB); `:mm.memory/visibility` + `:mm.memory/owning-project` are the S6
   compartment keys read by `clearance/entity-compartment`."
  [db-id project-eid]
  {:db/id                    db-id
   :dt/type                  :mm/Memory
   :db/ident                 (keyword "mem" (str "private-" db-id))
   :mm.memory/visibility     :private
   :mm.memory/owning-project {:db/id project-eid}})

(defn- public-entity
  "A `:public` `:mm/Memory`-shaped entity — the lattice bottom (everyone cleared)."
  [db-id]
  {:db/id                db-id
   :dt/type              :mm/Memory
   :db/ident             (keyword "mem" (str "public-" db-id))
   :mm.memory/visibility :public})

(defn- register-capturing!
  "Register a subscriber in the REAL notifications registry with `principal` as
   its `:identity` (the way `sse-stream-ready` registers a wire subscriber).
   Its `send!` captures each delivered frame into `capture-atom` and returns
   true (the successful-delivery signal `deliver-event!` expects).  Returns the
   subscriber-id."
  [principal capture-atom]
  (notifications/register!
    {:identity principal
     :send!    (fn [event] (swap! capture-atom conj event) true)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Guard — the EP-N2 delivery filter must be wired into entity-updated!.
;;
;; The clearance-filtered fan-out is a source-owned rewrite of
;; `resources/entity-updated!` (S5-PLAN §2.2 item 5).  Until it lands, the
;; delivery seam broadcasts unconditionally and the discriminating assertion
;; below (uncleared subscriber gets ZERO frames) would fail.  A green run here
;; is the wire proof that the EP-N2 filter is live over the real registry.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest private-update-fans-out-only-to-cleared-subscriber
  ;; The keystone AP-S4-2 assertion — driven through the REAL registry + the
  ;; REAL entity-updated! delivery seam (NOT the pure predicate, which
  ;; clearance_test.clj already tables).
  (let [prin-A  (principal-clearing :sub-A proj-a-eid)   ; clears ctx-A
        prin-B  (principal-clearing :sub-B proj-b-eid)   ; clears ctx-B only
        got-A   (atom [])
        got-B   (atom [])
        id-A    (register-capturing! prin-A got-A)
        id-B    (register-capturing! prin-B got-B)
        mem     (private-entity 30 proj-a-eid)           ; :private, owned by ctx-A
        uri     (resources/entity->uri mem)]
    ;; Both subscribers are ::broadcast-bound (the fail-open legacy default) —
    ;; the EP-N2 delivery filter must STILL exclude the uncleared one.  This is
    ;; the exact leak: a ::broadcast sub predates any URI, so subscribe-time
    ;; alone cannot judge it; delivery-time clearance is the only gate.
    (resources/subscribe! uri resources/broadcast-sentinel)
    (resources/entity-updated! mem)
    (testing "cleared same-compartment subscriber RECEIVES the private update"
      (is (= 1 (count @got-A))
          (str "subscriber " id-A " cleared for ctx-A must receive the ctx-A "
               ":private notification (the channel must still work); got "
               (pr-str @got-A))))
    (testing "uncleared out-of-compartment subscriber does NOT receive it (the leak, blocked)"
      (is (empty? @got-B)
          (str "subscriber " id-B " cleared only for ctx-B must NOT receive the "
               "ctx-A :private notification — this is the private→any leak the "
               "EP-N2 delivery filter closes.  A non-empty capture means "
               "resources/entity-updated! broadcast without a clearance check "
               "(the S5-PLAN §2.2 item 5 rewrite is not live); got "
               (pr-str @got-B))))))

(deftest private-update-blocks-uncleared-even-with-full-clearance-operator-present
  ;; Full-clearance operator (AP-11 mechanism (b)) receives it; the uncleared
  ;; restricted subscriber still does not — the filter keeps-if-cleared, it is
  ;; not a global mute nor a global pass.
  (let [prin-op  {:db/id 1 :auth/service-name :operator :auth/full-clearance? true}
        prin-B   (principal-clearing :sub-B proj-b-eid)
        got-op   (atom [])
        got-B    (atom [])
        _        (register-capturing! prin-op got-op)
        _        (register-capturing! prin-B  got-B)
        mem      (private-entity 31 proj-a-eid)
        uri      (resources/entity->uri mem)]
    (resources/subscribe! uri resources/broadcast-sentinel)
    (resources/entity-updated! mem)
    (testing "full-clearance operator receives a :private update (AP-11)"
      (is (= 1 (count @got-op))))
    (testing "uncleared restricted subscriber still excluded (filter is selective, not global)"
      (is (empty? @got-B)))))

(deftest public-update-fans-out-to-both-subscribers
  ;; Negative control — a :public entity is the lattice bottom, so EVERY
  ;; subscriber (cleared for any / no compartment) receives it.  Proves the
  ;; gate is a compartment FILTER, not a blanket suppression of the notify plane.
  (let [prin-A  (principal-clearing :pub-A proj-a-eid)
        prin-B  (principal-clearing :pub-B proj-b-eid)
        got-A   (atom [])
        got-B   (atom [])
        _       (register-capturing! prin-A got-A)
        _       (register-capturing! prin-B got-B)
        pub     (public-entity 32)
        uri     (resources/entity->uri pub)]
    (resources/subscribe! uri resources/broadcast-sentinel)
    (resources/entity-updated! pub)
    (testing "both subscribers receive a :public update (everyone cleared)"
      (is (= 1 (count @got-A))
          "subscriber cleared for ctx-A must receive the :public notification")
      (is (= 1 (count @got-B))
          "subscriber cleared for ctx-B must ALSO receive it — :public is the lattice bottom"))))

(deftest inert-until-s6-default-private-blocks-all-restricted-subscribers
  ;; The pre-S6 posture keystone: an entity with NO `:mm.memory/visibility` slot
  ;; (every entity, today) defaults to :private (*default-visibility*, AP-6), so
  ;; a restricted subscriber receives NOTHING until S6 mints real compartments.
  ;; This is the fail-closed guarantee the instant the gate lands, before any S6
  ;; slot exists.  A full-clearance operator still gets through.
  (let [prin-restricted (principal-clearing :restricted proj-a-eid)
        prin-op         {:db/id 2 :auth/service-name :op :auth/full-clearance? true}
        got-r           (atom [])
        got-op          (atom [])
        _               (register-capturing! prin-restricted got-r)
        _               (register-capturing! prin-op         got-op)
        ;; A pre-S6 entity: :dt/type + :db/id for URI resolution, NO visibility
        ;; slot — entity-compartment defaults it to :private.
        pre-s6-entity   {:db/id 40 :dt/type :mm/Memory :db/ident :mem/pre-s6-40}
        uri             (resources/entity->uri pre-s6-entity)]
    ;; sanity: the default really is :private (guards against a *default-visibility* flip)
    (is (= :private (:visibility (clearance/entity-compartment pre-s6-entity)))
        "pre-S6 default-visibility must be :private (AP-6) for this test's premise to hold")
    (resources/subscribe! uri resources/broadcast-sentinel)
    (resources/entity-updated! pre-s6-entity)
    (testing "restricted subscriber receives NOTHING for a pre-S6 (default-:private) entity"
      (is (empty? @got-r)
          (str "a pre-S6 entity defaults to :private; a restricted subscriber "
               "must be denied by default (fail-closed); got " (pr-str @got-r))))
    (testing "full-clearance operator still receives it (AP-11 short-circuit)"
      (is (= 1 (count @got-op))))))
