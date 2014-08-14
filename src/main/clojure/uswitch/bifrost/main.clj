(ns uswitch.bifrost.main
  (:require [uswitch.bifrost.system :refer (make-system)]
            [uswitch.bifrost.version :refer (current-version current-build-number)]
            [com.stuartsierra.component :refer (start)]
            [clojure.tools.logging :refer (info)]
            [clojure.tools.cli :refer (parse-opts)]
            [metrics.gauges :refer (gauge)])
  (:gen-class))

(defn wait! []
  (let [s (java.util.concurrent.Semaphore. 0)]
    (.acquire s)))

(def cli-options
  [["-c" "--config CONFIG" "Path to EDN configuration file"
    :default "./etc/config.edn"
    :validate [string?]]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options summary]} (parse-opts args cli-options)]
    (when (:help options)
      (println summary)
      (System/exit 0))
    (let [{:keys [config]} options]
      (info "Bifrost" (current-version))
      (when (current-build-number)
        (gauge "build-number" (current-build-number)))
      (info "Starting Bifrost with config" config)
      (start (make-system (read-string (slurp config))))
      (wait!))))
