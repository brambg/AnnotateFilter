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
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute
import org.apache.lucene.util.BytesRef

/**
 * Encodes a token's position length into its associated payload
 * Copied from [...](https://github.com/apache/lucene-solr/pull/772/)
 * 
 * @see PayloadLengthTermIntervalsSource
 */
class PayloadTokenLengthFilter
/**
 * Create a PayloadTokenLengthFilter
 */
    (input: TokenStream) : TokenFilter(input) {
    private val payloadAttribute: PayloadAttribute = addAttribute<PayloadAttribute>(PayloadAttribute::class.java)
    private val lengthAttribute: PositionLengthAttribute =
        addAttribute<PositionLengthAttribute>(PositionLengthAttribute::class.java)

    private val encodedLength = BytesRef(4)

    @Throws(IOException::class)
    override fun incrementToken(): Boolean {
        if (!input.incrementToken()) {
            return false
        }

        if (lengthAttribute.getPositionLength() > 1) {
            encodeLength(lengthAttribute.getPositionLength())
            println(lengthAttribute.getPositionLength())
            payloadAttribute.setPayload(encodedLength)
        }

        return true
    }

    private fun encodeLength(positionLength: Int) {
        var positionLength = positionLength
        val numBitsRequired = 32 - Integer.numberOfLeadingZeros(positionLength)
        val numBytesRequired = (numBitsRequired + 7) / 8
        encodedLength.length = numBytesRequired
        for (index in numBytesRequired - 1 downTo 0) {
            encodedLength.bytes[index] = positionLength.toByte()
            positionLength = positionLength ushr 8
        }
        assert(positionLength == 0)
    }
}