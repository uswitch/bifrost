(ns uswitch.bifrost.zk
  (:require [clj-kafka.zk :refer (committed-offset set-offset!)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [uswitch.bifrost.core :refer (out-chan)]
            [clojure.core.async :refer (go-loop <!)]
            [clojure.tools.logging :refer (info error)]))

(defrecord ZookeeperTracker [consumer-properties channable]
  Lifecycle
  (start [this]
    (info "Starting ZookeeperTracker")
    (let [commit-offset-ch  (out-chan channable)
          consumer-group-id (consumer-properties "group.id")]
      (go-loop [{:keys [topic partition offset] :as m} (<! commit-offset-ch)]
        (when m
          (info "Committing consumer information to ZooKeeper:" m)
          (try
            (set-offset! consumer-properties consumer-group-id topic partition offset)
            (info "Committed offset information to ZooKeeper" m)
            (catch Exception e
              (error e "Unable to commit offset to ZooKeeper")))
          (recur (<! commit-offset-ch))))
      (assoc this :commit-offset-ch commit-offset-ch)))
  (stop [this]
    (info "Stopping ZookeeperTracker")
    (dissoc this :commit-offset-ch)))

(defn zookeeper-tracker [{:keys [consumer-properties] :as config}]
  (map->ZookeeperTracker {:consumer-properties consumer-properties}))
