(ns user
  (:require [uswitch.bifrost.system :refer (make-system)]
            [clojure.tools.logging :refer (error)]
            [clojure.tools.namespace.repl :refer (refresh)]
            [com.stuartsierra.component :as component]))

(def system nil)

(defn init []
  (alter-var-root #'system
                  (constantly (make-system (read-string (slurp "./etc/config.edn"))))))

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
