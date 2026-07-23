# Fulltext Search

> Sandbar layers BM25F multi-field weighted scoring on top of Datomic's native Lucene-backed `:db/fulltext` indexing.  The analyzer (Unicode-aware tokenizer + Porter stemmer) is metamodel-driven; per-class `:dt/bm25f-weights` declare slot weights at the schema layer.  Result projection (snippets, facets, structural composition via `:where` Datalog) composes through one verb.  The search axis of the four-axis retrieval surface.

## Thesis

A retrieval substrate ought to answer **"find by content, ranked"** as a first-class question, not as a bolt-on feature.  Sandbar's fulltext-search primitive — `sandbar.search/search-bm25f` — is the answer: one verb, opts-shaped, composes with the rest of the substrate via shared `:where` Datalog clauses and shared `:include` projection options.

The implementation choice is settled.  Lucene's tokenization, term-frequency indexing, and inverted-list traversal are battle-tested at every scale that matters.  Sandbar uses Lucene through Datomic's native `:db/fulltext` integration for the indexing layer.  Scoring is **canonical Robertson-Zaragoza BM25F** (Robertson, Zaragoza & Taylor 2004) — the multi-field weighted form that single-field Lucene Similarity doesn't natively express.  Substrate-quality discipline: the analyzer is class-agnostic; per-class weights are declared at the metamodel, not hardcoded in the substrate.

## Lineage

### Lucene (Cutting, 1999-present)

Doug Cutting's Lucene is the canonical fulltext-indexing library — segment-based inverted indexes, term dictionaries with skip lists, positional information for phrase queries, BKD trees for numeric/spatial.  Used by Solr, Elasticsearch, OpenSearch, and (relevantly) Datomic's `:db/fulltext` attributes.  Sandbar inherits Lucene's tokenization + inverted-list traversal directly; it does not reimplement them.

### Datomic `:db/fulltext` (Hickey 2012-present)

Datomic attributes declared with `:db/fulltext true` are indexed by Lucene at transact time.  Querying is via `(fulltext $ <attribute> <query>)` — a Datalog form that returns `[eid value text score]` tuples.  Sandbar's `dt/search-fulltext` is a thin wrapper over this primitive.  Per `decisions/datomic_primary_backend_elevation_2026_05_11.md`, native Lucene integration is one of the five reasons Datomic was elevated to primary backend.

### BM25F (Robertson, Zaragoza & Taylor 2004)

BM25 is the canonical IR relevance function — Robertson & Spärck-Jones 1976 / Robertson, Walker, Beaulieu et al 1995-1998 / Spärck-Jones, Walker & Robertson 2000.  Three terms: term frequency (saturating), inverse document frequency (rarity prior), and document-length normalization.

BM25**F** (Robertson, Zaragoza & Taylor 2004; SIGIR) is the multi-field extension.  Each document has multiple fields (title, body, tags); per-field weights amplify or attenuate the contribution of each field; field-level length normalization respects each field's average length independently.  Per `decisions/bm25f_canonical_robertson_zaragoza_form.md`, Sandbar's implementation is canonical Robertson-Zaragoza form (not a Lucene-Similarity composition approximation).

### Porter stemming (Porter 1980)

The Porter Stemmer (Porter 1980, "An algorithm for suffix stripping") is the canonical algorithmic English stemmer.  Five-step rewrite pipeline that conflates morphological variants (`running` / `runs` / `ran` → stem `run`).  Sandbar's analyzer ships the Porter stemmer ported byte-for-byte from the corpus's reference implementation (`etc/lib/analysis.clj`); the ported form is used at index time AND query time so the stems match.

### Unicode-aware tokenization

Plain whitespace-tokenization fails on real text — diacritics, ligatures, mixed-script content, contraction apostrophes.  Sandbar's tokenizer uses Java's `java.text.BreakIterator` for word-boundary detection, with Unicode normalization (NFD) and combining-mark stripping for diacritic folding.  Same form as the corpus's `etc/lib/analysis.clj`; ported to Sandbar substrate at Stage 4a of the fulltext arc.

## The search verb

```clojure
(sandbar.search/search-bm25f
  {:class           :mm/Memory
   :query           "datomic recursive rules"
   :field-weights   {:mm.memory/name        12.0   ; optional override
                     :mm.memory/description  8.0
                     :mm.memory/body-raw     1.0}
   :limit           20
   :include         [:snippets :scores :facets]
   :where           '[[?e :mm.memory/memory-type :decision]]   ; optional Datalog
   :facet-by        [:mm.memory/memory-type :mm.memory/scope]  ; optional
   })

;; => {:hits [{:entity <entity-map> :score <num>
;;             :field-scores {:mm.memory/name 8.2 :body-raw 3.1 ...}
;;             :snippets    {:mm.memory/body-raw "...**datomic** **recursive** ..."}}
;;            ...]
;;     :total <int>
;;     :facets {:mm.memory/memory-type {:decision 12 :plan 7 :observation 4 ...}}
;;     :timing {:tokenize-ms 1 :score-ms 12 :total-ms 14}}
```

Three opts power the composition contract:

- **`:where`** — Datalog clauses that filter the candidate set BEFORE ranking.  Fulltext ∩ structured filter as one query.
- **`:facet-by`** — slot idents over which to compute facet counts on the matched set.  Aggregation composed with search.
- **`:include`** — projection opts: `:snippets` (approximate regex-based highlight windows), `:scores` (per-field score breakdown), `:facets` (the histogram emission).

### Schema-declared weights

Per-class weights live at the metamodel layer:

```edn
;; schema/mm.edn
{:db/ident   :mm/Memory
 :dt/type    :dt/Class
 :dt/subclass-of :dt/Resource
 :dt/slots   [:mm.memory/name :mm.memory/description :mm.memory/body-raw ...]
 :dt/bm25f-weights [[:mm.memory/name        12.0]
                    [:mm.memory/description  8.0]
                    [:mm.memory/body-raw     1.0]]}
```

The substrate reads weights from the class entity at search time via `dt/bm25f-weights-of` — no consumer hardcoding.  Weights are caller-overridable via the `:field-weights` opt; the metamodel declaration is the default.

### Snippet generation

`:include [:snippets]` emits per-slot snippet windows centered on the first query-term hit, with `**term**` markdown highlighting of all matched-term occurrences within a ~240-char window.  Implementation is approximate (regex-based, not Lucene-position-aware) — Lucene's native positional highlighter is a Phase-2 optimization deferred per `decisions/query_engine_architectural_cornerstone_2026_05_11.md`.

## The dt/* primitive surface

Substrate primitives in `sandbar.db.datatype`:

| Primitive                  | What it does                                                      |
|----------------------------|-------------------------------------------------------------------|
| `dt/search-fulltext`       | Single-attribute Lucene search; returns `[[eid score] ...]` pairs |
| `dt/bm25f-weights-of`      | Read declared weights for a class; returns `{slot weight}` map    |
| `dt/fulltext-indexed?`     | Predicate: does this attribute have `:db/fulltext true`?          |

The `sandbar.search` namespace composes these primitives + the analyzer + Datomic class-walking into the consumer-facing verb.

## Composition with the rest of the retrieval surface

Search is one axis of four (search / aggregate / navigate / orient).  The composition contract per `decisions/multi_axis_search_composition_2026_05_08.md`:

- **Search ∩ Aggregate** — `:facet-by` slot list on `search-bm25f` emits per-slot value counts over the match set.
- **Search ∩ Filter** — `:where` Datalog clauses constrain the candidate set BEFORE BM25F scoring.
- **Search ∩ Navigate** — `:from` + `:via` accept a seed entity + path-grammar expression as a PRE-FILTER restricting the candidate set to a graph-walk neighborhood (same path-grammar dialect as `navigate.path-via`; EDN-string form `"[:REP+ :cites]"`).  Composes with `:where` by intersection.
- **Search ∩ Structural rank** — re-rank by `:degree` / `:backlink-density` / `:recency` / `:freshness` over the BM25F-scored set via `:rank-by` (with `:temporal-slot` required for `:recency` / `:freshness`).
- **Search ∩ Tag tokenization** — ref-typed slots whose `:dt/range` is `:mm/Tag` automatically resolve to the target tag's weighted text content during scoring (value + alt-label + definition + scope-note + hidden-label + example).  Tagged memorials are findable through the tag's vocabulary without explicit indirection at the query layer.

## What fulltext search is NOT for

- **Exact-string matching** — that's a Datalog `[?e :slot "exact string"]` clause, not a BM25F query.  Fulltext stems and tokenizes; exact match doesn't survive.
- **Substring matching on idents** — that's `:FILTER` on the navigation axis, or a Datalog clause with `clojure.string/includes?`.
- **Vector / semantic similarity** — outside scope.  Future Sandbar may add an embedding-based axis; today's search is lexical BM25F.
- **Relevance feedback / query expansion** — outside scope.  Consumers do this above the substrate.

## Performance characteristics

Per `decisions/query_engine_architectural_cornerstone_2026_05_11.md` §12:

- **Indexing** — Lucene segment-based; transact time + segment-merge cost.  Production-scale fine.
- **Query (single-field)** — Lucene's inverted-list traversal; O(matching documents) at the index level.
- **Query (multi-field BM25F)** — Sandbar walks each weighted field, scores per-field, merges.  Linear in number of fields per query; modest constant.
- **Snippet generation** — regex-based; O(slot-text-length) per slot in match set.  Approximate; Phase-2 optimization is Lucene Highlighter for position-aware highlighting.
- **Facet counting** — Datalog aggregate over match set; O(match-set size) per facet.

Profiling cliffs we know about: high-frequency stop-words on a corpus of mostly-similar documents can produce large match sets where BM25F's discrimination is weak.  Mitigation: stop-word filtering at index time (Lucene-standard); enable per consumer demand.

## References

- Cutting, Doug.  *"Apache Lucene"*.  https://lucene.apache.org.  1999-present.
- Robertson, Stephen & Spärck-Jones, Karen.  *"Relevance weighting of search terms"*.  JASIST 27(3), 1976.  The probabilistic relevance framework underlying BM25.
- Robertson, Stephen E., Walker, S., Beaulieu, M., et al.  *"Okapi at TREC-7"*.  TREC-7 Proceedings, 1998.  BM25's emergence into the modern form.
- Spärck-Jones, Karen, Walker, S. & Robertson, S.E.  *"A probabilistic model of information retrieval: development and comparative experiments"*.  Information Processing & Management 36(6), 2000.  Two-part synthesis paper.
- Robertson, Stephen, Zaragoza, Hugo & Taylor, Michael.  *"Simple BM25 extension to multiple weighted fields"*.  CIKM '04.  The canonical BM25F derivation.
- Porter, M.F.  *"An algorithm for suffix stripping"*.  Program 14(3), 1980.  The Porter stemmer.
- Hickey, Rich.  *"Datomic"*.  https://docs.datomic.com.  2012-present.  Native `:db/fulltext` integration.

## See also

- `doc/concepts/aggregation.md` — the sibling axis that `:facet-by` composes with
- `doc/concepts/navigation.md` — the sibling axis that future `:from` + `:via` composition will compose with
- `doc/guides/searching-the-corpus.md` — task-oriented worked examples
- `doc/api/mcp-verbs.md` — `sandbar.search.bm25f` MCP entry (forthcoming at Stage 27)
- `doc/api/dt-star.md` — `dt/search-fulltext` / `dt/bm25f-weights-of` / `dt/fulltext-indexed?` substrate primitives
- The corpus's `decisions/bm25f_canonical_robertson_zaragoza_form.md` — ADR locking the canonical form
