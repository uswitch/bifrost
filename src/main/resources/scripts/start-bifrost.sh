#!/bin/bash
#
# Starts StoryCloud Bifrost server.
#

#
# $1: file full pathname
#
checkFileExists() {
	[[ -f $1 ]] || {
        echo "Error: $1 does not exist"
        exit -1
	}
}

#
# $1: directory name
#
checkDirExists() {
	[[ -d $1 ]] || {
        echo "Error: $1 does not exist"
        exit -1
	}
}

JAVA_HOME=${JAVA_HOME:-"/usr/java/jdk1.7.0_55"}
JAVA=$JAVA_HOME/bin/java
checkFileExists $JAVA

DEFAULT_JAR_DIR="${project.install.dir}"
JAR_DIR=${JAR_DIR:-"$DEFAULT_JAR_DIR"}
checkDirExists $JAR_DIR
JAR=$(ls $JAR_DIR/${project.build.finalName}-jar-with-dependencies.jar)
checkFileExists $JAR

LOG_CONF="${project.install.confDir}/log/logback-${project.name}.xml"
checkFileExists $LOG_CONF
JVM_OPTIONS="-Dlogback.configurationFile=$LOG_CONF $JVM_OPTIONS"

LOCAL_DATA_DIR=${LOCAL_DATA_DIR:-"/data/bifrost"}
JVM_OPTIONS="-Djava.io.tmpdir=$LOCAL_DATA_DIR $JVM_OPTIONS"

# Allowing all JVM options to be overwritten from the calling environment
JAVA_OPTIONS=${JAVA_OPTIONS:-${JVM_OPTIONS}}

CONFIG_EDN=${CONFIG_EDN:-"/etc/storycloud/bifrost.properties"}
checkFileExists $CONFIG_EDN

set -x
exec $JAVA $JAVA_OPTIONS -jar $JAR --config $CONFIG_EDN
set +x

exit 0

