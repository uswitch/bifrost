(ns uswitch.bifrost.system
  (:require [com.stuartsierra.component :refer (system-map Lifecycle using start stop)]
            [clojure.tools.logging :refer (info)]
            [clojure.core.async :refer (chan <! >! go-loop timeout alts! close!)]
            [uswitch.bifrost.async :refer (observable-chan)]
            [uswitch.bifrost.telemetry :refer (metrics-reporter)]
            [uswitch.bifrost.kafka :refer (kafka-system)]
            [uswitch.bifrost.s3 :refer (s3-system)]
            [uswitch.bifrost.zk :refer (zookeeper-tracker)])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

;; TODO
;; Don't depend on underlying channel type
(extend-type ManyToManyChannel
  Lifecycle
  (stop [this] (when this (close! this)))
  (start [this] this))

(def buffer-size 100)

(defn make-system [config]
  (system-map
   :metrics-reporter (metrics-reporter config)

   :rotated-event-ch (observable-chan "rotated-event-ch" buffer-size)
   :commit-offset-ch (observable-chan "commit-offset-ch" buffer-size)

   :kafka-system (using (kafka-system config)
                        [:rotated-event-ch])
   :s3-system (using (s3-system config)
                     [:rotated-event-ch :commit-offset-ch])
   :zookeeper-tracker (using (zookeeper-tracker config)
                             [:commit-offset-ch])))
