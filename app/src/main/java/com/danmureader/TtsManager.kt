package com.danmureader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 3.0f
        private const val SPEED_STEP = 0.25f
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isPaused = false
    private var currentSpeed = 1.5f
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false
    var onSpeakStart: ((String) -> Unit)? = null
    var onSpeakEnd: (() -> Unit)? = null
    var danmuCount = 0L

    // TTS 状态回调
    var onInitSuccess: (() -> Unit)? = null
    var onInitFailed: ((reason: String) -> Unit)? = null
    var onNeedInstall: ((reason: String) -> Unit)? = null

    /**
     * 检测 TTS 引擎状态
     * 返回: 0=正常可用, 1=无引擎, 2=引擎有但不支持中文, 3=初始化失败
     */
    fun checkEngineStatus(callback: (Int, String) -> Unit) {
        var checker: TextToSpeech? = null
        checker = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                callback(1, "手机未安装任何语音引擎")
                checker?.shutdown()
                return@TextToSpeech
            }
            val engine = checker ?: return@TextToSpeech
            val langResult = engine.isLanguageAvailable(Locale.CHINESE)
            val langResultSimplified = engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)

            if (langResult >= TextToSpeech.LANG_AVAILABLE ||
                langResultSimplified >= TextToSpeech.LANG_AVAILABLE) {
                callback(0, "语音引擎正常，支持中文")
            } else {
                val engineName = engine.defaultEngine ?: "未知"
                callback(2, "当前引擎($engineName)不支持中文语音")
            }
            engine.shutdown()
        }
    }

    fun init(onReady: (() -> Unit)? = null) {
        AppLogger.i(TAG, "正在初始化 TTS 引擎...")

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    // 尝试设置中文
                    var result = engine.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    }

                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val engineName = engine.defaultEngine ?: "未知"
                        AppLogger.e(TAG, "当前TTS引擎($engineName)不支持中文")
                        onInitFailed?.invoke("当前TTS引擎不支持中文语音，建议安装RHVoice或讯飞语记")
                        return@let
                    }

                    engine.setSpeechRate(currentSpeed)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                        }
                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            speakNext()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            AppLogger.w(TAG, "朗读出错: $utteranceId")
                            speakNext()
                        }
                    })
                    isInitialized = true
                    val engineName = engine.defaultEngine ?: "未知"
                    AppLogger.i(TAG, "TTS 引擎初始化成功 [引擎: $engineName, 语速: ${currentSpeed}x]")
                    onInitSuccess?.invoke()
                    onReady?.invoke()
                }
            } else {
                AppLogger.e(TAG, "TTS 引擎初始化失败，状态码: $status")
                onInitFailed?.invoke("TTS 引擎初始化失败(状态码:$status)")
            }
        }
    }

    fun speak(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        if (isPaused) return

        if (!isSpeaking) {
            doSpeak(cleaned)
        } else {
            pendingQueue.offer(cleaned)
        }
    }

    private fun doSpeak(text: String) {
        if (!isInitialized) {
            AppLogger.w(TAG, "TTS 未初始化，无法朗读: $text")
            return
        }
        danmuCount++
        onSpeakStart?.invoke(text)
        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "danmu_$danmuCount")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "danmu_$danmuCount")
    }

    private fun speakNext() {
        if (isPaused) return
        val next = pendingQueue.poll() ?: return
        doSpeak(next)
    }

    fun pause() {
        isPaused = true
        tts?.stop()
        isSpeaking = false
        AppLogger.i(TAG, "朗读已暂停")
    }

    fun resume() {
        isPaused = false
        speakNext()
        AppLogger.i(TAG, "朗读已恢复")
    }

    fun isPaused(): Boolean = isPaused

    fun speedUp(): Float {
        currentSpeed = (currentSpeed + SPEED_STEP).coerceAtMost(MAX_SPEED)
        tts?.setSpeechRate(currentSpeed)
        AppLogger.i(TAG, "语速调至: ${currentSpeed}x")
        return currentSpeed
    }

    fun speedDown(): Float {
        currentSpeed = (currentSpeed - SPEED_STEP).coerceAtLeast(MIN_SPEED)
        tts?.setSpeechRate(currentSpeed)
        AppLogger.i(TAG, "语速调至: ${currentSpeed}x")
        return currentSpeed
    }

    fun getCurrentSpeed(): Float = currentSpeed

    fun clearQueue() {
        pendingQueue.clear()
        tts?.stop()
        isSpeaking = false
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 打开系统 TTS 设置页面
     */
    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "无法打开TTS设置: ${e.message}")
        }
    }

    /**
     * 引导用户安装 RHVoice（开源免费TTS引擎，支持中文）
     */
    fun installRhVoice() {
        try {
            // 先尝试直接打开 RHVoice 的 Play Store 页面
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.github.olga_yakovleva.RhVoice.android")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Play Store 不可用，尝试浏览器
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://github.com/RhVoice/RhVoice/releases")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                AppLogger.e(TAG, "无法打开下载页面: ${e2.message}")
            }
        }
    }

    /**
     * 引导用户安装讯飞语记（国内常用TTS引擎）
     */
    fun installXunfei() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.iflytek.vflynote")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                // 尝试打开讯飞开放平台
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.xunfei.cn/")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                AppLogger.e(TAG, "无法打开下载页面: ${e2.message}")
            }
        }
    }

    /**
     * 引导用户安装 Google TTS
     */
    fun installGoogleTts() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.google.android.tts")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                AppLogger.e(TAG, "无法打开下载页面: ${e2.message}")
            }
        }
    }

    /**
     * 列出当前手机上所有可用的 TTS 引擎
     */
    fun listAvailableEngines(): List<String> {
        val engines = mutableListOf<String>()
        try {
            val tts = TextToSpeech(context) { _ -> }
            val engineList = tts.engines
            for (engine in engineList) {
                engines.add("${engine.label} (${engine.name})")
                AppLogger.d(TAG, "发现TTS引擎: ${engine.label} (${engine.name})")
            }
            tts.shutdown()
        } catch (e: Exception) {
            AppLogger.w(TAG, "获取引擎列表失败: ${e.message}")
        }
        return engines
    }
}