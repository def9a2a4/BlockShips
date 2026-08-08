# =============================================================================
# defCoreLib — compile-time dependency AND runtime `depend: [DefCoreLib]` plugin
# =============================================================================
# MUST stay above the targets that use it: make expands a rule's prerequisites when it READS the
# rule, so $(DEFCORELIB_BUILD_DEP) below would silently expand to nothing if defined further down.
#
# BlockShips refuses to enable without the DefCoreLib plugin jar in plugins/, so both CI
# (test-server-*) and the local loop (server-*) need a jar, not just a classpath entry. There is no
# published artifact to fetch — it always comes from a checkout on disk:
#
#   make build                              build ../defCoreLib, compile + run against it (default)
#   make build DEFCORELIB_DIR=/other/repo    ... using a checkout somewhere else
#   make build DEFCORELIB_JAR=/abs/x.jar     use that exact jar; skips the sibling build (CI does this)
#
# One switch drives both sides on purpose — you can't compile against one engine build and run
# against another by accident.
#
# blockships/gradle.properties still pins a defCoreLib ref, but nothing here reads it: it is CI's
# alone (.github/workflows/checks.yml clones and builds that commit). Bump it with `make defcorelib-pin`.
REPO_ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
DEFCORELIB_DIR ?= $(REPO_ROOT)/../defCoreLib

# An explicit DEFCORELIB_JAR (command line or environment) short-circuits the sibling entirely —
# that is what lets CI run with no defCoreLib checkout next to the repo.
ifeq ($(strip $(DEFCORELIB_JAR)),)
DEFCORELIB_BUILD_DEP   := defcorelib-build
GRADLE_DEFCORELIB_ARGS  = -PdefCoreLibDir=$(DEFCORELIB_DIR)
# Recursive `=`, NOT `:=` — the wildcard has to run after defcorelib-build has produced the jar.
# shadow jar only, never the `-plain` thin jar (Paper: "Ambiguous plugin name 'DefCoreLib'").
DEFCORELIB_JAR          = $(firstword $(filter-out %-plain.jar,\
	$(wildcard $(DEFCORELIB_DIR)/bin/defCoreLib-*.jar)))
else
DEFCORELIB_BUILD_DEP   :=
GRADLE_DEFCORELIB_ARGS  = -PdefCoreLibJar=$(DEFCORELIB_JAR)
endif

.PHONY: build
build: $(DEFCORELIB_BUILD_DEP)
	cd blockships && gradle shadowJar $(GRADLE_DEFCORELIB_ARGS)
	mkdir -p bin
	cp blockships/build/libs/BlockShips-*.jar bin

.PHONY: dump-issues
dump-issues:
	bash docs/dump-issues.sh

.PHONY: clean
clean:
	cd blockships && gradle clean
	rm -rf bin
	rm -rf blockships/build
	rm -rf blockships/.gradle
	rm -rf blockships/bin


.PHONY: server-plugin-copy
server-plugin-copy: defcorelib-jar
	rm -f server/plugins/BlockShips*.jar server/plugins/defCoreLib*.jar
	cp bin/*.jar server/plugins/
	cp $(DEFCORELIB_JAR) server/plugins/

.PHONY: server-clear-plugin-data
	rm -rf server/plugins/BlockShips/

.PHONY: server-start
server-start:
	cd server && java -Xmx2G -Xms2G -jar paper-1.21.11-55.jar nogui


.PHONY: server-start-alt
server-start-alt:
	cd server && java -Xmx2G -Xms2G -jar purpur-1.21.1-2329.jar nogui

.PHONY: server
server: server-plugin-copy server-start

# =============================================================================
# Test Server Recipes
# =============================================================================
#
# LOCAL TESTING (two terminals):
#   Terminal 1: make build test-server-download-all test-server-setup test-server-local
#   Terminal 2: make test-bot-install && make test-bot-run
#
# =============================================================================

TEST_SERVER_DIR := test-server
DOWNLOAD_CACHE := .download-cache
SERVER_VARIANT ?= paper
MINECRAFT_VERSION ?= 1.21.11

.PHONY: defcorelib-build
defcorelib-build:
	@test -d "$(DEFCORELIB_DIR)" || { \
		echo "ERROR: no defCoreLib checkout at $(DEFCORELIB_DIR)"; \
		echo "  git clone git@github.com:def9a2a4/defCoreLib.git $(DEFCORELIB_DIR)"; \
		echo "  or point elsewhere: make <target> DEFCORELIB_DIR=/path/to/defCoreLib"; \
		echo "  or use a prebuilt jar: make <target> DEFCORELIB_JAR=/abs/path/to.jar"; exit 1; }
	$(MAKE) -C $(DEFCORELIB_DIR) build

.PHONY: defcorelib-jar
defcorelib-jar: $(DEFCORELIB_BUILD_DEP)
	@test -n "$(DEFCORELIB_JAR)" && test -f "$(DEFCORELIB_JAR)" || { \
		echo "ERROR: DefCoreLib runtime jar not found: '$(DEFCORELIB_JAR)'"; \
		echo "  DEFCORELIB_DIR='$(DEFCORELIB_DIR)'"; exit 1; }
	@echo "DefCoreLib runtime jar: $(DEFCORELIB_JAR)"

# Bump the ref CI builds against to the sibling checkout's current HEAD. Local builds ignore the
# pin, so it rots silently without this.
.PHONY: defcorelib-pin
defcorelib-pin:
	@cd $(DEFCORELIB_DIR) && sha=$$(git rev-parse --short HEAD) && { \
		git diff-index --quiet HEAD -- || \
			echo "WARNING: $(DEFCORELIB_DIR) has uncommitted changes — they are NOT in $$sha"; \
		git merge-base --is-ancestor HEAD @{u} 2>/dev/null || \
			echo "WARNING: $$sha is not on the upstream branch — CI clones from GitHub and will fail to check it out"; \
		sed -i "s/^defCoreLibRef=.*/defCoreLibRef=$$sha/" $(REPO_ROOT)/blockships/gradle.properties; \
		echo "pinned defCoreLib $$sha"; \
	}

$(DOWNLOAD_CACHE)/plugins/ProtocolLib.jar:
	@mkdir -p $(DOWNLOAD_CACHE)/plugins
	curl -L -o $@ https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar

$(DOWNLOAD_CACHE)/plugins/ViaVersion.jar:
	@mkdir -p $(DOWNLOAD_CACHE)/plugins
	curl -L -o $@ https://github.com/ViaVersion/ViaVersion/releases/download/5.7.1/ViaVersion-5.7.1.jar

$(DOWNLOAD_CACHE)/plugins/ViaBackwards.jar:
	@mkdir -p $(DOWNLOAD_CACHE)/plugins
	curl -L -o $@ https://github.com/ViaVersion/ViaBackwards/releases/download/5.7.1/ViaBackwards-5.7.1.jar

.PHONY: test-server-download-to-cache
test-server-download-to-cache: $(DOWNLOAD_CACHE)/plugins/ProtocolLib.jar $(DOWNLOAD_CACHE)/plugins/ViaVersion.jar $(DOWNLOAD_CACHE)/plugins/ViaBackwards.jar $(DEFCORELIB_BUILD_DEP)

.PHONY: test-server-plugin-copy
test-server-plugin-copy: defcorelib-jar
	rm -rf $(TEST_SERVER_DIR)/plugins/
	mkdir -p $(TEST_SERVER_DIR)/plugins
	cp bin/*.jar $(TEST_SERVER_DIR)/plugins/
	cp $(DEFCORELIB_JAR) $(TEST_SERVER_DIR)/plugins/
ifeq ($(MINECRAFT_VERSION),1.21.1)
	cp $(DOWNLOAD_CACHE)/plugins/ProtocolLib.jar $(TEST_SERVER_DIR)/plugins/
endif
	cp $(DOWNLOAD_CACHE)/plugins/ViaVersion.jar $(TEST_SERVER_DIR)/plugins/
	cp $(DOWNLOAD_CACHE)/plugins/ViaBackwards.jar $(TEST_SERVER_DIR)/plugins/

# Bot username for testing
TEST_BOT_NAMES := TestBot

.PHONY: test-server-setup
test-server-setup: test-server-plugin-copy
	echo "eula=true" > $(TEST_SERVER_DIR)/eula.txt
	printf "online-mode=false\nserver-port=25565\nspawn-protection=0\nmax-tick-time=-1\n" > $(TEST_SERVER_DIR)/server.properties
	@python3 test-bot/generate_ops_json.py $(TEST_BOT_NAMES) -o $(TEST_SERVER_DIR)/ops.json
	@echo "Generated ops.json with $$(echo $(TEST_BOT_NAMES) | wc -w) operators"
	@if [ -f $(TEST_SERVER_DIR)/bukkit.yml ]; then \
		sed -i 's/connection-throttle: -\?[0-9]*/connection-throttle: -1/g' $(TEST_SERVER_DIR)/bukkit.yml; \
	else \
		printf 'settings:\n  connection-throttle: -1\n' > $(TEST_SERVER_DIR)/bukkit.yml; \
	fi
	@mkdir -p $(TEST_SERVER_DIR)/plugins/bStats
	@printf 'enabled: false\nserverUuid: "00000000-0000-0000-0000-000000000000"\nlogFailedRequests: false\nlogSentData: false\nlogResponseStatusText: false\n' > $(TEST_SERVER_DIR)/plugins/bStats/config.yml

$(DOWNLOAD_CACHE)/paper-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	curl -o $@ $$(curl -s -X POST "https://fill.papermc.io/graphql" \
		-H "Content-Type: application/json" \
		-d '{"query":"{ project(key: \"paper\") { version(key: \"$*\") { builds(orderBy: {direction: DESC}, first: 1) { edges { node { download(key: \"server:default\") { url } } } } } } }"}' \
		| jq -r '.data.project.version.builds.edges[0].node.download.url')

$(DOWNLOAD_CACHE)/purpur-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	$(eval BUILD := $(shell curl -s "https://api.purpurmc.org/v2/purpur/$*" | jq -r '.builds.latest'))
	curl -o $@ "https://api.purpurmc.org/v2/purpur/$*/$(BUILD)/download"

.PHONY: test-server-download
test-server-download: $(DOWNLOAD_CACHE)/$(SERVER_VARIANT)-$(MINECRAFT_VERSION).jar
	mkdir -p $(TEST_SERVER_DIR)
	cp $< $(TEST_SERVER_DIR)/server.jar

.PHONY: test-server-download-all
test-server-download-all: test-server-download-to-cache test-server-download

.PHONY: test-server-local
test-server-local:
	cd $(TEST_SERVER_DIR) && java -Xmx1G -Xms1G -jar server.jar nogui

.PHONY: clean-test-server
clean-test-server:
	rm -rf $(TEST_SERVER_DIR)

.PHONY: clean-download-cache
clean-download-cache:
	rm -rf $(DOWNLOAD_CACHE)

# Bot test targets
.PHONY: test-bot-install
test-bot-install:
	cd test-bot && npm install

# 	rm -f $(TEST_SERVER_DIR)/world/playerdata/30fecbe1-2271-3418-8553-d3ded0e95f56.dat
.PHONY: test-bot-enable-debug-glow
test-bot-enable-debug-glow:
	sed -i 's/collision-debug-glow: false/collision-debug-glow: true/g' $(TEST_SERVER_DIR)/plugins/BlockShips/config.yml

.PHONY: test-bot-write-version
test-bot-write-version:
	echo '$(MINECRAFT_VERSION)' > test-bot/.mc-version

.PHONY: test-bot-run
test-bot-run: test-bot-enable-debug-glow test-bot-write-version
	cd test-bot && npm test

.PHONY: test-chunk-bot-run
test-chunk-bot-run: test-bot-write-version
	cd test-bot && npm run test:chunk

# Server startup test only (used by CI for versions where the bot doesn't work yet)
# Starts server, verifies plugin loads, checks for errors, then shuts down
.PHONY: test-server-startup-only
test-server-startup-only:
	@cd $(TEST_SERVER_DIR) && \
	mkfifo server_input 2>/dev/null || true; \
	tail -f server_input | java -Xmx1G -Xms1G -jar server.jar nogui > server.log 2>&1 & \
	SERVER_PID=$$!; \
	sleep 0.5; \
	tail -f server.log & \
	TAIL_PID=$$!; \
	echo "Waiting for server to start..."; \
	for i in $$(seq 1 600); do \
		if grep -q "Done.*For help" server.log 2>/dev/null; then \
			echo ""; \
			echo "========== Server started successfully =========="; \
			break; \
		fi; \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			echo ""; \
			echo "========== Server process died unexpectedly =========="; \
			cat server.log; \
			exit 1; \
		fi; \
		sleep 1; \
	done; \
	if ! grep -q "Done.*For help" server.log 2>/dev/null; then \
		echo ""; \
		echo "========== Server startup timed out =========="; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	if ! grep -q "BlockShips.*enabled" server.log; then \
		echo "✗ BlockShips plugin failed to load"; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	echo "✓ BlockShips plugin loaded"; \
	echo ""; \
	echo "========== Shutting down server =========="; \
	echo "stop" > server_input; \
	for i in $$(seq 1 30); do \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			break; \
		fi; \
		sleep 1; \
	done; \
	kill $$TAIL_PID 2>/dev/null || true; \
	kill $$SERVER_PID 2>/dev/null || true; \
	rm -f server_input; \
	rm -f errors.log; \
	FAILED=0; \
	if grep -qE "ERROR.*BlockShips|BlockShips.*(Exception|Throwable)|\[BlockShips\].*(failed to restore|skipping)|at anon\.def9a2a4\.blockships" server.log 2>/dev/null; then \
		echo "=== SERVER ERRORS ===" | tee -a errors.log; \
		grep -E "ERROR.*BlockShips|BlockShips.*(Exception|Throwable)|\[BlockShips\].*(failed to restore|skipping)|at anon\.def9a2a4\.blockships" server.log | tee -a errors.log; \
		FAILED=1; \
	fi; \
	if [ $$FAILED -eq 1 ]; then \
		echo "✗ Tests failed"; \
		exit 1; \
	else \
		echo "✓ Server startup test passed"; \
	fi

# Full integration test with bot (used by CI)
# Starts server, waits for ready, OPs the bot, runs bot tests, then shuts down
.PHONY: test-server-ci
test-server-ci:
	@cd $(TEST_SERVER_DIR) && \
	mkfifo server_input 2>/dev/null || true; \
	tail -f server_input | java -Xmx1G -Xms1G -jar server.jar nogui > server.log 2>&1 & \
	SERVER_PID=$$!; \
	sleep 0.5; \
	tail -f server.log & \
	TAIL_PID=$$!; \
	echo "Waiting for server to start..."; \
	for i in $$(seq 1 600); do \
		if grep -q "Done.*For help" server.log 2>/dev/null; then \
			echo ""; \
			echo "========== Server started successfully =========="; \
			break; \
		fi; \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			echo ""; \
			echo "========== Server process died unexpectedly =========="; \
			cat server.log; \
			exit 1; \
		fi; \
		sleep 1; \
	done; \
	if ! grep -q "Done.*For help" server.log 2>/dev/null; then \
		echo ""; \
		echo "========== Server startup timed out =========="; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	if ! grep -q "BlockShips.*enabled" server.log; then \
		echo "✗ BlockShips plugin failed to load"; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	echo "✓ BlockShips plugin loaded"; \
	echo ""; \
	echo "========== Preparing for bot tests =========="; \
	echo "op TestBot" > server_input; \
	sleep 2; \
	echo ""; \
	echo "========== Running bot tests =========="; \
	echo '$(MINECRAFT_VERSION)' > ../test-bot/.mc-version; \
	cd .. && cd test-bot && npm run test:all; \
	BOT_EXIT=$$?; \
	cd ../$(TEST_SERVER_DIR); \
	echo ""; \
	echo "========== Shutting down server =========="; \
	echo "stop" > server_input; \
	for i in $$(seq 1 30); do \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			break; \
		fi; \
		sleep 1; \
	done; \
	kill $$TAIL_PID 2>/dev/null || true; \
	kill $$SERVER_PID 2>/dev/null || true; \
	rm -f server_input; \
	rm -f errors.log; \
	FAILED=0; \
	if [ $$BOT_EXIT -ne 0 ]; then \
		echo "=== BOT TEST FAILURES ===" | tee -a errors.log; \
		if [ -f ../test-bot/test-results.txt ]; then \
			echo "--- Main Tests ---" | tee -a errors.log; \
			cat ../test-bot/test-results.txt | tee -a errors.log; \
		fi; \
		if [ -f ../test-bot/chunk-test-results.txt ]; then \
			echo "" | tee -a errors.log; \
			echo "--- Chunk Tests ---" | tee -a errors.log; \
			cat ../test-bot/chunk-test-results.txt | tee -a errors.log; \
		fi; \
		echo "" | tee -a errors.log; \
		echo "Bot tests failed (exit code $$BOT_EXIT)" | tee -a errors.log; \
		FAILED=1; \
	fi; \
	if grep -qE "ERROR.*BlockShips|BlockShips.*(Exception|Throwable)|\[BlockShips\].*(failed to restore|skipping)|at anon\.def9a2a4\.blockships" server.log 2>/dev/null; then \
		echo "" | tee -a errors.log; \
		echo "=== SERVER ERRORS ===" | tee -a errors.log; \
		grep -E "ERROR.*BlockShips|BlockShips.*(Exception|Throwable)|\[BlockShips\].*(failed to restore|skipping)|at anon\.def9a2a4\.blockships" server.log | tee -a errors.log; \
		FAILED=1; \
	fi; \
	if [ $$FAILED -eq 1 ]; then \
		echo "✗ Tests failed"; \
		exit 1; \
	else \
		echo "✓ All tests passed"; \
	fi