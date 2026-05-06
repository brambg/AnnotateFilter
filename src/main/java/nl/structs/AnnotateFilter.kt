/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.structs

import java.io.IOException
import java.util.LinkedList
import java.util.SortedSet
import java.util.TreeSet
import kotlin.math.max
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.util.CharsRefBuilder
import org.apache.lucene.util.RollingBuffer
import org.apache.lucene.util.RollingBuffer.Resettable

class AnnotateFilter(input: TokenStream, annotations: LinkedList<Annotation?>) : TokenFilter(input) {
    private val termAtt: CharTermAttribute = addAttribute<CharTermAttribute>(CharTermAttribute::class.java)
    private val posIncrAtt: PositionIncrementAttribute =
        addAttribute<PositionIncrementAttribute>(PositionIncrementAttribute::class.java)
    private val posLenAtt: PositionLengthAttribute =
        addAttribute<PositionLengthAttribute>(PositionLengthAttribute::class.java)
    private val typeAtt: TypeAttribute = addAttribute<TypeAttribute>(TypeAttribute::class.java)
    private val offsetAtt: OffsetAttribute = addAttribute<OffsetAttribute>(OffsetAttribute::class.java)

    private val outputBuffer = LinkedList<BufferedOutputToken>()

    private var maxLookaheadUsed = 0

    private var liveToken = false

    // True once the input TokenStream is exhausted:
    private var finished = false

    private var lookaheadNextRead = 0
    private var lookaheadNextWrite = 0

    @JvmRecord
    data class Annotation(val startOffset: Int, val endOffset: Int, val annotation: String?)

    private val annotationIterator: MutableListIterator<Annotation?>
    private var currentAnnotation: Annotation? = null

    private val lookahead: RollingBuffer<BufferedInputToken> = object : RollingBuffer<BufferedInputToken>() {
        override fun newInstance(): BufferedInputToken {
            return BufferedInputToken()
        }
    }

    internal class BufferedInputToken : Resettable {
        val term: CharsRefBuilder = CharsRefBuilder()
        var state: State? = null
        var startOffset: Int = -1
        var endOffset: Int = -1

        override fun reset() {
            state = null
            term.clear()

            // Intentionally invalid to ferret out bugs:
            startOffset = -1
            endOffset = -1
        }
    }

    class BufferedOutputToken {
        val state: State?
        val term: String?
        val startPos: Int
        var endPos: Int
        val startOffset: Int
        val endOffset: Int
        var posLength: Int = 0
        val posIncrement: Int
        var isPartial: Boolean

        constructor(state: State?) {
            // constructor for original input tokens

            this.state = state
            this.term = null
            this.startPos = 0
            this.endPos = 0
            this.startOffset = 0
            this.endOffset = 0
            this.posIncrement = 0
            this.isPartial = false
        }

        constructor(
            term: String?,
            startPos: Int,
            endPos: Int,
            startOffset: Int,
            endOffset: Int,
            posIncrement: Int,
            isPartial: Boolean
        ) {
            // constructor for annotation tokens

            this.state = null
            this.term = term
            this.startPos = startPos
            this.endPos = endPos
            this.startOffset = startOffset
            this.endOffset = endOffset
            this.posIncrement = posIncrement
            this.isPartial = isPartial
        }
    }

    init {
        this.annotationIterator = annotations.listIterator()

        // We assume the annotationlist is ordered on the start offset
        // Add a sort option
    }

    @Throws(IOException::class)
    override fun incrementToken(): Boolean {
        if (!outputBuffer.isEmpty()) {
            // We still have pending outputs from a prior synonym match:
            releaseBufferedToken()
            // System.out.println(" syn: ret buffered=" + this);
            assert(!liveToken)
            return true
        }

        // Try to parse a new match at the current token:
        if (parse()) {
            // A new match was found:
            releaseBufferedToken()
            // System.out.println(" syn: after parse, ret buffered=" + this);
            assert(!liveToken)
            return true
        }

        if (lookaheadNextRead == lookaheadNextWrite) {
            // Fast path: parse pulled one token, but it didn't match
            // the start for any annotations, so we now return it "live" w/o having
            // cloned all of its atts:
            //System.out.println(" fast path: return live token");

            if (finished) {
                // System.out.println(" syn: ret END");
                return false
            }

            assert(liveToken)
            liveToken = false

            // NOTE: no need to change posInc since it's relative, i.e. whatever
            // node our output is upto will just increase by the incoming posInc.
            // We also don't need to change posLen, but only because we cannot
            // consume a graph, so the incoming token can never span a future
            // synonym match.
        } else {
            // We still have buffered lookahead tokens from a previous
            // parse attempt that required lookahead; just replay them now:
            // System.out.println(" restore buffer");
            assert(
                lookaheadNextRead < lookaheadNextWrite
            ) { "read=" + lookaheadNextRead + " write=" + lookaheadNextWrite }
            val token = lookahead.get(lookaheadNextRead)
            lookaheadNextRead++

            restoreState(token.state)

            lookahead.freeBefore(lookaheadNextRead)

            // System.out.println(" after restore offset=" + offsetAtt.startOffset() + "-" +
            // offsetAtt.endOffset());
            assert(!liveToken)
        }

        return true
    }

    private fun releaseBufferedToken() {
        // We moved some of the state in this function to the output token
        // in order to support multiple matches
        // pos length, pos increment, start offset and end offset

        val token = checkNotNull(outputBuffer.pollFirst())

        if (token.state != null) {
            // This is an original input token (keepOrig=true case):
            restoreState(token.state)

            // System.out.println(" release token " + termAtt.toString());

            // System.out.println(" startOffset=" + offsetAtt.startOffset() + " endOffset="
            // +
            // offsetAtt.endOffset());
        } else {
            clearAttributes()
            termAtt.append(token.term)
            offsetAtt.setOffset(token.startOffset, token.endOffset)
            // System.out.println(" startOffset=" + matchStartOffset + " endOffset=" +
            // matchEndOffset);
            typeAtt.setType(TOKEN_TYPE)
            posIncrAtt.setPositionIncrement(token.posIncrement)
            posLenAtt.setPositionLength(token.endPos - token.startPos) // TODO check
            // System.out.println(" release token " + token.term + " posIncr=" + token.posIncrement + " posLen=" + (token.endPos - token.startPos));
        }
    }

    /**
     * Scans the next input token(s) to see if a synonym matches. Returns true if a
     * match was found.
     */
    @Throws(IOException::class)
    private fun parse(): Boolean {
        // System.out.println(Thread.currentThread().getName() + ": S: parse: " +
        // System.identityHashCode(this));

        val matches = LinkedList<BufferedOutputToken>()

        // How many tokens in the current match
        var matchLength = 0
        var doFinalCapture = false

        var lookaheadUpto = lookaheadNextRead

        while (true) {
            // System.out.println(" cycle lookaheadUpto=" + lookaheadUpto + " maxPos=" +
            // lookahead.getMaxPos());

            // Pull next token's chars:
            //String termText;
            //final int bufferLen;

            val inputEndOffset: Int
            val inputStartOffset: Int

            if (lookaheadUpto <= lookahead.getMaxPos()) {
                // Still in our lookahead buffer
                val token = lookahead.get(lookaheadUpto)
                lookaheadUpto++
                // termText = token.term.toString();
                //bufferLen = token.term.length();
                inputEndOffset = token.endOffset
                inputStartOffset = token.startOffset

                // System.out.println(" use buffer now max=" + lookahead.getMaxPos());
            } else {
                // We used up our lookahead buffer of input tokens
                // -- pull next real input token:

                assert(finished || !liveToken)

                if (finished) {
                    // System.out.println(" break: finished");
                    break
                } else if (input.incrementToken()) {
                    // System.out.println(" input.incrToken");
                    liveToken = true
                    //termText = termAtt.toString();
                    //bufferLen = termAtt.length();
                    inputStartOffset = offsetAtt.startOffset()
                    inputEndOffset = offsetAtt.endOffset()
                    lookaheadUpto++
                } else {
                    // No more input tokens
                    finished = true
                    // System.out.println(" break: now set finished");
                    break
                }
            }

            // System.out.println(termText);
            matchLength++

            // System.out.println(" cycle term=" + new String(buffer, 0, bufferLen));

            // Check if there is a match after reading the token
            // 1: Are there annotations starting at this token?

            // If so, add new match to the partialMatches list with:
            // - posIncrement 0. We want to be able to add more than one annotation starting here. the increment is left to the original token. Check this behaviour
            // - startOffset and endOffset are used directly from the annotation. They are simply pointers into the original text.
            // - endpos is now set to 0, but we dont know if this is the final value. That's why it's partial.
            if (currentAnnotation == null) {
                currentAnnotation = annotationIterator.next()
                // System.out.println(currentAnnotation.annotation);
            }

            if (currentAnnotation!!.startOffset >= inputStartOffset && currentAnnotation!!.startOffset <= inputEndOffset) {
                // System.out.println("match");

                matches.add(
                    BufferedOutputToken(
                        currentAnnotation!!.annotation,
                        matchLength - 1,
                        0,
                        currentAnnotation!!.startOffset,
                        currentAnnotation!!.endOffset,
                        0,
                        true
                    )
                )

                // see if there are other annotations starting at the same token:
                while (annotationIterator.hasNext()) {
                    currentAnnotation = annotationIterator.next()

                    // System.out.println(currentAnnotation.annotation);
                    if (currentAnnotation!!.startOffset >= inputEndOffset) {
                        // We are past the end of the interval, so we can stop checking
                        // System.out.println("break");
                        break
                    }

                    if (currentAnnotation!!.startOffset >= inputStartOffset) {
                        // System.out.println("match");
                        matches.add(
                            BufferedOutputToken(
                                currentAnnotation!!.annotation,
                                matchLength - 1,
                                0,
                                currentAnnotation!!.startOffset,
                                currentAnnotation!!.endOffset,
                                0,
                                true
                            )
                        )
                    }
                }
            }

            // 2: Check the partialMatches if the current token is the end of any of them
            // If so, add the matchLength of the partial match
            // If the there are no partialMatches: all matching is done at the
            // current input position. break the while loop
            var doneMatching = true

            for (token in matches) {
                if (token.isPartial && token.endOffset <= inputEndOffset) {
                    token.endPos = matchLength
                    token.isPartial = false
                }
                if (token.isPartial) {
                    doneMatching = false
                }
            }

            if (doneMatching) {
                // All partial matches are fulfilled. We are done
                // searching for matching rules starting at the
                // current input position.
                break
            } else {
                // More matching is possible

                doFinalCapture = true
                if (liveToken) {
                    capture()
                }
            }
        }

        if (doFinalCapture && liveToken && !finished) {
            // Must capture the final token if we captured any prior tokens:
            capture()
        }

        // 4: are there matches (all should be full matches at this point)
        if (!matches.isEmpty()) {
            if (liveToken) {
                // Single input token synonym; we must buffer it now:
                capture()
            }

            bufferOutputTokens(matches, matchLength)

            return true
        } else {
            // System.out.println(" no match; lookaheadNextRead=" + lookaheadNextRead);
            return false
        }
    }

    /**
     * Expands the output graph into the necessary tokens, adding synonyms as side
     * paths parallel to
     * the input tokens, and buffers them in the output token buffer.
     */
    private fun bufferOutputTokens(matches: LinkedList<BufferedOutputToken>, matchLength: Int) {
        // We have a list of matches and the tokens that where needed for these matches.
        // We know there is a start of a match at the current position

        // Group the matches by their start position

        val uniqueStartPositions: SortedSet<Int> = TreeSet<Int>()

        for (token in matches) uniqueStartPositions.add(token.startPos)

        // First, output the token that started the match
        outputBuffer.add(BufferedOutputToken(lookahead.get(lookaheadNextRead).state))
        lookaheadNextRead++

        // then, iterate the grouped startpositions
        val posIterator = uniqueStartPositions.iterator()
        var previousPosition = -1

        while (posIterator.hasNext()) {
            val startPos = posIterator.next()

            // first, fill the gap with the previous match position with original tokens
            if (previousPosition > -1) {
                val gap = startPos - previousPosition

                for (j in 0..<gap) {
                    outputBuffer.add(BufferedOutputToken(lookahead.get(lookaheadNextRead).state))
                    lookaheadNextRead++
                }
            }

            // then, output all matches starting at this position
            for (match in matches) {
                if (match.startPos == startPos) {
                    outputBuffer.add(match)
                }
            }

            if (posIterator.hasNext()) {
                // remember the current position as the previous one for the next iteration
                previousPosition = startPos
            } else {
                // last match: fill until the end of the match with original tokens
                // TODO check!!

                val gap = (matchLength - 1) - startPos

                for (j in 0..<gap) {
                    outputBuffer.add(BufferedOutputToken(lookahead.get(lookaheadNextRead).state))
                    lookaheadNextRead++
                }
            }
        }

        // System.out.println(" precmatch; set lookaheadNextRead=" + lookaheadNextRead +
        // " now max="
        // + lookahead.getMaxPos());
        lookahead.freeBefore(lookaheadNextRead)

        // System.out.println(" match; set lookaheadNextRead=" + lookaheadNextRead + "
        // now max=" +
        // lookahead.getMaxPos());
    }

    /**
     * Buffers the current input token into lookahead buffer.
     */
    private fun capture() {
        assert(liveToken)
        liveToken = false
        val token = lookahead.get(lookaheadNextWrite)
        lookaheadNextWrite++

        token.state = captureState()
        token.startOffset = offsetAtt.startOffset()
        token.endOffset = offsetAtt.endOffset()
        assert(token.term.length() == 0)
        token.term.append(termAtt)

        maxLookaheadUsed = max(maxLookaheadUsed, lookahead.getBufferSize())
        // System.out.println(" maxLookaheadUsed=" + maxLookaheadUsed);
    }

    @Throws(IOException::class)
    override fun reset() {
        super.reset()
        lookahead.reset()
        lookaheadNextWrite = 0
        lookaheadNextRead = 0
        finished = false
        liveToken = false
        outputBuffer.clear()
        maxLookaheadUsed = 0
        // System.out.println("S: reset");
    }

    companion object {
        const val TOKEN_TYPE: String = "ANNOTATION"
    }
}