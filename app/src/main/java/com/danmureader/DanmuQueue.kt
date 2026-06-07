package com.danmureader

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 弹幕去重队列
 * 使用滑动窗口去重，避免重复朗读同一条弹幕
 */
class DanmuQueue(private val maxHistorySize: Int = 100) {

    private val recentTexts = ConcurrentLinkedDeque<String>()
    private val lock = Any()

    /**
     * 检查弹幕是否重复，如果不重复则加入历史并返回 true
     */
    fun offer(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return false

        synchronized(lock) {
            // 检查是否已在近期历史中
            if (recentTexts.contains(cleaned)) {
                return false
            }
            // 加入历史
            recentTexts.addLast(cleaned)
            // 维护滑动窗口大小
            while (recentTexts.size > maxHistorySize) {
                recentTexts.removeFirst()
            }
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            recentTexts.clear()
        }
    }

    fun size(): Int = recentTexts.size
}
