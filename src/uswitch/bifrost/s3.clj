(ns uswitch.bifrost.s3
  (:require [com.stuartsierra.component :refer (Lifecycle system-map using start stop)]
            [clojure.tools.logging :refer (info warn error debug)]
            [clojure.core.async :refer (<! >! go-loop chan close! alts! timeout >!!)]
            [clojure.java.io :refer (file)]
            [aws.sdk.s3 :refer (put-object bucket-exists? create-bucket)]
            [metrics.timers :refer (time! timer)]
            [metrics.gauges :refer (gauge)]
            [clj-kafka.zk :refer (committed-offset set-offset!)]
            [uswitch.bifrost.util :refer (close-channels)]
            [uswitch.bifrost.async :refer (observable-chan map->Spawner)]))

(def buffer-size 100)

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

(defn progress-s3-upload
  "Performs a step through uploading a file to S3. Returns {:goto :pause}"
  [state
   credentials bucket consumer-properties
   topic partition
   first-offset last-offset
   file-path]
  (debug "Asked to step" {:state state :file-path file-path})
  (case state
    nil          {:goto :upload-file}
    :upload-file (try (info "Starting S3 upload for" {:topic topic
                                                      :partition partition
                                                      :first-offset first-offset
                                                      :last-offset last-offset})
                      (upload-to-s3 credentials bucket (consumer-properties "group.id")
                                    topic partition
                                    first-offset
                                    file-path)
                      {:goto :commit}
                      (catch Exception e
                        (error e "Error whilst uploading to S3. Retrying in 15s.")
                        {:goto  :upload-file
                         :pause (* 15 1000)}))
    :commit      (try
                   (let [commit-offset (inc last-offset)]
                     (set-offset! consumer-properties (consumer-properties "group.id") topic partition commit-offset)
                     (info "Committed offset information to ZooKeeper" {:topic topic :partition partition :offset commit-offset}))
                   {:goto :delete}
                   (catch Exception e
                     (error e "Unable to commit offset to ZooKeeper. Retrying in 15s.")
                     {:goto :commit
                      :pause (* 15 1000)}))
    :delete      (try
                   (info "Deleting file" file-path)
                   (if (.delete (file file-path))
                     (info "Deleted" file-path)
                     (info "Unable to delete" file-path))
                   {:goto :done}
                   (catch Exception e
                     (error e "Error while deleting file" file-path)
                     {:goto :done}))))

(defn spawn-s3-upload
  [credentials bucket consumer-properties
   semaphore
   [partition topic]]
  (let [rotated-event-ch (observable-chan (str partition "-" topic "-rotation-event-ch") 100)]
    (info "Starting S3Upload component.")
    (when-not (bucket-exists? credentials bucket)
      (info "Creating" bucket "bucket")
      (create-bucket credentials bucket))
    (go-loop
     []
     (let [msg (<! rotated-event-ch)]
       (if (nil? msg)

         (info "Terminating S3 uploader")

         (let [{:keys [topic partition file-path first-offset last-offset]} msg]
           (debug "Attempting to acquire semaphore to begin upload" {:file-path file-path})
           (.acquire semaphore)
           (info "Starting S3 upload of" file-path)
           (loop [state nil]
             (let [{:keys [goto pause]} (progress-s3-upload state
                                                            credentials bucket consumer-properties
                                                            topic partition
                                                            first-offset last-offset
                                                            file-path)]
               (<! (timeout (or pause 0)))
               (if (= :done goto)
                 (info "Terminating stepping S3 upload machine.")
                 (recur goto))))
           (info "Done uploading to S3:" file-path)
           (.release semaphore)
           (recur)))))
    rotated-event-ch))

(defn s3-upload-spawner [config]
  (let [{:keys [credentials bucket consumer-properties uploaders-n]} config
        semaphore (java.util.concurrent.Semaphore. uploaders-n)]
    (gauge "S3-upload-semaphore-queue-length" (.getQueueLength semaphore))
    (map->Spawner {:key-fn (juxt :partition :topic)
                   :spawn (partial spawn-s3-upload
                                   credentials bucket consumer-properties
                                   semaphore)})))

(defn s3-system [config]
  (system-map :uploader (using (s3-upload-spawner config)
                               {:ch :rotated-event-ch})))
