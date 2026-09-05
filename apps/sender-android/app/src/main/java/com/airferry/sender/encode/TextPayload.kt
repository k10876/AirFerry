package com.airferry.sender.encode

/**
 * ETTEXTv1 wrapper — byte-for-byte with `apps/sender/src/wasm/text.ts`.
 *
 * `[ 8 magic "ETTEXTv1" ][ UTF-8 text ]`
 */
object TextPayload {
    val MAGIC: ByteArray = byteArrayOf(
        'E'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(),
        'X'.code.toByte(), 'T'.code.toByte(), 'v'.code.toByte(), '1'.code.toByte()
    )

    const val DEFAULT_NAME = "文字消息.txt"

    fun wrap(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return MAGIC + body
    }

    fun isText(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }
}
