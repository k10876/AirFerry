package com.airferry.sender.encode

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * ETBUNDL1 encoder — byte-for-byte with `apps/sender/src/wasm/bundle.ts`
 * and the scanner `BundleParser`.
 *
 * All integers big-endian. Used only when ≥2 items are shared.
 */
object BundleWriter {
    val MAGIC: ByteArray = byteArrayOf(
        'E'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'U'.code.toByte(),
        'N'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()
    )

    const val VERSION = 1
    const val MAX_FILES = 4096
    const val MAX_NAME_BYTES = 0xffff

    data class Entry(val name: String, val data: ByteArray)

    fun isBundle(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }

    fun build(entries: List<Entry>): ByteArray {
        require(entries.isNotEmpty()) { "buildBundle: no files" }
        require(entries.size <= MAX_FILES) {
            "一次最多发送 $MAX_FILES 个文件，请分批发送"
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC)
            data.writeShort(VERSION)
            data.writeShort(entries.size)
            for (entry in entries) {
                val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                require(nameBytes.size <= MAX_NAME_BYTES) {
                    "文件名 UTF-8 编码超过 $MAX_NAME_BYTES 字节: ${entry.name}"
                }
                data.writeShort(nameBytes.size)
                data.write(nameBytes)
                data.writeLong(entry.data.size.toLong())
                data.write(entry.data)
            }
        }
        return out.toByteArray()
    }
}
