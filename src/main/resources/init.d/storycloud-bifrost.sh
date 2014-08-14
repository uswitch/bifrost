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

CONSOLE_LOG="${project.install.logDir}/${project.name}/${project.name}-console.log"
DAEMON_USER="${project.install.user}"
DAEMON_CMD="nohup ${project.install.dir}/start-${project.name} >> $CONSOLE_LOG 2>&1"
DAEMON_CONF="${project.install.confDir}/${project.name}.yml"
PID_FILE="${project.install.pidDir}/${project.name}.pid"

start() {
    if daemon_status>/dev/null; then
    	echo "${project.name} is already running."
    	exit 1
    else
    	echo "Starting ${project.name} server..."
    fi
    runuser --shell=/bin/bash --preserve-environment $DAEMON_USER --command="$DAEMON_CMD & echo \$! > $PID_FILE"
    RETVAL=$?
    daemon_status
    return $RETVAL
}

stop() {
    if daemon_status; then
        echo "Stopping ${project.name} server..."
        killproc -p $PID_FILE -TERM
        RETVAL=$?
        daemon_status
    else
        return 0
    fi
    return $RETVAL
}

daemon_status() {
    status -p $PID_FILE ${project.name}-server
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
