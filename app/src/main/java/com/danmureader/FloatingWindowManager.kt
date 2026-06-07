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
import android.widget.TextView

/**
 * 悬浮窗管理器
 * 提供一个可拖拽的悬浮控制面板
 */
class FloatingWindowManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isShowing = false
    private var ttsManager: TtsManager? = null

    private var tvDanmuCount: TextView? = null
    private var tvSpeed: TextView? = null
    private var btnPlayPause: ImageButton? = null

    fun setTtsManager(manager: TtsManager) {
        ttsManager = manager
    }

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
            tvSpeed = view.findViewById(R.id.tvSpeed)
            btnPlayPause = view.findViewById(R.id.btnPlayPause)
            val btnSpeedUp = view.findViewById<ImageButton>(R.id.btnSpeedUp)
            val btnSpeedDown = view.findViewById<ImageButton>(R.id.btnSpeedDown)

            btnPlayPause?.setOnClickListener {
                ttsManager?.let { tts ->
                    if (tts.isPaused()) {
                        tts.resume()
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        tts.pause()
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                    }
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

            tvSpeed?.text = "%.2fx".format(ttsManager?.getCurrentSpeed() ?: 1.5f)
        }
    }

    private fun setupDragListener(params: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx * dx + dy * dy > 25) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    fun updateDanmuCount(count: Long) {
        tvDanmuCount?.let { tv ->
            tv.post { tv.text = "已读: ${count}条" }
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(floatingView)
        } catch (e: Exception) {
            // ignore
        }
        floatingView = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing
}
