pids=`ps -aux | grep datapool-service | grep $PWD | grep -v grep | awk '{printf("%s ", $2)}'`
kill -9 ${pids}
