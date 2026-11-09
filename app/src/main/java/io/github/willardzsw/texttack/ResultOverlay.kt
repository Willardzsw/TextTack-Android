package io.github.willardzsw.texttack

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 全屏的译文层：在每段原文的位置盖一块浅色底，写上译文。
 * 显示期间拦截触摸，点任意位置关闭。
 */
class ResultOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private var layer: View? = null

    val isShowing: Boolean get() = layer != null

    /** 在每个 [Shown.bounds] 位置盖一块底色，写上 [Shown.text]（译文）。 */
    data class Shown(val bounds: Rect, val text: String)

    fun show(items: List<Shown>) {
        dismiss()
        val container = ScreenAbsoluteLayout(context)
        for (item in items) {
            container.addView(makeLabel(item), ScreenAbsoluteLayout.Params(item.bounds))
        }
        container.setOnClickListener { dismiss() }

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
        windowManager.addView(container, params)
        layer = container
    }

    fun dismiss() {
        layer?.let { runCatching { windowManager.removeView(it) } }
        layer = null
    }

    private fun makeLabel(item: Shown): TextView {
        // 字号上限按原文框高度估：单行时大约是框高的 0.75
        val maxSp = (item.bounds.height() * 0.75f / scaledDensity()).roundToInt().coerceIn(8, 28)
        return TextView(context).apply {
            text = item.text
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val pad = (2 * density).roundToInt()
            setPadding(pad, 0, pad, 0)
            includeFontPadding = false
            setLineSpacing(0f, 0.95f)
            setAutoSizeTextTypeUniformWithConfiguration(6, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
            background = GradientDrawable().apply {
                setColor(0xF7FFFFFF.toInt())
                cornerRadius = 3 * density
            }
            elevation = 1 * density
        }
    }

    private fun scaledDensity(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)
}

/** 按屏幕绝对坐标摆放子 View 的布局：自动减去本窗口在屏幕上的偏移。 */
private class ScreenAbsoluteLayout(context: Context) : ViewGroup(context) {

    class Params(val screenRect: Rect) : ViewGroup.LayoutParams(screenRect.width(), screenRect.height())

    private val location = IntArray(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = (child.layoutParams as Params).screenRect
            child.measure(
                MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getLocationOnScreen(location)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = (child.layoutParams as Params).screenRect
            val left = rect.left - location[0]
            val top = rect.top - location[1]
            child.layout(left, top, left + rect.width(), top + rect.height())
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is Params
}
