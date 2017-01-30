(def base-version (clojure.string/trim-newline (slurp "./resources/VERSION")))

(spit "./resources/BUILD_NUMBER" (or (System/getenv "BUILD_NUMBER") "-1"))

(defproject bifrost base-version
  :description "Archive Kafka messages to Amazon S3"
  :url "http://github.com/uswitch/bifrost"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [com.stuartsierra/component "0.2.1"]
                 [com.fasterxml.jackson.core/jackson-databind   "2.4.4"]

                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [clj-kafka "0.2.6-0.8" :exclusions [org.slf4j/slf4j-simple]]
                 [riemann-clojure-client "0.2.9"]
                 [baldr "0.1.1"]
                 [org.pingles/clj-aws-s3 "0.3.10"]
                 [metrics-clojure "1.0.1"]
                 [org.xerial.snappy/snappy-java "1.1.0.1"]
                 [org.clojure/tools.cli "0.3.1"]
                 [com.microsoft.windowsazure/microsoft-windowsazure-api "0.4.6"]

                 ;; Logging
                 [org.clojure/tools.logging                     "0.3.1"]
                 [ch.qos.logback/logback-classic                "1.1.2"]
                 [org.slf4j/jul-to-slf4j                        "1.7.7"]
                 [org.slf4j/jcl-over-slf4j                      "1.7.7"]
                 [org.slf4j/log4j-over-slf4j                    "1.7.7"]

                 [net.logstash.logback/logstash-logback-encoder "3.4"]]

  :main uswitch.bifrost.main
  :uberimage {:base-image "mastodonc/basejava"
              :cmd ["/bin/bash" "/start-bifrost"]
              :files {"start-bifrost" "docker/start-bifrost.sh"}
              :tag "mastodonc/kixi.bifrost"}
  :profiles {:uberjar {:dependencies [[ch.qos.logback/logback-classic "1.1.2"]]
                       :aot          [uswitch.bifrost.main]}

             :dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.3"]
                                  [org.slf4j/slf4j-simple "1.6.4"]]
                   :plugins [[com.palletops/uberimage "0.3.0"]]}})
