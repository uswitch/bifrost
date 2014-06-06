(ns uswitch.bifrost.async
  (:require [clojure.core.async :refer (buffer chan)]
            [clojure.tools.logging :refer (info)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [metrics.gauges :refer (gauge)]))

(defn observable-buffer [name ^long n]
  (let [b (buffer n)]
    (gauge (str name "-bufSize") (count b))
    b))

(defn observable-chan [name ^long n]
  (chan (observable-buffer name n)))
