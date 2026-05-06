package nl.structs

import java.io.PrintWriter
import nl.structs.AnnotateFilter.Annotation
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute
import org.apache.lucene.tests.analysis.TokenStreamToDot

object TokenizeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        tokenizeTest()
    }

    private fun tokenizeTest() {
        try {
            StandardAnalyzer().use { analyzer ->
                val text = "This is a test of the tokenization process"
                var tokenStream = analyzer.tokenStream("field", text)

                val annotations = listOf(
                    Annotation(5, 9, "concept1"),
                    Annotation(10, 14, "concept3"),
                    Annotation(10, 42, "concept2"),
                    Annotation(18, 21, "concept32"),
                    Annotation(18, 34, "concept5"),
                    Annotation(35, 42, "concept8")
                )


                tokenStream = AnnotateFilter(tokenStream, annotations)
                outputDot(tokenStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(Exception::class)
    private fun outputDot(tokenStream: TokenStream) {
        PrintWriter("graph.dot").use { dotWriter ->
            TokenStreamToDot(null, tokenStream, dotWriter).toDot()
        }
    }

    @Throws(Exception::class)
    private fun printTokenStream(tokenStream: TokenStream) {
        val offsetAttribute = tokenStream.getAttribute(OffsetAttribute::class.java)
        val positionIncrementAttribute =
            tokenStream.getAttribute(PositionIncrementAttribute::class.java)
        val positionLengthAttribute =
            tokenStream.getAttribute(PositionLengthAttribute::class.java)
        val termAttribute = tokenStream.getAttribute(CharTermAttribute::class.java)

        tokenStream.reset()
        while (tokenStream.incrementToken()) {
            println(termAttribute.toString())
            println("position increment: " + positionIncrementAttribute.positionIncrement)
            println("position length: " + positionLengthAttribute.positionLength)
            println("offset: " + offsetAttribute.startOffset() + "-" + offsetAttribute.endOffset())
            println()
        }
        tokenStream.end()
        tokenStream.close()
    }
}