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

        // 抖音直播 Activity 标识
        private const val DOUYIN_LIVE_ACTIVITY = "LiveBroadcastActivity"

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
    private var isOnLivePage = false

    // 非弹幕关键词 - 用于精确过滤
    private val systemKeywords = listOf(
        "进入直播间", "关注了主播", "关注了", "点赞了", "送出", "送出了",
        "欢迎来到", "直播开始", "直播结束", "刚刚看过", "来了",
        "在线人数", "人观看", "万观看", "被管理员", "已被",
        "分享了直播间", "分享了", "加入了粉丝团", "粉丝团",
        "主播", "开播", "下播", "直播回放",
        "小时榜", "人气榜", "全国榜", "同城",
        "暂无更多", "加载中", "点击进入",
        "说:", "说："  // 避免我们自己格式化后的内容被二次读取
    )

    // 礼物相关关键词
    private val giftKeywords = listOf(
        "送出", "礼物", "嘉年华", "火箭", "飞机", "跑车",
        "小心心", "棒棒糖", "奶茶", "啤酒", "玫瑰",
        "气球", "保时捷", "游轮", "城堡", "花海",
        "×", "x" // 礼物数量格式如 "小心心×99"
    )

    // 个人信息/分享相关关键词
    private val profileKeywords = listOf(
        "的主页", "个人主页", "关注数", "粉丝数",
        "作品", "获赞", "抖音号",
        "举报", "拉黑", "设置备注",
        "私信", "发消息"
    )

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
            val packageName = event.packageName?.toString() ?: return
            if (packageName != "com.ss.android.ugc.aweme") return

            val rootNode = rootInActiveWindow ?: return

            // 检查当前是否在直播页面
            val currentActivity = event.className?.toString() ?: ""
            isOnLivePage = currentActivity.contains(DOUYIN_LIVE_ACTIVITY)

            // 心跳日志
            if (now - lastLogTime > 10000) {
                lastLogTime = now
                if (isOnLivePage) {
                    AppLogger.d(TAG, "抖音直播页面运行中，事件 $eventCount 次")
                } else {
                    AppLogger.d(TAG, "抖音非直播页面 ($currentActivity)，跳过弹幕获取")
                }
            }

            // 只在直播页面才获取弹幕
            if (!isOnLivePage) return

            val recyclerView = findDanmuRecyclerView(rootNode)
            if (recyclerView != null) {
                processDanmuRecyclerView(recyclerView)
            } else if (now - lastLogTime > 10000) {
                AppLogger.w(TAG, "在直播页面但未找到弹幕 RecyclerView")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理事件异常: ${e.message}")
        }
    }

    private fun findDanmuRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过精确控件 ID 查找
        val nodesById = root.findAccessibilityNodeInfosByViewId(DOUYIN_RECYCLERVIEW_ID)
        if (nodesById != null && nodesById.isNotEmpty()) {
            return nodesById[0]
        }

        // 方法2: 通过 RecyclerView 类名 + 位置特征查找
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        findRecyclerViews(root, recyclerViews)

        for (rv in recyclerViews) {
            val rect = android.graphics.Rect()
            rv.getBoundsInScreen(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            val screenWidth = resources.displayMetrics.widthPixels
            // 弹幕区域特征: 在屏幕下半部分，占屏幕宽度约60%-90%（不是全屏也不是太窄）
            val widthRatio = rect.width().toFloat() / screenWidth
            if (rect.top > screenHeight * 0.4 &&
                rect.top < screenHeight * 0.85 &&
                widthRatio > 0.3 && widthRatio < 0.95) {
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

            // 尝试解析用户名和内容的结构
            val danmuInfo = extractDanmuInfo(child)
            if (danmuInfo != null && danmuInfo.content.isNotEmpty()) {
                val fullText = "${danmuInfo.user} 说 ${danmuInfo.content}"
                if (danmuQueue.offer(fullText)) {
                    AppLogger.d(TAG, "捕获弹幕: $fullText")
                    ttsManager.speak(fullText)
                    floatingWindow.updateDanmuCount(ttsManager.danmuCount)
                }
            } else {
                // 降级处理: 直接提取文本
                val text = extractTextFromNode(child)
                if (text.isNotEmpty() && isDanmuText(text)) {
                    if (danmuQueue.offer(text)) {
                        AppLogger.d(TAG, "捕获弹幕(降级): $text")
                        ttsManager.speak(text)
                        floatingWindow.updateDanmuCount(ttsManager.danmuCount)
                    }
                }
            }
        }
    }

    /**
     * 尝试解析弹幕的用户名和内容
     * 抖音弹幕通常是: [用户名TextView] [弹幕内容TextView] 的结构
     */
    private data class DanmuInfo(val user: String, val content: String)

    private fun extractDanmuInfo(node: AccessibilityNodeInfo): DanmuInfo? {
        val childCount = node.childCount
        if (childCount < 2) return null

        val texts = mutableListOf<String>()
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                texts.add(text)
            }
        }

        // 弹幕结构通常是: 第一个子控件=用户名, 第二个子控件=弹幕内容
        if (texts.size >= 2) {
            val user = texts[0]
            val content = texts[1]
            // 验证: 用户名不应太长，内容不应为空
            if (user.length in 1..20 && content.isNotEmpty() && isDanmuText(content)) {
                return DanmuInfo(user, content)
            }
        }

        // 如果只有1个文本，且看起来像弹幕格式 "用户名:内容" 或 "用户名：内容"
        if (texts.size == 1) {
            val text = texts[0]
            val separators = listOf(": ", "：", ": ")
            for (sep in separators) {
                val idx = text.indexOf(sep)
                if (idx in 1..20) {
                    val user = text.substring(0, idx).trim()
                    val content = text.substring(idx + sep.length).trim()
                    if (content.isNotEmpty() && isDanmuText(content)) {
                        return DanmuInfo(user, content)
                    }
                }
            }
        }

        return null
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

    /**
     * 综合判断文本是否是弹幕内容
     */
    private fun isDanmuText(text: String): Boolean {
        if (text.length < 2) return false
        if (text.length > 100) return false  // 弹幕一般不会太长

        // 检查系统消息关键词
        for (keyword in systemKeywords) {
            if (text.contains(keyword)) return false
        }

        // 检查礼物关键词
        for (keyword in giftKeywords) {
            if (text.contains(keyword)) return false
        }

        // 检查个人信息关键词
        for (keyword in profileKeywords) {
            if (text.contains(keyword)) return false
        }

        // 过滤纯数字（如在线人数）
        if (text.matches(Regex("^[\\d,.万]+$"))) return false

        // 过滤只包含表情/特殊符号的文本
        if (text.matches(Regex("^[\\p{So}\\p{Cn}\\s]+$"))) return false

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