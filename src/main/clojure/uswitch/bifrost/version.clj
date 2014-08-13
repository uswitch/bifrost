(ns uswitch.bifrost.version
  (:require [clojure.java.io :refer (resource)]
            [clojure.string :refer (trim)]))

(defn current-build-number []
  (if-let [build-number-res (resource "BUILD_NUMBER")]
    (Integer/parseInt (trim (slurp build-number-res)))))

(defn current-version []
  (let [base-version (slurp (resource "VERSION"))]
    (str base-version (if-let [build-num (current-build-number)]
                        (str "-" build-num)))))
