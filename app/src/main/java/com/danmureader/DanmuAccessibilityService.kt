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
        private const val DOUYIN_LIVE_ACTIVITY_OLD = "LiveBroadcastActivity"
        private const val DOUYIN_LIVE_ACTIVITY_NEW = "LivePlayActivity"

        // 积压阈值：待朗读条数超过此值时开始跳过
        private const val BACKLOG_SKIP_THRESHOLD = 5
        // 最大积压阈值：超过此值则只保留最新几条
        private const val BACKLOG_MAX_THRESHOLD = 15

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
    private val processInterval = 200L
    private var eventCount = 0L
    private var lastLogTime = 0L
    private var isOnLivePage = false
    private var danmuReadTotal = 0L
    private var danmuSkipped = 0L

    // 系统/非弹幕关键词
    private val systemKeywords = listOf(
        "进入直播间", "关注了主播", "关注了", "点赞了", "送出", "送出了",
        "欢迎来到", "直播开始", "直播结束", "刚刚看过", "来了",
        "在线人数", "人观看", "万观看", "被管理员", "已被",
        "分享了直播间", "分享了", "加入了粉丝团", "粉丝团",
        "主播", "开播", "下播", "直播回放",
        "小时榜", "人气榜", "全国榜", "同城",
        "暂无更多", "加载中", "点击进入", "说:", "说："
    )

    private val giftKeywords = listOf(
        "送出", "礼物", "嘉年华", "火箭", "飞机", "跑车",
        "小心心", "棒棒糖", "奶茶", "啤酒", "玫瑰",
        "气球", "保时捷", "游轮", "城堡", "花海",
        "×", "¥", "金币", "钻石", "抖币"
    )

    private val profileKeywords = listOf(
        "的主页", "个人主页", "关注数", "粉丝数",
        "作品", "获赞", "抖音号", "举报", "拉黑",
        "设置备注", "私信", "发消息"
    )

    // 弹出面板特征关键词（表情面板、礼物面板、评论面板等弹出时会出现的文字）
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
        floatingWindow.setService(this)
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

            // 检查是否在直播页面
            val currentActivity = event.className?.toString() ?: ""
            isOnLivePage = currentActivity.contains(DOUYIN_LIVE_ACTIVITY_OLD) || currentActivity.contains(DOUYIN_LIVE_ACTIVITY_NEW)

            if (now - lastLogTime > 10000) {
                lastLogTime = now
                if (isOnLivePage) {
                    AppLogger.d(TAG, "直播页面运行中 | 事件:$eventCount | 已读:$danmuReadTotal | 跳过:$danmuSkipped")
                } else {
                    AppLogger.d(TAG, "非直播页面 ($currentActivity)")
                }
            }

            if (!isOnLivePage) return

            // 检查是否有弹出面板（表情、礼物等）
            if (isPopupPanelOpen(rootNode)) {
                AppLogger.d(TAG, "检测到弹出面板，暂停弹幕获取")
                return
            }

            // 查找弹幕 RecyclerView - 用多种方式
            val recyclerView = findDanmuRecyclerView(rootNode)
            if (recyclerView != null) {
                processDanmuRecyclerView(recyclerView)
            }

            // 智能处理积压
            handleBacklog()

        } catch (e: Exception) {
            AppLogger.e(TAG, "处理事件异常: ${e.message}")
        }
    }

    /**
     * 检测是否有弹出面板覆盖在直播页面上
     * 弹出面板出现时，底部弹幕区域通常会被遮挡或上移
     */
    private fun isPopupPanelOpen(root: AccessibilityNodeInfo): Boolean {
        // 检查根节点下是否有弹出面板的特征
        // 弹出面板通常是一个覆盖在上层的 ViewGroup
        return checkForPopupElements(root, 0)
    }

    private fun checkForPopupElements(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false  // 限制递归深度

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // 检查是否是弹窗/对话框/底部弹出面板
        if (className.contains("Dialog") || className.contains("Popup") ||
            className.contains("BottomSheet") || className.contains("Panel")) {
            // 进一步确认是抖音的弹出面板而不是系统弹窗
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            // 底部弹出面板通常从屏幕中下部开始
            if (rect.top > screenHeight * 0.3 && rect.height() > screenHeight * 0.2) {
                return true
            }
        }

        // 检查子节点中是否有弹出面板特征文本
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = child.text?.toString() ?: ""
            val childDesc = child.contentDescription?.toString() ?: ""
            val combined = childText + childDesc

            // 如果在屏幕下半部分发现了输入框，说明有评论/弹幕输入面板弹出
            if (child.isEditable) {
                val rect = android.graphics.Rect()
                child.getBoundsInScreen(rect)
                val screenHeight = resources.displayMetrics.heightPixels
                if (rect.top > screenHeight * 0.3) {
                    return true
                }
            }

            if (checkForPopupElements(child, depth + 1)) return true
        }

        return false
    }

    /**
     * 查找弹幕 RecyclerView
     * 优先用控件 ID，备用方案用位置 + 尺寸特征
     * 改进：支持弹幕区域位置变化（上移等情况）
     */
    private fun findDanmuRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 精确控件 ID
        val nodesById = root.findAccessibilityNodeInfosByViewId(DOUYIN_RECYCLERVIEW_ID)
        if (nodesById != null && nodesById.isNotEmpty()) {
            return nodesById[0]
        }

        // 方法2: 位置 + 尺寸特征匹配
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        findRecyclerViews(root, recyclerViews)

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        // 按优先级排序：越靠近弹幕典型位置的优先级越高
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = -1f

        for (rv in recyclerViews) {
            val rect = android.graphics.Rect()
            rv.getBoundsInScreen(rect)

            val widthRatio = rect.width().toFloat() / screenWidth
            val topRatio = rect.top.toFloat() / screenHeight

            // 弹幕区域特征评分：
            // - 宽度占屏幕 30%-95%
            // - 顶部在屏幕 15%-85% 范围内（支持上移）
            // - 不是全屏高度（排除主列表）
            // - 子控件数合理（弹幕一般有多个子项）
            if (widthRatio < 0.25 || widthRatio > 0.98) continue
            if (rect.height() > screenHeight * 0.85) continue  // 太高，可能是主列表
            if (rect.height() < 50) continue  // 太矮，不是弹幕区
            if (topRatio > 0.9) continue  // 太靠下

            val childCount = rv.childCount
            if (childCount < 1) continue

            // 计算评分：越接近典型弹幕位置分数越高
            var score = 0f
            // 典型弹幕区域在屏幕 50%-80% 的位置
            val typicalTop = 0.50f
            val distanceFromTypical = Math.abs(topRatio - typicalTop)
            score += (1f - distanceFromTypical) * 3f  // 距离典型位置越近分越高

            // 宽度适中加分（弹幕不会太宽也不会太窄）
            if (widthRatio in 0.4f..0.85f) score += 2f

            // 子控件数量合理加分
            if (childCount in 2..20) score += 1f

            if (score > bestScore) {
                bestScore = score
                bestMatch = rv
            }
        }

        return bestMatch
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

            // 尝试结构化解析
            val danmuInfo = extractDanmuInfo(child)
            if (danmuInfo != null) {
                val fullText = "${danmuInfo.user} 说 ${danmuInfo.content}"
                if (danmuQueue.offer(fullText)) {
                    AppLogger.d(TAG, "弹幕: $fullText")
                    ttsManager.speak(fullText)
                    danmuReadTotal++
                    floatingWindow.updateDanmuCount(danmuReadTotal)
                }
            } else {
                val text = extractTextFromNode(child)
                if (text.isNotEmpty() && isDanmuText(text)) {
                    if (danmuQueue.offer(text)) {
                        AppLogger.d(TAG, "弹幕(降级): $text")
                        ttsManager.speak(text)
                        danmuReadTotal++
                        floatingWindow.updateDanmuCount(danmuReadTotal)
                    }
                }
            }
        }
    }

    /**
     * 智能处理积压
     * 当待朗读队列过长时，跳过中间的弹幕，只保留最新的
     */
    private fun handleBacklog() {
        val pending = ttsManager.getPendingCount()

        if (pending > BACKLOG_MAX_THRESHOLD) {
            // 积压严重：清空队列，只保留最新几条
            val skipped = ttsManager.trimQueue(2)
            danmuSkipped += skipped
            AppLogger.w(TAG, "积压严重(" + pending + "条)，跳过" + skipped + "条旧弹幕")
            floatingWindow.updateSkippedCount(danmuSkipped)
        } else if (pending > BACKLOG_SKIP_THRESHOLD) {
            // 积压中等：加速语速
            ttsManager.autoSpeedUp()
        } else if (pending <= 2) {
            // 积压缓解：恢复正常语速
            ttsManager.autoSpeedRestore()
        }
    }

    /**
     * "跳到最新"功能 - 清空队列，从最新弹幕开始
     */
    fun skipToLatest() {
        val skipped = ttsManager.clearAndStop()
        danmuSkipped += skipped
        AppLogger.i(TAG, "用户手动跳到最新，跳过" + skipped + "条")
        floatingWindow.updateSkippedCount(danmuSkipped)
    }

    private data class DanmuInfo(val user: String, val content: String)

    private fun extractDanmuInfo(node: AccessibilityNodeInfo): DanmuInfo? {
        val childCount = node.childCount
        if (childCount < 2) return null

        val texts = mutableListOf<String>()
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) texts.add(text)
        }

        if (texts.size >= 2) {
            val user = texts[0]
            val content = texts[1]
            if (user.length in 1..20 && content.isNotEmpty() && isDanmuText(content)) {
                return DanmuInfo(user, content)
            }
        }

        if (texts.size == 1) {
            val text = texts[0]
            for (sep in listOf(": ", "：", ": ")) {
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
        if (text.matches(Regex("^[\\d,.万]+$"))) return false
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