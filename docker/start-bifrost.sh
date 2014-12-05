#!/bin/bash

CONFIG_FILE="/bifrost-config.edn"

cat <<EOF > ${CONFIG_FILE}
{:consumer-properties {"zookeeper.connect"  "${ZK01_PORT_2181_TCP_ADDR:-localhost}:${ZK01_PORT_2181_TCP_PORT:2181}"
                       "group.id"           "bifrost"
                       "auto.offset.reset"  "smallest" ; we explicitly commit offsets once files have
                                                       ; been uploaded to s3 so no need for auto commit
                       "auto.commit.enable" "false"}
 :topic-blacklist     nil
 :topic-whitelist     #{"${TOPIC:-events}"}
 :rotation-interval   60000 ; milliseconds
 :credentials         {:access-key "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID NOT_DEFINED}"
                       :secret-key "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY_ID NOT_DEFINED}"
                       :endpoint "s3.${AWS_DEFAULT_REGION}${AWS_DEFAULT_REGION:+.}amazonaws.com"}
 :uploaders-n         4 ; max-number of concurrent threads uploading to S3
 :bucket              "${BIFROST_BUCKET:-test-momondo-events}"
 :riemann-host        nil ; if :riemann-host is set, metrics will be pushed to that host
 }
EOF

java -jar /uberjar.jar --config ${CONFIG_FILE}
