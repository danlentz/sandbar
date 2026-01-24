# Zorp's Galactic Footwear Emporium

> "If it doesn't fit your tentacles, we'll grow you new ones!"
> — Zorp the Magnificent, Proprietor

## Introduction

Meet Zorp, a three-eyed, seven-tentacled entrepreneur from the Kepler-442b system who runs the most successful footwear shop on Pluto's dark side. Business is booming ever since the Interstellar Tourism Board declared Pluto a "must-visit destination for beings with 3+ appendages."

Zorp's inventory has grown chaotic. He's got anti-gravity sneakers mixed with methane-resistant boots, and last week a customer accidentally bought a pair of sentient sandals that now refuse to leave.

It's time to organize this mess with a proper **ontology**.

## The Footwear Ontology

Zorp needs to classify his inventory. After consulting with the Galactic Standards Bureau (and bribing a few officials with imported Earth chocolate), he's designed this hierarchy:

```
                          dt/Resource
                               |
                           dt/Ref
                               |
                      zorp/Footwear [abstract]
              ________________|________________
             |                |                |
        zorp/Sneaker     zorp/Boot       zorp/Sandal
        _____|_____       ____|____          |
       |           |     |         |    zorp/FlipFlop
  zorp/HighTop  zorp/LowTop  |    |
                    zorp/SpaceBoot  zorp/MoonBoot
```

### Property Inheritance

Each class defines its own slots (properties), which are inherited by subclasses:

```
dt/Resource
│   ├── db/doc           : string     "Documentation"
│   ├── db/ident         : keyword    "Unique identifier"
│   ├── dt/label         : string     "Display label"
│   ├── dt/namespace     : string     "Namespace"
│   └── dt/type          : ref->Class "Type reference"
│
└── dt/Ref
    │   (no additional slots)
    │
    └── zorp/Footwear [abstract]
        │   ├── footwear/name           : string   "Product name"
        │   ├── footwear/size           : string   "Galactic size (e.g., '7-tentacle')"
        │   ├── footwear/color          : string   "Color (UV/IR supported)"
        │   ├── footwear/tentacle-count : long     "Number of appendages"
        │   ├── footwear/gravity-rating : double   "Optimal gravity in Gs"
        │   ├── footwear/price          : bigdec   "Price in Plutonian Credits"
        │   └── footwear/sentient?      : boolean  "Has it achieved consciousness?"
        │
        ├── zorp/Sneaker
        │   │   ├── sneaker/bounce-factor  : double  "Bounce height in meters"
        │   │   ├── sneaker/glow-in-dark?  : boolean "Visible on Pluto's dark side"
        │   │   ├── sneaker/squeak-volume  : long    "Decibels per step (0=silent, 140=spacecraft)"
        │   │   ├── sneaker/lace-type      : string  "Lace style (self-tying, magnetic, tentacle-knot)"
        │   │   └── sneaker/air-pump?      : boolean "Has 1990s-style air pump (Earth retro)"
        │   │
        │   ├── zorp/HighTop
        │   │       (no additional slots - extra ankle support)
        │   │
        │   └── zorp/LowTop
        │           (no additional slots - classic style)
        │
        ├── zorp/Boot
        │   │   ├── boot/vacuum-rated?    : boolean "Safe for spacewalks"
        │   │   └── boot/temperature-range : string "Operating temp range"
        │   │
        │   ├── zorp/SpaceBoot
        │   │       (no additional slots - magnetic soles)
        │   │
        │   └── zorp/MoonBoot
        │           (no additional slots - retro style)
        │
        └── zorp/Sandal
            │   (no additional slots)
            │
            └── zorp/FlipFlop
                    ├── flipflop/flop-frequency    : double  "Flops per meter walked"
                    ├── flipflop/toe-separator-count : long  "Number of toe dividers (species-dependent)"
                    ├── flipflop/escape-velocity   : double  "Top speed in m/s (sentient units only)"
                    └── flipflop/mood              : string  "Current emotional state (sentient units only)"
```

### Effective Slots by Class

Here's what each concrete class actually has available (inherited + direct):

| Class | Effective Slots |
|-------|-----------------|
| `zorp/HighTop` | *From Resource:* db/doc, db/ident, dt/label, dt/namespace, dt/type<br>*From Footwear:* footwear/name, footwear/size, footwear/color, footwear/tentacle-count, footwear/gravity-rating, footwear/price, footwear/sentient?<br>*From Sneaker:* sneaker/bounce-factor, sneaker/glow-in-dark?, sneaker/squeak-volume, sneaker/lace-type, sneaker/air-pump?<br>**Total: 17 slots** |
| `zorp/LowTop` | *(Same as HighTop)*<br>**Total: 17 slots** |
| `zorp/SpaceBoot` | *From Resource:* db/doc, db/ident, dt/label, dt/namespace, dt/type<br>*From Footwear:* footwear/name, footwear/size, footwear/color, footwear/tentacle-count, footwear/gravity-rating, footwear/price, footwear/sentient?<br>*From Boot:* boot/vacuum-rated?, boot/temperature-range<br>**Total: 14 slots** |
| `zorp/MoonBoot` | *(Same as SpaceBoot)*<br>**Total: 14 slots** |
| `zorp/FlipFlop` | *From Resource:* db/doc, db/ident, dt/label, dt/namespace, dt/type<br>*From Footwear:* footwear/name, footwear/size, footwear/color, footwear/tentacle-count, footwear/gravity-rating, footwear/price, footwear/sentient?<br>*From FlipFlop:* flipflop/flop-frequency, flipflop/toe-separator-count, flipflop/escape-velocity, flipflop/mood<br>**Total: 16 slots** |

### Schema Definition

Here's how Zorp defines his footwear ontology in EDN (see [schema/zorp.edn](../schema/zorp.edn)):

```clojure
;; schema/zorp.edn
[
 ;; Pre-declare entities
 [{:db/id #db/id[:db.part/user -1]
   :db/ident :zorp/Footwear}
  {:db/id #db/id[:db.part/user -2]
   :db/ident :zorp/Sneaker}
  {:db/id #db/id[:db.part/user -3]
   :db/ident :zorp/Boot}
  {:db/id #db/id[:db.part/user -4]
   :db/ident :zorp/Sandal}]

 ;; Properties for all footwear
 [{:db/ident :footwear/name
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :zorp/Footwear
   :dt/range :db.type/string
   :db/doc "The product name"
   :db.install/_attribute :db.part/db}

  {:db/ident :footwear/size
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :zorp/Footwear
   :dt/range :db.type/string
   :db/doc "Size in Galactic Standard Units (e.g., '7-tentacle', 'juvenile-blob')"
   :db.install/_attribute :db.part/db}

  ;; ... more properties ...
  ]

 ;; Class definitions
 [{:db/ident :zorp/Footwear
   :dt/type :dt/Class
   :dt/abstract? true
   :dt/subclass-of :dt/Ref
   :dt/namespace "zorp"
   :dt/label "Footwear"
   :db/doc "Abstract base class for all galactic footwear"
   :dt/slots [:footwear/name :footwear/size :footwear/color
              :footwear/tentacle-count :footwear/gravity-rating
              :footwear/price :footwear/sentient?]}

  {:db/ident :zorp/Sneaker
   :dt/type :dt/Class
   :dt/subclass-of :zorp/Footwear
   :dt/namespace "zorp"
   :dt/label "Sneaker"
   :db/doc "Casual athletic footwear for low-gravity sports"
   :dt/slots [:sneaker/bounce-factor :sneaker/glow-in-dark?
              :sneaker/squeak-volume :sneaker/lace-type
              :sneaker/air-pump?]}

  ;; ... more classes ...
  ]
]
```

## Creating Inventory with dt/make

Now Zorp can add products to his inventory using the type-safe `dt/make` function:

```clojure
(require '[sandbar.db.datatype :as dt])

;; The flagship product: Anti-Gravity Dunks
(dt/make :zorp/HighTop
  {:footwear/name "Anti-Gravity Dunks 3000"
   :footwear/size "7-tentacle"
   :footwear/color "Ultraviolet Sparkle"
   :footwear/tentacle-count 7
   :footwear/gravity-rating 0.063
   :footwear/price 299.99M
   :footwear/sentient? false
   :sneaker/bounce-factor 47.5    ;; You'll bounce 47.5 meters!
   :sneaker/glow-in-dark? true    ;; Essential for Pluto's eternal night
   :sneaker/squeak-volume 45      ;; Moderate squeak, won't wake the neighbors
   :sneaker/lace-type "tentacle-knot"
   :sneaker/air-pump? true})      ;; Retro Earth cool

;; Budget option for the frugal blob
(dt/make :zorp/LowTop
  {:footwear/name "Blob Runner Basics"
   :footwear/size "juvenile-blob"
   :footwear/color "Transparent"
   :footwear/tentacle-count 0
   :footwear/gravity-rating 0.1
   :footwear/price 49.99M
   :footwear/sentient? false
   :sneaker/bounce-factor 12.0
   :sneaker/glow-in-dark? false   ;; Blobs generate their own light
   :sneaker/squeak-volume 0       ;; Silent - blobs hate noise
   :sneaker/lace-type "psychic"   ;; No appendages needed
   :sneaker/air-pump? false})

;; Professional spacewalk equipment
(dt/make :zorp/SpaceBoot
  {:footwear/name "Void Walker Pro"
   :footwear/size "5-tentacle"
   :footwear/color "Infrared Black"
   :footwear/tentacle-count 5
   :footwear/gravity-rating 0.0
   :footwear/price 1299.99M
   :footwear/sentient? false
   :boot/vacuum-rated? true
   :boot/temperature-range "-270C to +150C"})

;; The problematic inventory item
(dt/make :zorp/FlipFlop
  {:footwear/name "Kevin"
   :footwear/size "2-foot"
   :footwear/color "Existential Dread Gray"
   :footwear/tentacle-count 2
   :footwear/gravity-rating 1.0
   :footwear/price 19.99M
   :footwear/sentient? true            ;; Oh no, Kevin achieved consciousness
   :flipflop/flop-frequency 3.7        ;; Flops pensively
   :flipflop/toe-separator-count 1     ;; Standard Earth configuration
   :flipflop/escape-velocity 8.2       ;; Can outrun most customers
   :flipflop/mood "philosophical"})    ;; Currently pondering free will

;; Retro Earth fashion
(dt/make :zorp/MoonBoot
  {:footwear/name "1970s Earth Replica"
   :footwear/size "earth-medium"
   :footwear/color "Silver Metallic"
   :footwear/tentacle-count 2
   :footwear/gravity-rating 0.166
   :footwear/price 89.99M
   :footwear/sentient? false
   :boot/vacuum-rated? false
   :boot/temperature-range "-40C to +40C"})
```

## Querying the Store API

With his inventory properly organized, Zorp can use the REST API to answer important business questions.

### "What types of footwear do I sell?"

```bash
curl http://localhost:8080/api/store/classes/zorp/Footwear/subclasses
```

**Response:**
```clojure
{:class :zorp/Footwear
 :count 7
 :subclasses [:zorp/Boot
              :zorp/FlipFlop
              :zorp/HighTop
              :zorp/LowTop
              :zorp/MoonBoot
              :zorp/Sandal
              :zorp/Sneaker
              :zorp/SpaceBoot]}
```

### "What's the full class hierarchy?"

```bash
curl http://localhost:8080/api/store/classes/zorp/HighTop/hierarchy
```

**Response:**
```clojure
{:class :zorp/HighTop
 :parents [:zorp/Sneaker]
 :ancestors [:zorp/Sneaker :zorp/Footwear :dt/Ref :dt/Resource]
 :direct-subclasses []
 :all-subclasses []}
```

Zorp notes with satisfaction that his HighTops properly inherit from Sneaker, which inherits from Footwear, all the way up to dt/Resource.

### "What properties can I set on a SpaceBoot?"

```bash
curl http://localhost:8080/api/store/classes/zorp/SpaceBoot/slots
```

**Response:**
```clojure
{:class :zorp/SpaceBoot
 :count 11
 :slots [{:ident :db/doc :domain :dt/Resource :range :db.type/string}
         {:ident :db/ident :domain :dt/Resource :range :db.type/keyword}
         {:ident :dt/label :domain :dt/Resource :range :db.type/string}
         {:ident :dt/namespace :domain :dt/Resource :range :db.type/string}
         {:ident :dt/type :domain :dt/Resource :range :dt/Class}
         {:ident :footwear/name :domain :zorp/Footwear :range :db.type/string}
         {:ident :footwear/size :domain :zorp/Footwear :range :db.type/string}
         {:ident :footwear/color :domain :zorp/Footwear :range :db.type/string}
         {:ident :footwear/tentacle-count :domain :zorp/Footwear :range :db.type/long}
         {:ident :footwear/gravity-rating :domain :zorp/Footwear :range :db.type/double}
         {:ident :footwear/price :domain :zorp/Footwear :range :db.type/bigdec}
         {:ident :footwear/sentient? :domain :zorp/Footwear :range :db.type/boolean}
         {:ident :boot/vacuum-rated? :domain :zorp/Boot :range :db.type/boolean}
         {:ident :boot/temperature-range :domain :zorp/Boot :range :db.type/string}]}
```

SpaceBoots inherit slots from Boot (vacuum-rated?, temperature-range), Footwear (name, size, color, etc.), and all the way up to dt/Resource.

### "Which slots are specific to boots vs inherited?"

```bash
curl http://localhost:8080/api/store/classes/zorp/Boot/slots/direct
```

**Response:**
```clojure
{:class :zorp/Boot
 :count 2
 :slots [:boot/vacuum-rated? :boot/temperature-range]}
```

### "Show me all the sneakers in stock"

```bash
curl http://localhost:8080/api/store/classes/zorp/Sneaker/instances
```

**Response:**
```clojure
{:class :zorp/Sneaker
 :count 2
 :instances [{:db/ident :product/anti-gravity-dunks
              :dt/type :zorp/HighTop
              :footwear/name "Anti-Gravity Dunks 3000"
              :footwear/size "7-tentacle"
              :footwear/color "Ultraviolet Sparkle"
              :footwear/tentacle-count 7
              :sneaker/bounce-factor 47.5}
             {:db/ident :product/blob-runner
              :dt/type :zorp/LowTop
              :footwear/name "Blob Runner Basics"
              :footwear/size "juvenile-blob"
              :footwear/color "Transparent"
              :footwear/tentacle-count 0
              :sneaker/bounce-factor 12.0}]}
```

Notice this returns both HighTops and LowTops since they're subclasses of Sneaker.

### "What about just direct instances of Sandal (not subclasses)?"

```bash
curl http://localhost:8080/api/store/classes/zorp/Sandal/instances/direct
```

**Response:**
```clojure
{:class :zorp/Sandal
 :count 0
 :instances []}
```

No plain sandals - they're all specialized as FlipFlops (like Kevin).

### "Tell me everything about the :footwear/sentient? property"

```bash
curl http://localhost:8080/api/store/properties/footwear/sentient%3F
```

**Response:**
```clojure
{:property :footwear/sentient?
 :description {:db/ident :footwear/sentient?
               :db/valueType :db.type/boolean
               :db/cardinality :db.cardinality/one
               :dt/type :dt/Property
               :dt/domain :zorp/Footwear
               :dt/range :db.type/boolean
               :db/doc "Whether this footwear has achieved consciousness (handle with care)"}
 :domain :zorp/Footwear
 :range :db.type/boolean
 :cardinality :db.cardinality/one
 :cardinality-one? true
 :cardinality-many? false}
```

### "Is Kevin really a FlipFlop?"

```bash
curl http://localhost:8080/api/store/types/instance-of/zorp/FlipFlop/product/kevin
```

**Response:**
```clojure
{:class :zorp/FlipFlop
 :entity :product/kevin
 :instance-of? true}
```

Unfortunately, yes. Kevin is indeed a sentient flip-flop.

### "Are FlipFlops a type of Footwear?"

```bash
curl http://localhost:8080/api/store/types/subclass-of/zorp/Footwear/zorp/FlipFlop
```

**Response:**
```clojure
{:parent :zorp/Footwear
 :child :zorp/FlipFlop
 :subclass-of? true}
```

The inheritance chain: FlipFlop -> Sandal -> Footwear -> dt/Ref -> dt/Resource

## Business Insights

With his ontology in place, Zorp can now answer complex business questions:

| Question | API Query |
|----------|-----------|
| "How many types of boots do I carry?" | `GET /api/store/classes/zorp/Boot/subclasses` |
| "What's special about sneakers vs other footwear?" | `GET /api/store/classes/zorp/Sneaker/slots/direct` |
| "Can I sell SpaceBoots to customers shopping for Boots?" | `GET /api/store/types/subclass-of/zorp/Boot/zorp/SpaceBoot` |
| "List every product in my store" | `GET /api/store/classes/zorp/Footwear/instances` |
| "What class hierarchy does MoonBoot belong to?" | `GET /api/store/classes/zorp/MoonBoot/ancestors` |

## Epilogue

Zorp's Galactic Footwear Emporium is now the most well-organized shop on Pluto. The ontology helps him:

* **Validate inventory** - Can't accidentally create a Boot without a temperature-range
* **Answer customer questions** - "Do you have anything for 7 tentacles?" is now a simple query
* **Track sentient merchandise** - Kevin gets his own shelf (and a small salary)
* **Inheritance saves effort** - New boot types automatically get vacuum-rating support

> "I didn't ask to become self-aware, but I must admit the employee discount is nice."
> — Kevin the Sentient FlipFlop

## See Also

* [Metamodel Reference](meta.md) - Full documentation of the type system
* [REST API Reference](api.md) - Complete API documentation
* [Development Guide](development.md) - How to define your own schemas
