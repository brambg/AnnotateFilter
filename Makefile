tokenize-test:
	mvn compile exec:java -Dexec.mainClass="nl.structs.TokenizeTest"
	$(MAKE) graph.svg
	open graph.svg

clean:
	mvn clean

%.svg: %.dot
	dot -Tsvg $< > $@