package com.airferry.sender.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import com.airferry.sender.encode.QrBuffer
import com.airferry.sender.encode.QrMatrix
import com.airferry.sender.nativelib.NativeBridge

/**
 * Fullscreen QR fountain player.
 *
 * Each Choreographer tick (optionally throttled to [fps]) pulls the next
 * packed matrices from JNI and blits them with nearest-neighbour scaling so
 * module edges stay sharp for the scanner camera.
 */
class QrPlayView(context: Context) : View(context), Choreographer.FrameCallback {

    @Volatile var handle: Long = 0L
    @Volatile var fps: Int = 60
    @Volatile var multiQr: Int = 4
    @Volatile var running: Boolean = false

    private val nearest = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private val dest = Rect()
    private var lastTickNs = 0L
    private var moduleBmp: Bitmap? = null
    private var pixelBuf: IntArray = IntArray(0)
    private var matrices: List<QrMatrix> = emptyList()

    fun start() {
        if (running) return
        running = true
        lastTickNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val targetFps = fps
        val due = if (targetFps <= 0) {
            true
        } else {
            val minDelta = 1_000_000_000L / targetFps.coerceAtLeast(1)
            lastTickNs == 0L || frameTimeNanos - lastTickNs >= minDelta
        }
        if (due && handle != 0L) {
            lastTickNs = frameTimeNanos
            try {
                val packed = NativeBridge.senderNextQr(handle, multiQr.coerceIn(1, 4))
                if (packed != null) {
                    val parsed = QrBuffer.parse(packed)
                    if (parsed.isNotEmpty()) {
                        matrices = parsed
                        postInvalidateOnAnimation()
                    }
                }
            } catch (_: Exception) {
                // Keep the last good frame on screen; ShareActivity polls stats.
            }
        }
        if (running) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val codes = matrices
        if (codes.isEmpty()) return
        val n = codes.size
        val cols = if (n >= 2) 2 else 1
        val rows = (n + cols - 1) / cols
        val wantMulti = n >= 2
        val margin = if (wantMulti) 2 else 4
        val side = codes[0].side
        val quiet = side + margin * 2
        val cell = minOf(width / cols, height / rows)
        val modulePx = maxOf(1, cell / quiet)
        val drawSize = modulePx * quiet
        val gridW = cols * drawSize
        val gridH = rows * drawSize
        val gridOx = (width - gridW) / 2
        val gridOy = (height - gridH) / 2

        for (i in codes.indices) {
            val bmp = raster(codes[i], margin, quiet)
            val c = i % cols
            val r = i / cols
            dest.set(
                gridOx + c * drawSize,
                gridOy + r * drawSize,
                gridOx + c * drawSize + drawSize,
                gridOy + r * drawSize + drawSize
            )
            canvas.drawBitmap(bmp, null, dest, nearest)
        }
    }

    private fun raster(matrix: QrMatrix, margin: Int, quiet: Int): Bitmap {
        val need = quiet * quiet
        if (pixelBuf.size != need) pixelBuf = IntArray(need)
        val px = pixelBuf
        px.fill(Color.WHITE)
        val side = matrix.side
        val modules = matrix.modules
        for (y in 0 until side) {
            val row = (y + margin) * quiet + margin
            val srcRow = y * side
            for (x in 0 until side) {
                if (modules[srcRow + x].toInt() != 0) {
                    px[row + x] = Color.BLACK
                }
            }
        }
        val existing = moduleBmp
        val bmp = if (existing == null || existing.width != quiet || existing.height != quiet) {
            existing?.recycle()
            Bitmap.createBitmap(quiet, quiet, Bitmap.Config.ARGB_8888).also { moduleBmp = it }
        } else {
            existing
        }
        bmp.setPixels(px, 0, quiet, 0, 0, quiet, quiet)
        return bmp
    }
}
