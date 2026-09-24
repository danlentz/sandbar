# Tutorial — Zorp's Galactic Footwear Emporium

Zorp sells footwear for customers with varied numbers of feet, uncertain gravity, and occasional disagreements with their shoes. His inventory needs a model that shares ordinary product properties while leaving room for specialized ones.

In this tutorial you will load a small class hierarchy, add three products, and query them at different levels of the hierarchy. You will also meet Kevin, a philosophical flip-flop.

## Load the example model

Use a disposable development database with Sandbar's required schemas loaded and a connected Clojure REPL. The example schema is included in [schema/zorp.edn](../../schema/zorp.edn), but is not part of the default schema set.

```clojure
(require '[sandbar.db.datomic :as db]
         '[sandbar.db.datatype :as dt])

(db/load-schema :zorp)
```

This loads the example into the configured development database. Start with an empty inventory so the counts below have their stated meaning.

Use a fresh development process and load this model before creating inventory or warming type queries. Single-file schema loading does not clear previously cached type relations. The tutorial does not establish safe hot editing of an already used inheritance graph.

## Explore the hierarchy

```text
Footwear (abstract)
├── Sneaker
│   ├── HighTop
│   └── LowTop
├── Boot
│   ├── SpaceBoot
│   └── MoonBoot
└── Sandal
    └── FlipFlop
```

The root describes common properties: name, size, color, tentacle count, gravity rating, price, and sentience. Sneakers add bounce and laces; boots add vacuum and temperature properties; flip-flops add mood and a few practical concerns such as escape velocity.

```clojure
(set (dt/direct-subclasses-of :zorp/Footwear))
;; => #{:zorp/Sneaker :zorp/Boot :zorp/Sandal}

(count (dt/subclasses-of :zorp/Footwear))
;; => 8

(contains? (dt/slots-of :zorp/HighTop) :footwear/price)
;; => true

(contains? (dt/slots-of :zorp/HighTop) :sneaker/bounce-factor)
;; => true
```

`HighTop` declares no extra slots. Its effective slots include those inherited from `Sneaker`, `Footwear`, and the substrate's resource classes. Declaring the hierarchy once lets the query machinery recover those consequences.

With the bundled schema, the effective slot counts are:

| Class | Effective slots | Explanation |
| --- | --- | --- |
| Footwear | 12 | Seven footwear properties plus five inherited substrate properties |
| Sneaker, HighTop, LowTop | 17 | Footwear plus five sneaker properties |
| Boot, SpaceBoot, MoonBoot | 14 | Footwear plus two boot properties |
| Sandal | 12 | Footwear's properties |
| FlipFlop | 16 | Sandal plus four flip-flop properties |

Use `dt/slots-of` to inspect the actual set. The counts are observations of this example schema, not limits on a class.

## Add inventory

Give each product an explicit ident so later examples can refer to it:

```clojure
(dt/make :zorp/HighTop
  {:db/ident :zorp.product/orbit-high-top
   :footwear/name "Orbit High Top"
   :footwear/price 79.95M
   :sneaker/bounce-factor 4.2})

(dt/make :zorp/MoonBoot
  {:db/ident :zorp.product/lunar-boot
   :footwear/name "Lunar Boot"
   :footwear/price 119.95M})

(dt/make :zorp/FlipFlop
  {:db/ident :zorp.product/kevin
   :footwear/name "Kevin"
   :footwear/price 29.95M
   :footwear/sentient? true
   :flipflop/mood "philosophical"})
```

The `M` suffix creates a Clojure decimal value, matching the price attribute's `:db.type/bigdec`. Other properties use their declared value types. `dt/make` checks the class data before transacting.

The example schema leaves these product properties optional. The lunar boot therefore does not need a temperature range to be created. If Zorp needs that rule before listing boots for sale, it must be declared as a requirement and enforced by the accepting operation.

## Query the right population

```clojure
(count (dt/all-instances-of :zorp/Footwear))
;; => 3

(count (dt/direct-instances-of :zorp/Footwear))
;; => 0

(set (dt/named-idents-of :zorp/Sneaker))
;; => #{:zorp.product/orbit-high-top}
```

There are three pieces of footwear, even though none declares its type as the abstract root. The sneaker query includes the high-top through inheritance. The direct-instance query asks a different question and returns no root instances.

Choose `dt/named-idents-of` when the names are sufficient. Choose `dt/named-entities-of` or `dt/all-instances-of` when you need the product values. These return different shapes deliberately.

## Inspect one product and its model

```clojure
(def kevin (dt/find-by-ident :zorp.product/kevin))

(:flipflop/mood kevin)
;; => "philosophical"

(dt/class-ident-of kevin)
;; => :zorp/FlipFlop

(dt/instance-of? :zorp/Footwear kevin)
;; => true

(dt/type-isa? :zorp/Footwear :zorp/FlipFlop)
;; => true

(dt/range-of :footwear/price)
;; => :db.type/bigdec
```

The first predicate asks about a stored entity; the second asks about two classes. Notice the argument order: the broader class comes first. `dt/class-entity-of` retrieves metadata for a class ident if you want to inspect its declaration.

The model is ordinary queryable data. A client can discover which slots a class supports and what range a property has without a separate handwritten inventory of the schema.

## Ask a small business question

Which sentient products need a conversation before a sale?

```clojure
(->> (dt/all-instances-of :zorp/Footwear)
     (filter :footwear/sentient?)
     (mapv #(select-keys % [:db/ident :footwear/name :flipflop/mood])))
;; => [{:db/ident :zorp.product/kevin,
;;      :footwear/name "Kevin",
;;      :flipflop/mood "philosophical"}]
```

This small example filters the enumerated entities in Clojure. Larger inventories can push structural predicates into the query layer; the [aggregation chapter](../concepts/aggregation.md) explains those operations.

Kevin's mood is data. It has no automatic effect on sale eligibility until the application defines and invokes that policy. This is the same distinction that separates declaring a class from deploying a workflow or enforcing a shape.

## Try an invalid construction

The root class is abstract:

```clojure
(try
  (dt/make :zorp/Footwear {:footwear/name "Abstract shoe"})
  (catch clojure.lang.ExceptionInfo e
    (:errors (ex-data e))))
;; includes {:type :abstract-class, :class :zorp/Footwear, ...}
```

This refusal concerns the class model. A content rule such as “a sentient product has a recorded mood” belongs in a shape or application validator, with tests for both its positive and negative cases. [Authoring shapes](authoring-shapes.md) develops that next step.

## Bring the model to a client

The tutorial uses the Clojure API so you can see modeling and query behavior directly. The MCP catalog provides stable schema-inspection operations, but a newly introduced domain namespace also needs an explicit instance-exposure policy before an external client can read it. Adding a class does not grant access to its data.

To build your own domain, follow [Defining new classes](defining-new-classes.md). For the mechanics behind inherited slots and broader populations, read [the metamodel](../concepts/metamodel.md) and [inference](../concepts/rdfs-entailment.md). The [`dt/*` reference](../api/dt-star.md) gives the operation signatures used here.
