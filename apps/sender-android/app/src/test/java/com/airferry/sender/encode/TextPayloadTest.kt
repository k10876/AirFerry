package com.airferry.sender.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPayloadTest {
    @Test
    fun wrapsUtf8AfterEightByteMagic() {
        val wrapped = TextPayload.wrap("你好 AirFerry")
        assertTrue(TextPayload.isText(wrapped))
        assertEquals("ETTEXTv1", wrapped.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals("你好 AirFerry", wrapped.copyOfRange(8, wrapped.size).toString(Charsets.UTF_8))
    }

    @Test
    fun rejectsNonMagic() {
        assertFalse(TextPayload.isText("hello".toByteArray()))
        assertFalse(TextPayload.isText(ByteArray(4)))
    }

    @Test
    fun magicMatchesTsLiteral() {
        assertArrayEquals("ETTEXTv1".toByteArray(Charsets.US_ASCII), TextPayload.MAGIC)
    }
}
