#!/bin/bash

CONFIG_FILE="/bifrost-config.edn"

cat <<EOF > ${CONFIG_FILE}
{:consumer-properties {"zookeeper.connect"  "${ZK01_PORT_2181_TCP_ADDR}:${ZK01_PORT_2181_TCP_PORT}"
                       "group.id"           "bifrost"
                       "auto.offset.reset"  "smallest" ; we explicitly commit offsets once files have
                                                       ; been uploaded to s3 so no need for auto commit
                       "auto.commit.enable" "false"}
 :topic-blacklist     nil
 :topic-whitelist     nil ; if :topic-whitelist is defined, only topics
                          ; from the whitelist will be backed up. The
                          ; value should be a set of strings.
 :rotation-interval   60000 ; milliseconds
 :credentials         {:access-key "${AWS_ACCESS_KEY}"
                       :secret-key "${AWS_SECRET_KEY}"
                       :endpoint "${AWS_DEFAULT_REGION:-s3-eu-west-1}.amazonaws.com"}
 :uploaders-n         4 ; max-number of concurrent threads uploading to S3
 :bucket              "momondo-events"
 :riemann-host        nil ; if :riemann-host is set, metrics will be pushed to that host
 }
EOF

java -jar /uberjar.jar --config /bifrost-config.edn
