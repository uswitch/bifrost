(ns uswitch.bifrost.s3
  (:require [com.stuartsierra.component :refer (Lifecycle system-map using)]
            [clojure.tools.logging :refer (info warn error)]
            [clojure.core.async :refer (<! go-loop chan close!)]
            [clojure.java.io :refer (file)]
            [aws.sdk.s3 :refer (put-object bucket-exists? create-bucket)]
            [metrics.timers :refer (time! timer)]
            [uswitch.bifrost.util :refer (close-channels)]
            [uswitch.bifrost.async :refer (observable-chan)]))

(def buffer-size 50)

(defn generate-key [consumer-group-id topic partition first-offset]
  (format "%s/%s/partition=%s/%s.baldr.gz"
          consumer-group-id
          topic
          partition
          (format "%010d" first-offset)))

(defn upload-to-s3 [credentials bucket consumer-group-id topic partition first-offset file-path]
  (let [f (file file-path)]
    (if (.exists f)
      (let [key      (generate-key consumer-group-id topic partition first-offset)
            dest-url (str "s3n://" bucket "/" key)]
        (info "Uploading" file-path "to" dest-url)
        (time! (timer (str topic "-s3-upload-time"))
               (put-object credentials bucket key f))
        (info "Finished uploading" dest-url))
      (warn "Unable to find file" file-path))))

(defrecord S3Upload [credentials bucket consumer-properties uploaders-n
                     rotated-event-ch commit-offset-ch delete-local-file-ch]
  Lifecycle
  (start [this]
    (info "Starting S3Upload component.")
    (when-not (bucket-exists? credentials bucket)
      (info "Creating" bucket "bucket")
      (create-bucket credentials bucket))

    (doseq [i (range uploaders-n)]
      (go-loop [msg (<! rotated-event-ch)]
               (when msg
                 (let [{:keys [topic partition file-path first-offset last-offset]} msg
                       consumer-group-id (consumer-properties "group.id")]
                   (try (upload-to-s3 credentials bucket consumer-group-id topic partition first-offset file-path)
                        (>! delete-local-file-ch {:file-path file-path})
                        (>! commit-offset-ch {:topic     topic
                                              :partition partition
                                              :offset    last-offset})
                        (catch Exception e
                          ;; depending on the error we'll need to retry!!
                          (error e "Error whilst uploading to S3"))))
                 (recur (<! rotated-event-ch)))))
    this)
  (stop [this]
    this))

(defn s3-upload [config]
  (map->S3Upload (select-keys config [:credentials :bucket :consumer-properties :uploaders-n])))

(defrecord Deleter [delete-local-file-ch]
  Lifecycle
  (start [this]
    (info "Starting deleter")
    (go-loop [msg (<! delete-local-file-ch)]
             (when msg
               (let [{:keys [file-path]} msg]
                 (info "Deleting file" file-path)
                 (if (.delete (file file-path))
                   (info "Deleted" file-path)))
               (recur (<! delete-local-file-ch)))))
  (stop [this]
    this))

(defn deleter []
  (map->Deleter {}))

(defn s3-system [config]
  (system-map :delete-local-file-ch (observable-chan "delete-local-file-ch" buffer-size)
              :uploader (using (s3-upload config)
                               [:rotated-event-ch :commit-offset-ch :delete-local-file-ch])
              :deleter (using (deleter)
                              [:delete-local-file-ch])))
