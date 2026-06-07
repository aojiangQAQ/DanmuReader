package com.danmureader

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "danmu_reader_settings"
    private const val KEY_SPAM_FILTER = "spam_filter_enabled"
    private const val KEY_CUSTOM_BLOCK_WORDS = "custom_block_words"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSpamFilterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPAM_FILTER, true)
    }

    fun setSpamFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPAM_FILTER, enabled).apply()
    }

    fun getCustomBlockWords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_BLOCK_WORDS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun setCustomBlockWords(context: Context, words: List<String>) {
        val cleaned = words.map { it.trim() }.filter { it.isNotEmpty() }
        prefs(context).edit().putString(KEY_CUSTOM_BLOCK_WORDS, cleaned.joinToString("|")).apply()
    }

    fun addCustomBlockWord(context: Context, word: String): Boolean {
        val current = getCustomBlockWords(context).toMutableList()
        val trimmed = word.trim()
        if (trimmed.isEmpty() || current.contains(trimmed)) return false
        current.add(trimmed)
        setCustomBlockWords(context, current)
        return true
    }

    fun removeCustomBlockWord(context: Context, word: String) {
        val current = getCustomBlockWords(context).toMutableList()
        current.remove(word.trim())
        setCustomBlockWords(context, current)
    }
}