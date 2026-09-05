package com.airferry.sender.encode

/** One QR matrix: `modules[y * side + x]` is 1 = dark, 0 = light. */
data class QrMatrix(val side: Int, val modules: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return side == other.side && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int = 31 * side + modules.contentHashCode()
}

/**
 * Parser for the packed QR buffer shared by WASM `next_qr_scratch` and
 * JNI `senderNextQr`:
 *
 * `[u32le count][for each matrix: u32le side + side*side bytes]`
 */
object QrBuffer {
    fun parse(buf: ByteArray): List<QrMatrix> {
        if (buf.size < 4) return emptyList()
        val count = le32(buf, 0)
        if (count <= 0 || count > 8) return emptyList()
        var pos = 4
        val out = ArrayList<QrMatrix>(count)
        repeat(count) {
            if (pos + 4 > buf.size) return emptyList()
            val side = le32(buf, pos)
            pos += 4
            if (side <= 0 || side > 177) return emptyList()
            val n = side * side
            if (pos + n > buf.size) return emptyList()
            out.add(QrMatrix(side, buf.copyOfRange(pos, pos + n)))
            pos += n
        }
        return out
    }

    fun pack(matrices: List<QrMatrix>): ByteArray {
        val size = 4 + matrices.sumOf { 4 + it.modules.size }
        val out = ByteArray(size)
        writeLe32(out, 0, matrices.size)
        var pos = 4
        for (m in matrices) {
            writeLe32(out, pos, m.side)
            pos += 4
            m.modules.copyInto(out, pos)
            pos += m.modules.size
        }
        return out
    }

    private fun le32(b: ByteArray, i: Int): Int {
        return (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            ((b[i + 3].toInt() and 0xff) shl 24)
    }

    private fun writeLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xff).toByte()
        b[i + 1] = ((v ushr 8) and 0xff).toByte()
        b[i + 2] = ((v ushr 16) and 0xff).toByte()
        b[i + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
