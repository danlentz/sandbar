# Navigating with paths

> Hands-on walkthrough of Sandbar's three navigation primitives — `edges` / `walk` / `path-via`.  When to use each.  Worked examples for the Canonical-8 + Tier-2 operators.  How to compose path expressions with the rest of the retrieval surface.  For the algebraic theory, see [`doc/concepts/path-grammar.md`](../concepts/path-grammar.md); for the surface-overview, [`doc/concepts/navigation.md`](../concepts/navigation.md).

## Pick the right primitive

| Question                                              | Primitive                                |
|-------------------------------------------------------|------------------------------------------|
| "What does this entity reference?"                    | `sandbar.navigate.edges/outbound-edges`  |
| "Who references this entity?"                         | `sandbar.navigate.edges/inbound-edges`   |
| "What's reachable within N hops?"                     | `sandbar.navigate.walk/graph-walk`       |
| "What's reachable via *this specific path shape*?"    | `sandbar.navigate.path/path-via`         |

The verbs share a result-envelope shape: `{:reachable [...] :total <int> :returned <int>}` for walk + path-via; `{:edges [...] :total :returned}` for edges.  All support `:limit` (default 0 = no cap).

## Pattern 1 — Direct edge enumeration

The simplest navigation.  One Datalog query.

```clojure
(require '[sandbar.navigate.edges :as nav])

;; What does :dt/Property reference?  (all outbound ref-attribute pairs)
(nav/outbound-edges {:entity :dt/Property})
;; => {:edges [{:predicate :dt/subclass-of :target #:db{:id 12345 :ident :dt/Resource}}
;;             {:predicate :dt/slots        :target #:db{:id 67890 :ident :dt/Property}}
;;             ...]
;;     :total 17 :returned 17}

;; Restrict to a predicate set
(nav/outbound-edges
  {:entity    :dt/Property
   :predicate [:dt/subclass-of :dt/slots]})

;; Restrict to a target type
(nav/outbound-edges
  {:entity      :dt/Property
   :target-type :dt/Class})

;; Who references :dt/Resource?  (inbound)
(nav/inbound-edges
  {:entity      :dt/Resource
   :predicate   :dt/subclass-of
   :source-type :dt/Class})
```

**Use when:** the question is shape-local.  Library-card-style 10-axis views (composing inbound-edges across many typed-edge predicates) live here.

## Pattern 2 — Bounded BFS reachability

The common-case answer.

```clojure
(require '[sandbar.navigate.walk :as walk])

;; What's reachable from :decisions/foundation within 3 hops?
(walk/graph-walk
  {:from :decisions/foundation
   :hops 3})

;; Restrict the walk to specific predicates
(walk/graph-walk
  {:from       :decisions/foundation
   :hops       4
   :predicates [:cites :evidences :informs]})

;; Walk backward (inbound edges from each frontier)
(walk/graph-walk
  {:from      :decisions/cornerstone
   :hops      3
   :direction :inverse})       ; :forward (default) / :inverse / :bidirectional

;; Attach shortest-path step sequence to each result
(walk/graph-walk
  {:from    :decisions/foundation
   :hops    3
   :include [:paths]})
;; => {:reachable [{:entity <entity-map> :hop 1
;;                  :path  [{:predicate :cites :direction :forward}]}
;;                 {:entity <entity-map> :hop 2
;;                  :path  [{:predicate :cites :direction :forward}
;;                          {:predicate :evidences :direction :forward}]}
;;                 ...]}
```

BFS guarantees first-arrival shortest-path.  Visited-set deduplication prevents revisit.  Self-loops handled: the seed is excluded from its own walk results.

**Use when:** "neighborhood-shaped" navigation.  Most real-world cases fit here.  Hop-cap defaults to 4 — adequate for most graph-walk queries against a corpus of ~1000-10k entities.

## Pattern 3 — Path expressions

The complete answer.  Wilbur-lineage Kleene-algebra-over-binary-relations.

### Basic forms — Canonical-8

```clojure
(require '[sandbar.navigate.path :as path])

;; Atomic predicate
(path/path-via {:from :dt/Property :via :dt/subclass-of})

;; Sequence — :SEQ (n-ary)
(path/path-via {:from :dt/Property
                :via  [:SEQ :dt/subclass-of :dt/subclass-of]})  ; 2 hops up

;; Alternation — :OR (n-ary)
(path/path-via {:from :decisions/foundation
                :via  [:OR :cites :evidences :informs]})         ; 1 hop via any

;; Transitive closure — :REP+ (1+ applications)
(path/path-via {:from :dt/Property
                :via  [:REP+ :dt/subclass-of]})                  ; all ancestors

;; Reflexive-transitive closure — :REP* (0+ applications)
(path/path-via {:from :dt/Property
                :via  [:REP* :dt/subclass-of]})                  ; self + all ancestors

;; Inverse — :INV (swap subject/object roles)
(path/path-via {:from :dt/Resource
                :via  [:INV :dt/subclass-of]})                   ; subclasses of :dt/Resource

;; Identity — :SELF (no-op step)
(path/path-via {:from :dt/Property :via :SELF})                  ; just :dt/Property

;; Wildcard predicate — :ANY
(path/path-via {:from :dt/Property :via :ANY})                   ; all outbound edges

;; Specific-node restriction — :RESTRICT [pred value]
(path/path-via
  {:from :decisions/foundation
   :via  [:SEQ [:REP* :cites]
               [:RESTRICT [:dt/type :mm.memory/decision]]]})     ; only decision-typed results
```

### Tier-2 forms

```clojure
;; Negated property set — :NOT (atomic predicates only)
(path/path-via {:from :dt/Property
                :via  [:NOT :dt/subclass-of]})                   ; any outbound except :dt/subclass-of

;; Zero-or-one — :OPT (desugars to (:OR p :SELF))
(path/path-via {:from :dt/Property
                :via  [:OPT :dt/subclass-of]})                   ; self + immediate parent

;; Bounded repetition — :REP p min max (Cypher-style)
(path/path-via {:from :decisions/foundation
                :via  [:REP :cites 2 4]})                        ; 2-to-4 hops via :cites

;; URI-substring filter — :FILTER (on :db/ident)
(path/path-via {:from :dt/Resource
                :via  [:FILTER [:INV :dt/subclass-of] "Property"]})  ; subclasses w/ "Property" in ident

;; Functional predicate — :TEST (via registered fn)
(path/path-via {:from :dt/Resource
                :via  [:TEST [:INV :dt/subclass-of] :keyword?]})  ; subclasses w/ keyword? at end
```

For the full operator table, including Tier-3 (vocabulary-registered, compilation deferred), see [`doc/concepts/path-grammar.md`](../concepts/path-grammar.md).

### Composition idioms

The algebra composes — paths are values.

```clojure
;; SEQUENCE OF OR
(path/path-via
  {:from :decisions/foundation
   :via  [:SEQ [:OR :cites :evidences]                ; one hop via :cites OR :evidences
               [:REP+ :informs]]})                     ; then 1+ :informs hops

;; KLEENE PLUS OF ALTERNATION
(path/path-via
  {:from :decisions/foundation
   :via  [:REP+ [:OR :cites :evidences]]})            ; transitive via either predicate

;; OPTIONAL SUFFIX
(path/path-via
  {:from :decisions/foundation
   :via  [:SEQ :cites [:OPT [:RESTRICT [:dt/type :mm.memory/decision]]]]})

;; INVERSE OF REP+
(path/path-via
  {:from :decisions/cornerstone
   :via  [:INV [:REP+ :cites]]})                      ; entities that transitively cite cornerstone

;; PATH AS VALUE — :include [:paths] surfaces path data (currently flagged
;; :path-data-deferred for recursive paths; lands at follow-on)
(path/path-via
  {:from    :decisions/foundation
   :via     [:REP+ :cites]
   :include [:paths]})
```

## EDN forms vs Clojure-data forms

`:via` accepts either:

- **EDN string** — `"[:REP+ :dt/subclass-of]"` — for MCP / REST consumers
- **Clojure data** — `[:REP+ :dt/subclass-of]` — for in-process consumers

The MCP wire encoding is EDN-string (JSON has no native representation for keywords or symbols); REST query-param encoding is URL-encoded EDN.  **Important:** when URL-encoding, `+` decodes as space (per `application/x-www-form-urlencoded`).  Use `%2B` for the literal `+` in `:REP+`:

```
GET /api/navigate/path?from=:dt/Property&via=%5B:REP%2B%20:dt/subclass-of%5D
```

## Through MCP

The `sandbar.navigate.path-via` MCP verb accepts the same opts shape:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
        "name":"sandbar.navigate.path-via",
        "arguments":{
          "from":":dt/Property",
          "via":"[:REP+ :dt/subclass-of]",
          "limit":50}}}'
```

## Cross-axis composition

Path-grammar composes with the rest of the retrieval surface via the four-axis composition contract.  Stage 29 wires `:from` + `:via` onto the search axis — live in `sandbar.search.bm25f` (both the Clojure fn and the MCP verb); the aggregate-axis composition is not yet wired.

Today (Stage P-6 landed):

```clojure
;; Walk a graph neighborhood, then filter Datalog-side
(let [{:keys [reachable]}
      (path/path-via {:from :decisions/foundation
                      :via  [:REP* :cites]})
      eids (mapv :db/id reachable)]
  ;; Use the eids as input to a separate Datalog query for content-filtering
  ...)
```

Stage 29 composition (live):

```clojure
(sandbar.search/search-bm25f
  {:from  :decisions/foundation
   :via   [:REP* :cites]            ; graph-walk neighborhood becomes candidate set
   :query "datomic recursive rules"
   :limit 20})
```

## What about the corpus `/memory-search --from --via`?

The corpus's filesystem-walker implementation of path-grammar (`etc/lib/index.clj`, ~449 LOC) was the proof-of-concept for the operator semantics.  Stage P-7 of the comprehensive arc wires `/memory-search --from --via` through Sandbar's `path-via` with a hybrid-period parity comparison.  The corpus's substrate trajectory is to deprecate the filesystem-walker once Sandbar parity is verified.

## See also

- [`doc/concepts/navigation.md`](../concepts/navigation.md) — surface overview (edges / walk / path)
- [`doc/concepts/path-grammar.md`](../concepts/path-grammar.md) — algebra + lineage + operator surface
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — `sandbar.navigate.path-via` MCP entry
- [`doc/api/http-rest.md`](../api/http-rest.md) — `GET /api/navigate/path` REST entry
