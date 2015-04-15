(ns uswitch.bifrost.system
  (:require [com.stuartsierra.component :refer (system-map Lifecycle using start stop)]
            [clojure.tools.logging :refer (info error)]
            [clojure.core.async :refer (chan <! >! go-loop timeout alts! close! <!!)]
            [uswitch.bifrost.async :refer (observable-chan)]
            [uswitch.bifrost.telemetry :refer (metrics-reporter)]
            [uswitch.bifrost.kafka :refer (kafka-system)]
            [uswitch.bifrost.s3 :refer (s3-system)]
            [uswitch.bifrost.azureblob :refer (azureblob-system)])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

(defn purge!
  "Closes ch and removes all messages."
  [ch]
  (try (close! ch)
       (loop [v (<!! ch)]
         (when v (recur (<!! ch))))
       ch
       (catch Exception e
         (error e "Unable to purge channel" ch)
         nil)))

;; TODO
;; Don't depend on underlying channel type
(extend-type ManyToManyChannel
  Lifecycle
  (stop [this] (when this (purge! this)))
  (start [this] this))

(def buffer-size 100)

(defn make-storage-system [{:keys [cloud-storage] :as config}]
  (let [storage-type (:type cloud-storage)]
    (case storage-type
      :s3-storage (s3-system config)
      :azure-storage (azureblob-system config)
      (do
        (error "Unknown/Unsupported :cloud-storage in config ->" storage-type)
        (System/exit 2)))))

(defn make-system [config]
  (system-map
   :metrics-reporter (metrics-reporter config)

   :rotated-event-ch (observable-chan "rotated-event-ch" buffer-size)

   :kafka-system (using (kafka-system config) [:rotated-event-ch])
   :storage-system (using (make-storage-system config) [:rotated-event-ch])))
