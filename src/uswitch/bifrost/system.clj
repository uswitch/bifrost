(ns uswitch.bifrost.system
  (:require [com.stuartsierra.component :refer (system-map Lifecycle using start stop)]
            [clojure.tools.logging :refer (info)]
            [clojure.core.async :refer (chan <! >! go-loop timeout alts! close!)]
            [uswitch.bifrost.async :refer (observable-chan)]
            [uswitch.bifrost.telemetry :refer (metrics-reporter)]
            [uswitch.bifrost.kafka :refer (topic-listener consumer-spawner)]
            [uswitch.bifrost.s3 :refer (s3-upload)]
            [uswitch.bifrost.core :refer (out-chan)]
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

   :topic-added-ch (observable-chan "topic-added-ch" buffer-size)
   :rotated-event-ch (observable-chan "rotated-event-ch" buffer-size)

   :topic-listener (using (topic-listener config)
                          [:topic-added-ch])
   :consumer-spawner (using (consumer-spawner config)
                            [:topic-added-ch :rotated-event-ch])
   :s3-uploader (using (s3-upload config)
                       [:rotated-event-ch])
   :zookeeper-tracker (using (zookeeper-tracker config)
                             {:channable :s3-uploader})))
