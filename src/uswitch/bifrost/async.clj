(ns uswitch.bifrost.async
  (:require [clojure.core.async :refer (buffer chan go-loop close! <! >!)]
            [clojure.tools.logging :refer (info)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [metrics.gauges :refer (gauge)]))

(defn observable-buffer [name ^long n]
  (let [b (buffer n)]
    (gauge (str name "-bufSize") (count b))
    b))

(defn observable-chan [name ^long n]
  (chan (observable-buffer name n)))

(defrecord Spawner [ch key-fn spawn]
  Lifecycle
  (start [this]
    (info "Starting spawner")
    (let [children (atom nil)]
      (go-loop
       []
       (if-let [v (<! ch)]
         (let [k (key-fn v)
               child-ch (or (get @children k) (spawn k))]
           (swap! children assoc k child-ch)
           (>! child-ch v)
           (recur))
         (info "Spawner channel closed. Exiting loop")))
      (assoc this :children children)))
  (stop [this]
    (info "Closing children of spawner" this)
    (doseq [[k ch] @(:children this)]
      (when ch (close! ch)))
    (dissoc this :children)))
