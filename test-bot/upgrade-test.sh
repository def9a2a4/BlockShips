#!/bin/bash
# 0.0.17 -> current upgrade test. Builds the real 0.0.17 from its tag, runs `upgrade-test.js setup` on it, swaps
# in the current BlockShips + DefCoreLib on the same world, runs `upgrade-test.js verify`.
#   test-bot/upgrade-test.sh [DEFCORELIB_JAR]     (Paper 1.21.11, Java 21; uses test-server/, port 25565)
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
V=1.21.11
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}

if [ ! -f .temp/v0017/bin/BlockShips-0.0.17.jar ]; then
    [ -d .temp/v0017 ] || git worktree add .temp/v0017 v0.0.17
    make -C .temp/v0017 build
fi
make build ${1:+DEFCORELIB_JAR=$1}
make clean-test-server
make test-server-download-all MINECRAFT_VERSION=$V ${1:+DEFCORELIB_JAR=$1}
make test-server-setup MINECRAFT_VERSION=$V ${1:+DEFCORELIB_JAR=$1}
echo $V > test-bot/.mc-version

run_phase() {
    cd "$REPO/test-server"
    rm -f server_input; mkfifo server_input
    exec 3<>server_input
    "$JAVA" -Xmx2G -jar server.jar nogui < server_input > server.log 2>&1 &
    local pid=$!
    for _ in $(seq 1 300); do grep -q "Done.*For help" server.log && break; sleep 1; done
    echo "op TestBot" >&3; sleep 2
    local rc=0
    (cd "$REPO/test-bot" && node upgrade-test.js "$1") || rc=$?
    echo "stop" >&3
    for _ in $(seq 1 60); do kill -0 $pid 2>/dev/null || break; sleep 1; done
    kill $pid 2>/dev/null || true
    exec 3>&-; rm -f server_input
    cp server.log "$REPO/test-bot/upgrade-$1-server.log"
    cd "$REPO"
    return $rc
}

# Phase 1: 0.0.17 alone (it has no DefCoreLib dependency).
rm -f test-server/plugins/BlockShips-*.jar test-server/plugins/defCoreLib-*.jar
cp .temp/v0017/bin/BlockShips-0.0.17.jar test-server/plugins/
run_phase setup

# Phase 2: same world and plugins/BlockShips data, current jars.
rm -f test-server/plugins/BlockShips-*.jar
cp bin/BlockShips-*.jar test-server/plugins/
cp "${1:-$(ls ../defCoreLib/bin/defCoreLib-*.jar | grep -v -- -plain | sort -V | tail -1)}" test-server/plugins/
run_phase verify
