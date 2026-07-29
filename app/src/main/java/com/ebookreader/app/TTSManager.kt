package com.ebookreader.app

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTS 管理器——封装 Android 系统语音合成
 *
 * 支持：语速调节、段落排队、播放/暂停/跳过
 */
class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var speed = 1.0f         // 语速（1.0 = 正常）
    private var isSpeaking = false
    private var paused = false
    private var currentUtteranceId = ""
    private val queue = ConcurrentLinkedQueue<Pair<String, String>>()  // (文本, ID)

    var onStatusChanged: ((Status) -> Unit)? = null
    var onUtteranceComplete: ((String) -> Unit)? = null

    enum class Status { READY, SPEAKING, PAUSED, IDLE, ERROR }

    init {
        initTTS(context)
    }

    var initError: String? = null
        private set

    private fun initTTS(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                // 检查系统是否有 TTS 引擎
                val intent = Intent()
                intent.action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
                initError = "TTS引擎未安装。请从应用商店安装 Google文字转语音"
                isReady = false
                onStatusChanged?.invoke(Status.ERROR)
                return@TextToSpeech
            }

            // 尝试多个中文 Locale
            val locales = listOf(
                Locale.SIMPLIFIED_CHINESE,
                Locale.CHINESE,
                Locale.CHINA,
                Locale("zh"),
                Locale.TRADITIONAL_CHINESE
            )
            var ok = false
            for (loc in locales) {
                val result = tts?.setLanguage(loc) ?: TextToSpeech.LANG_MISSING_DATA
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    ok = true
                    break
                }
            }

            if (!ok) {
                isReady = false
                initError = "缺少中文语音数据。请安装 Google文字转语音 并下载中文离线包"
                onStatusChanged?.invoke(Status.ERROR)
                return@TextToSpeech
            }

            isReady = true
            tts?.setSpeechRate(speed)
            onStatusChanged?.invoke(Status.READY)
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                currentUtteranceId = utteranceId ?: ""
                onStatusChanged?.invoke(Status.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                onUtteranceComplete?.invoke(utteranceId ?: "")
                // 播放队列中下一个
                playNextInQueue()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                onStatusChanged?.invoke(Status.ERROR)
                playNextInQueue()
            }
        })
    }

    // ==========================================
    // 公开 API
    // ==========================================

    /** 朗读一段文字（如果有正在读的内容，排队等待） */
    fun speak(text: String, utteranceId: String = "") {
        if (!isReady || text.isBlank()) return

        val id = if (utteranceId.isNotEmpty()) utteranceId else "tts_${System.currentTimeMillis()}"

        if (isSpeaking) {
            queue.add(Pair(text, id))
        } else {
            doSpeak(text, id)
        }
    }

    /** 立即朗读（打断当前） */
    fun speakImmediately(text: String) {
        if (!isReady || text.isBlank()) return
        queue.clear()
        stop()
        doSpeak(text, "immediate_${System.currentTimeMillis()}")
    }

    /** 设置语速 */
    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 3.0f)
        tts?.setSpeechRate(speed)
    }

    /** 获取当前语速 */
    fun getSpeed(): Float = speed

    /** 暂停 */
    fun pause() {
        paused = true
        // Android TTS 没有原生暂停，只能停止
        // 记录当前位置是个复杂的问题，这里简化处理
        onStatusChanged?.invoke(Status.PAUSED)
    }

    /** 恢复 */
    fun resume() {
        paused = false
        // 重新读队列中的下一段
        playNextInQueue()
    }

    /** 跳过当前段落 */
    fun skip() {
        tts?.stop()
        isSpeaking = false
        playNextInQueue()
    }

    /** 停止 */
    fun stop() {
        tts?.stop()
        isSpeaking = false
        paused = false
        queue.clear()
        onStatusChanged?.invoke(Status.IDLE)
    }

    /** 是否正在播放 */
    fun isActive(): Boolean = isSpeaking || paused

    /** 销毁 */
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ==========================================
    // 内部
    // ==========================================

    private fun doSpeak(text: String, id: String) {
        // 把长文本拆分成长度合适的句子
        val sentences = splitIntoSentences(text)
        sentences.forEachIndexed { index, sentence ->
            val sentenceId = "${id}_${index}"
            tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentenceId)
        }
    }

    private fun playNextInQueue() {
        if (paused) return
        val next = queue.poll()
        if (next != null) {
            doSpeak(next.first, next.second)
        } else {
            isSpeaking = false
            onStatusChanged?.invoke(Status.IDLE)
        }
    }

    /**
     * 智能断句——按中文标点切分，保持朗读自然停顿
     */
    private fun splitIntoSentences(text: String): List<String> {
        if (text.length <= 40) return listOf(text)

        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        // 断句标点
        val breakPoints = setOf('。', '！', '？', '；', '，', '.', '!', '?', ';', ',', '\n')

        for (ch in text) {
            buffer.append(ch)
            if (breakPoints.contains(ch) && buffer.length > 3) {
                result.add(buffer.toString().trim())
                buffer.clear()
            }
            // 每 60 字符强制断句
            if (buffer.length >= 60) {
                result.add(buffer.toString().trim())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) {
            result.add(buffer.toString().trim())
        }

        return result.ifEmpty { listOf(text) }
    }
}
