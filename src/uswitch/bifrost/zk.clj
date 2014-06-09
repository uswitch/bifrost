(ns uswitch.bifrost.zk
  (:require [clj-kafka.zk :refer (committed-offset set-offset!)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [clojure.core.async :refer (go <!)]
            [clojure.tools.logging :refer (info error)]))

(defrecord ZookeeperTracker [consumer-properties commit-offset-ch]
  Lifecycle
  (start [this]
    (info "Starting ZookeeperTracker")
    (let [consumer-group-id (consumer-properties "group.id")]
      (go
       (loop [{:keys [topic partition offset] :as m} (<! commit-offset-ch)]
         (when m
           (info "Committing consumer information to ZooKeeper:" m)
           (try
             (set-offset! consumer-properties consumer-group-id topic partition offset)
             (info "Committed offset information to ZooKeeper" m)
             (catch Exception e
               (error e "Unable to commit offset to ZooKeeper")))
           (recur (<! commit-offset-ch))))
       (info "Stopping ZookeeperTracker"))
      this))
  (stop [this]
    this))

(defn zookeeper-tracker [{:keys [consumer-properties] :as config}]
  (map->ZookeeperTracker {:consumer-properties consumer-properties}))
