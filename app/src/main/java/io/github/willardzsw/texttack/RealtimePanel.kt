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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 实时框选用的显示层：
 * - 区域外面一圈绿色细框（画在区域外，不会被截进区域里），本身不挡手指
 * - 框外一个"拖动"把手：按住拖，框和译文栏一起移动，松手回调 [onMoved]
 * - 译文显示在区域下方的横栏里（下方放不下就放上方），不盖在原文上，免得下一次截图截到自己
 */
class RealtimePanel(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private var layer: ScreenAbsoluteLayout? = null
    private var outline: View? = null
    private var handle: TextView? = null
    private var panel: TextView? = null
    private var region: Rect? = null
    private var text: String = ""
    private var onMoved: ((Rect) -> Unit)? = null

    fun show(region: Rect, onMoved: (Rect) -> Unit) {
        dismiss()
        this.region = Rect(region)
        this.onMoved = onMoved
        val container = ScreenAbsoluteLayout(context)
        layer = container
        val o = OutlineView(context)
        outline = o
        container.addView(o, ScreenAbsoluteLayout.Params(outlineRect(region)))
        windowManager.addView(container, fullScreenParams(touchable = false))

        // 把手单独一个可触摸的小窗口，其余部分不挡手指
        val h = TextView(context).apply {
            text = "⇕ 拖动"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xE666BB6A.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        h.setOnTouchListener(DragListener())
        handle = h
        val hp = WindowManager.LayoutParams(
            dp(HANDLE_W), dp(HANDLE_H),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        windowManager.addView(h, hp)
        layoutAll()
    }

    /** 更新译文；空字符串表示清空。 */
    fun setText(text: String) {
        this.text = text
        layoutPanel()
    }

    fun dismiss() {
        layer?.let { runCatching { windowManager.removeView(it) } }
        handle?.let { runCatching { windowManager.removeView(it) } }
        layer = null
        handle = null
        outline = null
        panel = null
        region = null
        text = ""
    }

    // ---------- 布局 ----------

    private fun outlineRect(r: Rect) = Rect(r).apply { inset(-dp(4), -dp(4)) }

    /** 把手放在框上方；上面放不下就放下方。返回是否在下方。 */
    private fun handleRect(r: Rect): Pair<Rect, Boolean> {
        val w = dp(HANDLE_W)
        val h = dp(HANDLE_H)
        val left = (r.centerX() - w / 2).coerceIn(0, screen().width() - w)
        val above = r.top - dp(6) - h
        return if (above >= statusTop()) Rect(left, above, left + w, above + h) to false
        else Rect(left, r.bottom + dp(6), left + w, r.bottom + dp(6) + h) to true
    }

    private fun layoutAll() {
        val r = region ?: return
        outline?.layoutParams = ScreenAbsoluteLayout.Params(outlineRect(r))
        layer?.requestLayout()
        handle?.let { h ->
            val (hr, _) = handleRect(r)
            val lp = h.layoutParams as WindowManager.LayoutParams
            lp.x = hr.left
            lp.y = hr.top
            runCatching { windowManager.updateViewLayout(h, lp) }
        }
        layoutPanel()
    }

    private fun layoutPanel() {
        val container = layer ?: return
        val r = region ?: return
        panel?.let { container.removeView(it) }
        panel = null
        if (text.isBlank()) return

        val screen = screen()
        val width = maxOf(r.width(), (screen.width() * 0.6f).roundToInt()).coerceAtMost(screen.width() - dp(16))
        val view = TextView(context).apply {
            this.text = this@RealtimePanel.text
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
        // 把手在下方时，译文栏再往下让开
        val below = r.bottom + gap + (if (handleRect(r).second) dp(HANDLE_H) + dp(6) else 0)
        val left = (r.centerX() - width / 2).coerceIn(dp(8), screen.width() - width - dp(8))
        val aboveBottom = if (handleRect(r).second) r.top - gap else r.top - dp(6) - dp(HANDLE_H) - gap
        val top = when {
            below + h <= screen.bottom -> below
            aboveBottom - h >= 0 -> aboveBottom - h
            else -> screen.bottom - h - gap
        }
        container.addView(view, ScreenAbsoluteLayout.Params(Rect(left, top, left + width, top + h)))
        panel = view
    }

    private fun screen() = windowManager.maximumWindowMetrics.bounds
    private fun statusTop() = dp(28)

    private fun fullScreenParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    /** 拖把手：框跟着手指平移，限制在屏幕内；松手回调新区域。 */
    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var start = Rect()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val r = region ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    start = Rect(r)
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen()
                    val dx = (e.rawX - downX).roundToInt().coerceIn(-start.left, s.width() - start.right)
                    val dy = (e.rawY - downY).roundToInt().coerceIn(statusTop() - start.top, s.height() - start.bottom)
                    region = Rect(start).apply { offset(dx, dy) }
                    layoutAll()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    region?.let { onMoved?.invoke(Rect(it)) }
                }
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private class OutlineView(context: Context) : View(context) {
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

    private companion object {
        const val HANDLE_W = 76
        const val HANDLE_H = 28
    }
}
