package io.github.willardzsw.texttack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 框选层：全屏变暗，手指拖出一个框，松手后回调框（屏幕坐标）。
 * 只点一下不拖（或框太小）= 取消。
 */
class RegionSelector(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var view: SelectView? = null
    private val minSize = (24 * context.resources.displayMetrics.density).roundToInt()

    val isShowing: Boolean get() = view != null

    fun show(onSelected: (Rect) -> Unit, onCancel: () -> Unit) {
        dismiss()
        val v = SelectView(context) { rect ->
            dismiss()
            if (rect != null && rect.width() >= minSize && rect.height() >= minSize) onSelected(rect) else onCancel()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        windowManager.addView(v, params)
        view = v
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class SelectView(context: Context, private val done: (Rect?) -> Unit) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val dim = Paint().apply { color = 0x66000000 }
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val border = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            isAntiAlias = true
        }
        private var startX = 0f
        private var startY = 0f
        private var rect: Rect? = null
        private val location = IntArray(2)

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val r = rect ?: return
            getLocationOnScreen(location)
            val local = Rect(r).apply { offset(-location[0], -location[1]) }
            canvas.drawRect(local, clear)
            canvas.drawRect(local, border)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    rect = null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - startX) > 4 || abs(event.rawY - startY) > 4) {
                        rect = Rect(
                            min(startX, event.rawX).toInt(), min(startY, event.rawY).toInt(),
                            max(startX, event.rawX).toInt(), max(startY, event.rawY).toInt(),
                        )
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> done(rect)
                MotionEvent.ACTION_CANCEL -> done(null)
            }
            return true
        }
    }
}
