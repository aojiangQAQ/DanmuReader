package com.danmureader

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class FloatingWindowManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isShowing = false
    private var isCollapsed = false
    private var ttsManager: TtsManager? = null
    private var service: DanmuAccessibilityService? = null

    private var tvDanmuCount: TextView? = null
    private var tvSkippedCount: TextView? = null
    private var tvSpeed: TextView? = null
    private var btnPlayPause: ImageButton? = null
    private var layoutExpanded: LinearLayout? = null
    private var layoutCollapsed: LinearLayout? = null
    private var tvCollapsedInfo: TextView? = null

    fun setTtsManager(manager: TtsManager) { ttsManager = manager }
    fun setService(svc: DanmuAccessibilityService) { service = svc }

    fun show() {
        if (isShowing) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_control, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        initViews()
        setupDrag(params)
        windowManager?.addView(floatingView, params)
        isShowing = true
    }

    private fun initViews() {
        floatingView?.let { view ->
            tvDanmuCount = view.findViewById(R.id.tvDanmuCount)
            tvSkippedCount = view.findViewById(R.id.tvSkippedCount)
            tvSpeed = view.findViewById(R.id.tvSpeed)
            btnPlayPause = view.findViewById(R.id.btnPlayPause)
            val btnSpeedUp = view.findViewById<ImageButton>(R.id.btnSpeedUp)
            val btnSpeedDown = view.findViewById<ImageButton>(R.id.btnSpeedDown)
            val btnSkipLatest = view.findViewById<View>(R.id.btnSkipLatest)
            val btnToggle = view.findViewById<View>(R.id.btnToggle)
            val btnToggleCollapsed = view.findViewById<View>(R.id.btnToggleCollapsed)
            layoutExpanded = view.findViewById(R.id.layoutExpanded)
            layoutCollapsed = view.findViewById(R.id.layoutCollapsed)
            tvCollapsedInfo = view.findViewById(R.id.tvCollapsedInfo)

            btnToggle?.setOnClickListener { toggleCollapse() }
            btnToggleCollapsed?.setOnClickListener { toggleCollapse() }

            view.findViewById<View>(R.id.btnCollapsedPlayPause)?.setOnClickListener {
                ttsManager?.let { tts -> if (tts.isPaused()) tts.resume() else tts.pause() }
                updatePlayPauseIcon()
            }

            btnPlayPause?.setOnClickListener {
                ttsManager?.let { tts -> if (tts.isPaused()) tts.resume() else tts.pause() }
                updatePlayPauseIcon()
            }

            btnSpeedUp?.setOnClickListener {
                ttsManager?.let { tts -> tvSpeed?.text = "%.2fx".format(tts.speedUp()) }
            }

            btnSpeedDown?.setOnClickListener {
                ttsManager?.let { tts -> tvSpeed?.text = "%.2fx".format(tts.speedDown()) }
            }

            btnSkipLatest?.setOnClickListener { service?.skipToLatest() }

            tvSpeed?.text = "%.2fx".format(ttsManager?.getCurrentSpeed() ?: 1.5f)

            isCollapsed = false
            layoutExpanded?.visibility = View.VISIBLE
            layoutCollapsed?.visibility = View.GONE
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) {
            layoutExpanded?.visibility = View.GONE
            layoutCollapsed?.visibility = View.VISIBLE
            updateCollapsedInfo()
        } else {
            layoutExpanded?.visibility = View.VISIBLE
            layoutCollapsed?.visibility = View.GONE
        }
    }

    private fun updateCollapsedInfo() {
        val count = ttsManager?.danmuCount ?: 0
        val paused = ttsManager?.isPaused() ?: false
        tvCollapsedInfo?.post { tvCollapsedInfo?.text = "弹幕 " + (if (paused) "暂停" else "朗读中") + " | " + count }
    }

    private fun updatePlayPauseIcon() {
        val isPaused = ttsManager?.isPaused() ?: false
        btnPlayPause?.setImageResource(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        val dragHandle = floatingView?.findViewById<View>(R.id.dragHandle) ?: return
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    fun updateDanmuCount(count: Long) {
        tvDanmuCount?.let { tv -> tv.post { tv.text = "已读:" + count } }
        if (isCollapsed) updateCollapsedInfo()
    }

    fun updateSkippedCount(count: Long) {
        tvSkippedCount?.let { tv -> tv.post { tv.text = "跳过:" + count } }
    }

    fun hide() {
        if (!isShowing) return
        try { windowManager?.removeView(floatingView) } catch (_: Exception) {}
        floatingView = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing
}