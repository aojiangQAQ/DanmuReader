package com.danmureader

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局日志管理器
 * 通过广播将日志发送到 MainActivity 显示，同时写入 Logcat
 */
object AppLogger {

    const val ACTION_LOG = "com.danmureader.LOG_MESSAGE"
    const val EXTRA_MESSAGE = "log_message"
    const val EXTRA_LEVEL = "log_level"

    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    private val history = CopyOnWriteArrayList<String>()
    private val maxHistory = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        addAndBroadcast(tag, msg, LEVEL_DEBUG)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        addAndBroadcast(tag, msg, LEVEL_INFO)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        addAndBroadcast(tag, msg, LEVEL_WARN)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        addAndBroadcast(tag, msg, LEVEL_ERROR)
    }

    private fun addAndBroadcast(tag: String, msg: String, level: Int) {
        val time = timeFormat.format(Date())
        val prefix = when (level) {
            LEVEL_DEBUG -> "D"
            LEVEL_INFO -> "I"
            LEVEL_WARN -> "W"
            LEVEL_ERROR -> "E"
            else -> "?"
        }
        val line = "[$time][$prefix/$tag] $msg"
        history.add(line)
        while (history.size > maxHistory) {
            history.removeAt(0)
        }
        // 发送广播
        try {
            val intent = Intent(ACTION_LOG).apply {
                putExtra(EXTRA_MESSAGE, line)
                putExtra(EXTRA_LEVEL, level)
                setPackage(appContext?.packageName)
            }
            appContext?.sendBroadcast(intent)
        } catch (e: Exception) {
            // ignore broadcast errors
        }
    }

    fun getHistory(): List<String> = history.toList()
}