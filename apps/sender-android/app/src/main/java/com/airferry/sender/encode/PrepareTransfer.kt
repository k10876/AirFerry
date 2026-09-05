package com.airferry.sender.encode

import com.airferry.sender.nativelib.NativeBridge
import com.airferry.sender.share.StagedItem
import kotlin.math.min

data class SegmentPlan(
    val payload: ByteArray,
    val index: Int,
    val count: Int,
    val offset: Long,
    val rawSha256: ByteArray,
    val rootSha256: ByteArray
)

data class TransferPlan(
    val displayName: String,
    val originalSize: Long,
    val compressedSize: Long,
    val algorithm: Int,
    val crc32: Long,
    val sessionIdLo: Long,
    val sessionIdHi: Long,
    val isText: Boolean,
    val isBundle: Boolean,
    val segments: List<SegmentPlan>
) {
    val segmented: Boolean get() = segments.size > 1
}

private const val FINGERPRINT_SLICE = 1024

/**
 * Build the on-wire payload (ETTEXTv1 / raw file / ETBUNDL1), compress via
 * JNI, and split into descriptor-v5 segments when needed.
 *
 * Must run off the main thread: zstd/xz on a few MiB can take seconds.
 */
object PrepareTransfer {
    fun run(items: List<StagedItem>): TransferPlan {
        require(items.isNotEmpty()) { "没有可发送的内容" }
        val isText = items.size == 1 && items[0].isText
        val isBundle = items.size >= 2
        val raw: ByteArray
        val displayName: String
        val sessionName: String
        val mtime: Long
        val sizeForId: Long
        if (isText) {
            val text = items[0].file.readText(Charsets.UTF_8)
            raw = TextPayload.wrap(text)
            displayName = Filenames.normalizeTxt(items[0].displayName)
            sessionName = displayName
            mtime = System.currentTimeMillis()
            sizeForId = raw.size.toLong()
        } else if (isBundle) {
            val entries = items.map { item ->
                val name = if (item.isText) {
                    Filenames.normalizeTxt(item.displayName)
                } else {
                    Filenames.sanitize(item.displayName)
                }
                BundleWriter.Entry(name, item.file.readBytes())
            }
            raw = BundleWriter.build(entries)
            displayName = "${items.size}个文件打包"
            sessionName = items.joinToString("\u0001") { it.displayName }
            mtime = items.maxOf { it.lastModifiedMs }
            sizeForId = raw.size.toLong()
        } else {
            val item = items[0]
            raw = item.file.readBytes()
            if (raw.isEmpty()) throw IllegalArgumentException("暂不支持发送空文件（0 B）")
            displayName = Filenames.sanitize(item.displayName)
            sessionName = displayName
            mtime = item.lastModifiedMs
            sizeForId = raw.size.toLong()
        }

        val fp = fingerprint(raw)
        val sid = NativeBridge.deriveSessionId(sessionName, sizeForId, mtime, fp)
        require(sid.size == 2) { "deriveSessionId 返回异常" }
        val packed = NativeBridge.compressPrepare(raw)
        val prepared = CompressResult.parse(packed)
        val originalSize = raw.size.toLong()
        val segmentRaw = NativeBridge.segmentRawBytes()
        val count = segmentCountFor(prepared.compressed.size.toLong(), segmentRaw)
        val needsSeg = count > 1
        val rootSha = NativeBridge.sha256(raw)
        val segments = if (!needsSeg) {
            listOf(
                SegmentPlan(
                    payload = prepared.compressed,
                    index = 0,
                    count = 1,
                    offset = 0L,
                    rawSha256 = NativeBridge.sha256(prepared.compressed),
                    rootSha256 = rootSha
                )
            )
        } else {
            val n = count.coerceAtLeast(1)
            (0 until n).map { i ->
                val off = i * segmentRaw
                val end = min(prepared.compressed.size.toLong(), off + segmentRaw).toInt()
                val slice = prepared.compressed.copyOfRange(off.toInt(), end)
                SegmentPlan(
                    payload = slice,
                    index = i,
                    count = n,
                    offset = off,
                    rawSha256 = NativeBridge.sha256(slice),
                    rootSha256 = rootSha
                )
            }
        }
        return TransferPlan(
            displayName = displayName,
            originalSize = originalSize,
            compressedSize = prepared.compressed.size.toLong(),
            algorithm = prepared.algorithm,
            crc32 = prepared.crc32,
            sessionIdLo = sid[0],
            sessionIdHi = sid[1],
            isText = isText,
            isBundle = isBundle,
            segments = segments
        )
    }

    fun createHandle(plan: TransferPlan, index: Int, params: TransferParams): Long {
        val seg = plan.segments[index]
        return if (!plan.segmented) {
            NativeBridge.senderCreate(
                compressedPayload = seg.payload,
                sessionIdLo = plan.sessionIdLo,
                sessionIdHi = plan.sessionIdHi,
                redundancyPct = params.redundancyPct,
                symbolSize = params.symbolSize,
                filename = plan.displayName,
                originalFileSize = plan.originalSize,
                crc32 = plan.crc32,
                compression = plan.algorithm
            )
        } else {
            NativeBridge.senderCreateSegment(
                compressedPayload = seg.payload,
                rootSessionIdLo = plan.sessionIdLo,
                rootSessionIdHi = plan.sessionIdHi,
                segmentIndex = seg.index,
                segmentCount = seg.count,
                originalOffset = seg.offset,
                rootOriginalSize = plan.compressedSize,
                rootSha256 = seg.rootSha256,
                rawSha256 = seg.rawSha256,
                redundancyPct = params.redundancyPct,
                symbolSize = params.symbolSize,
                filename = plan.displayName,
                originalSize = plan.originalSize,
                crc32 = plan.crc32,
                compression = plan.algorithm
            )
        }
    }

    private fun fingerprint(raw: ByteArray): ByteArray {
        val head = raw.copyOfRange(0, min(FINGERPRINT_SLICE, raw.size))
        val tailStart = (raw.size - FINGERPRINT_SLICE).coerceAtLeast(0)
        val tail = raw.copyOfRange(tailStart, raw.size)
        return NativeBridge.contentFingerprint(head, tail)
    }

    private fun segmentCountFor(compressedSize: Long, segmentRaw: Long): Int {
        if (compressedSize <= 0L) return 1
        val n = ((compressedSize + segmentRaw - 1) / segmentRaw).toInt().coerceAtLeast(1)
        require(n <= 131_072) { "分段数量 $n 超过上限" }
        return n
    }
}
