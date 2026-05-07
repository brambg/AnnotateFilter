all: help
SHELL=/bin/bash
.SECONDARY:
.DELETE_ON_ERROR:

GREEN=\033[1;32m
RED=\033[1;31m
BLUE=\033[1;34m
RESET=\033[0m

test:
	mvn compile exec:java -Dexec.mainClass="nl.structs.TokenizeTest"
	$(MAKE) graph.svg
	open graph.svg

clean:
	mvn clean

%.svg: %.dot
	dot -Tsvg $< > $@

.PHONY: help
help:
	@echo "available \`make' targets:"
	@echo -e "  $(BLUE)clean$(RESET)  - remove generated files"
	@echo -e "  $(BLUE)bundle$(RESET) - generate the plugin bundle"
	@echo -e "  $(BLUE)test$(RESET)   - run the tests"

