package com.quang.dichphim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Mắt" của app: chụp màn hình cỡ nhỏ (cạnh dài ~768 px) để gửi kèm âm thanh,
 * giúp AI thấy ai đang nói và bối cảnh phim.
 */
class Vision(private val ctx: Context, private val projection: MediaProjection) {

    private val lock = Any()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastJpeg: ByteArray? = null
    @Volatile var blackFrames = 0
        private set

    fun start(): Boolean = synchronized(lock) {
        return try {
            val (w, h, dpi) = size()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = projection.createVirtualDisplay(
                "dichphim-vision", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Gọi khi xoay màn hình (dọc <-> ngang) để khung hình không bị méo/nhỏ. */
    fun onRotate() = synchronized(lock) {
        val d = display ?: return@synchronized
        try {
            val (w, h, dpi) = size()
            val old = reader
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            d.resize(w, h, dpi)
            d.surface = r.surface
            reader = r
            lastJpeg = null
            old?.close()
        } catch (_: Exception) { }
    }

    private fun size(): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val (rw, rh) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = ctx.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
        val scale = 768f / max(rw, rh)
        val w = ((rw * scale).roundToInt() / 2) * 2
        val h = ((rh * scale).roundToInt() / 2) * 2
        val dpi = (ctx.resources.displayMetrics.densityDpi * scale).roundToInt().coerceAtLeast(80)
        return Triple(w, h, dpi)
    }

    /** Lấy khung hình mới nhất dạng JPEG; null nếu không có hình (web chặn quay => màn đen). */
    fun grab(): ByteArray? = synchronized(lock) {
        val r = reader ?: return null
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null }
            ?: return lastJpeg  // màn hình đứng yên -> dùng lại khung cũ
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bw = rowStride / pixelStride
            val full = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (bw != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full

            if (isBlack(bmp)) {
                blackFrames++
                lastJpeg = null
                return null
            }
            blackFrames = 0
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (bmp !== full) bmp.recycle()
            full.recycle()
            lastJpeg = out.toByteArray()
            lastJpeg
        } catch (_: Exception) {
            null
        } finally {
            img.close()
        }
    }

    private fun isBlack(b: Bitmap): Boolean {
        var bright = 0
        val stepX = max(1, b.width / 12)
        val stepY = max(1, b.height / 12)
        var y = stepY / 2
        while (y < b.height) {
            var x = stepX / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                val l = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (l > 60) bright++
                x += stepX
            }
            y += stepY
        }
        return bright < 4
    }

    fun stop() = synchronized(lock) {
        try { display?.release() } catch (_: Exception) { }
        display = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        lastJpeg = null
    }
}
