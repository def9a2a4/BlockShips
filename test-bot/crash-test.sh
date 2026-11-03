#!/bin/bash
# Crash test: kills the server (kill -9) right after a ship docks, right after one launches, and after one has
# sailed off with no world-save event since its launch; boots it again after each and checks the ship came back
# right. See crash-test.js.
#   test-bot/crash-test.sh [DEFCORELIB_JAR]     (Paper 1.21.11, Java 21; uses test-server/, port 25565)
#   ONLY=sail|drift    run only that case
#   MC_PORT=25568      run the server on another port (default 25565)
#   RUNTIME_JAR=path   run the server on this DefCoreLib jar instead (e.g. an older one, to see a case fail)
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
V=1.21.11
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}

make build ${1:+DEFCORELIB_JAR=$1}
make clean-test-server
make test-server-download-all MINECRAFT_VERSION=$V ${1:+DEFCORELIB_JAR=$1}
make test-server-setup MINECRAFT_VERSION=$V ${1:+DEFCORELIB_JAR=$1}
echo $V > test-bot/.mc-version
export MC_PORT=${MC_PORT:-25565}
sed -i "s/^server-port=.*/server-port=$MC_PORT/" test-server/server.properties
rm -f test-bot/.crash-state.json test-bot/crash-server.log
: > test-bot/crash-test-results.all

PID=
start() {
    cd "$REPO/test-server"
    rm -f server_input; mkfifo server_input
    exec 3<>server_input
    "$JAVA" -Xmx2G -jar server.jar nogui < server_input > server.log 2>&1 &
    PID=$!
    for _ in $(seq 1 300); do grep -q "Done.*For help" server.log && break; sleep 1; done
    echo "op TestBot" >&3; sleep 2
    cd "$REPO"
}
stop() {
    echo "stop" >&3
    for _ in $(seq 1 60); do kill -0 $PID 2>/dev/null || break; sleep 1; done
    kill $PID 2>/dev/null || true
    exec 3>&-; rm -f test-server/server_input
    cat test-server/server.log >> test-bot/crash-server.log
}
crash() {
    kill -9 $PID; wait $PID 2>/dev/null || true
    exec 3>&-; rm -f test-server/server_input
    cat test-server/server.log >> test-bot/crash-server.log
}
phase() {
    local rc=0
    (cd test-bot && MC_SERVER_LOG=$REPO/test-server/server.log node crash-test.js "$1") || rc=$?
    cat test-bot/crash-test-results.txt >> test-bot/crash-test-results.all
    return $rc
}
# $1: ticks between a chunk's own autosaves. World-save events (and player saves) are pushed out of reach so the
# only writes are the ones a phase makes on purpose.
autosave() {
    sed -i 's/^\(  *autosave:\).*/\1 72000/' test-server/bukkit.yml
    sed -i "s/^\(  *auto-save-interval:\).*/\1 $1/" test-server/config/paper-world-defaults.yml
}

if [ -n "$RUNTIME_JAR" ]; then rm -f test-server/plugins/defCoreLib-*.jar; cp "$RUNTIME_JAR" test-server/plugins/; fi

FAILED=0
start; stop                                # writes the default configs
if [ "$ONLY" = drift ]; then
autosave 72000
start
phase drift || FAILED=1
crash
start
phase verify-drift || FAILED=1
stop
fi
if [ -z "$ONLY" ]; then
autosave 72000
start
phase dock || FAILED=1
crash
start
phase verify-dock || FAILED=1
phase launch || FAILED=1
crash
start
phase verify-launch || FAILED=1
phase drift || FAILED=1
crash
start
phase verify-drift || FAILED=1
phase relaunch || FAILED=1               # first: its save-all would write the redock case to disk too
phase redock || FAILED=1
crash
start
phase verify-redock || FAILED=1
phase verify-relaunch || FAILED=1
stop
fi

if [ -z "$ONLY" ] || [ "$ONLY" = sail ]; then
autosave 40                                # chunks write themselves every 2 s; still no world-save event
start
phase sail || FAILED=1
crash
start
phase verify-sail || FAILED=1
stop
fi

grep -E "PASS|FAIL" test-bot/crash-test-results.all || true
echo "crash test rc=$FAILED"
exit $FAILED
