(ns uswitch.bifrost.telemetry
  (:require [clojure.tools.logging :refer (info error)]
            [com.stuartsierra.component :refer (Lifecycle)]
            [clojure.string :refer (trim-newline)])
  (:import [com.yammer.metrics.reporting RiemannReporter RiemannReporter$Config]
           [com.aphyr.riemann.client RiemannClient]
           [java.net InetSocketAddress]))

(defn current-hostname []
  (trim-newline (slurp (.getInputStream (.exec (Runtime/getRuntime) "hostname")))))

(defrecord MetricsReporter [riemann-host]
  Lifecycle
  (start [this]
    (if riemann-host
      (try (let [config (.build (doto (RiemannReporter$Config/newBuilder )
                                  (.localHost (current-hostname))
                                  (.host riemann-host)
                                  (.period 10)))
                 riemann-client (RiemannClient/udp (InetSocketAddress. (.host config) (.port config)))
                 reporter (RiemannReporter. config riemann-client)]
             (info "Starting RiemannReporter with config" config)
             (info "Connecting RiemannClient")
             (.connect riemann-client)
             (info "Connected to Riemann")
             (.start reporter (.period config) (.unit config))
             (info "RiemannReporter started")
             (assoc this :reporter reporter))
           (catch Exception e
             (error e "Error whilst starting RiemannReporter")
             this))
      this))
  (stop [this]
    (when-let [reporter (:reporter this)]
      (info "Shutting down MetricsReporter")
      (.shutdown reporter))
    (dissoc this :reporter)))

(defn metrics-reporter [{:keys [riemann-host] :as config}]
  (MetricsReporter. riemann-host))
