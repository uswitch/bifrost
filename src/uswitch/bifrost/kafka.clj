(ns uswitch.bifrost.kafka
  (:require [clj-kafka.zk :refer (topics)]
            [com.stuartsierra.component :refer (Lifecycle start stop system-map using)]
            [clojure.java.io :refer (file output-stream)]
            [clojure.core.async :refer (chan <! >! thread go-loop timeout alts! close! put! <!! >!!)]
            [clojure.set :refer (difference intersection)]
            [clj-kafka.consumer.zk :refer (consumer messages shutdown)]
            [clojure.tools.logging :refer (info debug error)]
            [baldr.core :refer (baldr-writer)]
            [uswitch.bifrost.util :refer (close-channels clear-keys)]
            [uswitch.bifrost.async :refer (observable-chan)]
            [metrics.meters :refer (meter mark! defmeter)]
            [metrics.counters :refer (defcounter inc!)])
  (:import [java.util.zip GZIPOutputStream]
           [java.util Date]
           [java.io File]
           [org.apache.commons.compress.archivers.tar
            TarArchiveOutputStream
            TarArchiveEntry]))

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

(defn- tar-writer
  [out-stream]
  (let [tar-out-stream (TarArchiveOutputStream. out-stream)]
    (fn [offset payload]
      (if payload
        (let [e (doto (TarArchiveEntry. (File. (File. "/") (str offset)))
                  (.setSize (alength payload))
                  (.setModTime (Date. (long (System/currentTimeMillis)))))]
          (doto tar-out-stream
            (.putArchiveEntry e)
            (.write payload)
            (.closeArchiveEntry)))
        (.close tar-out-stream)))))

(def write-constructors
  {:baldr (fn [out-stream] (let [writer (baldr-writer out-stream)]
                            (fn [offset payload] (writer payload))))
   :tar   (fn [out-stream] (tar-writer out-stream))})

(defn consume-message
  [{:keys [write topic partition meter first-offset] :as state} message]
  (debug "BaldrConsumer" topic "received" (:offset message))
  (write (:offset message) (:value message))
  (mark! meter)
  (mark! baldr-writes)
  (when (not first-offset)
    (info "First offset:" {:offset (:offset message)
                           :topic  topic
                           :partition partition}))
  (assoc state
    :first-offset (or first-offset (:offset message))
    :last-offset (:offset message)))

(defn initialise-file
  [{:keys [topic partition seq-file-format] :as state}]
  (let [out-file     (doto (java.io.File/createTempFile
                            (str topic "-" partition "-")
                            (str "." (name seq-file-format) ".gz"))
                       (.deleteOnExit))
        out-stream   (GZIPOutputStream. (output-stream out-file))
        write        ((get write-constructors seq-file-format) out-stream)
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
           first-offset last-offset seq-file-format]
    :as state}]
  (if last-offset
    (do
      (when out-stream
        (info "Closing" out-path)
        (.close out-stream)
        (put! rotated-event-ch {:file-path       out-path
                                :seq-file-format seq-file-format
                                :topic           topic
                                :partition       partition
                                :first-offset    first-offset
                                :last-offset     last-offset}))
      (initialise-file state))
    state))

;; -- end state machines

(defn partition-consumer
  [topic partition rotation-interval
   rotated-event-ch seq-file-format]
  (info "Starting partition consumer for" topic "-" partition)
  (let [message-ch (observable-chan (str topic "-" partition) buffer-size)]
    (go-loop [state (initialise-file
                     (map->State {:topic             topic
                                  :partition         partition
                                  :meter             (meter (str topic "-" partition "-baldrWrite")
                                                            "records")
                                  :rotated-event-ch  rotated-event-ch
                                  :seq-file-format   seq-file-format}))
              timer (timeout rotation-interval)]
             (let [[v c] (alts! [message-ch timer])]
               (if (= c message-ch)
                 (recur (consume-message state v)
                        timer)
                 (recur (rotate state)
                        (timeout rotation-interval)))))
    message-ch))

(defcounter zookeeper-consumers)

(defn safe-zookeeper-consumer
  "Repeatedly tries to construct a ZooKeeper consumer. Will retry every
  15s and log errors. Returns a channel that contains the obtained
  consumer."
  [consumer-properties]
  (let [get-consumer (fn []
                       (try (consumer consumer-properties)
                            (catch Exception e
                              (error e "Unable to create ZooKeeper consumer")
                              nil)))]
    (go-loop
     []
     (if-let [c (get-consumer)]
       (do
         (inc! zookeeper-consumers)
         c)
       (do (<! (timeout (* 15 1000)))
           (recur))))))

(defrecord TopicBaldrConsumer [consumer-properties topic rotation-interval
                               ch seq-file-format]
  Lifecycle
  (start [this]
    (let [c (<!! (safe-zookeeper-consumer consumer-properties))
          run? (atom true)]
      (thread
       (loop [msgs (messages c topic)
              partition->message-ch {}]
         (if @run?
           (if-let [{:keys [partition] :as msg} (first msgs)]
             (let [message-ch (or (partition->message-ch partition)
                                  (partition-consumer topic partition rotation-interval
                                                      ch seq-file-format))]
               (>!! message-ch msg)
               (recur (rest msgs)
                      (assoc partition->message-ch partition message-ch)))
             (do
               (<!! (timeout 50))
               (recur (rest msgs)
                      partition->message-ch)))
           ;; if we shouldn't run, close down message-chs
           (doseq [[partition message-ch] partition->message-ch]
             (close! message-ch)))))
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
    (clear-keys this :consumer :run?)))

(defn spawn-topic-baldr-consumer
  [consumer-properties topic rotation-interval ch seq-file-format]
  (let [consumer (TopicBaldrConsumer. consumer-properties topic rotation-interval
                                      ch seq-file-format)]
    (start consumer)))

(defn listen-topics [topic-blacklist topic-whitelist topics]
  (difference (if topic-whitelist
                (intersection topic-whitelist topics)
                topics)
              topic-blacklist))

(defrecord ConsumerSpawner [consumer-properties rotation-interval
                            topic-blacklist topic-whitelist
                            seq-file-format
                            topic-added-ch rotated-event-ch]
  Lifecycle
  (start [this]
    (info "Starting ConsumerSpawner")
    (let [consumers        (atom [])]
      (go-loop []
        (if-let [new-topics (<! topic-added-ch)]
          (do (doseq [topic (listen-topics topic-blacklist topic-whitelist new-topics)]
                (info "Spawning consumer for" topic)
                (swap!
                 consumers conj
                 (spawn-topic-baldr-consumer
                  consumer-properties
                  topic
                  rotation-interval rotated-event-ch
                  seq-file-format)))
              (recur))
          (info "Closing down ConsumerSpawner")))
      (info "ConsumerSpawner started")
      (assoc this :consumers consumers)))
  (stop [this]
    (when-let [consumers (:consumers this)]
      (doseq [consumer @consumers]
        (info "Stopping consumer" consumer)
        (stop consumer)))
    (info "Finished shutting down consumers")
    (clear-keys this :consumers)))

(defn consumer-spawner
  [config]
  (map->ConsumerSpawner
   (select-keys config [:consumer-properties :rotation-interval
                        :topic-blacklist :topic-whitelist
                        :seq-file-format])))

(defn kafka-system
  [config]
  (system-map :topic-added-ch (observable-chan "topic-added-ch" buffer-size)
              :topic-listener (using (topic-listener config)
                                     [:topic-added-ch])
              :consumer-spawner (using (consumer-spawner config)
                                       [:topic-added-ch :rotated-event-ch])))
