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
        private const val BACKLOG_SKIP_THRESHOLD = 5
        private const val BACKLOG_MAX_THRESHOLD = 15

        fun isServiceRunning(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            for (service in enabledServices) {
                if (service.resolveInfo.serviceInfo.packageName == context.packageName) return true
            }
            return false
        }
    }

    private lateinit var ttsManager: TtsManager
    private lateinit var danmuQueue: DanmuQueue
    private lateinit var floatingWindow: FloatingWindowManager
    private var isRunning = false
    private var lastProcessTime = 0L
    private val processInterval = 200L
    private var danmuSkipped = 0L

    // 福袋刷屏去重
    private val recentContentMap = LinkedHashMap<String, Long>(64, 0.75f, true)
    private val spamTimeWindow = 10000L
    private val spamMinCount = 3

    private val systemKeywords = listOf(
        "进入直播间", "关注了主播", "关注了", "点赞了", "送出", "送出了",
        "欢迎来到", "直播开始", "直播结束", "刚刚看过", "来了",
        "在线人数", "人观看", "万观看", "被管理员", "已被",
        "分享了直播间", "分享了", "加入了粉丝团", "粉丝团",
        "主播", "开播", "下播", "直播回放", "直播中",
        "小时榜", "人气榜", "全国榜", "同城",
        "暂无更多", "加载中", "点击进入", "限时日常",
        "福袋", "领取", "参与", "送给", "人气票", "为你闪耀"
    )

    private val giftKeywords = listOf(
        "送出", "礼物", "嘉年华", "火箭", "飞机", "跑车",
        "小心心", "棒棒糖", "奶茶", "啤酒", "玫瑰",
        "气球", "保时捷", "游轮", "城堡", "花海",
        "金币", "钻石", "抖币", "小恶魔", "青绿画卷"
    )

    private val profileKeywords = listOf(
        "的主页", "个人主页", "关注数", "粉丝数",
        "作品", "获赞", "抖音号", "举报", "拉黑",
        "设置备注", "私信", "发消息"
    )

    private val popupPanelKeywords = listOf(
        "热门", "精选", "送礼", "背包", "特效",
        "发送", "评论", "输入", "说点什么",
        "表情", "贴纸", "滤镜", "美颜",
        "连麦", "PK", "购物车", "小黄车",
        "更多功能", "分享到", "保存", "收藏",
        "价格", "充值", "余额"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.i(TAG, "无障碍服务已连接")

        danmuQueue = DanmuQueue()
        ttsManager = TtsManager(this)
        floatingWindow = FloatingWindowManager(this)

        ttsManager.init { AppLogger.i(TAG, "TTS 引擎初始化成功") }
        ttsManager.onSpeakStart = { text -> AppLogger.i(TAG, "朗读: $text") }

        floatingWindow.setTtsManager(ttsManager)
        floatingWindow.setService(this)
        floatingWindow.show()

        isRunning = true
        AppLogger.i(TAG, "服务就绪")

        sendBroadcast(Intent("com.danmureader.SERVICE_STATUS_CHANGED").apply { setPackage(packageName) })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < processInterval) return
        lastProcessTime = now

        try {
            val packageName = event.packageName?.toString() ?: return
            if (packageName != "com.ss.android.ugc.aweme") return
            val rootNode = rootInActiveWindow ?: return
            if (isPopupPanelOpen(rootNode)) return
            val recyclerView = findDanmuRecyclerView(rootNode) ?: return
            processDanmuRecyclerView(recyclerView, now)
            handleBacklog()
        } catch (e: Exception) {
            AppLogger.e(TAG, "异常: " + e.message)
        }
    }

    private fun isPopupPanelOpen(root: AccessibilityNodeInfo): Boolean = checkForPopupElements(root, 0)

    private fun checkForPopupElements(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false
        val className = node.className?.toString() ?: ""
        if (className.contains("Dialog") || className.contains("Popup") ||
            className.contains("BottomSheet") || className.contains("Panel")) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            if (rect.top > screenHeight * 0.3 && rect.height() > screenHeight * 0.2) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) {
                val rect = android.graphics.Rect()
                child.getBoundsInScreen(rect)
                if (rect.top > resources.displayMetrics.heightPixels * 0.3) return true
            }
            if (checkForPopupElements(child, depth + 1)) return true
        }
        return false
    }

    private fun findDanmuRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodesById = root.findAccessibilityNodeInfosByViewId(DOUYIN_RECYCLERVIEW_ID)
        if (nodesById != null && nodesById.isNotEmpty()) return nodesById[0]

        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        findRecyclerViews(root, recyclerViews)

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = -1f

        for (rv in recyclerViews) {
            val rect = android.graphics.Rect()
            rv.getBoundsInScreen(rect)
            val widthRatio = rect.width().toFloat() / screenWidth
            val topRatio = rect.top.toFloat() / screenHeight
            if (widthRatio < 0.25 || widthRatio > 0.98) continue
            if (rect.height() > screenHeight * 0.85 || rect.height() < 50) continue
            if (topRatio > 0.9) continue
            if (rv.childCount < 1) continue
            var score = (1f - Math.abs(topRatio - 0.50f)) * 3f
            if (widthRatio in 0.4f..0.85f) score += 2f
            if (rv.childCount in 2..20) score += 1f
            if (score > bestScore) { bestScore = score; bestMatch = rv }
        }
        return bestMatch
    }

    private fun findRecyclerViews(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString()?.contains(RECYCLERVIEW_CLASS) == true) result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findRecyclerViews(child, result)
        }
    }

    private fun processDanmuRecyclerView(recyclerView: AccessibilityNodeInfo, now: Long) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChild(i) ?: continue
            val text = extractTextFromNode(child)
            if (text.isEmpty()) continue

            val parsed = parseDanmu(text) ?: continue
            val (user, content) = parsed

            // 过滤非弹幕
            if (!isDanmuText(content)) {
                AppLogger.d(TAG, "[过滤-非弹幕] " + user + ": " + content)
                continue
            }

            // 自定义屏蔽词检测
            val customWords = SettingsManager.getCustomBlockWords(this)
            if (customWords.any { content.contains(it) }) {
                AppLogger.d(TAG, "[跳过-屏蔽词] " + user + ": " + content)
                danmuSkipped++
                floatingWindow.updateSkippedCount(danmuSkipped)
                continue
            }

            // 福袋刷屏检测（仅在开关开启时生效）
            if (SettingsManager.isSpamFilterEnabled(this) && isSpamContent(content, now)) {
                AppLogger.d(TAG, "[跳过-刷屏] " + user + ": " + content)
                danmuSkipped++
                floatingWindow.updateSkippedCount(danmuSkipped)
                continue
            }

            val fullText = user + " 说 " + content
            if (danmuQueue.offer(fullText)) {
                AppLogger.d(TAG, "弹幕: " + fullText)
                ttsManager.speak(fullText)
            }
        }
    }

    private fun parseDanmu(raw: String): Pair<String, String>? {
        var text = raw.trim()
        text = text.replace("\u200E", "").replace("\u200F", "").replace("\u200B", "").trim()
        while (text.startsWith("*") || text.startsWith(" ")) {
            text = text.removePrefix("*").trim()
        }

        val separators = listOf("\uff1a", ": ", ":")
        for (sep in separators) {
            val idx = text.indexOf(sep)
            if (idx in 1..30) {
                val user = text.substring(0, idx).trim()
                val content = text.substring(idx + sep.length).trim()
                if (user.isNotEmpty() && content.isNotEmpty()) {
                    return Pair(user, content)
                }
            }
        }

        // 没有分隔符，检查是否是纯弹幕内容（无用户名）
        if (text.length >= 2) {
            // 不再默认为"观众"，直接返回null，因为没有用户名的大概率不是弹幕
            return null
        }
        return null
    }

    private fun isSpamContent(content: String, now: Long): Boolean {
        val iterator = recentContentMap.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > spamTimeWindow) iterator.remove()
        }
        recentContentMap[content] = now
        var count = 0
        for (entry in recentContentMap) {
            if (entry.key == content && now - entry.value <= spamTimeWindow) count++
        }
        return count >= spamMinCount
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrEmpty()) sb.append(nodeText)
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
        if (text.length < 2 || text.length > 100) return false
        for (kw in systemKeywords) { if (text.contains(kw)) return false }
        for (kw in giftKeywords) { if (text.contains(kw)) return false }
        for (kw in profileKeywords) { if (text.contains(kw)) return false }
        for (kw in popupPanelKeywords) { if (text.contains(kw)) return false }
        if (text.matches(Regex("^[\\d,.]+$"))) return false
        if (text.matches(Regex("^[\\p{So}\\p{Cn}\\s]+$"))) return false
        if (text.matches(Regex("^\\[.*\\]+$"))) return false
        return true
    }

    private fun handleBacklog() {
        val pending = ttsManager.getPendingCount()
        if (pending > BACKLOG_MAX_THRESHOLD) {
            val skipped = ttsManager.trimQueue(2)
            danmuSkipped += skipped
            floatingWindow.updateSkippedCount(danmuSkipped)
        } else if (pending > BACKLOG_SKIP_THRESHOLD) {
            ttsManager.autoSpeedUp()
        } else if (pending <= 2) {
            ttsManager.autoSpeedRestore()
        }
    }

    fun skipToLatest() {
        val skipped = ttsManager.clearAndStop()
        if (skipped > 0) {
            danmuSkipped += skipped
            floatingWindow.updateSkippedCount(danmuSkipped)
            AppLogger.i(TAG, "\u8DF3\u8FC7 " + skipped + " \u6761")
        }
    }

    override fun onInterrupt() { AppLogger.w(TAG, "服务被中断") }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ttsManager.destroy()
        floatingWindow.hide()
        AppLogger.i(TAG, "服务已销毁")
        sendBroadcast(Intent("com.danmureader.SERVICE_STATUS_CHANGED").apply { setPackage(packageName) })
    }
}