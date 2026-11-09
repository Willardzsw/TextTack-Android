package io.github.willardzsw.texttack

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 屏幕边上的悬浮按钮，用无障碍悬浮层显示（不需要悬浮窗权限）。
 * 可拖动，松手贴到最近的左右边，位置记在 SharedPreferences 里。
 */
class FloatingButton(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val prefs = context.getSharedPreferences("floating_button", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private val sizePx = (48 * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private var view: TextView? = null
    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    fun show() {
        if (view != null) return
        val screen = screenBounds()
        params.x = prefs.getInt(KEY_X, screen.width() - sizePx)
        params.y = prefs.getInt(KEY_Y, screen.height() / 3)
        clampToScreen()

        val button = TextView(context).apply {
            text = "译"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E6FD9.toInt())
                setStroke((1.5f * density).roundToInt(), 0x99FFFFFF.toInt())
            }
            elevation = 6 * density
            contentDescription = "贴译：点击读取屏幕文字，长按截屏"
        }
        button.setOnTouchListener(TouchHandler())
        windowManager.addView(button, params)
        view = button
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    /** 截屏前临时隐藏，避免把按钮截进去。 */
    fun setVisible(visible: Boolean) {
        view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun screenBounds() = windowManager.maximumWindowMetrics.bounds

    private fun clampToScreen() {
        val screen = screenBounds()
        params.x = params.x.coerceIn(0, (screen.width() - sizePx).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.height() - sizePx).coerceAtLeast(0))
    }

    private fun updatePosition() {
        clampToScreen()
        view?.let { windowManager.updateViewLayout(it, params) }
    }

    private inner class TouchHandler : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false

        private val longPressRunnable = Runnable {
            longPressed = true
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, longPressMs)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !longPressed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        updatePosition()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        snapToEdge()
                    } else if (!longPressed) {
                        v.performClick()
                        onTap()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) snapToEdge()
                }
            }
            return true
        }

        private fun snapToEdge() {
            val screen = screenBounds()
            val center = params.x + sizePx / 2
            params.x = if (center < screen.width() / 2) 0 else screen.width() - sizePx
            updatePosition()
            prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
        }
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
