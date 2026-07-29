package com.ebookreader.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 核心辅助功能服务
 *
 * 职责：
 * 1. 监听屏幕内容变化（翻页）
 * 2. 提取当前页的正文文字
 * 3. 调用 TTS 朗读
 * 4. 与悬浮控制面板通信
 */
class ReaderAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ReaderAccessibilityService? = null
            private set

        /** 向服务发指令 */
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SKIP = "skip"
        const val ACTION_STOP = "stop"
        const val ACTION_SPEED = "speed"
    }

    private lateinit var tts: TTSManager
    private var isAutoReading = false       // 是否启用了自动朗读
    private var lastTextHash = 0             // 上一页文字哈希（用于检测翻页）
    private var screenHeight = 0
    private var currentTextFragments = emptyList<String>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TTSManager(this)

        tts.onStatusChanged = { status ->
            // 通知悬浮窗更新状态
            FloatingControlService.instance?.updatePlayState(status)
        }

        tts.onUtteranceComplete = { _ ->
            // 一段读完后如果有新内容自动继续
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenHeight = resources.displayMetrics.heightPixels
        // 启动悬浮窗
        startFloatingService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isAutoReading) return

        // 仅在内容变化时检测翻页
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 延迟一点，等页面渲染完成
                android.os.Handler(mainLooper).postDelayed({
                    detectPageTurnAndRead()
                }, 500)
            }
        }
    }

    override fun onInterrupt() {
        tts.stop()
        isAutoReading = false
    }

    override fun onDestroy() {
        tts.destroy()
        instance = null
        stopFloatingService()
        super.onDestroy()
    }

    // ==========================================
    // 翻页检测 & 朗读
    // ==========================================

    /**
     * 检测是否翻页，如果内容变化较大则开始朗读新内容
     */
    private fun detectPageTurnAndRead() {
        val root = rootInActiveWindow ?: return
        val screenH = screenHeight

        // 提取当前页面的正文
        val fragments = TextFilter.extractBodyText(root, screenH)
        root.recycle()

        if (fragments.isEmpty()) return

        // 计算当前页文字哈希
        val combinedText = fragments.joinToString("") { it.text }
        val newHash = combinedText.hashCode()

        // 内容没有实质变化（可能只是滚动了一点点），不重新读
        val similarity = if (lastTextHash != 0) {
            // 粗略判断：hash 变化小于 40% 视为同一页
            abs(newHash - lastTextHash).toFloat() / abs(lastTextHash).toFloat().coerceAtLeast(1f)
        } else 1f

        if (similarity < 0.3f) {
            // 内容变化不大，可能是同一页的局部刷新
            return
        }

        lastTextHash = newHash
        currentTextFragments = fragments.map { it.text }

        // 朗读
        val textToRead = currentTextFragments.joinToString("")
        if (textToRead.isNotBlank()) {
            tts.speakImmediately(textToRead)
        }
    }

    /**
     * 手动触发朗读当前页面
     */
    fun readCurrentPage() {
        val root = rootInActiveWindow ?: return
        val fragments = TextFilter.extractBodyText(root, screenHeight)
        root.recycle()

        if (fragments.isEmpty()) {
            android.widget.Toast.makeText(this, "未检测到可读文字，请打开阅读App", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        currentTextFragments = fragments.map { it.text }
        val textToRead = currentTextFragments.joinToString("")
        if (textToRead.isNotBlank()) {
            tts.speakImmediately(textToRead)
        }
    }

    // ==========================================
    // 指令处理（由悬浮窗调用）
    // ==========================================

    fun handleAction(action: String, extra: Any? = null) {
        when (action) {
            ACTION_START -> {
                isAutoReading = true
                readCurrentPage()
            }
            ACTION_PAUSE -> {
                tts.pause()
                isAutoReading = false
            }
            ACTION_RESUME -> {
                isAutoReading = true
                tts.resume()
                // 如果没有在读，读当前页
                if (!tts.isActive()) {
                    readCurrentPage()
                }
            }
            ACTION_SKIP -> {
                tts.skip()
            }
            ACTION_STOP -> {
                isAutoReading = false
                tts.stop()
            }
            ACTION_SPEED -> {
                val newSpeed = extra as? Float ?: return
                tts.setSpeed(newSpeed)
            }
        }
    }

    fun getCurrentSpeed(): Float = tts.getSpeed()

    // ==========================================
    // 悬浮窗
    // ==========================================

    private fun startFloatingService() {
        val intent = Intent(this, FloatingControlService::class.java)
        startForegroundService(intent)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingControlService::class.java)
        stopService(intent)
    }
}
