package com.danmureader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DanmuAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DanmuService"
        private const val DOUYIN_RECYCLERVIEW_ID = "com.ss.android.ugc.aweme:id/ru7"
        private const val RECYCLERVIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

        fun isServiceRunning(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            for (service in enabledServices) {
                if (service.resolveInfo.serviceInfo.packageName == context.packageName) {
                    return true
                }
            }
            return false
        }
    }

    private lateinit var ttsManager: TtsManager
    private lateinit var danmuQueue: DanmuQueue
    private lateinit var floatingWindow: FloatingWindowManager
    private var isRunning = false
    private var lastProcessTime = 0L
    private val processInterval = 300L
    private var eventCount = 0L
    private var lastLogTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.i(TAG, "无障碍服务已连接，正在初始化...")

        danmuQueue = DanmuQueue()
        ttsManager = TtsManager(this)
        floatingWindow = FloatingWindowManager(this)

        ttsManager.init {
            AppLogger.i(TAG, "TTS 引擎初始化成功")
        }

        ttsManager.onSpeakStart = { text ->
            AppLogger.i(TAG, "朗读: $text")
        }

        floatingWindow.setTtsManager(ttsManager)
        floatingWindow.show()
        AppLogger.i(TAG, "悬浮控制窗已显示")

        isRunning = true
        AppLogger.i(TAG, "服务就绪，等待抖音直播弹幕...")

        sendBroadcast(Intent("com.danmureader.SERVICE_STATUS_CHANGED").apply {
            setPackage(packageName)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < processInterval) return
        lastProcessTime = now

        eventCount++

        try {
            val rootNode = rootInActiveWindow ?: return
            val packageName = event.packageName?.toString() ?: return

            // 每 10 秒输出一次心跳日志
            if (now - lastLogTime > 10000) {
                lastLogTime = now
                if (packageName == "com.ss.android.ugc.aweme") {
                    AppLogger.d(TAG, "抖音前台运行中，已接收事件 $eventCount 次")
                } else {
                    AppLogger.d(TAG, "当前App: $packageName (非抖音，跳过)")
                }
            }

            if (packageName != "com.ss.android.ugc.aweme") return

            val recyclerView = findDanmuRecyclerView(rootNode)
            if (recyclerView != null) {
                processDanmuRecyclerView(recyclerView)
            } else {
                // 每 10 秒提醒一次找不到弹幕区域
                if (now - lastLogTime > 10000) {
                    AppLogger.w(TAG, "未找到弹幕 RecyclerView，可能不在直播页面")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理事件异常: ${e.message}")
        }
    }

    private fun findDanmuRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过精确控件 ID 查找
        val nodesById = root.findAccessibilityNodeInfosByViewId(DOUYIN_RECYCLERVIEW_ID)
        if (nodesById != null && nodesById.isNotEmpty()) {
            AppLogger.d(TAG, "通过控件 ID 找到弹幕区域")
            return nodesById[0]
        }

        // 方法2: 通过 RecyclerView 类名 + 位置特征查找
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        findRecyclerViews(root, recyclerViews)

        if (recyclerViews.isNotEmpty()) {
            AppLogger.d(TAG, "找到 ${recyclerViews.size} 个 RecyclerView，正在匹配弹幕区域...")
        }

        for (rv in recyclerViews) {
            val rect = android.graphics.Rect()
            rv.getBoundsInScreen(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            if (rect.top > screenHeight * 0.4 && rect.width() > resources.displayMetrics.widthPixels * 0.3) {
                AppLogger.d(TAG, "通过位置匹配找到弹幕区域 (top=${rect.top})")
                return rv
            }
        }
        return null
    }

    private fun findRecyclerViews(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString()?.contains(RECYCLERVIEW_CLASS) == true) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findRecyclerViews(child, result)
        }
    }

    private fun processDanmuRecyclerView(recyclerView: AccessibilityNodeInfo) {
        val childCount = recyclerView.childCount
        if (childCount <= 0) return

        for (i in 0 until childCount) {
            val child = recyclerView.getChild(i) ?: continue
            val text = extractTextFromNode(child)
            if (text.isNotEmpty()) {
                if (isDanmuText(text)) {
                    if (danmuQueue.offer(text)) {
                        AppLogger.d(TAG, "捕获弹幕: $text")
                        ttsManager.speak(text)
                        floatingWindow.updateDanmuCount(ttsManager.danmuCount)
                    } else {
                        // 重复弹幕，静默跳过
                    }
                } else {
                    AppLogger.d(TAG, "过滤非弹幕: $text")
                }
            }
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractTextFromNode(child)
            if (childText.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(childText)
            }
        }
        return sb.toString().trim()
    }

    private fun isDanmuText(text: String): Boolean {
        if (text.length < 2) return false
        val systemKeywords = listOf(
            "进入直播间", "关注了", "点赞了", "送出了",
            "欢迎来到", "直播开始", "直播结束",
            "刚刚看过", "来了", "在线人数"
        )
        for (keyword in systemKeywords) {
            if (text.contains(keyword)) return false
        }
        return true
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ttsManager.destroy()
        floatingWindow.hide()
        AppLogger.i(TAG, "无障碍服务已销毁")
        sendBroadcast(Intent("com.danmureader.SERVICE_STATUS_CHANGED").apply {
            setPackage(packageName)
        })
    }
}