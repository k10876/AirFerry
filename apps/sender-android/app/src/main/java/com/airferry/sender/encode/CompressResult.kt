package com.airferry.sender.encode

/** Parsed JNI `compressPrepare` buffer. */
data class CompressResult(
    val algorithm: Int,
    val crc32: Long,
    val compressed: ByteArray
) {
    companion object {
        fun parse(packed: ByteArray): CompressResult {
            require(packed.size >= 5) { "compressPrepare result too short (${packed.size})" }
            val algorithm = packed[0].toInt() and 0xff
            val crc32 = (
                (packed[1].toLong() and 0xff) or
                    ((packed[2].toLong() and 0xff) shl 8) or
                    ((packed[3].toLong() and 0xff) shl 16) or
                    ((packed[4].toLong() and 0xff) shl 24)
                ) and 0xffff_ffffL
            return CompressResult(
                algorithm = algorithm,
                crc32 = crc32,
                compressed = packed.copyOfRange(5, packed.size)
            )
        }
    }
}
