(ns user
  (:require [clojure.tools.logging :refer (error)]
            [clojure.tools.namespace.repl :refer (refresh)]
            [com.stuartsierra.component :as component]))

(def system nil)

(defn init []
  ;; We do some gymnastics here to make sure that the REPL can always start
  ;; even in the presence of compilation errors.
  (require '[uswitch.bifrost.system])

  (let [make-system (resolve 'uswitch.bifrost.system/make-system)]
    (alter-var-root #'system
                  (constantly (make-system (read-string (slurp "./etc/config.edn")))))))

(defn start []
  (alter-var-root #'system (fn [s] (try (component/start s)
                                       (catch Exception e
                                         (error e "Error when starting system")
                                         nil))) ))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (try (component/stop s)
                                               (catch Exception e
                                                 (error e "Error when stopping system")
                                                 nil))))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))
