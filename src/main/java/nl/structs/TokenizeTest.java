package nl.structs;

import java.io.PrintWriter;
import java.util.*;

import nl.structs.AnnotateFilter.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.tests.analysis.TokenStreamToDot;

public class TokenizeTest {

    public static void main(String[] args) {
        tokenizeTest();
    }

    private static void tokenizeTest() {
        try (var analyzer = new StandardAnalyzer()) {

            var text = "This is a test of the tokenization process";

            var tokenStream = analyzer.tokenStream("field", text);

            var annotations = new LinkedList<Annotation>();

            annotations.add(new Annotation(5, 9, "concept1"));
            annotations.add(new Annotation(10, 14, "concept3"));
            annotations.add(new Annotation(10, 42, "concept2"));
            annotations.add(new Annotation(18, 21, "concept32"));
            annotations.add(new Annotation(18, 34, "concept5"));
            annotations.add(new Annotation(35, 42, "concept8"));

            tokenStream = new AnnotateFilter(tokenStream, annotations);

            outputDot(tokenStream);

//            printTokenStream(tokenStream);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void outputDot(TokenStream tokenStream) throws Exception {
        try (var dotWriter = new PrintWriter("graph.dot")) {
            new TokenStreamToDot(null, tokenStream, dotWriter).toDot();
        }
    }

    private static void printTokenStream(TokenStream tokenStream) throws Exception {
        var offsetAttribute = tokenStream.getAttribute(OffsetAttribute.class);
        var positionIncrementAttribute = tokenStream.getAttribute(PositionIncrementAttribute.class);
        var positionLengthAttribute = tokenStream.getAttribute(PositionLengthAttribute.class);
        var termAttribute = tokenStream.getAttribute(CharTermAttribute.class);

        tokenStream.reset();
        while (tokenStream.incrementToken()) {
            System.out.println(termAttribute.toString());
            System.out.println("position increment: " + positionIncrementAttribute.getPositionIncrement());
            System.out.println("position length: " + positionLengthAttribute.getPositionLength());
            System.out.println("offset: " + offsetAttribute.startOffset() + "-" + offsetAttribute.endOffset());
            System.out.println();
        }
        tokenStream.end();
        tokenStream.close();
    }

}