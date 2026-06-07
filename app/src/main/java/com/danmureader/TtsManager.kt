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
import java.util.concurrent.atomic.AtomicBoolean

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

    var onInitSuccess: (() -> Unit)? = null
    var onInitFailed: ((reason: String) -> Unit)? = null

    /**
     * 检测 TTS 引擎状态
     * 状态码: 0=正常可用, 1=无引擎初始化失败, 2=引擎有但不支持中文, 3=初始化异常
     */
    fun checkEngineStatus(callback: (Int, String) -> Unit) {
        AppLogger.i(TAG, "开始检测 TTS 引擎...")

        // 先列出所有可用引擎
        try {
            val tempTts = TextToSpeech(context, null)
            val engines = tempTts.engines
            AppLogger.i(TAG, "系统发现 ${engines.size} 个 TTS 引擎:")
            for (eng in engines) {
                AppLogger.i(TAG, "  - ${eng.label} (${eng.name})")
            }
            val defaultEngine = tempTts.defaultEngine
            AppLogger.i(TAG, "默认引擎: $defaultEngine")
            tempTts.shutdown()
        } catch (e: Exception) {
            AppLogger.e(TAG, "列出引擎失败: ${e.message}")
        }

        // 使用 ApplicationContext 避免生命周期问题
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val callbackSent = AtomicBoolean(false)

        // 设置超时保护
        handler.postDelayed({
            if (!callbackSent.getAndSet(true)) {
                AppLogger.e(TAG, "TTS 引擎检测超时(5秒)")
                callback(3, "TTS 引擎响应超时，请重启手机或重新安装语音引擎")
            }
        }, 5000)

        try {
            var checker: TextToSpeech? = null
            checker = TextToSpeech(appContext) { status ->
                // 确保回调在主线程执行
                handler.post {
                    if (callbackSent.get()) return@post

                    AppLogger.i(TAG, "TTS 初始化回调, status=$status (SUCCESS=${TextToSpeech.SUCCESS})")

                    if (status != TextToSpeech.SUCCESS) {
                        callbackSent.set(true)
                        AppLogger.e(TAG, "TTS 初始化失败, status=$status")

                        // 尝试获取更多信息
                        try {
                            val engines = checker?.engines
                            if (engines.isNullOrEmpty()) {
                                callback(1, "手机未安装任何语音引擎")
                            } else {
                                val engineNames = engines.joinToString(", ") { it.label.toString() }
                                callback(1, "语音引擎初始化失败(状态码:$status)，已安装引擎: $engineNames")
                            }
                        } catch (e: Exception) {
                            callback(1, "语音引擎初始化失败(状态码:$status)")
                        }
                        checker?.shutdown()
                        return@post
                    }

                    val engine = checker
                    if (engine == null) {
                        callbackSent.set(true)
                        callback(3, "TTS 对象异常为空")
                        return@post
                    }

                    // 检查中文支持
                    val engineName = try { engine.defaultEngine ?: "未知" } catch (e: Exception) { "未知" }
                    AppLogger.i(TAG, "TTS 引擎: $engineName")

                    val langResult = try { engine.isLanguageAvailable(Locale.CHINESE) } catch (e: Exception) { -1 }
                    val langSimplified = try { engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) } catch (e: Exception) { -1 }

                    AppLogger.i(TAG, "中文支持: CHINESE=$langResult, SIMPLIFIED_CHINESE=$langSimplified")

                    // LANG_AVAILABLE=0, LANG_COUNTRY_AVAILABLE=1, LANG_COUNTRY_VAR_AVAILABLE=2
                    if (langResult >= TextToSpeech.LANG_AVAILABLE ||
                        langSimplified >= TextToSpeech.LANG_AVAILABLE) {
                        callbackSent.set(true)
                        callback(0, "语音引擎正常 [$engineName]，支持中文")
                    } else {
                        callbackSent.set(true)
                        callback(2, "引擎[$engineName]不支持中文(中文检查:$langResult, 简体检查:$langSimplified)")
                    }

                    engine.shutdown()
                }
            }
        } catch (e: Exception) {
            if (!callbackSent.getAndSet(true)) {
                AppLogger.e(TAG, "创建 TTS 实例异常: ${e.message}")
                callback(3, "创建 TTS 实例失败: ${e.message}")
            }
        }
    }

    fun init(onReady: (() -> Unit)? = null) {
        AppLogger.i(TAG, "正在初始化 TTS 引擎...")

        val appContext = context.applicationContext
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        var result = engine.setLanguage(Locale.CHINESE)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        }

                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            val engineName = try { engine.defaultEngine ?: "未知" } catch (e: Exception) { "未知" }
                            AppLogger.e(TAG, "当前TTS引擎($engineName)不支持中文，语言设置结果: $result")
                            onInitFailed?.invoke("当前TTS引擎不支持中文语音")
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
                        val engineName = try { engine.defaultEngine ?: "未知" } catch (e: Exception) { "未知" }
                        AppLogger.i(TAG, "TTS 引擎初始化成功 [引擎: $engineName, 语速: ${currentSpeed}x]")
                        onInitSuccess?.invoke()
                        onReady?.invoke()
                    }
                } else {
                    AppLogger.e(TAG, "TTS 引擎初始化失败，状态码: $status")
                    onInitFailed?.invoke("TTS 引擎初始化失败(状态码:$status)")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建 TTS 实例异常: ${e.message}")
            onInitFailed?.invoke("创建 TTS 实例异常: ${e.message}")
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

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "无法打开TTS设置: ${e.message}")
            // 备用方案
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun installRhVoice() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.github.olga_yakovleva.RhVoice.android")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
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

    fun installXunfei() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.iflytek.vflynote")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
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
}