(ns uswitch.bifrost.core)

(defprotocol Producer
  (out-chan [this]))
