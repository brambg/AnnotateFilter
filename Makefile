tokenize-test:
	mvn test
	$(MAKE) graph.svg
	open graph.svg

clean:
	mvn clean

%.svg: %.dot
	dot -Tsvg $< > $@