package com.quang.dichphim

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/** Dòng phụ đề nổi trên mọi ứng dụng (kể cả Chrome toàn màn hình). Kéo lên/xuống để đổi chỗ. */
class Overlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var added = false

    private val tv = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
        maxLines = 4
        val pad = (12 * ctx.resources.displayMetrics.density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = 18f * ctx.resources.displayMetrics.density
        }
        visibility = View.GONE
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (70 * ctx.resources.displayMetrics.density).toInt()
    }

    private val hide = Runnable { tv.visibility = View.GONE }

    init {
        setupDrag()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var downY = 0f
        var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > 4) {
                        lp.y = (startY - dy).toInt().coerceAtLeast(0)
                        if (added) wm.updateViewLayout(tv, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawY - downY) < 10) { // chạm nhẹ = ẩn
                        ui.removeCallbacks(hide); tv.visibility = View.GONE
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun attach(): Boolean {
        if (added) return true
        return try {
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()
            wm.addView(tv, lp)
            added = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun show(text: String, fontSp: Float) {
        ui.post {
            if (!attach()) return@post
            tv.textSize = fontSp
            tv.text = text
            tv.visibility = View.VISIBLE
            ui.removeCallbacks(hide)
            ui.postDelayed(hide, (3500L + text.length * 90L).coerceAtMost(12000L))
        }
    }

    fun remove() {
        ui.post {
            ui.removeCallbacks(hide)
            if (added) {
                try { wm.removeView(tv) } catch (_: Exception) { }
                added = false
            }
        }
    }
}
