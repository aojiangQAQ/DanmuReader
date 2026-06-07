package com.danmureader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
     * 用 PackageManager 查询系统中所有 TTS 引擎
     * 这种方式比 TextToSpeech.engines 更可靠
     */
    fun listInstalledEngines(): List<Pair<String, String>> {
        val engines = mutableListOf<Pair<String, String>>()

        // 方法1: 查询 Intent.ACTION_TTS_SERVICE
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos: List<ResolveInfo> = pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm)?.toString() ?: info.serviceInfo.packageName
            val packageName = info.serviceInfo.packageName
            engines.add(Pair(label, packageName))
            AppLogger.i(TAG, "发现TTS服务: $label ($packageName)")
        }

        // 方法2: 备用 - 查询系统设置中的 TTS 列表
        if (engines.isEmpty()) {
            AppLogger.w(TAG, "通过 Intent 查询未找到TTS引擎，尝试备用方案...")
            val ttsIntent = Intent("android.speech.tts.engine.CHECK_TTS_DATA")
            val activities = pm.queryIntentActivities(ttsIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (act in activities) {
                val label = act.loadLabel(pm)?.toString() ?: act.activityInfo.packageName
                val packageName = act.activityInfo.packageName
                if (!engines.any { it.second == packageName }) {
                    engines.add(Pair(label, packageName))
                    AppLogger.i(TAG, "备用方案发现TTS: $label ($packageName)")
                }
            }
        }

        if (engines.isEmpty()) {
            AppLogger.e(TAG, "系统中未发现任何TTS引擎!")
        }

        return engines
    }

    /**
     * 检测 TTS 引擎状态
     * 状态码: 0=正常可用, 1=无引擎, 2=引擎不支持中文, 3=初始化超时/异常
     */
    fun checkEngineStatus(callback: (Int, String) -> Unit) {
        AppLogger.i(TAG, "开始检测 TTS 引擎...")

        // 第一步: 用 PackageManager 检测已安装的 TTS 引擎
        val engines = listInstalledEngines()

        if (engines.isEmpty()) {
            callback(1, "手机未安装任何语音引擎，请先安装一个")
            return
        }

        val engineNames = engines.joinToString(", ") { it.first }
        AppLogger.i(TAG, "共发现 ${engines.size} 个引擎: $engineNames")

        // 第二步: 尝试实际初始化 TTS 引擎，验证是否可用
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val callbackSent = AtomicBoolean(false)

        // 超时保护
        handler.postDelayed({
            if (!callbackSent.getAndSet(true)) {
                AppLogger.e(TAG, "TTS 引擎初始化超时(5秒)")
                callback(3, "引擎响应超时。已安装: $engineNames，请尝试重启手机")
            }
        }, 5000)

        try {
            var checker: TextToSpeech? = null
            checker = TextToSpeech(appContext) { status ->
                handler.post {
                    if (callbackSent.get()) return@post

                    AppLogger.i(TAG, "TTS 初始化回调 status=$status")

                    if (status != TextToSpeech.SUCCESS) {
                        callbackSent.set(true)
                        AppLogger.e(TAG, "TTS 初始化失败 status=$status")
                        callback(1, "引擎初始化失败(状态码:$status)。已安装: $engineNames")
                        checker?.shutdown()
                        return@post
                    }

                    val engine = checker ?: run {
                        callbackSent.set(true)
                        callback(3, "TTS 对象异常为空")
                        return@post
                    }

                    // 获取当前使用的引擎名
                    val activeEngine = try { engine.defaultEngine ?: "未知" } catch (e: Exception) { "未知" }
                    AppLogger.i(TAG, "当前激活引擎: $activeEngine")

                    // 检查中文支持
                    val langZH = try { engine.isLanguageAvailable(Locale.CHINESE) } catch (e: Exception) { -99 }
                    val langZHCN = try { engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) } catch (e: Exception) { -99 }
                    AppLogger.i(TAG, "语言可用性: zh=$langZH, zh_CN=$langZHCN (可用>=0)")

                    // LANG_AVAILABLE=0, LANG_COUNTRY_AVAILABLE=1, LANG_COUNTRY_VAR_AVAILABLE=2
                    // LANG_NOT_SUPPORTED=-2, LANG_MISSING_DATA=-1
                    if (langZH >= TextToSpeech.LANG_AVAILABLE || langZHCN >= TextToSpeech.LANG_AVAILABLE) {
                        callbackSent.set(true)
                        callback(0, "引擎就绪 [$activeEngine]，支持中文")
                    } else {
                        callbackSent.set(true)
                        val reason = when {
                            langZH == -1 || langZHCN == -1 -> "缺少中文语音数据包"
                            langZH == -2 || langZHCN == -2 -> "不支持中文"
                            else -> "语言检查结果: zh=$langZH, zh_CN=$langZHCN"
                        }
                        callback(2, "引擎[$activeEngine] $reason，建议安装RHVoice")
                    }

                    engine.shutdown()
                }
            }
        } catch (e: Exception) {
            if (!callbackSent.getAndSet(true)) {
                AppLogger.e(TAG, "创建TTS实例异常: ${e.message}")
                callback(3, "创建TTS实例失败: ${e.message}")
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
                            AppLogger.e(TAG, "TTS引擎($engineName)不支持中文，语言结果: $result")
                            onInitFailed?.invoke("当前TTS引擎不支持中文语音")
                            return@let
                        }
                        engine.setSpeechRate(currentSpeed)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { isSpeaking = true }
                            override fun onDone(utteranceId: String?) { isSpeaking = false; speakNext() }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                isSpeaking = false
                                AppLogger.w(TAG, "朗读出错: $utteranceId")
                                speakNext()
                            }
                        })
                        isInitialized = true
                        val engineName = try { engine.defaultEngine ?: "未知" } catch (e: Exception) { "未知" }
                        AppLogger.i(TAG, "TTS初始化成功 [引擎: $engineName, 语速: ${currentSpeed}x]")
                        onInitSuccess?.invoke()
                        onReady?.invoke()
                    }
                } else {
                    AppLogger.e(TAG, "TTS初始化失败，状态码: $status")
                    onInitFailed?.invoke("TTS初始化失败(状态码:$status)")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建TTS实例异常: ${e.message}")
            onInitFailed?.invoke("创建TTS实例异常: ${e.message}")
        }
    }

    fun speak(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || isPaused) return
        if (!isSpeaking) doSpeak(cleaned) else pendingQueue.offer(cleaned)
    }

    private fun doSpeak(text: String) {
        if (!isInitialized) { AppLogger.w(TAG, "TTS未初始化，无法朗读"); return }
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

    fun pause() { isPaused = true; tts?.stop(); isSpeaking = false; AppLogger.i(TAG, "朗读已暂停") }
    fun resume() { isPaused = false; speakNext(); AppLogger.i(TAG, "朗读已恢复") }
    fun isPaused(): Boolean = isPaused

    fun speedUp(): Float {
        currentSpeed = (currentSpeed + SPEED_STEP).coerceAtMost(MAX_SPEED)
        tts?.setSpeechRate(currentSpeed)
        AppLogger.i(TAG, "语速: ${currentSpeed}x")
        return currentSpeed
    }

    fun speedDown(): Float {
        currentSpeed = (currentSpeed - SPEED_STEP).coerceAtLeast(MIN_SPEED)
        tts?.setSpeechRate(currentSpeed)
        AppLogger.i(TAG, "语速: ${currentSpeed}x")
        return currentSpeed
    }

    fun getCurrentSpeed(): Float = currentSpeed
    fun clearQueue() { pendingQueue.clear(); tts?.stop(); isSpeaking = false }
    fun destroy() { tts?.stop(); tts?.shutdown(); tts = null; isInitialized = false }

    fun openTtsSettings() {
        try {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (_: Exception) {}
        }
    }

    fun installRhVoice() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.github.olga_yakovleva.RhVoice.android")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RhVoice/RhVoice/releases")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (_: Exception) { AppLogger.e(TAG, "无法打开RHVoice下载页面") }
        }
    }

    fun installXunfei() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.iflytek.vflynote")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.xunfei.cn/")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (_: Exception) { AppLogger.e(TAG, "无法打开讯飞下载页面") }
        }
    }

    fun installGoogleTts() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (_: Exception) { AppLogger.e(TAG, "无法打开Google TTS下载页面") }
        }
    }
}