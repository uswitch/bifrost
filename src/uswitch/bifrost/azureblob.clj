(ns uswitch.bifrost.azureblob
  (:require [com.stuartsierra.component :refer (Lifecycle system-map using start stop)]
            [uswitch.bifrost.async :refer (observable-chan map->Spawner)]
            [clojure.core.async :refer (<! >! go-loop thread chan close! alts! timeout >!! <!!)]
            [clojure.tools.logging :refer (info warn error debug)]
            [metrics.timers :refer (time! timer)]
            [clj-kafka.zk :refer (committed-offset set-offset!)]
            [clojure.java.io :refer (file)])
  (:import [com.microsoft.windowsazure.services.core.storage CloudStorageAccount]
           [com.microsoft.windowsazure.services.blob.client CloudBlobClient CloudBlobContainer CloudBlockBlob BlobRequestOptions]
           [java.io FileInputStream]))

(defn generate-blob-name [consumer-group-id topic partition first-offset]
  (format "%s/%s/partition=%s/%s.baldr.gz"
          consumer-group-id
          topic
          partition
          (format "%010d" first-offset)))

(defn- ensure-container-exists
  [{:keys [account-name account-key storage-container endpoint-protocol] :as azurestorage-credentials
    :or {endpoint-protocol "https"}}]
  (let [connection-string (str "DefaultEndpointsProtocol=" endpoint-protocol ";AccountName=" account-name ";AccountKey=" account-key)
        storage-account (CloudStorageAccount/parse connection-string)
        blob-client (.createCloudBlobClient storage-account)
        blob-container (.getContainerReference blob-client storage-container)]
    ;ensure the container exists
    (.createIfNotExist blob-container)
    blob-container))

;always calculate and send MD5 for server to check against
(def ^:private blob-req-options (doto (BlobRequestOptions.)
                                     (.setStoreBlobContentMD5 true)))
(defn- upload-blob [^CloudBlockBlob blob f]
  (.upload blob (FileInputStream. f) (.length f) nil blob-req-options nil))

(defn upload-to-azure
  [^CloudBlobContainer blob-container topic blob-name file-path]
  (let [f (file file-path)]
    (if (.exists f)
      (let [blob (.getBlockBlobReference blob-container blob-name)]
        (info "Uploading " file-path " to storage container as " blob-name)
        (time! (timer (str topic "-azureblob-upload-time"))
               (upload-blob blob f))
        (info "Finished uploading " blob-name))
      (warn "Unable to find file " file-path))))


(defn progress-azureblob-upload
  "Performs a step through uploading a file to Azure. Returns {:goto :pause}"
  [state
   blob-container consumer-properties
   topic partition
   first-offset last-offset
   file-path]
  (debug "Asked to step" {:state state :file-path file-path})
  (case state
    nil           {:goto :upload-file}
    :upload-file  (try
                    (info "Starting Azure Blob upload for " {:topic topic
                                                             :partition partition
                                                             :first-offset first-offset
                                                             :last-offset last-offset})
                    (let [consumer-group-id (consumer-properties "group.id")
                          blob-name (generate-blob-name consumer-group-id topic partition first-offset)]
                      (upload-to-azure blob-container topic blob-name file-path))
                    {:goto :commit}
                    (catch Exception e
                      (error e "Error whilst uploading to Azure Blob Storage. Retrying in 15s.")
                      {:goto :upload-file
                       :pause (* 15 1000)}))
    :commit       (try
                    (let [commit-offset (inc last-offset)]
                      (set-offset! consumer-properties (consumer-properties "group.id") topic partition commit-offset)
                      (info "Committed offset information to ZooKeeper" {:topic topic :partition partition :offset commit-offset}))
                    {:goto :delete}
                    (catch Exception e
                      (error e "Unable to commit offset to ZooKeeper. Retrying in 15s.")
                      {:goto :commit
                       :pause (* 15 1000)}))
    :delete       (try
                    (info "Deleting file " file-path)
                    (if (.delete (file file-path))
                      (info "Deleted" file-path)
                      (info "Unable to delete " file-path))
                    {:goto :done}
                    (catch Exception e
                      (error e "Error while deleting file " file-path)
                      {:goto :done}))))


(defn spawn-azureblob-upload
  [azurestorage-credentials consumer-properties semaphore [partition topic]]
  (let [rotated-event-ch (observable-chan (str partition "-" topic "-rotation-event-ch") 100)
        blob-container (ensure-container-exists azurestorage-credentials)]
    (info "Starting AzureBlobUpload component.")
    (thread
      (loop []
        (let [msg (<!! rotated-event-ch)]
          (if (nil? msg)

            (debug "Terminating AzureBlobUpload")

            (let [{:keys [topic partition file-path first-offset last-offset]} msg]
              (debug "Attempting to acquire semaphore to begin upload" {:file-path file-path})
              (>!! semaphore :token)
              (info "Starting Azure blob upload of" file-path)
              (loop [state nil]
                (let [{:keys [goto pause]} (progress-azureblob-upload state
                                                                      blob-container consumer-properties
                                                                      topic partition
                                                                      first-offset last-offset
                                                                      file-path)]
                  (<!! (timeout (or pause 0)))
                  (if (= :done goto)
                    (info "Terminating stepping Azure Blob upload machine.")
                    (recur goto))))
              (info "Done Uploading to Azure Blob storage:" file-path)
              (<!! semaphore)
              (recur))))))
    rotated-event-ch))


(defn azureblob-upload-spawner [{:keys [cloud-storage consumer-properties uploaders-n]}]
  (let [credentials (:credentials cloud-storage)
        semaphore (observable-chan "semaphore" uploaders-n)]
    (map->Spawner {:key-fn (juxt :partition :topic)
                   :spawn (partial spawn-azureblob-upload
                                   credentials consumer-properties
                                   semaphore)})))

(defn azureblob-system [config]
  (system-map :uploader (using (azureblob-upload-spawner config)
                               {:ch :rotated-event-ch})))
