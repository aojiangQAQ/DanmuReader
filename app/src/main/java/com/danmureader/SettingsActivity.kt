package com.danmureader

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchSpamFilter: SwitchCompat
    private lateinit var etNewWord: EditText
    private lateinit var btnAddWord: MaterialButton
    private lateinit var tvWordList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchSpamFilter = findViewById(R.id.switchSpamFilter)
        etNewWord = findViewById(R.id.etNewWord)
        btnAddWord = findViewById(R.id.btnAddWord)
        tvWordList = findViewById(R.id.tvWordList)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // 初始化开关状态
        switchSpamFilter.isChecked = SettingsManager.isSpamFilterEnabled(this)
        switchSpamFilter.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setSpamFilterEnabled(this, isChecked)
            AppLogger.i("Settings", if (isChecked) "福袋屏蔽已开启" else "福袋屏蔽已关闭")
        }

        // 添加屏蔽词
        btnAddWord.setOnClickListener {
            val word = etNewWord.text.toString().trim()
            if (word.isEmpty()) {
                Toast.makeText(this, "请输入屏蔽词", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (SettingsManager.addCustomBlockWord(this, word)) {
                etNewWord.text.clear()
                refreshWordList()
                AppLogger.i("Settings", "添加屏蔽词: $word")
                Toast.makeText(this, "已添加: $word", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "该屏蔽词已存在", Toast.LENGTH_SHORT).show()
            }
        }

        refreshWordList()
    }

    private fun refreshWordList() {
        val words = SettingsManager.getCustomBlockWords(this)
        if (words.isEmpty()) {
            tvWordList.text = "暂无屏蔽词"
            return
        }
        // 用 clickable span 显示，点击可删除
        val display = words.joinToString("\n") { "✕ $it" }
        tvWordList.text = display
        tvWordList.setOnClickListener {
            // 简化处理：长按删除全部，单击提示
        }
        tvWordList.setOnLongClickListener {
            SettingsManager.setCustomBlockWords(this, emptyList())
            refreshWordList()
            Toast.makeText(this, "已清除全部屏蔽词", Toast.LENGTH_SHORT).show()
            true
        }
    }
}