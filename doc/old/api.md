# REST API Reference

Reference for REST API.

## Overview

The Sandbar API provides HTTP endpoints for querying the metamodel, including classes, properties, entities, and type relationships.

### Base URL

```
http://localhost:8080/api
```

### Content Types

The API supports multiple content types via the `Accept` header:

| Content Type | Description |
|--------------|-------------|
| `application/edn` | Clojure EDN format (default) |
| `application/json` | JSON |
| `application/transit+json` | Transit JSON encoding |

### URL Conventions

Clojure keywords with namespaces map directly to URL paths:

| Keyword | URL Path |
|---------|----------|
| `:dt/Resource` | `/dt/Resource` |
| `:model/User` | `/model/User` |
| `:db.type/string` | `/db.type/string` |

## Status Endpoints

### GET /api/status

Returns system health and version information.

**Response**

```clojure
{:time #inst "2024-01-15T10:30:00.000Z"
 :clojure {:major 1
           :minor 12
           :incremental 4
           :qualifier nil}}
```

## Schema Endpoints

### GET /api/store/schema

Returns an overview of the entire schema.

**Response**

```clojure
{:schema {:class-count 42
          :property-count 58
          :classes [:db.type/bigdec :db.type/bigint ...]
          :properties [:db/cardinality :db/doc ...]}}
```

## Class Endpoints

### GET /api/store/classes

Lists all classes in the metamodel.

**Response**

```clojure
{:count 42
 :classes [:db.type/bigdec
           :db.type/bigint
           :db.type/boolean
           :dt/Class
           :dt/Property
           :dt/Resource
           :model/User
           ...]}
```

### GET /api/store/classes/:ns/:name

Returns detailed information about a specific class.

**Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| `ns` | string | Namespace part of the class keyword |
| `name` | string | Name part of the class keyword |

**Example**

```
GET /api/store/classes/dt/Resource
GET /api/store/classes/model/User
```

**Response**

```clojure
{:class :dt/Resource
 :description {:db/ident :dt/Resource
               :dt/type :dt/Class
               :dt/context "meta"
               :dt/label "Resource"
               :db/doc "Resource is the abstract superclass of all classes"}
 :abstract? false
 :slots [:db/doc :db/ident :dt/label :dt/context :dt/type]
 :direct-slots [:db/doc :db/ident :dt/label :dt/context :dt/type]
 :inherited-slots []
 :parents []
 :subclasses [:dt/Class :dt/List :dt/Literal :dt/Property :dt/Ref ...]
 :instance-count 85}
```

**Error Response** (404)

```clojure
{:error "Class not found"
 :class :nonexistent/Class}
```

### GET /api/store/classes/:ns/:name/instances

Returns all instances of a class, including instances of subclasses.

**Example**

```
GET /api/store/classes/dt/Class/instances
```

**Response**

```clojure
{:class :dt/Class
 :count 42
 :instances [{:db/ident :dt/Resource :dt/type :dt/Class ...}
             {:db/ident :dt/Property :dt/type :dt/Class ...}
             ...]}
```

### GET /api/store/classes/:ns/:name/instances/direct

Returns only direct instances of a class (excludes subclass instances).

**Example**

```
GET /api/store/classes/dt/Class/instances/direct
```

**Response**

```clojure
{:class :dt/Class
 :count 15
 :instances [...]}
```

### GET /api/store/classes/:ns/:name/slots

Returns all effective slots for a class (direct + inherited).

**Example**

```
GET /api/store/classes/model/User/slots
```

**Response**

```clojure
{:class :model/User
 :count 8
 :slots [{:ident :db/doc
          :domain :dt/Resource
          :range :db.type/string
          :cardinality :db.cardinality/one
          :required? false}
         {:ident :user/login
          :domain :model/User
          :range :db.type/string
          :cardinality :db.cardinality/one
          :required? false}
         ...]}
```

### GET /api/store/classes/:ns/:name/slots/direct

Returns only slots directly declared on this class.

**Example**

```
GET /api/store/classes/model/User/slots/direct
```

**Response**

```clojure
{:class :model/User
 :count 3
 :slots [:user/login :user/secret :user/uuid]}
```

### GET /api/store/classes/:ns/:name/slots/required

Returns required slots for a class.

**Example**

```
GET /api/store/classes/model/User/slots/required
```

**Response**

```clojure
{:class :model/User
 :count 0
 :slots []}
```

### GET /api/store/classes/:ns/:name/hierarchy

Returns the complete class hierarchy (parents, ancestors, subclasses).

**Example**

```
GET /api/store/classes/model/User/hierarchy
```

**Response**

```clojure
{:class :model/User
 :parents [:dt/Ref]
 :ancestors [:dt/Ref :dt/Resource]
 :direct-subclasses []
 :all-subclasses []}
```

### GET /api/store/classes/:ns/:name/subclasses

Returns all subclasses (transitive).

**Example**

```
GET /api/store/classes/dt/Resource/subclasses
```

**Response**

```clojure
{:class :dt/Resource
 :count 38
 :subclasses [:dt/Class
              :dt/Class*
              :dt/Class**
              :dt/List
              :dt/Literal
              :dt/Property
              :dt/Ref
              :model/User
              ...]}
```

### GET /api/store/classes/:ns/:name/subclasses/direct

Returns only direct subclasses.

**Example**

```
GET /api/store/classes/dt/Resource/subclasses/direct
```

**Response**

```clojure
{:class :dt/Resource
 :count 6
 :subclasses [:dt/Class
              :dt/List
              :dt/Literal
              :dt/Property
              :dt/Ref
              :dt/Resource*]}
```

### GET /api/store/classes/:ns/:name/ancestors

Returns all ancestor classes.

**Example**

```
GET /api/store/classes/model/User/ancestors
```

**Response**

```clojure
{:class :model/User
 :count 2
 :ancestors [:dt/Ref :dt/Resource]}
```

### GET /api/store/classes/:ns/:name/parents

Returns direct parent classes.

**Example**

```
GET /api/store/classes/model/User/parents
```

**Response**

```clojure
{:class :model/User
 :count 1
 :parents [:dt/Ref]}
```

### GET /api/store/classes/:ns/:name/validate

Validates all instances of a class (including instances of subclasses) against their class schemas.

This is useful for batch validation. For example, validating `:dt/Resource` will validate **all** typed entities in the database since every class inherits from Resource.

**Example**

```
GET /api/store/classes/dt/Class/validate
GET /api/store/classes/dt/Resource/validate
GET /api/store/classes/model/User/validate
```

**Response** (all valid)

```clojure
{:class :dt/Class
 :total 42
 :valid 42
 :invalid 0
 :all-valid? true
 :errors []}
```

**Response** (some invalid)

```clojure
{:class :model/User
 :total 150
 :valid 148
 :invalid 2
 :all-valid? false
 :errors [{:entity 17592186045500
           :class :model/User
           :errors [{:type :missing-required
                     :slot :user/email
                     :message "Required slot :user/email is missing"}]}
          {:entity 17592186045501
           :class :model/User
           :errors [{:type :invalid-type
                     :slot :user/age
                     :expected :db.type/long
                     :message "Slot :user/age expects :db.type/long"}]}]}
```

**Error Types**

| Type | Description |
|------|-------------|
| `:no-class` | Entity has no `:dt/type` |
| `:abstract-class` | Entity's class is abstract |
| `:missing-required` | Required slot is missing |
| `:invalid-type` | Slot value doesn't match range type |
| `:cardinality-violation` | Single-valued slot has multiple values |
| `:custom-validation` | Custom validator returned an error |

## Property Endpoints

### GET /api/store/properties

Lists all properties in the metamodel.

**Response**

```clojure
{:count 58
 :properties [:db/cardinality
              :db/doc
              :db/fulltext
              :db/ident
              :db/unique
              :dt/domain
              :dt/range
              :dt/type
              :user/login
              ...]}
```

### GET /api/store/properties/:ns/:name

Returns detailed information about a specific property.

**Example**

```
GET /api/store/properties/dt/type
```

**Response**

```clojure
{:property :dt/type
 :description {:db/ident :dt/type
               :db/valueType :db.type/ref
               :db/cardinality :db.cardinality/one
               :dt/type :dt/Property
               :dt/domain :dt/Resource
               :dt/range :dt/Class
               :db/doc "A reference to the classes of a Resource"}
 :domain :dt/Resource
 :range :dt/Class
 :cardinality :db.cardinality/one
 :required? false
 :cardinality-one? true
 :cardinality-many? false}
```

### GET /api/store/properties/:ns/:name/domain

Returns the domain class of a property.

**Example**

```
GET /api/store/properties/user/login/domain
```

**Response**

```clojure
{:property :user/login
 :domain :model/User
 :domain-description {:db/ident :model/User
                      :dt/type :dt/Class
                      ...}}
```

### GET /api/store/properties/:ns/:name/range

Returns the range type of a property.

**Example**

```
GET /api/store/properties/user/login/range
```

**Response**

```clojure
{:property :user/login
 :range :db.type/string
 :range-description {:db/ident :db.type/string
                     :dt/type :dt/Class
                     ...}}
```

## Entity Endpoints

### GET /api/store/entities/:ns/:name

Returns an entity by its `:db/ident` keyword.

**Example**

```
GET /api/store/entities/dt/Resource
GET /api/store/entities/model/User
```

**Response**

```clojure
{:id :dt/Resource
 :class :dt/Class
 :entity {:db/ident :dt/Resource
          :dt/type :dt/Class
          :dt/context "meta"
          :dt/label "Resource"
          :db/doc "Resource is the abstract superclass of all classes"
          :dt/slots [:db/doc :db/ident :dt/context :dt/label :dt/type]}}
```

### GET /api/store/entities/:ns/:name/validate

Validates an entity against its class schema.

**Example**

```
GET /api/store/entities/dt/Resource/validate
```

**Response** (valid)

```clojure
{:id :dt/Resource
 :valid? true
 :validation {:status :valid}}
```

**Response** (invalid)

```clojure
{:id :some/invalid-entity
 :valid? false
 :validation {:entity 12345
              :errors [{:type :missing-required
                        :slot :required/field
                        :message "Required slot not present"}]}}
```

### GET /api/store/entities/:ns/:name/class

Returns the class of an entity.

**Example**

```
GET /api/store/entities/dt/Resource/class
```

**Response**

```clojure
{:id :dt/Resource
 :class :dt/Class
 :class-description {:db/ident :dt/Class
                     :dt/type :dt/Class
                     ...}}
```

## Type Predicate Endpoints

### GET /api/store/types/instance-of/:class-ns/:class-name/:entity-ns/:entity-name

Checks if an entity is an instance of a class.

**Example**

```
GET /api/store/types/instance-of/dt/Class/dt/Resource
```

**Response**

```clojure
{:class :dt/Class
 :entity :dt/Resource
 :instance-of? true}
```

### GET /api/store/types/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name

Checks if one class is a subclass of another.

**Example**

```
GET /api/store/types/subclass-of/dt/Resource/model/User
```

**Response**

```clojure
{:parent :dt/Resource
 :child :model/User
 :subclass-of? true}
```

## Error Responses

### 404 Not Found

Returned when a requested resource doesn't exist.

```clojure
{:error "Class not found"
 :class :nonexistent/Class}
```

### 406 Not Acceptable

Returned when the requested content type is not supported.

```
Not Acceptable: text/html
```

## Example Usage

### Using curl

```bash
# Get all classes (EDN is the default format)
curl http://localhost:8080/api/store/classes

# Get class details
curl http://localhost:8080/api/store/classes/model/User

# Get class hierarchy
curl http://localhost:8080/api/store/classes/model/User/hierarchy

# Check type relationship
curl http://localhost:8080/api/store/types/subclass-of/dt/Resource/model/User

# Request JSON format explicitly
curl -H "Accept: application/json" \
  http://localhost:8080/api/store/classes
```

### Using HTTPie

```bash
# Get all classes (EDN is the default format)
http GET localhost:8080/api/store/classes

# Get property details
http GET localhost:8080/api/store/properties/dt/type

# Request JSON format explicitly
http GET localhost:8080/api/store/classes Accept:application/json
```

### Using Clojure

```clojure
(require '[clj-http.client :as http]
         '[clojure.edn :as edn])

(defn api-get [path]
  (-> (http/get (str "http://localhost:8080/api/store" path))
      :body
      edn/read-string))

(api-get "/classes")
(api-get "/classes/model/User")
(api-get "/classes/model/User/slots")
```
