
.PHONY: clean
clean:
	cd blockships && gradle clean


.PHONY: build

build:
	cd blockships && gradle build
	cp blockships/build/libs/*.jar bin
