package com.airferry.sender.encode

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleWriterTest {
    @Test
    fun writesBigEndianBundleMatchingScannerParser() {
        val payload = BundleWriter.build(
            listOf(
                BundleWriter.Entry("报告 2026.txt", "hello".toByteArray()),
                BundleWriter.Entry("空.dat", byteArrayOf())
            )
        )
        assertTrue(BundleWriter.isBundle(payload))
        val parsed = parse(payload)
        assertEquals(listOf("报告 2026.txt", "空.dat"), parsed.map { it.first })
        assertArrayEquals("hello".toByteArray(), parsed[0].second)
        assertArrayEquals(byteArrayOf(), parsed[1].second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyList() {
        BundleWriter.build(emptyList())
    }

    private fun parse(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(8)
        input.readFully(magic)
        assertArrayEquals(BundleWriter.MAGIC, magic)
        assertEquals(1, input.readUnsignedShort())
        val count = input.readUnsignedShort()
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            val nameLen = input.readUnsignedShort()
            val nameBytes = ByteArray(nameLen)
            input.readFully(nameBytes)
            val size = input.readLong()
            val data = ByteArray(size.toInt())
            input.readFully(data)
            out += String(nameBytes, Charsets.UTF_8) to data
        }
        return out
    }
}
