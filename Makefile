
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

# Test server recipes (for CI)
TEST_SERVER_DIR := test-server

.PHONY: test-server-setup
test-server-setup:
	mkdir -p $(TEST_SERVER_DIR)/plugins
	echo "eula=true" > $(TEST_SERVER_DIR)/eula.txt
	printf "online-mode=false\nserver-port=25565\nspawn-protection=0\nmax-tick-time=-1\n" > $(TEST_SERVER_DIR)/server.properties

.PHONY: test-server-download
test-server-download:
	@if [ -z "$(SERVER_VARIANT)" ] || [ -z "$(MINECRAFT_VERSION)" ]; then \
		echo "Error: SERVER_VARIANT and MINECRAFT_VERSION must be set"; \
		exit 1; \
	fi
	@mkdir -p $(TEST_SERVER_DIR)
	@if [ "$(SERVER_VARIANT)" = "paper" ]; then \
		API_URL="https://api.papermc.io/v2/projects/paper/versions/$(MINECRAFT_VERSION)/builds"; \
		BUILD=$$(curl -s "$$API_URL" | jq -r '.builds[-1].build'); \
		echo "Downloading Paper $(MINECRAFT_VERSION) build $$BUILD"; \
		curl -o $(TEST_SERVER_DIR)/server.jar "https://api.papermc.io/v2/projects/paper/versions/$(MINECRAFT_VERSION)/builds/$$BUILD/downloads/paper-$(MINECRAFT_VERSION)-$$BUILD.jar"; \
	elif [ "$(SERVER_VARIANT)" = "purpur" ]; then \
		API_URL="https://api.purpurmc.org/v2/purpur/$(MINECRAFT_VERSION)"; \
		BUILD=$$(curl -s "$$API_URL" | jq -r '.builds.latest'); \
		echo "Downloading Purpur $(MINECRAFT_VERSION) build $$BUILD"; \
		curl -o $(TEST_SERVER_DIR)/server.jar "https://api.purpurmc.org/v2/purpur/$(MINECRAFT_VERSION)/$$BUILD/download"; \
	else \
		echo "Error: Unknown SERVER_VARIANT '$(SERVER_VARIANT)'. Use 'paper' or 'purpur'"; \
		exit 1; \
	fi

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