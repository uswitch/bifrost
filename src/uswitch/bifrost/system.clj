(ns uswitch.bifrost.system
  (:require [com.stuartsierra.component :refer (system-map Lifecycle using start stop)]
            [clojure.tools.logging :refer (info)]
            [clojure.core.async :refer (chan <! >! go-loop timeout alts! close!)]
            [uswitch.bifrost.telemetry :refer (metrics-reporter)]
            [uswitch.bifrost.kafka :refer (topic-listener consumer-spawner)]
            [uswitch.bifrost.s3 :refer (s3-upload)]
            [uswitch.bifrost.core :refer (out-chan)]
            [uswitch.bifrost.zk :refer (zookeeper-tracker)]))

(defrecord PrintSink [prefix channable]
  Lifecycle
  (start [this]
    (let [ch (out-chan channable)]
      (go-loop [msg (<! ch)]
        (when msg
          (info prefix msg)
          (recur (<! ch)))))
    this)
  (stop [this]
    this))

(defn print-sink
  [prefix]
  (map->PrintSink {:prefix prefix}))

(defn make-system [config]
  (system-map
   :metrics-reporter (metrics-reporter config)
   :topic-listener (topic-listener config)
   :consumer-spawner (using (consumer-spawner config)
                            {:topic-available :topic-listener})
   :s3-uploader (using (s3-upload config)
                       {:channable :consumer-spawner})
   :zookeeper-tracker (using (zookeeper-tracker config)
                             {:channable :s3-uploader})))
