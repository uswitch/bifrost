(ns uswitch.bifrost.kafka
  (:require [clj-kafka.zk :refer (topics)]
            [com.stuartsierra.component :refer (Lifecycle start stop)]
            [clojure.java.io :refer (file output-stream)]
            [clojure.core.async :refer (chan <! >! go-loop timeout alts! close! put!)]
            [clojure.set :refer (difference intersection)]
            [clj-kafka.consumer.zk :refer (consumer messages shutdown)]
            [clojure.tools.logging :refer (info debug error)]
            [baldr.core :refer (baldr-writer)]
            [uswitch.bifrost.util :refer (close-channels)]
            ;; TODO: Remove obser
            [uswitch.bifrost.async :refer (observable-chan)]
            [uswitch.bifrost.core :refer (Producer out-chan)]
            [metrics.meters :refer (meter mark! defmeter)])
  (:import [java.util.zip GZIPOutputStream]))

(defn- get-available-topics
  [zookeeper-connect]
  (set (topics {"zookeeper.connect" zookeeper-connect})))

(def buffer-size 100)

(defrecord TopicListener [zookeeper-connect topic-added-ch]
  Lifecycle
  (start [this]
    (info "Starting topic listener..." this)
    (let [control-ch (chan)]
      (go-loop [current-topics nil]
        (let [available-topics (get-available-topics zookeeper-connect)
              new-topics       (difference available-topics current-topics)]
          (when (seq new-topics)
            (>! topic-added-ch new-topics))
          (let [[v c] (alts! [(timeout 10000) control-ch])]
            (cond
             (= c control-ch) :do-not-recur
             :else            (recur available-topics)))))
      (info "Topic listener started.")
      (assoc this
        :control-ch control-ch)))
  (stop [this]
    (info "Stopping topic listener...")
    (close-channels this :control-ch)))

(defn topic-listener [config]
  (map->TopicListener {:zookeeper-connect (get-in config [:consumer-properties "zookeeper.connect"])}))

;; Consumer (state machine (ALL THE THINGS!))

(defrecord State [rotated-event-ch
                  out-path out-stream write
                  first-offset last-offset
                  meter])

(defmeter baldr-writes "records")

(defn consume-message
  [{:keys [write topic meter first-offset] :as state} message]
  (debug "BaldrConsumer" topic "received" (:offset message))
  (write (:value message))
  (mark! meter)
  (mark! baldr-writes)
  (assoc state
    :first-offset (or first-offset (:offset message))
    :last-offset (:offset message)))

(defn initialise-file
  [{:keys [topic partition] :as state}]
  (let [out-file     (doto (java.io.File/createTempFile (str topic "-" partition "-") ".baldr.gz")
                       (.deleteOnExit))
        out-stream   (GZIPOutputStream. (output-stream out-file))
        write        (baldr-writer out-stream)
        out-path     (.getAbsolutePath out-file)]
    (info "Writing output to" out-path)
    (assoc state
      :out-path     out-path
      :out-stream   out-stream
      :write        write
      :first-offset nil
      :last-offset  nil)))

(defn rotate
  [{:keys [out-stream topic partition out-path rotated-event-ch
           first-offset last-offset]
    :as state}]
  (if last-offset
    (do
      (when out-stream
        (info "Closing" out-path)
        (.close out-stream)
        (put! rotated-event-ch {:file-path    out-path
                                :topic        topic
                                :partition    partition
                                :first-offset first-offset
                                :last-offset  last-offset}))
      (initialise-file state))
    state))

;; -- end state machines

(defn partition-consumer
  [topic partition rotation-interval rotated-event-ch]
  (info "Starting partition consumer for" topic "-" partition)
  (let [message-ch (observable-chan (str topic "-" partition) buffer-size)]
    (go-loop [state (initialise-file
                     (map->State {:topic             topic
                                  :partition         partition
                                  :meter             (meter (str topic "-" partition "-baldrWrite")
                                                            "records")
                                  :rotated-event-ch  rotated-event-ch}))
              timer (timeout rotation-interval)]
             (let [[v c] (alts! [message-ch timer])]
               (if (= c message-ch)
                 (recur (consume-message state v)
                        timer)
                 (recur (rotate state)
                        (timeout rotation-interval)))))
    message-ch))

(defrecord TopicBaldrConsumer [consumer-properties topic rotation-interval ch]
  Lifecycle
  (start [this]
    (let [c (consumer consumer-properties)
          run? (atom true)]
      (go-loop [msgs (messages c topic)
                partition->message-ch {}]
               (if @run?
                 (if-let [{:keys [partition] :as msg} (first msgs)]
                   (let [message-ch (or (partition->message-ch partition)
                                        (partition-consumer topic partition rotation-interval ch))]
                     (>! message-ch msg)
                     (recur (rest msgs)
                            (assoc partition->message-ch partition message-ch)))
                   (do
                     (<! (timeout 5))
                     (recur msgs partition->message-ch)))
                 ;; if we shouldn't run, close down message-chs
                 (doseq [[partition message-ch] partition->message-ch]
                   (close! message-ch))))
      (assoc this
        :consumer c
        :run? run?)))
  (stop [this]
    (when-let [consumer (:consumer this)]
      (info "TopicBaldrConsumer Shutting down Kafka consumer")
      (shutdown consumer))
    (when-let [run? (:run? this)]
      (info "TopicBaldrConsumer Terminating message loop")
      (reset! run? false))
    (dissoc this :consumer :run?)))

(defn spawn-topic-baldr-consumer
  [consumer-properties topic rotation-interval ch]
  (let [consumer (TopicBaldrConsumer. consumer-properties topic rotation-interval ch)]
    (start consumer)))

(defn listen-topics [topic-blacklist topic-whitelist topics]
  (difference (if topic-whitelist
                (intersection topic-whitelist topics)
                topics)
              topic-blacklist))

(defrecord ConsumerSpawner [consumer-properties rotation-interval topic-blacklist topic-whitelist topic-added-ch]
  Producer
  (out-chan [this]
    (:out-ch this))
  Lifecycle
  (start [this]
    (info "Starting ConsumerSpawner")
    (let [rotated-event-ch (observable-chan "rotated-event-ch" buffer-size)
          consumers        (atom [])]
      (go-loop []
        (if-let [new-topics (<! topic-added-ch)]
          (do (doseq [topic (listen-topics topic-blacklist topic-whitelist new-topics)]
                (info "Spawning consumer for" topic)
                (swap! consumers conj (spawn-topic-baldr-consumer consumer-properties topic rotation-interval rotated-event-ch)))
              (recur))
          (do
            (info "Closing down ConsumerSpawner")
            (close! rotated-event-ch))))
      (info "ConsumerSpawner started")
      (assoc this :out-ch rotated-event-ch :consumers consumers)))
  (stop [this]
    (when-let [consumers (:consumers this)]
      (doseq [consumer @consumers]
        (info "Stopping consumer" consumer)
        (stop consumer)))
    (info "Finished shutting down consumers")
    (dissoc this :out-ch :consumers)))

(defn consumer-spawner
  [config]
  (map->ConsumerSpawner
   (select-keys config [:consumer-properties :rotation-interval :topic-blacklist :topic-whitelist])))
