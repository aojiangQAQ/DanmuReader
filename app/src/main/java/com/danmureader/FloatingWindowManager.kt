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
    private var layoutExpanded: View? = null
    private var layoutCollapsed: View? = null
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
            x = 50
            y = 300
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
            layoutExpanded = view.findViewById(R.id.layoutExpanded)
            layoutCollapsed = view.findViewById(R.id.layoutCollapsed)
            tvCollapsedInfo = view.findViewById(R.id.tvCollapsedInfo)

            view.findViewById<View>(R.id.btnToggle)?.setOnClickListener { toggleCollapse() }
            view.findViewById<View>(R.id.btnToggleCollapsed)?.setOnClickListener { toggleCollapse() }

            view.findViewById<View>(R.id.btnCollapsedPlayPause)?.setOnClickListener {
                ttsManager?.let { tts -> if (tts.isPaused()) tts.resume() else tts.pause() }
                updatePlayPauseIcon()
            }

            btnPlayPause?.setOnClickListener {
                ttsManager?.let { tts -> if (tts.isPaused()) tts.resume() else tts.pause() }
                updatePlayPauseIcon()
            }

            view.findViewById<View>(R.id.btnSpeedUp)?.setOnClickListener {
                ttsManager?.let { tts -> tvSpeed?.text = "%.1fx".format(tts.speedUp()) }
            }

            view.findViewById<View>(R.id.btnSpeedDown)?.setOnClickListener {
                ttsManager?.let { tts -> tvSpeed?.text = "%.1fx".format(tts.speedDown()) }
            }

            view.findViewById<View>(R.id.btnSkipLatest)?.setOnClickListener { service?.skipToLatest() }

            tvSpeed?.text = "%.1fx".format(ttsManager?.getCurrentSpeed() ?: 1.5f)

            isCollapsed = false
            layoutExpanded?.visibility = View.VISIBLE
            layoutCollapsed?.visibility = View.GONE
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        layoutExpanded?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        layoutCollapsed?.visibility = if (isCollapsed) View.VISIBLE else View.GONE
        if (isCollapsed) updateCollapsedInfo()
    }

    private fun updateCollapsedInfo() {
        val count = ttsManager?.danmuCount ?: 0
        tvCollapsedInfo?.post { tvCollapsedInfo?.text = count.toString() }
    }

    private fun updatePlayPauseIcon() {
        val isPaused = ttsManager?.isPaused() ?: false
        btnPlayPause?.setImageResource(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        val dragHandle = floatingView?.findViewById<View>(R.id.dragHandle) ?: return
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0
            private var tx = 0f; private var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt(); windowManager?.updateViewLayout(floatingView, params); return true }
                }
                return false
            }
        })
    }

    fun updateDanmuCount(count: Long) {
        tvDanmuCount?.post { tvDanmuCount?.text = "已读 $count" }
        if (isCollapsed) tvCollapsedInfo?.post { tvCollapsedInfo?.text = count.toString() }
    }

    fun updateSkippedCount(count: Long) {
        tvSkippedCount?.post { tvSkippedCount?.text = "跳过 $count" }
    }

    fun hide() {
        if (!isShowing) return
        try { windowManager?.removeView(floatingView) } catch (_: Exception) {}
        floatingView = null; isShowing = false
    }

    fun isShowing(): Boolean = isShowing
}