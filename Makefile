
.PHONY: build
build:
	cd blockships && gradle shadowJar
	mkdir -p bin
	cp blockships/build/libs/BlockShips-*.jar bin

.PHONY: clean
clean:
	cd blockships && gradle clean
	rm -rf bin
	rm -rf blockships/build
	rm -rf blockships/.gradle
	rm -rf blockships/bin


.PHONY: server-plugin-copy
server-plugin-copy:
	rm -f server/plugins/BlockShips*.jar
	cp bin/*.jar server/plugins/

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
# Terminal 1:
# make build && make test-server-setup && make test-server-download && cd test-server && java -Xmx1G -Xms1G -jar server.jar nogui
# 
# Terminal 2:
# make test-bot-install && make test-bot-run
#
# =============================================================================

TEST_SERVER_DIR := test-server
DOWNLOAD_CACHE := .download-cache
SERVER_VARIANT ?= paper
MINECRAFT_VERSION ?= 1.21.1

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
test-server-download-to-cache: $(DOWNLOAD_CACHE)/plugins/ProtocolLib.jar $(DOWNLOAD_CACHE)/plugins/ViaVersion.jar $(DOWNLOAD_CACHE)/plugins/ViaBackwards.jar

.PHONY: test-server-plugin-copy
test-server-plugin-copy: test-server-download-to-cache
	rm -rf $(TEST_SERVER_DIR)/plugins/
	mkdir -p $(TEST_SERVER_DIR)/plugins
	cp bin/*.jar $(TEST_SERVER_DIR)/plugins/
	cp $(DOWNLOAD_CACHE)/plugins/*.jar $(TEST_SERVER_DIR)/plugins/

.PHONY: test-server-setup
test-server-setup: test-server-plugin-copy
	echo "eula=true" > $(TEST_SERVER_DIR)/eula.txt
	printf "online-mode=false\nserver-port=25565\nspawn-protection=0\nmax-tick-time=-1\n" > $(TEST_SERVER_DIR)/server.properties
	printf '[{"uuid":"30fecbe1-2271-3418-8553-d3ded0e95f56","name":"TestBot","level":4}]\n' > $(TEST_SERVER_DIR)/ops.json

$(DOWNLOAD_CACHE)/paper-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	$(eval BUILD := $(shell curl -s "https://api.papermc.io/v2/projects/paper/versions/$*/builds" | jq -r '.builds[-1].build'))
	curl -o $@ "https://api.papermc.io/v2/projects/paper/versions/$*/builds/$(BUILD)/downloads/paper-$*-$(BUILD).jar"

$(DOWNLOAD_CACHE)/purpur-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	$(eval BUILD := $(shell curl -s "https://api.purpurmc.org/v2/purpur/$*" | jq -r '.builds.latest'))
	curl -o $@ "https://api.purpurmc.org/v2/purpur/$*/$(BUILD)/download"

.PHONY: test-server-download
test-server-download: $(DOWNLOAD_CACHE)/$(SERVER_VARIANT)-$(MINECRAFT_VERSION).jar
	mkdir -p $(TEST_SERVER_DIR)
	cp $< $(TEST_SERVER_DIR)/server.jar

.PHONY: test-server-run
test-server-run:
	@cd $(TEST_SERVER_DIR) && \
	mkfifo server_input && \
	tail -f server_input | java -Xmx1G -Xms1G -jar server.jar nogui > server.log 2>&1 & \
	SERVER_PID=$$!; \
	tail -f server.log & \
	TAIL_PID=$$!; \
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
		exit 1; \
	fi; \
	if grep -q "BlockShips.*enabled" server.log; then \
		echo "✓ BlockShips plugin loaded successfully"; \
	else \
		echo "✗ BlockShips plugin failed to load"; \
		cat server.log; \
		exit 1; \
	fi; \
	if grep -qE "ERROR.*BlockShips|BlockShips.*Exception" server.log; then \
		echo "✗ Errors detected in BlockShips plugin"; \
		grep -E "ERROR.*BlockShips|BlockShips.*Exception" server.log; \
		exit 1; \
	fi; \
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
	echo "✓ Server test completed"

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
.PHONY: test-bot-run
test-bot-run:
	cd test-bot && MC_VERSION=$(MINECRAFT_VERSION) npm test

# Full integration test with bot
# Starts server, waits for ready, OPs the bot, runs bot tests, then shuts down
.PHONY: test-server-with-bot
test-server-with-bot:
	@cd $(TEST_SERVER_DIR) && \
	mkfifo server_input 2>/dev/null || true; \
	tail -f server_input | java -Xmx1G -Xms1G -jar server.jar nogui > server.log 2>&1 & \
	SERVER_PID=$$!; \
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
	cd .. && cd test-bot && MC_VERSION=$(MINECRAFT_VERSION) npm test; \
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
	if [ $$BOT_EXIT -eq 0 ]; then \
		echo "✓ All tests completed successfully"; \
	else \
		echo "✗ Bot tests failed"; \
		exit 1; \
	fi