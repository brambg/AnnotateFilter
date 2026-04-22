package main.java.nl.structs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import org.apache.lucene.tests.analysis.TokenStreamToDot;

import java.io.PrintWriter;
import java.util.LinkedList;

public class TokenizeTest {
  TokenizeTest() {
    var analyzer = new StandardAnalyzer();
    try {
  
      var text = "This is a test of the tokenization process";
  
      var tokenStream = analyzer.tokenStream("field", text);

      var annotations = new LinkedList<AnnotateFilter.Annotation>();
      
      annotations.add(new AnnotateFilter.Annotation(5, 9, "concept1"));
      annotations.add(new AnnotateFilter.Annotation(10, 14, "concept3"));
      annotations.add(new AnnotateFilter.Annotation(10, 42, "concept2"));
      annotations.add(new AnnotateFilter.Annotation(18, 21, "concept32"));
      annotations.add(new AnnotateFilter.Annotation(18, 34, "concept5"));
      annotations.add(new AnnotateFilter.Annotation(35, 42, "concept8"));
      
      tokenStream = new AnnotateFilter(tokenStream, annotations);

      outputDot(tokenStream);

      //printTokenStream(tokenStream);

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      analyzer.close();
    }
  }

  public static void main(String[] args) {
    new TokenizeTest();
  }

  private static void outputDot(TokenStream tokenStream) throws Exception {
    var dotwriter = new PrintWriter("graph.dot");
    new TokenStreamToDot(null, tokenStream, dotwriter).toDot();
    dotwriter.close();
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
      System.out.println("");
    }
    tokenStream.end();
    tokenStream.close();
  }

}