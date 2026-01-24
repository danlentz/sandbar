# System Architecture

This document describes the architecture and design of the Sandbar system.

## Overview

Sandbar is a Clojure web service built on three core technologies:

* **Datomic** - Immutable database with time-travel queries
* **Pedestal** - High-performance HTTP server
* **Component** - Lifecycle management for stateful resources

```
┌─────────────────────────────────────────────────────────────┐
│                      HTTP Clients                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Pedestal HTTP Server                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Routes    │─▶│Interceptors │─▶│     Handlers        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Datatype   │  │  Endpoint   │  │      Content        │  │
│  │    API      │  │   Macros    │  │    Negotiation      │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Database Layer                            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                   Datomic Peer                       │    │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────────┐    │    │
│  │  │  Schema   │  │   Query   │  │  Transactions │    │    │
│  │  └───────────┘  └───────────┘  └───────────────┘    │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## Component System

The application uses Stuart Sierra's Component library for managing stateful resources with proper startup/shutdown ordering.

### System Components

```clojure
(defn make-system [config]
  (component/system-map
    :datomic  (datomic/make-datomic (:db-uri config))
    :pedestal (component/using
                (pedestal/make-pedestal (:http-port config))
                [:datomic])
    :nrepl    (nrepl/make-nrepl (:nrepl-port config))))
```

#### Datomic Component

`sandbar.db.datomic`

Manages the Datomic Peer connection:

* Connects to the transactor on startup
* Creates database if it doesn't exist
* Loads schema from EDN files
* Provides connection via `*conn*` dynamic var
* Disconnects on shutdown

#### Pedestal Component

`sandbar.server.pedestal`

Manages the HTTP server:

* Depends on Datomic component
* Configures routes from `sandbar.service.routes`
* Sets up interceptor chains
* Starts Jetty server on configured port

#### nREPL Component

`sandbar.server.nrepl`

Manages the network REPL server:

* Starts nREPL on port 28888
* Includes CIDER middleware for editor integration
* Enables live code reloading during development

### Lifecycle

```clojure
;; In sandbar.core

(defn go []
  (init)
  (start))

(defn stop []
  (when-let [sys @system]
    (component/stop sys)
    (reset! system nil)))
```

## HTTP Service Layer

### Route Definition

Routes are defined in `sandbar.service.routes` using Pedestal's terse routing syntax:

```clojure
(def routes
  `[[["/" {:get home-page}
       ^:interceptors [(body-params/body-params (content/body-parsers))
                       params/url-decode-path-params]
      ["/api"
       ^:interceptors [content/data-body
                       content/accept-content
                       params/parsed-params
                       params/validated-params]
       ["/status" {:get status/status-handler}]
       ["/store"
        ["/classes" {:get store/list-classes}
         ["/:ns/:name" {:get store/get-class}
          ["/slots" {:get store/list-slots}]
          ...]]
        ...]]]]])
```

### Interceptor Chain

Requests flow through a chain of interceptors:

| Interceptor | Purpose |
|-------------|---------|
| `body-params` | Parse request body (JSON, EDN, form data) |
| `url-decode-path-params` | Decode URL-encoded path parameters |
| `data-body` | Set up response body serialization |
| `accept-content` | Content negotiation (JSON, EDN, Transit) |
| `parsed-params` | Merge and parse all parameters |
| `validated-params` | Validate parameters against schema |
| `log-params` | Log request parameters (development) |
| `log-response` | Log response details (development) |

### Handler Definition

Handlers are defined using the `defhandler` macro:

```clojure
(defhandler get-class
  "GET /api/store/classes/:ns/:name - Full class description"
  [request db-conn {:keys [ns name]}]
  (let [class-kw (keyword ns name)]
    (if-let [desc (describe-entity class-kw)]
      {:class class-kw
       :description desc
       :slots (dt/slots-of class-kw)
       ...}
      (endpoint/not-found {:error "Class not found"}))))
```

The macro provides:

* Automatic parameter destructuring
* Database connection injection
* Standardized response formatting

## Database Layer

### Schema Definition

Schema files are stored in `schema/*.edn`:

```clojure
;; schema/user.edn
[
 [{:db/id #db/id[:db.part/user -1]
   :db/ident :model/User}]

 [{:db/ident :user/login
   :db/valueType :db.type/string
   :dt/domain :model/User
   :dt/type :dt/Property
   ...}]

 [{:db/ident :model/User
   :dt/type :dt/Class
   :dt/subclass-of :dt/Ref
   :dt/slots [:user/uuid :user/login :user/secret]}]
]
```

### Datatype API

The `sandbar.db.datatype` namespace provides the metamodel API:

| Function | Description |
|----------|-------------|
| `all-classes` | List all class idents |
| `all-properties` | List all property idents |
| `class-of` | Get the class of an entity |
| `parents-of` | Get direct parent classes |
| `ancestors-of` | Get all ancestor classes |
| `subclasses-of` | Get all subclasses (transitive) |
| `slots-of` | Get all effective slots (inherited + direct) |
| `instance-of?` | Check if entity is instance of class |
| `subclass-of?` | Check if class is subclass of another |
| `make` | Create a validated typed instance |
| `validate` | Validate an entity against its class |

## Configuration

Configuration is stored in `config/config.edn`:

```clojure
{:http-port 8080
 :nrepl-port 28888
 :db-uri "datomic:dev://localhost:4334/sandbar"
 :required-schema [:meta :literal :ref :fn :any :user :twit]}
```

Accessed via `sandbar.util.edn`:

```clojure
(edn/config-value :http-port)    ;; => 8080
(edn/config-value :db-uri)       ;; => "datomic:dev://..."
```

## Code Organization

```
src/sandbar/
├── core.clj              # System lifecycle (go, stop, init, start)
├── sys.clj               # Global system state
│
├── api/
│   ├── status.clj        # Health check endpoint
│   └── store.clj         # Metamodel REST API
│
├── db/
│   ├── datomic.clj       # Datomic connection component
│   ├── datatype.clj      # Metamodel query API
│   ├── fn.clj            # Database functions
│   └── rules.clj         # Datalog rules
│
├── server/
│   ├── nrepl.clj         # nREPL server component
│   └── pedestal.clj      # HTTP server component
│
├── service/
│   ├── config.clj        # Pedestal service configuration
│   ├── content.clj       # Content negotiation interceptors
│   ├── endpoint.clj      # Handler macros
│   ├── params.clj        # Parameter validation
│   └── routes.clj        # Route definitions
│
└── util/
    ├── codec.clj         # Encoding utilities
    ├── common.clj        # Common utilities
    ├── diff.clj          # Data diffing
    ├── edn.clj           # EDN/config loading
    └── http_status.clj   # HTTP status codes
```

## Extension Points

### Adding a New Domain Class

1. Create a schema file in `schema/myclass.edn`
2. Add to `:required-schema` in `config/config.edn`
3. Restart the system to load the schema

```clojure
;; schema/myclass.edn
[
 [{:db/ident :model/MyClass
   :dt/type :dt/Class
   :dt/subclass-of :dt/Ref
   :dt/namespace "model"
   :dt/label "MyClass"
   :dt/slots [:myclass/name :myclass/value]}]

 [{:db/ident :myclass/name
   :db/valueType :db.type/string
   :dt/type :dt/Property
   :dt/domain :model/MyClass
   :dt/range :db.type/string
   :db/cardinality :db.cardinality/one}]
]
```

### Adding a New API Endpoint

1. Define handler in `src/sandbar/api/`
2. Add validator in the handler file
3. Add route in `src/sandbar/service/routes.clj`

```clojure
;; In api/myapi.clj
(defvalidator ::my-handler [_] identity)

(defhandler my-handler
  "GET /api/my-endpoint"
  [_ _ params]
  {:result "success"})

;; In service/routes.clj
["/my-endpoint" {:get myapi/my-handler}]
```

## Performance Considerations

* **Datomic caching** - Entity cache reduces database round-trips
* **Interceptor chain** - Efficient request processing pipeline
* **Lazy evaluation** - Datalog queries return lazy sequences
* **Connection pooling** - Datomic Peer manages connections automatically
