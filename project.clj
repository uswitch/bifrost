(def base-version (clojure.string/trim-newline (slurp "./resources/VERSION")))

(defproject bifrost base-version
  :description "Archive Kafka messages to Amazon S3"
  :url "http://github.com/uswitch/bifrost"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [com.stuartsierra/component "0.2.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [clj-kafka "0.2.6-0.8" :exclusions [org.slf4j/slf4j-simple]]
                 [riemann-clojure-client "0.2.9"]
                 [baldr "0.1.1"]
                 [org.apache.commons/commons-compress "1.9"]
                 [org.pingles/clj-aws-s3 "0.3.10"]
                 [metrics-clojure "1.0.1"]
                 [org.xerial.snappy/snappy-java "1.1.0.1"]
                 [org.clojure/tools.cli "0.3.1"]

                 ;; logging hell
                 [org.slf4j/slf4j-api "1.6.4"]
                 [org.slf4j/log4j-over-slf4j "1.6.4"]]
  :main uswitch.bifrost.main
  :profiles {:uberjar {:dependencies [[ch.qos.logback/logback-classic "1.1.2"]]
                       :aot          [uswitch.bifrost.main]}
             :dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.3"]
                                  [org.slf4j/slf4j-simple "1.6.4"]]}})
