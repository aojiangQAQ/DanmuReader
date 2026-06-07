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
        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.layout_floating_control, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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
        setupDragListener(params)
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

            // 折叠/展开按钮
            btnToggle?.setOnClickListener { toggleCollapse() }
            btnToggleCollapsed?.setOnClickListener { toggleCollapse() }

            // 折叠状态的播放/暂停
            val btnCollapsedPlayPause = view.findViewById<View>(R.id.btnCollapsedPlayPause)
            btnCollapsedPlayPause?.setOnClickListener {
                ttsManager?.let { tts ->
                    if (tts.isPaused()) {
                        tts.resume()
                    } else {
                        tts.pause()
                    }
                    updatePlayPauseIcon()
                }
            }

            btnPlayPause?.setOnClickListener {
                ttsManager?.let { tts ->
                    if (tts.isPaused()) tts.resume() else tts.pause()
                    updatePlayPauseIcon()
                }
            }

            btnSpeedUp?.setOnClickListener {
                ttsManager?.let { tts ->
                    val speed = tts.speedUp()
                    tvSpeed?.text = "%.2fx".format(speed)
                }
            }

            btnSpeedDown?.setOnClickListener {
                ttsManager?.let { tts ->
                    val speed = tts.speedDown()
                    tvSpeed?.text = "%.2fx".format(speed)
                }
            }

            btnSkipLatest?.setOnClickListener {
                service?.skipToLatest()
            }

            tvSpeed?.text = "%.2fx".format(ttsManager?.getCurrentSpeed() ?: 1.5f)

            // 默认展开
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
        // 更新窗口大小
        floatingView?.post {
            try {
                val params = floatingView?.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    windowManager?.updateViewLayout(floatingView, params)
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateCollapsedInfo() {
        val count = ttsManager?.danmuCount ?: 0
        val paused = ttsManager?.isPaused() ?: false
        val status = if (paused) "暂停" else "朗读中"
        tvCollapsedInfo?.post {
            tvCollapsedInfo?.text = "弹幕 $status | $count"
        }
    }

    private fun updatePlayPauseIcon() {
        val isPaused = ttsManager?.isPaused() ?: false
        val icon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        btnPlayPause?.setImageResource(icon)
    }

    private fun setupDragListener(params: WindowManager.LayoutParams) {
        // 在展开和折叠布局上分别设置拖拽监听
        val dragTargets = listOfNotNull(layoutExpanded, layoutCollapsed)
        for (target in dragTargets) {
            target.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x; initialY = params.y
                            initialTouchX = event.rawX; initialTouchY = event.rawY
                            isDragging = false
                            return false  // 不消费，让子view也能收到
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (dx * dx + dy * dy > 100) isDragging = true
                            if (isDragging) {
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                windowManager?.updateViewLayout(floatingView, params)
                                return true
                            }
                            return false
                        }
                        MotionEvent.ACTION_UP -> {
                            return isDragging  // 只在拖拽时消费，否则让子view处理点击
                        }
                    }
                    return false
                }
            })
        }
    }

    fun updateDanmuCount(count: Long) {
        tvDanmuCount?.let { tv -> tv.post { tv.text = "已读:$count" } }
        if (isCollapsed) updateCollapsedInfo()
    }

    fun updateSkippedCount(count: Long) {
        tvSkippedCount?.let { tv -> tv.post { tv.text = "跳过:$count" } }
    }

    fun hide() {
        if (!isShowing) return
        try { windowManager?.removeView(floatingView) } catch (_: Exception) {}
        floatingView = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing
}