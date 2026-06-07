package com.danmureader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    private lateinit var btnExportLog: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

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
        btnExportLog = findViewById(R.id.btnExportLog)
        btnSettings = findViewById(R.id.btnSettings)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        layoutInstallEngines = findViewById(R.id.layoutInstallEngines)

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnTestTts.setOnClickListener { testTts() }
        btnClearLog.setOnClickListener { tvLogs.text = ""; AppLogger.i("MainActivity", "日志已清除") }
        btnExportLog.setOnClickListener { exportLog() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        btnInstallRhVoice.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装 RHVoice...")
            TtsManager(this).installRhVoice()
        }
        btnInstallGoogleTts.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装 Google TTS...")
            TtsManager(this).installGoogleTts()
        }
        btnInstallXunfei.setOnClickListener {
            AppLogger.i("MainActivity", "正在跳转安装讯飞语记...")
            TtsManager(this).installXunfei()
        }
        btnTtsSettings.setOnClickListener {
            AppLogger.i("MainActivity", "正在打开 TTS 设置页面...")
            TtsManager(this).openTtsSettings()
        }

        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { updateUI() }
        }
        val statusFilter = IntentFilter("com.danmureader.SERVICE_STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }

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
        checkTtsEngine()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        checkTtsEngine()
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
        val installed = ttsManager.listInstalledEngines()
        AppLogger.i("MainActivity", "PackageManager 扫描到 ${installed.size} 个TTS引擎")

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

        if (overlayOk) {
            tvOverlayStatus.text = "✓ 悬浮窗权限已授予"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_green))
            btnOverlay.text = "已授权"; btnOverlay.isEnabled = false
        } else {
            tvOverlayStatus.text = "✗ 悬浮窗权限未授予"
            tvOverlayStatus.setTextColor(getColor(R.color.accent_red))
            btnOverlay.text = "去授权"; btnOverlay.isEnabled = true
        }

        if (accessibilityOk) {
            tvAccessibilityStatus.text = "✓ 无障碍服务运行中"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_green))
            btnAccessibility.text = "已开启"; btnAccessibility.isEnabled = false
        } else {
            tvAccessibilityStatus.text = "✗ 无障碍服务未开启"
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_red))
            btnAccessibility.text = "去开启"; btnAccessibility.isEnabled = true
        }

        when {
            !ttsOk -> { tvStatus.text = "请先安装语音引擎（第一步最重要）"; tvStatus.setTextColor(getColor(R.color.accent_red)) }
            !overlayOk -> { tvStatus.text = "请授予悬浮窗权限"; tvStatus.setTextColor(getColor(R.color.accent_blue)) }
            !accessibilityOk -> { tvStatus.text = "请开启无障碍服务"; tvStatus.setTextColor(getColor(R.color.accent_blue)) }
            else -> { tvStatus.text = "✓ 一切就绪！打开抖音直播间即可自动朗读弹幕"; tvStatus.setTextColor(getColor(R.color.accent_green)) }
        }
    }

    private fun testTts() {
        AppLogger.i("MainActivity", "=== 开始朗读测试 ===")
        btnTestTts.isEnabled = false
        btnTestTts.text = "正在测试..."

        val ttsManager = TtsManager(this)
        ttsManager.onInitSuccess = {
            handler.post { btnTestTts.text = "✓ 朗读正常"; btnTestTts.isEnabled = true }
        }
        ttsManager.onInitFailed = { reason ->
            handler.post {
                btnTestTts.text = "✗ 测试失败"; btnTestTts.isEnabled = true
                AppLogger.e("MainActivity", "朗读测试失败: $reason")
            }
        }
        ttsManager.init {
            AppLogger.i("MainActivity", "TTS 初始化成功，播放测试语音...")
            ttsManager.speak("你好，这是弹幕朗读助手的测试语音。如果你能听到这段话，说明朗读功能正常。")
        }
        handler.postDelayed({ ttsManager.destroy(); AppLogger.i("MainActivity", "=== 朗读测试结束 ===") }, 10000)
    }

    /**
     * 导出日志到文件并通过分享发送
     */
    private fun exportLog() {
        val history = AppLogger.getHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val fileName = "danmu_log_${dateFormat.format(Date())}.txt"

            // 写入到应用缓存目录（不需要存储权限）
            val logDir = File(cacheDir, "logs")
            logDir.mkdirs()
            val logFile = File(logDir, fileName)

            val header = buildString {
                appendLine("=== 弹幕朗读助手 日志 ===")
                appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("App版本: 1.0")
                appendLine("========================")
                appendLine()
            }

            logFile.writeText(header + history.joinToString("\n"))

            AppLogger.i("MainActivity", "日志已导出: $fileName")

            // 通过系统分享发送
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "弹幕朗读助手日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志"))

        } catch (e: Exception) {
            AppLogger.e("MainActivity", "导出日志失败: ${e.message}")
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            AppLogger.i("MainActivity", "已跳转到悬浮窗权限设置")
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "无法打开悬浮窗设置: ${e.message}")
            Toast.makeText(this, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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