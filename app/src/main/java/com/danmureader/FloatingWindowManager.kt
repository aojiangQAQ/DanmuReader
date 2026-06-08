package com.danmureader

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class FloatingWindowManager(private val context: Context) {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var showing = false
    private var collapsed = false
    private var ttsManager: TtsManager? = null
    private var service: DanmuAccessibilityService? = null

    private var tvCount: TextView? = null
    private var tvSkipped: TextView? = null
    private var tvSpeed: TextView? = null
    private var btnPlay: TextView? = null
    private var layoutControls: LinearLayout? = null
    private var layoutCollapsedInfo: LinearLayout? = null
    private var tvCollapsedCount: TextView? = null

    fun setTtsManager(m: TtsManager) { ttsManager = m }
    fun setService(s: DanmuAccessibilityService) { service = s }

    fun show() {
        if (showing) return
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        root = LayoutInflater.from(context).inflate(R.layout.layout_floating_control, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
        }

        bindViews()
        bindClicks()
        setupDrag(p)
        wm?.addView(root, p)
        showing = true
    }

    private fun bindViews() {
        root?.let { v ->
            tvCount = v.findViewById(R.id.tvDanmuCount)
            tvSkipped = v.findViewById(R.id.tvSkippedCount)
            tvSpeed = v.findViewById(R.id.tvSpeed)
            btnPlay = v.findViewById(R.id.btnPlayPause)
            layoutControls = v.findViewById(R.id.layoutControls)
            layoutCollapsedInfo = v.findViewById(R.id.layoutCollapsedInfo)
            tvCollapsedCount = v.findViewById(R.id.tvCollapsedCount)
            tvSpeed?.text = "%.1fx".format(ttsManager?.getCurrentSpeed() ?: 1.5f)
        }
    }

    private fun bindClicks() {
        root?.let { v ->
            v.findViewById<View>(R.id.btnToggle)?.setOnClickListener { toggle() }
            v.findViewById<View>(R.id.btnPlayPause)?.setOnClickListener {
                ttsManager?.let { t -> if (t.isPaused()) t.resume() else t.pause(); updateIcon() }
            }
            v.findViewById<View>(R.id.btnSpeedUp)?.setOnClickListener {
                ttsManager?.let { t -> tvSpeed?.text = "%.1fx".format(t.speedUp()) }
            }
            v.findViewById<View>(R.id.btnSpeedDown)?.setOnClickListener {
                ttsManager?.let { t -> tvSpeed?.text = "%.1fx".format(t.speedDown()) }
            }
            v.findViewById<View>(R.id.btnSkipLatest)?.setOnClickListener { service?.skipToLatest() }
            v.findViewById<View>(R.id.btnReplay)?.setOnClickListener {
                ttsManager?.replayLast()
            }
        }
    }

    private fun toggle() {
        collapsed = !collapsed
        if (collapsed) {
            layoutControls?.visibility = View.GONE
            layoutCollapsedInfo?.visibility = View.VISIBLE
            tvCollapsedCount?.text = (ttsManager?.danmuCount ?: 0).toString()
        } else {
            layoutControls?.visibility = View.VISIBLE
            layoutCollapsedInfo?.visibility = View.GONE
        }
    }

    private fun updateIcon() {
        val paused = ttsManager?.isPaused() ?: false
        btnPlay?.text = if (paused) "\u25B6" else "\u23F8"
    }

    private fun setupDrag(p: WindowManager.LayoutParams) {
        root?.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0
            private var tx = 0f; private var ty = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = p.x; iy = p.y; tx = e.rawX; ty = e.rawY; moved = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (!moved && dx * dx + dy * dy < 400) return false
                        moved = true
                        p.x = ix + dx.toInt(); p.y = iy + dy.toInt()
                        wm?.updateViewLayout(root, p)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        return moved
                    }
                }
                return false
            }
        })
    }

    fun updateDanmuCount(count: Long) {
        tvCount?.post { tvCount?.text = "\u5DF2\u8BFB $count" }
        if (collapsed) tvCollapsedCount?.post { tvCollapsedCount?.text = count.toString() }
    }

    fun updateSkippedCount(count: Long) {
        tvSkipped?.post { tvSkipped?.text = "\u8DF3\u8FC7 $count" }
    }

    fun hide() {
        if (!showing) return
        try { wm?.removeView(root) } catch (_: Exception) {}
        root = null; showing = false
    }

    fun isShowing(): Boolean = showing
}