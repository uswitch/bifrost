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

# TODO-Behnam use deployable parameters - NB. no maven here :-(

JAR_DIR=${JAR_DIR:-"/home/vagrant/Projects/storycloud/bifrost/target"}
checkDirExists $JAR_DIR
JAR=$(ls $JAR_DIR/*-standalone.jar 2>/dev/null)
checkFileExists $JAR

LOG_CONF=${LOG_CONF:-"/home/vagrant/Projects/storycloud/bifrost/resources/conf/logback.xml"}
checkFileExists $LOG_CONF
JVM_OPTIONS="-Dlogback.configurationFile=$LOG_CONF $JVM_OPTIONS"

LOCAL_DATA_DIR=${LOCAL_DATA_DIR:-"/data/bifrost"}
JVM_OPTIONS="-Djava.io.tmpdir=$LOCAL_DATA_DIR $JVM_OPTIONS"

# Allowing all JVM options to be overwritten from the calling environment
JAVA_OPTIONS=${JAVA_OPTIONS:-${JVM_OPTIONS}}

CONFIG_EDN=${CONFIG_EDN:-"/Users/bbehzadi/Projects/storycloud/bifrost/resources/conf/config.edn"}

set -x
exec $JAVA $JAVA_OPTIONS -jar $JAR --config $CONFIG_EDN
set +x

exit 0
