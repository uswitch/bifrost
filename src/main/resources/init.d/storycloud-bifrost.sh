#!/bin/bash
#
### BEGIN INIT INFO
# Provides: $api
# Default-Start: 3 4 5
# Default-Stop: 0 1 6
### END INIT INFO

# Source function library.
. /etc/init.d/functions
RETVAL=0

# TODO-Behnam use deployable parameters - NB. no maven here :-(
PROJECT_NAME=bifrost                                                                # ${project.name}
PROJECT_INSTALL_DIR=/home/vagrant/Projects/storycloud/bifrost/resources/scripts     # ${project.install.dir}
PROJECT_INSTALL_USER=vagrant                                                        # ${project.install.user}
PROJECT_INSTALL_LOGDIR=/var/log/storycloud                                          # ${project.install.logDir}
PROJECT_INSTALL_PID=/var/run                                                        # ${project.install.pidDir}

CONSOLE_LOG="${PROJECT_INSTALL_LOGDIR}/${PROJECT_NAME}/${PROJECT_NAME}-console.log"
DAEMON_USER="${PROJECT_INSTALL_USER}"
DAEMON_CMD="nohup ${PROJECT_INSTALL_DIR}/start-bifrost >> $CONSOLE_LOG 2>&1"
PID_FILE="${PROJECT_INSTALL_PID}/${PROJECT_NAME}.pid"

start() {
    if daemon_status>/dev/null; then
    	echo "${PROJECT_NAME} is already running."
    	exit 1
    else
    	echo "Starting ${PROJECT_NAME} server..."
    fi
    runuser --shell=/bin/bash --preserve-environment $DAEMON_USER --command="$DAEMON_CMD & echo \$! > $PID_FILE"
    RETVAL=$?
    daemon_status
    return $RETVAL
}

stop() {
    if daemon_status; then
        echo "Stopping ${PROJECT_NAME} server..."
        killproc -p $PID_FILE -TERM
        RETVAL=$?
        daemon_status
    else
        return 0
    fi
    return $RETVAL
}

daemon_status() {
    status -p $PID_FILE ${PROJECT_NAME}-server
    return $?
}

restart() {
        stop
        start
}

reload()  {
    restart
}

case "$1" in
  start)
        start
        ;;
  stop)
        stop
        ;;
  status)
        daemon_status
        ;;
  restart)
        restart
        ;;
  reload)
        reload
        ;;
  *)
        echo $"Usage: $0 {start|stop|status|restart}"
        exit 2
esac

exit $?
