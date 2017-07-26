(ns uswitch.bifrost.telemetry
  (:require [clojure.tools.logging :refer (info warn error)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [clojure.string :refer (trim-newline)]
            [metrics.gauges :refer (gauge)]
            [uswitch.bifrost.util :refer (clear-keys)])
  (:import [com.yammer.metrics.reporting RiemannReporter RiemannReporter$Config]
           [com.yammer.metrics Metrics]
           [com.aphyr.riemann.client RiemannClient]
           [com.readytalk.metrics StatsDReporter]
           [java.util.concurrent TimeUnit]
           [java.net InetSocketAddress]))

(defn current-hostname []
  (trim-newline (slurp (.getInputStream (.exec (Runtime/getRuntime) "hostname")))))


(defn riemann-reporter [host]
  (let [config (.build (doto (RiemannReporter$Config/newBuilder )
                         (.localHost (current-hostname))
                         (.host host)
                         (.period 1)))
        riemann-client (RiemannClient/udp (InetSocketAddress. (.host config) (.port config)))
        reporter (RiemannReporter. config riemann-client)]
    (info "Connecting RiemannReporter with config" config)
    (.connect riemann-client)
    (info "Connected to Riemann")
    reporter))

(defn statsd-reporter [host port]
  (StatsDReporter. (Metrics/defaultRegistry) host (Integer/valueOf port) "bifrost"))

(defrecord MetricsReporter [riemann-host statsd-host]
  Lifecycle
  (start [this]
    (try
      (let [reporter (cond (not (nil? riemann-host)) (riemann-reporter riemann-host)
                           :default                  (statsd-reporter (or (System/getenv "STATSD_HOST") statsd-host "localhost")
                                                                      (or (System/getenv "STATSD_PORT") 8125)))]
        (.start reporter 10 TimeUnit/SECONDS)
        (info "MetricsReporter started")
        (assoc this :reporter reporter))
      (catch Exception e
        (error e "error starting metrics reporter")
        this)))
  (stop [this]
    (when-let [reporter (:reporter this)]
      (info "Shutting down MetricsReporter")
      (.shutdown reporter))
    (clear-keys this :reporter)))

(defn metrics-reporter [{:keys [riemann-host statsd-host] :as config}]
  (map->MetricsReporter config))


(defprotocol RateGauge
  (reset-gauge! [gauge])
  (stop-gauge!  [gauge])
  (update-gauge! [gauge val]))

(defn rate-gauge [name]
  (let [state (atom nil)
        g     (gauge name
                     (if-let [current-state @state]
                       (let [{:keys [started-at updated-at value]} current-state]
                         (if updated-at
                           (let [elapsed (- updated-at started-at)]
                             (if (> elapsed 0) (* 1000 (/ value elapsed)) 0))
                           0))
                       0))]
    (reify RateGauge
      (stop-gauge! [this]
        (reset! state nil))
      (reset-gauge! [this]
        (reset! state {:value 0
                       :started-at (System/currentTimeMillis)}))
      (update-gauge! [this new-val]
        (swap! state (fn [{:keys [value] :as state}]
                       (assoc state
                         :value (+ new-val value)
                         :updated-at (System/currentTimeMillis))))))))
