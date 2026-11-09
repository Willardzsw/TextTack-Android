package io.github.willardzsw.texttack

import android.content.Context
import android.graphics.Color
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
 * 全屏的结果显示层：在每段文字原来的位置画一个半透明框。
 * 显示期间拦截触摸，点任意位置关闭。
 */
class ResultOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private var layer: View? = null

    val isShowing: Boolean get() = layer != null

    fun show(blocks: List<TextBlock>) {
        dismiss()
        val container = ScreenAbsoluteLayout(context)
        for (block in blocks) {
            container.addView(makeLabel(block), ScreenAbsoluteLayout.Params(block.bounds))
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

    private fun makeLabel(block: TextBlock): TextView {
        val strokeColor = when (block.source) {
            TextBlock.Source.NODE_TEXT -> 0xFFFFC107.toInt()        // 黄
            TextBlock.Source.OCR -> 0xFF66BB6A.toInt()              // 绿
        }
        return TextView(context).apply {
            text = block.text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val pad = (2 * density).roundToInt()
            setPadding(pad, 0, pad, 0)
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(6, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            background = GradientDrawable().apply {
                setColor(0xB3000000.toInt())
                setStroke((1 * density).roundToInt().coerceAtLeast(1), strokeColor)
                cornerRadius = 2 * density
            }
        }
    }
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
