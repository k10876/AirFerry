package com.airferry.sender.nativelib

/**
 * JNI bridge to `libtransfer_engine.so` for the Android Share sender.
 *
 * Symbols are `Java_com_airferry_sender_nativelib_NativeBridge_*` in
 * `core/transfer-engine/src/jni.rs`. The handle is a raw pointer stored as
 * Long and is **not** thread-safe — the host must serialize all calls on
 * the same handle (the play loop is a single Choreographer callback).
 */
object NativeBridge {
    init {
        System.loadLibrary("transfer_engine")
    }

    const val NATIVE_ABI_VERSION = 1

    external fun nativeAbiVersion(): Int

    /** `SEGMENT_RAW_BYTES` = MAX_OBJECT_BYTES − MAX_SYMBOL_SIZE. */
    external fun segmentRawBytes(): Long

    /** Bytes that always hold 4× Version-40 matrices. */
    external fun qrScratchBytes(): Int

    /** FNV-1a 64 over head||tail; 8 little-endian bytes. */
    external fun contentFingerprint(head: ByteArray, tail: ByteArray): ByteArray

    /** FNV-1a 128; returns `longArrayOf(lo, hi)` (unsigned bits). */
    external fun deriveSessionId(
        name: String,
        size: Long,
        mtimeMs: Long,
        fingerprint: ByteArray
    ): LongArray

    external fun sha256(data: ByteArray): ByteArray

    /** CRC32 as an unsigned 32-bit value in a Long. */
    external fun crc32(data: ByteArray): Long

    /**
     * Packed: `[u8 algorithm][u32le crc32 of original][compressed bytes]`.
     * Throws [IllegalArgumentException] on empty input / compressor failure.
     */
    external fun compressPrepare(raw: ByteArray): ByteArray

    external fun senderCreate(
        compressedPayload: ByteArray,
        sessionIdLo: Long,
        sessionIdHi: Long,
        redundancyPct: Int,
        symbolSize: Int,
        filename: String,
        originalFileSize: Long,
        crc32: Long,
        compression: Int
    ): Long

    external fun senderCreateSegment(
        compressedPayload: ByteArray,
        rootSessionIdLo: Long,
        rootSessionIdHi: Long,
        segmentIndex: Int,
        segmentCount: Int,
        originalOffset: Long,
        rootOriginalSize: Long,
        rootSha256: ByteArray,
        rawSha256: ByteArray,
        redundancyPct: Int,
        symbolSize: Int,
        filename: String,
        originalSize: Long,
        crc32: Long,
        compression: Int
    ): Long

    external fun senderDestroy(handle: Long)

    /**
     * Next `count` QR matrices, packed little-endian:
     * `[u32 count][for each: u32 side + side*side module bytes]`.
     */
    external fun senderNextQr(handle: Long, count: Int): ByteArray?

    external fun senderTotalSymbols(handle: Long): Int
    external fun senderIsSegmented(handle: Long): Int
    external fun senderStatsJson(handle: Long): ByteArray?
}
