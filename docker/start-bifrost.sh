#!/bin/bash

DATA_DIR="/data/bifrost/${HOSTNAME}/"
mkdir -p ${DATA_DIR}

CONFIG_FILE="/bifrost-config.edn"

# This isn't exhaustive, but works for now.
# SEE http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region
if [ -n "$AWS_DEFAULT_REGION" ] ; then
    AWS_S3_ENDPOINT="s3-${AWS_DEFAULT_REGION}.amazonaws.com"
else
    AWS_S3_ENDPOINT="s3.amazonaws.com"
fi

case "${AWS_DEFAULT_REGION}" in
    us-east-1)      AWS_S3_ENDPOINT="s3.amazonaws.com" ;;
    us-west-1)      AWS_S3_ENDPOINT="s3-eu-west-1.amazonaws.com" ;;
    us-west-2)      AWS_S3_ENDPOINT="s3-eu-west-2.amazonaws.com" ;;
    eu-west-1)      AWS_S3_ENDPOINT="s3-eu-west-1.amazonaws.com" ;;
    eu-central-1)   AWS_S3_ENDPOINT="s3.eu-central-1.amazonaws.com" ;;
    ap-southeast-1) AWS_S3_ENDPOINT="s3-ap-southeast-1.amazonaws.com" ;;
    ap-southeast-2) AWS_S3_ENDPOINT="s3-ap-southeast-2.amazonaws.com" ;;
    ap-northeast-1) AWS_S3_ENDPOINT="s3-ap-northeast-1.amazonaws.com" ;;
    sa-east-1)      AWS_S3_ENDPOINT="s3-sa-east-1.amazonaws.com" ;;
    *)              echo "Unknown AWS region"; exit 1 ;;
esac

BUCKET=${BIFROST_BUCKET?NOT DEFINED}

function join { local IFS="$1"; shift; echo "$*"; }

if [ -z "${ZK_CONNECT}" ] ; then
    ZK_CHROOT=${ZK_CHROOT:-/}
    echo "ZK_CHROOT is ${ZK_CHROOT}"

    #Add entries for zookeeper peers.
    hosts=()
    for i in {1..255}
    do
        zk_name=$(printf "ZK%02d" ${i})
        zk_addr_name="${zk_name}_PORT_2181_TCP_ADDR"
        zk_port_name="${zk_name}_PORT_2181_TCP_PORT"

        [ ! -z "${!zk_addr_name}" ] && hosts+=("${!zk_addr_name}:${!zk_port_name}")
    done

    ZK_CONNECT="$(join , ${hosts[@]})${ZK_CHROOT}"

fi

FETCH_MESSAGE_MAX_BYTES=${BIFROST_CONSUMER_FETCH_SIZE:-$(( 16 * 1024 * 1024 ))} # default 16 Mb

if [ -z "${TOPICS}" ] ; then
    topics=()
    for t in {1..255}
    do
        topic_name=$(printf "TOPIC%02d" ${t})
        [ ! -z "${!topic_name}" ] && topics+=("\"${!topic_name}\"")
    done

    TOPICS="#{${topics[@]}}"

fi

function bytes_for_humans {
    local -i bytes=$1;
    if [[ $bytes -lt 1048576 ]]; then
        echo "$(( (bytes + 1023)/1024 ))KiB"
    else
        echo "$(( (bytes + 1048575)/1048576 ))MiB"
    fi
}

ROTATION_INTERVAL=${BIFROST_ROTATION_INTERVAL:-60000} # default 60s

echo "Zookeeper connect string is ${ZK_CONNECT}"
echo "DATA_DIR is ${DATA_DIR}"
echo "AWS S3 endpoint is ${AWS_S3_ENDPOINT}"
echo "FETCH_MESSAGE_MAX_BYTES is ${FETCH_MESSAGE_MAX_BYTES} [$(bytes_for_humans ${FETCH_MESSAGE_MAX_BYTES})]"
echo "BIFROST_BUCKET is ${BUCKET}"
echo "BIFROST_ROTATION_INTERVAL is ${ROTATION_INTERVAL}"
echo "TOPICS is ${TOPICS}"

cat <<EOF > ${CONFIG_FILE}
{:consumer-properties {"zookeeper.connect"       "${ZK_CONNECT}"
                       "group.id"                "${BIFROST_CONSUMER_GROUP_ID:?BIFROST_CONSUMER_GROUP_ID_NOT_DEFINED}"
                       "auto.offset.reset"       "smallest" ; we explicitly commit offsets once files have
                                                         ; been uploaded to s3 so no need for auto commit
                       "fetch.message.max.bytes" "${FETCH_MESSAGE_MAX_BYTES}"
                       "auto.commit.enable"      "false"}
 :topic-blacklist     nil
 :topic-whitelist     ${TOPICS}
 :rotation-interval   ${ROTATION_INTERVAL}
 :credentials         {:access-key "${AWS_ACCESS_KEY_ID:?NOT_DEFINED}"
                       :secret-key "${AWS_SECRET_ACCESS_KEY:?NOT_DEFINED}"
                       :endpoint "${AWS_S3_ENDPOINT}"}
 :uploaders-n         4 ; max-number of concurrent threads uploading to S3
 :bucket              "${BIFROST_BUCKET:?NOT DEFINED}"
 :riemann-host        nil ; if :riemann-host is set, metrics will be pushed to that host
 }
EOF

java -XX:ErrorFile=bifrost.error.log -Xmx1792m -XX:+HeapDumpOnOutOfMemoryError -jar -Djava.io.tmpdir=${DATA_DIR} /uberjar.jar --config ${CONFIG_FILE}
