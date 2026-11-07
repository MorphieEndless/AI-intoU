package com.yingti.app.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.yingti.app.AppState
import com.yingti.app.ToyControl
import com.yingti.app.ui.UiPrefs
import kotlin.math.abs

/** 全局红色悬浮球：任何界面（含应用挂后台时）一键急停 / 暂停 / 继续。 */
class FloatingControlOverlay(private val context: Context) {
    var onClick: () -> Unit = {}

    private val uiContext: Context = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else context
        } else context
    }.getOrElse { context }

    private val windowManager: WindowManager? =
        runCatching { uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull()
    private val density = uiContext.resources.displayMetrics.density.takeIf { it > 0f } ?: 3f
    private val size = (56 * density).toInt()
    private var view: OverlayBallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var control = ToyControl.IDLE

    val isShowing: Boolean get() = view != null

    fun update(enabled: Boolean) {
        if (enabled && canDrawOverlays()) show() else hide()
    }

    fun setControl(control: ToyControl) {
        this.control = control
        view?.let {
            it.contentDescription = descriptionFor(control)
            it.invalidate()
        }
    }

    fun hide() {
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        params = null
        AppState.update { if (it.systemOverlayShowing) it.copy(systemOverlayShowing = false) else it }
    }

    private fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun screenBounds(): Pair<Int, Int> {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            if (b != null && b.width() > 0 && b.height() > 0) {
                return b.width() to b.height()
            }
        }
        val dm = uiContext.resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else 1080
        val h = if (dm.heightPixels > 0) dm.heightPixels else 2400
        return w to h
    }

    private fun show() {
        val wm = windowManager ?: return
        val (screenW, screenH) = screenBounds()
        val maxX = (screenW - size).coerceAtLeast(0)
        val maxY = (screenH - size).coerceAtLeast(0)
        val defaultX = (screenW - size - (16 * density).toInt()).coerceIn(0, maxX)
        val defaultY = (screenH / 3).coerceIn(0, maxY)
        val targetX = UiPrefs.overlayX(context).takeIf { it >= 0 }?.coerceIn(0, maxX) ?: defaultX
        val targetY = UiPrefs.overlayY(context).takeIf { it >= 0 }?.coerceIn(0, maxY) ?: defaultY

        view?.let { existing ->
            val lp = params ?: return
            if (lp.x != targetX || lp.y != targetY) {
                lp.x = targetX
                lp.y = targetY
                runCatching { wm.updateViewLayout(existing, lp) }
            }
            AppState.update { if (!it.systemOverlayShowing) it.copy(systemOverlayShowing = true) else it }
            return
        }

        runCatching {
            val ball = OverlayBallView(uiContext).apply {
                elevation = 6 * density
                contentDescription = descriptionFor(control)
            }
            val lp = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = targetX
                y = targetY
            }
            attachDrag(ball, lp)
            wm.addView(ball, lp)
            view = ball
            params = lp
            AppState.update { if (!it.systemOverlayShowing) it.copy(systemOverlayShowing = true) else it }
        }.onFailure {
            view = null
            params = null
            AppState.update { if (it.systemOverlayShowing) it.copy(systemOverlayShowing = false) else it }
        }
    }

    private fun descriptionFor(ctrl: ToyControl): String = when (ctrl) {
        ToyControl.RUNNING -> "暂停全部输出"
        ToyControl.PAUSED -> "继续播放"
        ToyControl.IDLE -> "STOP ALL"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(ball: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        ball.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && abs(dx) <= slop && abs(dy) <= slop) return@setOnTouchListener true
                    moved = true
                    val (screenW, screenH) = screenBounds()
                    lp.x = (startX + dx.toInt()).coerceIn(0, (screenW - size).coerceAtLeast(0))
                    lp.y = (startY + dy.toInt()).coerceIn(0, (screenH - size).coerceAtLeast(0))
                    runCatching { windowManager?.updateViewLayout(ball, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) UiPrefs.setOverlayPosition(context, lp.x, lp.y)
                    else v.performClick()
                    true
                }
                else -> false
            }
        }
        ball.setOnClickListener { onClick() }
    }

    private inner class OverlayBallView(ctx: Context) : View(ctx) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFB3261E.toInt()
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = 0x33FFFFFF
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val rect = RectF()
        private val playPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(cx, cy) - density
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, borderPaint)

            val iconHalf = r * 0.38f
            val corner = 2f * density
            when (control) {
                ToyControl.RUNNING -> {
                    val barW = iconHalf * 0.52f
                    val gap = iconHalf * 0.34f
                    rect.set(cx - gap - barW, cy - iconHalf, cx - gap, cy + iconHalf)
                    canvas.drawRoundRect(rect, corner, corner, iconPaint)
                    rect.set(cx + gap, cy - iconHalf, cx + gap + barW, cy + iconHalf)
                    canvas.drawRoundRect(rect, corner, corner, iconPaint)
                }
                ToyControl.PAUSED -> {
                    playPath.reset()
                    val left = cx - iconHalf * 0.72f
                    val right = cx + iconHalf * 0.95f
                    playPath.moveTo(left, cy - iconHalf)
                    playPath.lineTo(left, cy + iconHalf)
                    playPath.lineTo(right, cy)
                    playPath.close()
                    canvas.drawPath(playPath, iconPaint)
                }
                ToyControl.IDLE -> {
                    val s = iconHalf * 0.85f
                    rect.set(cx - s, cy - s, cx + s, cy + s)
                    canvas.drawRoundRect(rect, corner, corner, iconPaint)
                }
            }
        }
    }
}
