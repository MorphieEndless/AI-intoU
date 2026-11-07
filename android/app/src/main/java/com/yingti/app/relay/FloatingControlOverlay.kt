package com.yingti.app.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.yingti.app.R
import com.yingti.app.ToyControl
import com.yingti.app.ui.UiPrefs
import kotlin.math.abs

/** 全局红色悬浮球：任何界面一键急停 / 暂停 / 继续。失败（无权限等）一律静默降级。 */
class FloatingControlOverlay(private val context: Context) {
    var onClick: () -> Unit = {}
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val size = (56 * density).toInt()
    private var view: ImageView? = null
    private var control = ToyControl.IDLE

    fun update(enabled: Boolean) {
        if (enabled && Settings.canDrawOverlays(context)) show() else hide()
    }

    fun setControl(control: ToyControl) {
        this.control = control
        view?.let { applyIcon(it) }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun show() {
        if (view != null) return
        runCatching {
            val ball = ImageView(context)
            val background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFB3261E.toInt())
            }
            ball.background = background
            ball.elevation = 6 * density
            val pad = (14 * density).toInt()
            ball.setPadding(pad, pad, pad, pad)
            applyIcon(ball)
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val metrics = context.resources.displayMetrics
                x = UiPrefs.overlayX(context).takeIf { it >= 0 } ?: (metrics.widthPixels - size - (16 * density).toInt())
                y = UiPrefs.overlayY(context).takeIf { it >= 0 } ?: metrics.heightPixels / 3
            }
            attachDrag(ball, params)
            windowManager.addView(ball, params)
            view = ball
        }
    }

    private fun applyIcon(ball: ImageView) {
        val (icon, description) = when (control) {
            ToyControl.RUNNING -> R.drawable.ic_overlay_pause to "暂停全部输出"
            ToyControl.PAUSED -> R.drawable.ic_overlay_play to "继续播放"
            ToyControl.IDLE -> R.drawable.ic_overlay_stop to "STOP ALL"
        }
        ball.setImageResource(icon)
        ball.contentDescription = description
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(ball: ImageView, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val metrics = context.resources.displayMetrics
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        ball.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && abs(dx) <= slop && abs(dy) <= slop) return@setOnTouchListener true
                    moved = true
                    params.x = (startX + dx.toInt()).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
                    params.y = (startY + dy.toInt()).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(ball, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) UiPrefs.setOverlayPosition(context, params.x, params.y)
                    else v.performClick()
                    true
                }
                else -> false
            }
        }
        ball.setOnClickListener { onClick() }
    }
}
