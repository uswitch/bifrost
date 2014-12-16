#!/bin/bash

DATA_DIR="/data/bifrost/${HOSTNAME}/"

CONFIG_FILE="/bifrost-config.edn"

# This isn't exhaustive, but works for now.
# SEE http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region
if [ -n "$AWS_DEFAULT_REGION" ] ; then
    AWS_S3_ENDPOINT="s3-${AWS_DEFAULT_REGION}.amazonaws.com"
else
    AWS_S3_ENDPONT="s3.amazonaws.com"
fi

mkdir -p ${DATA_DIR}
echo "DATA_DIR is ${DATA_DIR}"
echo "AWS S3 endpoint is ${AWS_S3_ENDPOINT}"

cat <<EOF > ${CONFIG_FILE}
{:consumer-properties {"zookeeper.connect"  "${ZK01_PORT_2181_TCP_ADDR:-localhost}:${ZK01_PORT_2181_TCP_PORT:2181}"
                       "group.id"           "${BIFROST_CONSUMER_GROUP_ID:?BIFROST_CONSUMER_GROUP_ID_NOT_DEFINED}"
                       "auto.offset.reset"  "smallest" ; we explicitly commit offsets once files have
                                                       ; been uploaded to s3 so no need for auto commit
                       "auto.commit.enable" "false"}
 :topic-blacklist     nil
 :topic-whitelist     #{"${TOPIC:-events}"}
 :rotation-interval   60000 ; milliseconds
 :credentials         {:access-key "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID NOT_DEFINED}"
                       :secret-key "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY_ID NOT_DEFINED}"
                       :endpoint "${AWS_S3_ENDPOINT}"}
 :uploaders-n         4 ; max-number of concurrent threads uploading to S3
 :bucket              "${BIFROST_BUCKET:-test-momondo-events}"
 :riemann-host        nil ; if :riemann-host is set, metrics will be pushed to that host
 }
EOF

java -jar -Djava.io.tmpdir=${DATA_DIR} /uberjar.jar --config ${CONFIG_FILE}
