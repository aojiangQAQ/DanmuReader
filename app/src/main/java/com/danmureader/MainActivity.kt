package com.danmureader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvTtsStatus: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnTestTts: MaterialButton
    private lateinit var btnInstallRhVoice: MaterialButton
    private lateinit var btnInstallGoogleTts: MaterialButton
    private lateinit var btnInstallXunfei: MaterialButton
    private lateinit var btnTtsSettings: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

    // 引擎安装区域
    private lateinit var layoutInstallEngines: View

    private var statusReceiver: BroadcastReceiver? = null
    private var logReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvTtsStatus = findViewById(R.id.tvTtsStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnTestTts = findViewById(R.id.btnTestTts)
        btnInstallRhVoice = findViewById(R.id.btnInstallRhVoice)
        btnInstallGoogleTts = findViewById(R.id.btnInstallGoogleTts)
        btnInstallXunfei = findViewById(R.id.btnInstallXunfei)
        btnTtsSettings = findViewById(R.id.btnTtsSettings)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        layoutInstallEngines = findViewById(R.id.layoutInstallEngines)

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnTestTts.setOnClickListener { testTts() }
        btnClearLog.setOnClickListener { tvLogs.text = ""; AppLogger.i("MainActivity", "日志已清除") }

        btnInstallRhVoice.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装 RHVoice...")
            val ttsManager = TtsManager(this)
            ttsManager.installRhVoice()
        }
        btnInstallGoogleTts.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装 Google TTS...")
            val ttsManager = TtsManager(this)
            ttsManager.installGoogleTts()
        }
        btnInstallXunfei.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装讯飞语记...")
            val ttsManager = TtsManager(this)
            ttsManager.installXunfei()
        }
        btnTtsSettings.setOnClickListener {
            AppLogger.i("MainActivity", "正在打开 TTS 设置页面...")
            val ttsManager = TtsManager(this)
            ttsManager.openTtsSettings()
        }

        // 注册服务状态广播
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateUI()
            }
        }
        val statusFilter = IntentFilter("com.danmureader.SERVICE_STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }

        // 注册日志广播
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra(AppLogger.EXTRA_MESSAGE) ?: return
                appendLog(msg)
            }
        }
        val logFilter = IntentFilter(AppLogger.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, logFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, logFilter)
        }

        AppLogger.i("MainActivity", "应用启动完成，正在检测环境...")

        // 启动时自动检测 TTS 引擎
        checkTtsEngine()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        // 从安装页面返回后重新检测
        checkTtsEngine()
        // 加载历史日志
        val history = AppLogger.getHistory()
        if (history.isNotEmpty() && tvLogs.text.isEmpty()) {
            tvLogs.text = history.joinToString("\n")
            scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun checkTtsEngine() {
        tvTtsStatus.text = "正在检测语音引擎..."
        tvTtsStatus.setTextColor(getColor(R.color.text_secondary))
        layoutInstallEngines.visibility = View.GONE

        val ttsManager = TtsManager(this)
        ttsManager.checkEngineStatus { code, message ->
            handler.post {
                when (code) {
                    0 -> {
                        tvTtsStatus.text = "✓ $message"
                        tvTtsStatus.setTextColor(getColor(R.color.accent_green))
                        layoutInstallEngines.visibility = View.GONE
                        AppLogger.i("MainActivity", "TTS 检测通过: $message")
                    }
                    1 -> {
                        tvTtsStatus.text = "✗ $message"
                        tvTtsStatus.setTextColor(getColor(R.color.accent_red))
                        layoutInstallEngines.visibility = View.VISIBLE
                        AppLogger.w("MainActivity", "TTS引擎问题: $message")
                    }
                    2 -> {
                        tvTtsStatus.text = "✗ $message"
                        tvTtsStatus.setTextColor(getColor(R.color.accent_red))
                        layoutInstallEngines.visibility = View.VISIBLE
                        AppLogger.w("MainActivity", "TTS引擎不支持中文: $message")
                    }
                    3 -> {
                        tvTtsStatus.text = "✗ $message"
                        tvTtsStatus.setTextColor(getColor(R.color.accent_red))
                        layoutInstallEngines.visibility = View.VISIBLE
                        AppLogger.e("MainActivity", "TTS检测异常: $message")
                    }
                }
                updateUI()
            }
        }
    }

    private fun updateUI() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = DanmuAccessibilityService.isServiceRunning(this)
        val ttsOk = tvTtsStatus.text.startsWith("✓")

        // 悬浮窗权限状态
        if (overlayOk) {
            tvOverlayStatus.text = "✓ 悬浮窗权限已授予"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_green))
            btnOverlay.text = "已授权"
            btnOverlay.isEnabled = false
        } else {
            tvOverlayStatus.text = "✗ 悬浮窗权限未授予"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_red))
            btnOverlay.text = "去授权"
            btnOverlay.isEnabled = true
        }

        // 无障碍服务状态
        if (accessibilityOk) {
            tvAccessibilityStatus.text = "✓ 无障碍服务运行中"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_green))
            btnAccessibility.text = "已开启"
            btnAccessibility.isEnabled = false
        } else {
            tvAccessibilityStatus.text = "✗ 无障碍服务未开启"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_red))
            btnAccessibility.text = "去开启"
            btnAccessibility.isEnabled = true
        }

        // 总状态
        when {
            !ttsOk -> {
                tvStatus.text = "请先安装语音引擎（第一步最重要）"
                tvStatus.setTextColor(getColor(R.color.accent_red))
            }
            !overlayOk -> {
                tvStatus.text = "请授予悬浮窗权限"
                tvStatus.setTextColor(getColor(R.color.accent_blue))
            }
            !accessibilityOk -> {
                tvStatus.text = "请开启无障碍服务"
                tvStatus.setTextColor(getColor(R.color.accent_blue))
            }
            else -> {
                tvStatus.text = "✓ 一切就绪！打开抖音直播间即可自动朗读弹幕"
                tvStatus.setTextColor(getColor(R.color.accent_green))
            }
        }
    }

    private fun testTts() {
        AppLogger.i("MainActivity", "=== 开始朗读测试 ===")
        btnTestTts.isEnabled = false
        btnTestTts.text = "正在测试..."

        val ttsManager = TtsManager(this)
        ttsManager.onInitSuccess = {
            handler.post {
                btnTestTts.text = "✓ 朗读正常"
                btnTestTts.isEnabled = true
            }
        }
        ttsManager.onInitFailed = { reason ->
            handler.post {
                btnTestTts.text = "✗ 测试失败"
                btnTestTts.isEnabled = true
                AppLogger.e("MainActivity", "朗读测试失败: $reason")
            }
        }
        ttsManager.init {
            AppLogger.i("MainActivity", "TTS 初始化成功，播放测试语音...")
            ttsManager.speak("你好，这是弹幕朗读助手的测试语音。如果你能听到这段话，说明朗读功能正常。")
        }
        handler.postDelayed({
            ttsManager.destroy()
            AppLogger.i("MainActivity", "=== 朗读测试结束 ===")
        }, 10000)
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            AppLogger.i("MainActivity", "已跳转到悬浮窗权限设置")
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "无法打开悬浮窗设置: ${e.message}")
            Toast.makeText(this, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            AppLogger.i("MainActivity", "已跳转到无障碍设置")
            Toast.makeText(this, "请找到「弹幕朗读助手」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "无法打开无障碍设置: ${e.message}")
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLogs.append(msg + "\n")
            scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        statusReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        logReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }
}