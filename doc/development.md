# Development Guide

Guide for developing and extending Sandbar.

## Development Environment

### Prerequisites

* Java 8 or later (OpenJDK or Oracle JDK)
* [Leiningen](https://leiningen.org) 2.9+
* [Datomic](https://www.datomic.com) transactor
* Editor with nREPL support (Emacs/CIDER, IntelliJ/Cursive, VS Code/Calva)

### Initial Setup

```bash
# Clone the repository
git clone <repository-url>
cd sandbar

# Install dependencies
lein deps

# Start the Datomic transactor (in another terminal)
cd /path/to/datomic
bin/transactor config/dev-transactor.properties
```

## REPL-Driven Development

The recommended development workflow uses the REPL for immediate feedback.

### Starting the REPL

```bash
lein repl
```

### System Lifecycle

```clojure
;; The sandbar.core namespace provides lifecycle functions
(require '[sandbar.core :refer [go stop init start]])

;; Start everything
(go)

;; Stop everything
(stop)

;; Restart after code changes
(stop)
(go)
```

### Connecting Your Editor

The system starts an nREPL server on port 28888. Connect from your editor:

**Emacs/CIDER:**
```
M-x cider-connect RET localhost RET 28888 RET
```

**IntelliJ/Cursive:**
```
Run → Edit Configurations → + → Clojure REPL → Remote
Host: localhost, Port: 28888
```

**VS Code/Calva:**
```
Ctrl+Shift+P → Calva: Connect to a Running REPL Server
```

### Hot Reloading

After editing source files:

```clojure
;; Reload a specific namespace
(require 'sandbar.api.store :reload)

;; Reload all changed namespaces
(require '[clojure.tools.namespace.repl :refer [refresh]])
(refresh)
```

## Testing

### Running Tests

```bash
# Run all tests
lein test

# Run a specific test namespace
lein test sandbar.api-store-test

# Run tests matching a pattern
lein test :only sandbar.datatype-test/all-classes-test

# Run with verbose output
lein test 2>&1 | head -100
```

### Test Fixtures

Tests use in-memory Datomic databases:

```clojure
(def test-uri "datomic:mem://my-test")

(defn with-test-db [f]
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (reset! db/**conn* conn)
    (try
      (load-test-schema conn)
      (f)
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)))))

(use-fixtures :each with-test-db)
```

### Writing Tests

```clojure
(ns sandbar.my-test
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]))

(deftest my-feature-test
  (testing "feature works correctly"
    (is (= expected-value (my-function input)))
    (is (some? (dt/class-of :model/User)))))
```

## Adding New Features

### Adding a New Domain Class

1. Create the schema file:

```clojure
;; schema/product.edn
[
 ;; Pre-declare the entity
 [{:db/id #db/id[:db.part/user -1]
   :db/ident :model/Product}]

 ;; Define properties
 [{:db/ident :product/name
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :model/Product
   :dt/range :db.type/string
   :db/doc "Product name"
   :db.install/_attribute :db.part/db}

  {:db/ident :product/price
   :db/valueType :db.type/bigdec
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :model/Product
   :dt/range :db.type/bigdec
   :db/doc "Product price"
   :db.install/_attribute :db.part/db}]

 ;; Define the class
 [{:db/ident :model/Product
   :dt/type :dt/Class
   :dt/subclass-of :dt/Ref
   :dt/context "model"
   :dt/label "Product"
   :db/doc "A product in the catalog"
   :dt/slots [:product/name :product/price]}]

 ;; Define aggregates
 [{:db/id #db/id[:db.part/user -1]
   :dt/type :dt/Class
   :dt/context "model"
   :dt/label "Product[]"
   :db/ident :model/Product*
   :dt/subclass-of :dt/Resource*
   :db/doc "1D aggregate of Products"
   :dt/component :model/Product
   :dt/_list :model/Product
   :dt/slots []}

  {:db/id #db/id[:db.part/user -2]
   :dt/type :dt/Class
   :dt/context "model"
   :dt/label "Product[][]"
   :db/ident :model/Product**
   :dt/subclass-of :dt/Resource**
   :db/doc "2D aggregate of Products"
   :dt/component #db/id[:db.part/user -1]
   :dt/_list #db/id[:db.part/user -1]
   :dt/slots []}]
]
```

2. Add to configuration:

```clojure
;; config/config.edn
{:required-schema [:meta :literal :ref :fn :any :user :twit :product]}
```

3. Restart the system to load the new schema.

### Adding a New API Endpoint

1. Create or update an API handler file:

```clojure
;; src/sandbar/api/product.clj
(ns sandbar.api.product
  (:require [sandbar.db.datatype :as dt]
            [sandbar.service.endpoint :as endpoint :refer [defhandler]]
            [sandbar.service.params :refer [defvalidator]]))

(defvalidator ::list-products [_] identity)
(defvalidator ::get-product [_] identity)

(defhandler list-products
  "GET /api/products - List all products"
  [_ _ _]
  (let [products (dt/all-instances-of :model/Product)]
    {:count (count products)
     :products (mapv #(into {} %) products)}))

(defhandler get-product
  "GET /api/products/:id - Get product by ID"
  [_ _ {:keys [id]}]
  (if-let [product (dt/entity (Long/parseLong id))]
    {:product (into {} product)}
    (endpoint/not-found {:error "Product not found"})))
```

2. Add routes:

```clojure
;; src/sandbar/service/routes.clj
(require '[sandbar.api.product :as product])

;; In the routes definition:
["/products" {:get product/list-products}
 ["/:id" {:get product/get-product}]]
```

### Adding Interceptors

```clojure
(def my-interceptor
  {:name ::my-interceptor
   :enter (fn [context]
            ;; Process request
            (update context :request assoc :my-data "value"))
   :leave (fn [context]
            ;; Process response
            context)
   :error (fn [context ex]
            ;; Handle errors
            (assoc context :response {:status 500 :body "Error"}))})
```

## Database Operations

### Querying

```clojure
(require '[datomic.api :as d]
         '[sandbar.db.datomic :as db])

;; Get the current database value
(db/db)

;; Run a Datalog query
(d/q '[:find ?e ?name
       :where
       [?e :dt/type :model/User]
       [?e :user/login ?name]]
     (db/db))

;; Get an entity
(d/entity (db/db) :model/User)

;; Touch to realize all attributes
(d/touch (d/entity (db/db) :model/User))
```

### Transactions

```clojure
;; Transact data
@(d/transact (db/conn)
   [{:db/id #db/id[:db.part/user]
     :dt/type :model/User
     :user/login "newuser"
     :user/secret "hash"}])

;; Using the datatype API
(dt/make :model/User {:user/login "alice"
                       :user/secret "hash123"})
```

### Schema Migrations

For schema changes, create a migration file:

```clojure
;; schema/migrations/001-add-product-sku.edn
[{:db/ident :product/sku
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one
  :db/unique :db.unique/identity
  :dt/type :dt/Property
  :dt/domain :model/Product
  :dt/range :db.type/string
  :db/doc "Stock keeping unit"
  :db.install/_attribute :db.part/db}]
```

## Debugging

### Logging

```clojure
(require '[clojure.tools.logging :as log])

(log/info "Processing request" {:user user-id})
(log/debug "Query result" result)
(log/error "Error occurred" ex)
```

### Inspecting State

```clojure
;; Get the current system
@sandbar.sys/system

;; Check database connection
(db/conn)

;; Inspect entity
(d/touch (db/entity :model/User))
```

### Common Issues

**Database not connected:**
```clojure
;; Check if system is running
@sandbar.sys/system

;; Restart
(stop)
(go)
```

**Schema not loading:**
```clojure
;; Check required schema in config
(sandbar.util.edn/config-value :required-schema)

;; Manually load a schema
(sandbar.db.datomic/load-schema :product)
```

## Code Style

### Naming Conventions

* Namespaces: `sandbar.category.name` (e.g., `sandbar.api.store`)
* Functions: `kebab-case` (e.g., `get-class`, `list-properties`)
* Predicates: suffix with `?` (e.g., `valid?`, `abstract?`)
* Private functions: prefix with `-` (e.g., `(defn- helper ...)`)
* Constants: `*earmuffs*` for dynamic vars (e.g., `*conn*`)

### Handler Pattern

```clojure
(defvalidator ::my-handler [params]
  ;; Return validated params or throw
  params)

(defhandler my-handler
  "Brief description of endpoint"
  [request db-conn params]
  ;; Return response map
  {:data result})
```

### Error Handling

```clojure
(defhandler get-item
  [_ _ {:keys [id]}]
  (if-let [item (find-item id)]
    {:item item}
    (endpoint/not-found {:error "Item not found"
                         :id id})))
```

## Building for Production

### Creating an Uberjar

```bash
lein uberjar
```

This creates `target/sandbar-*-standalone.jar`.

### Running the Uberjar

```bash
java -jar target/sandbar-*-standalone.jar
```

### Environment Configuration

Use environment variables or system properties:

```bash
# Environment variables
export DB_URI="datomic:dev://localhost:4334/production"
export HTTP_PORT=8080

# Or system properties
java -Ddb.uri="datomic:..." -Dhttp.port=8080 -jar sandbar.jar
```

## Useful Resources

* [Clojure REPL Guide](https://clojure.org/guides/repl/introduction)
* [Datomic Documentation](https://docs.datomic.com)
* [Pedestal Guides](https://pedestal.io/guides/index)
* [Component Library](https://github.com/stuartsierra/component)
