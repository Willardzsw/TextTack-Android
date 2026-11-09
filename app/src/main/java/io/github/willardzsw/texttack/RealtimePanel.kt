package io.github.willardzsw.texttack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 截图实时翻译用的显示层（不挡手指）：
 * - 在框选区域外面画一圈细框，提示正在盯着哪里（画在区域外，不会被截进区域里）
 * - 译文显示在区域下方的横栏里（下方放不下就放上方），不盖在原文上，免得下一次截图截到自己
 */
class RealtimePanel(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private var layer: ScreenAbsoluteLayout? = null
    private var region: Rect? = null
    private var panel: TextView? = null

    fun show(region: Rect) {
        dismiss()
        this.region = Rect(region)
        val container = ScreenAbsoluteLayout(context)
        val outline = Rect(region).apply { inset(-dp(4), -dp(4)) }
        container.addView(OutlineView(context, dp(4)), ScreenAbsoluteLayout.Params(outline))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        windowManager.addView(container, params)
        layer = container
    }

    /** 更新译文；空字符串表示清空。 */
    fun setText(text: String) {
        val container = layer ?: return
        val r = region ?: return
        panel?.let { container.removeView(it) }
        panel = null
        if (text.isBlank()) return

        val screen = windowManager.maximumWindowMetrics.bounds
        val width = maxOf(r.width(), (screen.width() * 0.6f).roundToInt()).coerceAtMost(screen.width() - dp(16))
        val view = TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 8
            background = GradientDrawable().apply {
                setColor(0xE0202020.toInt())
                cornerRadius = 6 * density
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((screen.height() * 0.35f).roundToInt(), View.MeasureSpec.AT_MOST),
        )
        val h = view.measuredHeight
        val gap = dp(8)
        val left = (r.centerX() - width / 2).coerceIn(dp(8), screen.width() - width - dp(8))
        val top = when {
            r.bottom + gap + h <= screen.bottom -> r.bottom + gap
            r.top - gap - h >= 0 -> r.top - gap - h
            else -> screen.bottom - h - gap
        }
        container.addView(view, ScreenAbsoluteLayout.Params(Rect(left, top, left + width, top + h)))
        panel = view
    }

    fun dismiss() {
        layer?.let { runCatching { windowManager.removeView(it) } }
        layer = null
        panel = null
        region = null
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    @SuppressLint("ViewConstructor")
    private class OutlineView(context: Context, private val inset: Int) : View(context) {
        private val paint = Paint().apply {
            color = 0xCC66BB6A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * context.resources.displayMetrics.density
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            val half = paint.strokeWidth / 2
            canvas.drawRect(half, half, width - half, height - half, paint)
        }
    }
}
