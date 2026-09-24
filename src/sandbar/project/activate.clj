(ns sandbar.project.activate
  "Resolve a logical project binding from operator-controlled local config.

  Precedence is SANDBAR_PROJECT, the sandbar.project JVM property, then the
  client directory's .sandbar/config.edn :project key. The value names the
  stable :mm.project/ident; missing or unresolved input uses the private
  :project/UNASSIGNED sentinel. A legacy Project db/ident is also accepted.

  The binding feeds project-route's label calculation. This namespace starts
  no service and opens no database connection. It does not enroll an AI client,
  bind each remote request to a project, select a filesystem destination, or
  provide publication filtering. Those require the surrounding onboarding
  procedure and shared-service integration."
  (:require [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]
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

  These values are operator-controlled configuration, not an authorization
  boundary. A valid key for another project selects that project; only absent
  or unresolved keys fall back to UNASSIGNED. Remote clients still need an
  explicit, verified project-binding contract with the shared service."
  []
  (or (->project-key (config/getenv "SANDBAR_PROJECT"))
      (->project-key (config/getprop "sandbar.project"))
      (->project-key (config/value :project))
      route/unassigned-project-ident))

(defn active-project
  "The ACTIVE `:mm/Project` entity under `db` (per `active-project-key`), or the
   `:project/UNASSIGNED` sentinel entity when the key resolves to no live project
   (fail-closed). Resolve the stable :mm.project/ident first; a legacy db/ident
   is accepted only when it names a Project. Returns nil only if the schema
   has not seeded the sentinel."
  [db]
  (let [k (active-project-key)
        project-at (fn [reference]
                     (when-let [eid (ref/ref->eid db reference)]
                       (let [ent (d/entity db eid)
                             class-ident (label/class-ident-of ent)]
                         (when (and class-ident (label/class-isa? db :mm/Project class-ident))
                           ent))))]
    (or (project-at [:mm.project/ident k])
        (project-at k)
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
