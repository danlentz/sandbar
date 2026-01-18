(defproject sandbag "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/data.csv "1.1.1"]
                 [com.dean/interval-tree "0.1.2"]
              ;;   [geheimtur "0.3.3"]


                 [org.clojure/tools.logging "1.3.1"]

                 [nrepl "1.6.0-alpha2"]
                 [cider/cider-nrepl "0.58.0"]
                 [refactor-nrepl/refactor-nrepl "3.11.0"]

                 ;; [com.datomic/local "1.0.291"]
                 [com.datomic/peer "1.0.7482"]

                 [cheshire "6.1.0"]
                 [danlentz/clj-uuid "0.2.0"]
                 [dco-dev/interval-tree "0.1.2"]

                 [com.stuartsierra/component "1.2.0"]
                 [io.pedestal/pedestal.service "0.8.1"]
                 [io.pedestal/pedestal.jetty "0.8.1"]
                 [com.taoensso/sente "1.21.0"]
;                 [com.taoensso/telemere "1.2.1"]


;;                 [datomic-schematode "0.1.0-RC3"]

                 [ch.qos.logback/logback-classic "1.5.25" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/slf4j-api    "2.0.17"]

                 ]

  :plugins [[lein-ancient "1.0.0-RC3"]
            ; [lein-asciidoctor  "0.1.14"]
            ; [cider/cider-nrepl "0.58.0"]
            ]
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]
  :min-lein-version "2.4.0"
  :resource-paths ["config", "resources", "schema"]

  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "sandbag.core/go"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.7.2"]]}
             :uberjar {:aot [sandbag.core] }}
  :main ^{:skip-aot true} sandbag.core

  )
