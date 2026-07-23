(ns sandbar.project.activate
  "W1.ctx SPINE — per-project CONTEXT ACTIVATION from the operating-model ruling
  (ratified fork 5, Dan 2026-07-07): *\"start the tool FROM the project's own
  directory\"* (NOT corpus-dir-and-reach-out; W1.deploy §4).

  Under that model the session's ACTIVE project is named by the directory it was
  launched from — surfaced two ways, most-specific first:

    1. `SANDBAR_PROJECT` env-var / `sandbar.project` JVM prop  (per-session, wins)
    2. the client-project config `:project` key                (the project's
       own `<CLIENT_DIR>/.sandbar/config.edn` naming ITSELF — fork-5's whole
       point: a session started from project P's dir reads P's config)

  Absent both ⇒ the fail-closed `:project/UNASSIGNED` sentinel: an un-activated
  session binds the private UNASSIGNED scope, never the public bottom.  This is
  the confidentiality floor sitting at the TOOLING layer, not just the DB layer
  (W1.deploy §4): the active binding is per-project, so one session never
  silently inherits another project's scope.

  Activation resolves to the project's routing decision via
  `sandbar.project.route/project-route` — consuming the ONE shared label core,
  so MOST-RESTRICTIVE composition holds at the activation point too.

  ── SCOPE BOUNDARY (W1.ctx spine; W1.deploy is a LATER phase, OUT here) ──────
  This is the LOGICAL activation (which project/context/scope is active for the
  session).  The PHYSICAL bring-up it feeds — spinning up the per-scope
  transactor process, loading the store-registry file, the credential air-gap —
  is W1.deploy, NOT built here.  This ns reads config + the DB and returns the
  logical binding; it starts nothing and connects nowhere.

  Spec: arc-plan §1.6 R9 / DAN-GATE 5 (operating model), W1.deploy §4."
  (:require [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.ref :as ref]
            [sandbar.project.route :as route]))

(defn- ->project-key
  "Coerce a raw activation value to a `:mm.project/ident` keyword.  A keyword
  passes through; a string (env-var / prop form, with or without a leading `:`)
  is read as a keyword; anything else ⇒ nil (fail-closed to UNASSIGNED)."
  [raw]
  (cond
    (keyword? raw)          raw
    (and (string? raw)
         (seq raw))         (keyword (subs raw (if (= \: (first raw)) 1 0)))
    :else                   nil))

(defn active-project-key
  "The ACTIVE project's `:mm.project/ident`, resolved from the operating-model
  surfaces most-specific-first: `SANDBAR_PROJECT` env-var (or the
  `sandbar.project` JVM prop) → the client-project config `:project` key →
  the fail-closed `:project/UNASSIGNED` sentinel.

  The env/prop reads route through `config/getenv` / `config/getprop` (the
  redefinable seams) so a test can stub the launched-from-dir signal without
  touching the real environment.  Never throws — an un-activated session
  resolves to `:project/UNASSIGNED`.

  TRUST ASSUMPTION (A-3 / CODEX-1 LOW).  The three activation surfaces —
  `SANDBAR_PROJECT`, the `sandbar.project` JVM prop, and the client-project
  config `:project` key — are OPERATOR-CONTROLLED inputs (the operator chooses
  which project directory to launch the tool from; fork-5 operating model), NOT
  attacker-controlled or memory-content-derived.  Reading a LOGICAL project
  ident from them is trusted config, not a physical-topology build: this ns
  opens no connection, starts no process, and reads no transactor URL (that is
  W1.deploy).  No attacker-controlled data flows into the activation key, and a
  mis-configured key can only MIS-NAME the active project — which fail-closes to
  a private per-project scope (`:routes-to-public? false`), never widening to
  the public bottom."
  []
  (or (->project-key (config/getenv "SANDBAR_PROJECT"))
      (->project-key (config/getprop "sandbar.project"))
      (->project-key (config/value :project))
      route/unassigned-project-ident))

(defn active-project
  "The ACTIVE `:mm/Project` entity under `db` (per `active-project-key`), or the
  `:project/UNASSIGNED` sentinel entity when the key resolves to no live project
  (fail-closed).  Returns nil only if the schema has not seeded the sentinel."
  [db]
  (let [k (active-project-key)]
    (or (some->> (ref/ref->eid db k) (d/entity db))
        (some->> (ref/ref->eid db route/unassigned-project-ident)
                 (d/entity db)))))

(defn active-route
  "The routing decision the ACTIVE project binds for this session — the
  per-project context activation made concrete (`route/project-route` over
  `active-project`).  Fail-closed: an un-activated / unresolvable session binds
  the `:project/UNASSIGNED` private scope (`:routes-to-public? false`)."
  [db]
  (route/project-route db (active-project db)))

(defn active-contexts
  "The `:mm/Context` eids the active project runs in — the contexts ACTIVATED
  for this session (the `runs-in-context` set of `active-project`).  Empty when
  the active project (or the UNASSIGNED sentinel) resolves no context."
  [db]
  (:contexts (active-route db)))
