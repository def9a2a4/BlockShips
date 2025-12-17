
.PHONY: build
build:
	cd blockships && gradle build
	cp blockships/build/libs/*.jar bin

.PHONY: clean
clean:
	cd blockships && gradle clean


.PHONY: server-plugin-copy
server-plugin-copy:
	rm -f server/plugins/BlockShips*.jar
	cp bin/*.jar server/plugins/

.PHONY: server-clear-plugin-data
	rm -rf server/plugins/BlockShips/

.PHONY: server-start
server-start:
	cd server && java -Xmx2G -Xms2G -jar paper-1.21.10-105.jar nogui

.PHONY: server
server: server-plugin-copy server-start