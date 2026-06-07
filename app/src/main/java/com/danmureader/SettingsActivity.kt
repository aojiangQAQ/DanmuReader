package com.danmureader

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchSpamFilter: SwitchCompat
    private lateinit var etNewWord: EditText
    private lateinit var chipGroupWords: ChipGroup
    private lateinit var tvNoWords: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchSpamFilter = findViewById(R.id.switchSpamFilter)
        etNewWord = findViewById(R.id.etNewWord)
        chipGroupWords = findViewById(R.id.chipGroupWords)
        tvNoWords = findViewById(R.id.tvNoWords)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        switchSpamFilter.isChecked = SettingsManager.isSpamFilterEnabled(this)
        switchSpamFilter.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setSpamFilterEnabled(this, isChecked)
            AppLogger.i("Settings", if (isChecked) "福袋屏蔽已开启" else "福袋屏蔽已关闭")
        }

        findViewById<MaterialButton>(R.id.btnAddWord).setOnClickListener {
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
        chipGroupWords.removeAllViews()
        val words = SettingsManager.getCustomBlockWords(this)

        if (words.isEmpty()) {
            tvNoWords.visibility = View.VISIBLE
            chipGroupWords.visibility = View.GONE
            return
        }

        tvNoWords.visibility = View.GONE
        chipGroupWords.visibility = View.VISIBLE

        for (word in words) {
            val chip = Chip(this)
            chip.text = word
            chip.isCloseIconVisible = true
            chip.setChipBackgroundColorResource(R.color.bg_card_light)
            chip.setTextColor(getColor(R.color.text_primary))
            chip.setCloseIconTintResource(R.color.accent_red)
            chip.textSize = 13f

            chip.setOnCloseIconClickListener {
                SettingsManager.removeCustomBlockWord(this, word)
                refreshWordList()
                AppLogger.i("Settings", "删除屏蔽词: $word")
                Toast.makeText(this, "已删除: $word", Toast.LENGTH_SHORT).show()
            }

            chipGroupWords.addView(chip)
        }
    }
}