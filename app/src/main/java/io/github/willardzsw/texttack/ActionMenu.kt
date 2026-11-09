package io.github.willardzsw.texttack

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 长按悬浮按钮弹出的小菜单：在按钮旁边排一列圆角按钮。点菜单外任意处收起。
 */
class ActionMenu(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    class Item(val label: String, val onClick: () -> Unit)

    private val density = context.resources.displayMetrics.density
    private var layer: ScreenAbsoluteLayout? = null

    val isShowing: Boolean get() = layer != null

    fun show(anchor: Rect, items: List<Item>) {
        dismiss()
        val screen = windowManager.maximumWindowMetrics.bounds
        val w = dp(84)
        val h = dp(44)
        val gap = dp(8)
        val onRight = anchor.centerX() > screen.width() / 2
        val left = if (onRight) anchor.left - gap - w else anchor.right + gap
        val total = items.size * h + (items.size - 1) * gap
        val top = (anchor.centerY() - total / 2).coerceIn(dp(8), (screen.height() - total - dp(8)).coerceAtLeast(dp(8)))

        val container = ScreenAbsoluteLayout(context)
        items.forEachIndexed { i, item ->
            val y = top + i * (h + gap)
            val button = TextView(context).apply {
                text = item.label
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xE61E6FD9.toInt())
                    cornerRadius = h / 2f
                }
                elevation = dp(4).toFloat()
                setOnClickListener {
                    dismiss()
                    item.onClick()
                }
            }
            container.addView(button, ScreenAbsoluteLayout.Params(Rect(left, y, left + w, y + h)))
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

    private fun dp(v: Int) = (v * density).roundToInt()
}
