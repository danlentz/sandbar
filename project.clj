(defproject sandbar "0.1.0"
  :description "Metacircular metamodel platform on Datomic — RDFS-style classes + properties + inheritance, equipped with a four-axis retrieval surface (BM25F fulltext search, structural + temporal aggregation, Wilbur-lineage path-grammar navigation, library-card orientation), exposed simultaneously through HTTP REST and Model Context Protocol (MCP) for AI clients"
  :author "Dan Lentz"
  :url "https://github.com/danlentz/sandbar"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :signing {:gpg-key "0CA466A1AB48F0C0264AF55307BAD70176C4B179"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/core.logic "1.1.1"]
                 [org.clojure/core.match "1.1.1"]
                 [org.clojure/data.codec "0.2.1"]
                 [org.clojure/data.csv "1.1.1"]
                 [org.clojure/data.fressian "1.1.1"]
                 [org.clojure/tools.logging "1.3.1"]
                 [org.clojure/tools.namespace "1.5.1"]

                 [nrepl "1.6.0-alpha2"]
                 [cider/cider-nrepl "0.58.0"]
                 [refactor-nrepl/refactor-nrepl "3.11.0"]

                 ;; [com.datomic/local "1.0.291"]
                 [com.datomic/peer "1.0.7482"]


                 [clj-http "3.13.1"]

                 [cheshire "6.1.0"]
                 [clj-commons/clj-yaml "1.0.29"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [danlentz/clj-uuid "0.2.0"]
                 [rm-hull/table "0.7.1"]

                 [com.stuartsierra/component "1.2.0"]

                 [io.pedestal/pedestal.service "0.8.1"]
                 [io.pedestal/pedestal.jetty "0.8.1"]
                 [ring/ring-core "1.15.3"]

                 [buddy/buddy-hashers "2.0.167"]

                 [com.taoensso/sente "1.21.0"]
;                 [com.taoensso/telemere "1.2.1"]


;;                 [datomic-schematode "0.1.0-RC3"]

                 [ch.qos.logback/logback-classic "1.5.25" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/slf4j-api    "2.0.17"]

                 ]

  :plugins [[lein-ancient "1.0.0-RC3"]
             [lein-asciidoctor  "0.1.14"]
            ; [cider/cider-nrepl "0.58.0"]
            ]
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]
  :min-lein-version "2.4.0"
  :resource-paths ["config", "resources", "schema"]

  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "sandbar.core/go"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.7.2"]]}
             :uberjar {:aot [sandbar.core] }}


  :asciidoc {:sources ["doc/*.adoc"]
             :to-dir "doc/html"
             :toc              :left
             :doctype          :article
             :format           :html5
             :extract-css      true
             :source-highlight true}

  :main ^{:skip-aot true} sandbar.core)
