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

(defn credentials
  [config]
  (let [{{:keys [access-key secret-key]
          :or {access-key (System/getenv "AWS_ACCESS_KEY_ID")
               secret-key (System/getenv "AWS_SECRET_ACCESS_KEY")}} :credentials} config]
    (update-in config [:credentials]
               assoc :access-key access-key :secret-key secret-key)))

(defn -main [& args]
  (let [{:keys [options summary]} (parse-opts args cli-options)]
    (when (:help options)
      (println summary)
      (System/exit 0))
    (let [{:keys [config]} options
          config (-> config slurp read-string credentials)]
      (info "Bifrost" (current-version))
      (when (current-build-number)
        (gauge "build-number" (current-build-number)))
      (info "Starting Bifrost with config" config)
      (start (make-system config))
      (wait!))))
