package io.github.willardzsw.texttack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 实时框选用的显示层：
 * - 区域外面一圈绿色细框（画在区域外，不会被截进区域里），本身不挡手指
 * - 框上方一个移动把手（三道横线），框右下角外一个缩放把手（三角）；松手回调 [onChanged]
 * - 译文显示在区域下方的横栏里（下方放不下就放上方），保留最近几句，旧的颜色淡一些
 */
class RealtimePanel(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private var layer: ScreenAbsoluteLayout? = null
    private var outline: View? = null
    private var mover: View? = null
    private var resizer: View? = null
    private var panel: TextView? = null
    private var region: Rect? = null
    private var lines: List<String> = emptyList()
    private var onChanged: ((Rect) -> Unit)? = null

    /** 全宽横带模式：移动把手只能上下移动，缩放把手在下边中间，只改高度。 */
    private var band = false

    fun show(region: Rect, band: Boolean, onChanged: (Rect) -> Unit) {
        dismiss()
        this.band = band
        this.region = Rect(region)
        this.onChanged = onChanged
        val container = ScreenAbsoluteLayout(context)
        layer = container
        val o = OutlineView(context)
        outline = o
        container.addView(o, ScreenAbsoluteLayout.Params(outlineRect(region)))
        windowManager.addView(container, fullScreenParams(touchable = false))

        // 两个把手各是一个可触摸的小窗口，其余部分不挡手指
        mover = GripView(context).also {
            it.setOnTouchListener(DragListener(resize = false))
            windowManager.addView(it, handleParams(dp(MOVER_W), dp(MOVER_H)))
        }
        resizer = (if (band) EdgeView(context) else CornerView(context)).also {
            it.setOnTouchListener(DragListener(resize = true))
            windowManager.addView(it, handleParams(dp(RESIZER), dp(RESIZER)))
        }
        layoutAll()
    }

    /** 更新译文（最近几句，最新的在最后）。 */
    fun setLines(lines: List<String>) {
        this.lines = lines
        layoutPanel()
    }

    fun dismiss() {
        layer?.let { runCatching { windowManager.removeView(it) } }
        mover?.let { runCatching { windowManager.removeView(it) } }
        resizer?.let { runCatching { windowManager.removeView(it) } }
        layer = null
        mover = null
        resizer = null
        outline = null
        panel = null
        region = null
        lines = emptyList()
    }

    // ---------- 布局 ----------

    private fun outlineRect(r: Rect) = Rect(r).apply { inset(-dp(4), -dp(4)) }

    /** 移动把手放在框上方；上面放不下就放下方。返回是否在下方。 */
    private fun moverRect(r: Rect): Pair<Rect, Boolean> {
        val w = dp(MOVER_W)
        val h = dp(MOVER_H)
        val left = (r.centerX() - w / 2).coerceIn(0, screen().width() - w)
        val above = r.top - dp(8) - h
        return if (above >= minTop()) Rect(left, above, left + w, above + h) to false
        else Rect(left, r.bottom + dp(8), left + w, r.bottom + dp(8) + h) to true
    }

    /** 缩放把手：贴在框右下角外侧；横带模式放在下边中间。 */
    private fun resizerRect(r: Rect): Rect {
        val s = dp(RESIZER)
        if (band) {
            val left = r.centerX() - s / 2
            val top = (r.bottom + dp(2)).coerceAtMost(screen().height() - s)
            return Rect(left, top, left + s, top + s)
        }
        val left = (r.right + dp(2)).coerceAtMost(screen().width() - s)
        val top = (r.bottom + dp(2)).coerceAtMost(screen().height() - s)
        return Rect(left, top, left + s, top + s)
    }

    private fun layoutAll() {
        val r = region ?: return
        outline?.layoutParams = ScreenAbsoluteLayout.Params(outlineRect(r))
        layer?.requestLayout()
        move(mover, moverRect(r).first)
        move(resizer, resizerRect(r))
        layoutPanel()
    }

    private fun move(view: View?, to: Rect) {
        val v = view ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = to.left
        lp.y = to.top
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun layoutPanel() {
        val container = layer ?: return
        val r = region ?: return
        panel?.let { container.removeView(it) }
        panel = null
        if (lines.isEmpty()) return

        val text = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) text.append('\n')
            val start = text.length
            text.append(line)
            if (i < lines.size - 1) {
                text.setSpan(ForegroundColorSpan(0xFF9E9E9E.toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val screen = screen()
        val width = maxOf(r.width(), (screen.width() * 0.6f).roundToInt()).coerceAtMost(screen.width() - dp(16))
        val view = TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 10
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
        val gap = dp(10)
        val moverBelow = moverRect(r).second
        // 框下方留出缩放把手（和移动把手在下方时）的位置
        val below = r.bottom + dp(RESIZER) + gap + (if (moverBelow) dp(MOVER_H) else 0)
        val aboveBottom = if (moverBelow) r.top - gap else r.top - dp(8) - dp(MOVER_H) - gap
        val left = (r.centerX() - width / 2).coerceIn(dp(8), screen.width() - width - dp(8))
        val top = when {
            below + h <= screen.bottom -> below
            aboveBottom - h >= 0 -> aboveBottom - h
            else -> screen.bottom - h - gap
        }
        container.addView(view, ScreenAbsoluteLayout.Params(Rect(left, top, left + width, top + h)))
        panel = view
    }

    private fun screen() = windowManager.maximumWindowMetrics.bounds
    private fun minTop() = dp(28)

    private fun handleParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
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

    /** 拖把手：移动时整框平移，缩放时改右下角；限制在屏幕内；松手回调新区域。 */
    private inner class DragListener(private val resize: Boolean) : View.OnTouchListener {
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
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    region = if (resize) {
                        val minSize = dp(MIN_REGION)
                        Rect(
                            start.left, start.top,
                            if (band) start.right else (start.right + dx).coerceIn(start.left + minSize, s.width()),
                            (start.bottom + dy).coerceIn(start.top + minSize, s.height()),
                        )
                    } else {
                        Rect(start).apply {
                            offset(
                                if (band) 0 else dx.coerceIn(-start.left, s.width() - start.right),
                                dy.coerceIn(minTop() - start.top, s.height() - start.bottom),
                            )
                        }
                    }
                    layoutAll()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    region?.let { onChanged?.invoke(Rect(it)) }
                }
            }
            return true
        }
    }

    /** 移动把手：绿色圆角条，上面三道白色横线。 */
    @SuppressLint("ViewConstructor")
    private class GripView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE666BB6A.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.5f * d
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, bg)
            val x0 = w * 0.32f
            val x1 = w * 0.68f
            for (i in -1..1) {
                val y = h / 2 + i * 3.5f * d
                canvas.drawLine(x0, y, x1, y, line)
            }
        }
    }

    /** 缩放把手：右下角的绿色三角；横带模式（[band]）由外部换成 [EdgeView]。 */
    @SuppressLint("ViewConstructor")
    private class CornerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE666BB6A.toInt() }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(w, h * 0.15f)
            path.lineTo(w, h)
            path.lineTo(w * 0.15f, h)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /** 横带模式的缩放把手：绿色圆点里一个上下箭头形状的短竖线。 */
    @SuppressLint("ViewConstructor")
    private class EdgeView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE666BB6A.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.5f * d
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawCircle(w / 2, h / 2, minOf(w, h) / 2, bg)
            val x0 = w * 0.3f
            val x1 = w * 0.7f
            canvas.drawLine(x0, h * 0.42f, x1, h * 0.42f, line)
            canvas.drawLine(x0, h * 0.58f, x1, h * 0.58f, line)
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
        const val MOVER_W = 44
        const val MOVER_H = 18
        const val RESIZER = 26
        const val MIN_REGION = 48
    }
}
