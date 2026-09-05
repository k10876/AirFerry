package com.airferry.sender.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CompressResultTest {
    @Test
    fun parsesLittleEndianHeader() {
        val packed = byteArrayOf(
            1,
            0x78, 0x56, 0x34, 0x12,
            9, 8, 7
        )
        val parsed = CompressResult.parse(packed)
        assertEquals(1, parsed.algorithm)
        assertEquals(0x12345678L, parsed.crc32)
        assertArrayEquals(byteArrayOf(9, 8, 7), parsed.compressed)
    }
}
