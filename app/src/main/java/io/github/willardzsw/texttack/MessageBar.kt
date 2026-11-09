package io.github.willardzsw.texttack

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 屏幕下方的提示条，用无障碍悬浮层显示，几秒后自动消失。
 * 代替系统 Toast：应用通知被关闭时（荣耀等系统默认如此），系统会屏蔽 Toast。
 * 不拦截触摸。
 */
class MessageBar(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    fun show(message: String, durationMs: Long = 4000) {
        // 每次重新添加，保证提示在其他悬浮层（如结果框）的上面
        hide()
        val label = createView().also { tv ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (96 * density).roundToInt()
            }
            windowManager.addView(tv, params)
            view = tv
        }
        label.text = message
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, durationMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun createView() = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        val h = (16 * density).roundToInt()
        val v = (10 * density).roundToInt()
        setPadding(h, v, h, v)
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.85f).roundToInt()
        background = GradientDrawable().apply {
            setColor(0xE6323232.toInt())
            cornerRadius = 20 * density
        }
    }
}
