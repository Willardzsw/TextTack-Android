package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 贴译的核心服务（目前是空壳）。
 *
 * 以后要做的（见 docs/第一版范围.md）：
 * - 显示悬浮按钮（TYPE_ACCESSIBILITY_OVERLAY）
 * - 读界面节点文字；读不到时 takeScreenshot() + 本地 OCR
 * - 在原位置显示译文
 */
class TranslateAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 第一版只在用户点悬浮按钮时主动读取，这里暂不处理事件
    }

    override fun onInterrupt() {
    }
}
