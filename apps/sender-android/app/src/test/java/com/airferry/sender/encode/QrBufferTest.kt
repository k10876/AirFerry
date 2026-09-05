package com.airferry.sender.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrBufferTest {
    @Test
    fun roundtripsOneAndFourMatrices() {
        val one = QrMatrix(3, byteArrayOf(1, 0, 1, 0, 1, 0, 1, 0, 1))
        val packed = QrBuffer.pack(listOf(one))
        val parsed = QrBuffer.parse(packed)
        assertEquals(1, parsed.size)
        assertEquals(3, parsed[0].side)
        assertTrue(one.modules.contentEquals(parsed[0].modules))

        val four = List(4) { i ->
            QrMatrix(2, byteArrayOf(i.toByte(), 0, 1, 0))
        }
        val packed4 = QrBuffer.pack(four)
        val parsed4 = QrBuffer.parse(packed4)
        assertEquals(4, parsed4.size)
        assertEquals(2, parsed4[3].side)
        assertEquals(3.toByte(), parsed4[3].modules[0])
    }

    @Test
    fun truncatedBufferYieldsEmpty() {
        assertTrue(QrBuffer.parse(byteArrayOf(1, 0)).isEmpty())
        val packed = QrBuffer.pack(listOf(QrMatrix(2, byteArrayOf(1, 1, 1, 1))))
        assertTrue(QrBuffer.parse(packed.copyOf(packed.size - 1)).isEmpty())
    }
}
