package com.builtbymac.ringshield

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import org.webrtc.EglBase

/**
 * Apple TV-grade, zero-glitch 60 fps pure-video overlay for Nvidia Shield Pro.
 *
 * Why this never jumps, clips, or stutters:
 *  - The transparent WindowManager host is created ONCE at exact full-screen dimensions
 *    (MATCH_PARENT x MATCH_PARENT) and NEVER resized.
 *  - In PiP mode, FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE makes the transparent area 100%
 *    pass-through to YouTube/Netflix/Dolby Vision apps.
 *  - The video card itself stays laid out at full display size (1920x1080) and is positioned
 *    into the corner PiP rect via pure GPU transform matrices (pivot 0,0 + scaleX/Y + transX/Y).
 *  - Entrance, PiP -> Fullscreen, Fullscreen -> PiP, and Exit are therefore pure 60 fps OpenGL
 *    transform animations with ZERO WindowManager layout resizes and ZERO EGL surface reallocations!
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterEase = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val morphEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val exitEase = PathInterpolator(0.4f, 0f, 1f, 1f)

    private var hostWindow: FrameLayout? = null
    private var scrimView: View? = null
    private var cardView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var screenW: Int = 1920
    private var screenH: Int = 1080
    private var currentCornerRadiusPx: Float = 0f

    private var dotAnimator: ObjectAnimator? = null
    private var cornerAnimator: ValueAnimator? = null
    private var dismissRunnable: Runnable? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var onKeepAliveCallback: (() -> Unit)? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    @Volatile
    var isFullscreen: Boolean = false
        private set

    @Volatile
    var isKeepAlive: Boolean = false
        private set

    private var isHiding: Boolean = false

    fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun showOverlay(
        cfg: PocConfig,
        eglContext: EglBase.Context,
        startFullscreen: Boolean = false,
        keepAliveUntilDismissed: Boolean = false,
        onFirstFrameVisible: () -> Unit,
        onKeepAliveActivated: () -> Unit = {},
        onAutoDismiss: () -> Unit,
    ): TextureViewRenderer? {
        if (!canDrawOverlays()) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot display overlay")
            return null
        }

        hideImmediate()

        val (sw, sh) = getScreenSize()
        screenW = sw
        screenH = sh
        currentCfg = cfg
        onDismissCallback = onAutoDismiss
        onKeepAliveCallback = onKeepAliveActivated
        isFullscreen = startFullscreen
        isKeepAlive = keepAliveUntilDismissed || startFullscreen
        isShowing = true
        isHiding = false

        val pip = computePipTransform(cfg)
        currentCornerRadiusPx = if (startFullscreen) 0f else pip.unscaledCornerRadius

        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (isFullscreen && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            dismissAnimated()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            setFullscreen(false)
                            return true
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val scrim = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        scrimView = scrim
        host.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
            elevation = if (startFullscreen) 0f else dp(18).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, currentCornerRadiusPx)
                }
            }
            clipToOutline = true
        }

        val videoView = TextureViewRenderer(context).apply {
            init(
                eglContext = eglContext,
                onFirstFrame = {
                    mainHandler.post {
                        if (hostWindow === host && !isHiding) {
                            animateEntrance(card, scrim, pip, startFullscreen)
                            onFirstFrameVisible()
                        }
                    }
                },
            )
        }

        card.addView(
            videoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        )

        if (cfg.showLiveDot) {
            card.addView(buildSubtleLiveDot(pip.scaleX))
        }

        // Pre-position card at exact target rect BEFORE adding window (stays alpha=0 until first frame)
        if (startFullscreen) {
            card.scaleX = 1f
            card.scaleY = 1f
            card.translationX = 0f
            card.translationY = 0f
        } else {
            card.scaleX = pip.scaleX
            card.scaleY = pip.scaleY
            card.translationX = pip.x
            card.translationY = pip.y
        }

        host.addView(card, FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START))

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsFor(fullscreen = startFullscreen),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "ring-shield-overlay"
        }

        wm.addView(host, params)
        hostWindow = host
        cardView = card
        rootParams = params
        renderer = videoView

        if (startFullscreen) {
            host.isFocusable = true
            host.isFocusableInTouchMode = true
            host.requestFocus()
        }

        scheduleAutoDismiss(if (isKeepAlive) KEEP_ALIVE_SAFETY_CAP_MS else cfg.overlayDurationSec * 1000L)
        return videoView
    }

    fun toggleFullscreen() {
        mainHandler.post {
            if (!isShowing || isHiding) return@post
            setFullscreen(!isFullscreen)
        }
    }

    /**
     * Pure 60 fps GPU transform-matrix PiP <-> Fullscreen morph.
     * Window size NEVER changes — only focus/touch pass-through flags toggle cleanly!
     */
    fun setFullscreen(expand: Boolean) {
        mainHandler.post {
            val host = hostWindow ?: return@post
            val card = cardView ?: return@post
            val scrim = scrimView ?: return@post
            val params = rootParams ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isFullscreen == expand) return@post

            isFullscreen = expand
            card.animate().cancel()
            scrim.animate().cancel()
            cornerAnimator?.cancel()

            if (expand && !isKeepAlive) {
                isKeepAlive = true
                scheduleAutoDismiss(KEEP_ALIVE_SAFETY_CAP_MS)
                onKeepAliveCallback?.invoke()
            }

            // Update only focus/touch flags (window geometry stays locked at 1920x1080 -> zero glitch!)
            params.flags = flagsFor(fullscreen = expand)
            runCatching { wm.updateViewLayout(host, params) }
            if (expand) {
                host.isFocusable = true
                host.isFocusableInTouchMode = true
                host.requestFocus()
            } else {
                host.clearFocus()
                host.isFocusable = false
                host.isFocusableInTouchMode = false
            }

            val pip = computePipTransform(cfg)
            card.pivotX = 0f
            card.pivotY = 0f

            if (expand) {
                card.elevation = 0f
                scrim.animate()
                    .alpha(0.95f)
                    .setDuration(320L)
                    .setInterpolator(morphEase)
                    .start()

                animateCornerRadius(card, currentCornerRadiusPx, 0f, 340L)

                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(360L)
                    .setInterpolator(morphEase)
                    .start()
            } else {
                card.elevation = dp(18).toFloat()
                scrim.animate()
                    .alpha(0f)
                    .setDuration(260L)
                    .setInterpolator(morphEase)
                    .start()

                animateCornerRadius(card, currentCornerRadiusPx, pip.unscaledCornerRadius, 300L)

                card.animate()
                    .scaleX(pip.scaleX)
                    .scaleY(pip.scaleY)
                    .translationX(pip.x)
                    .translationY(pip.y)
                    .setDuration(320L)
                    .setInterpolator(morphEase)
                    .start()
            }
        }
    }

    fun dismissAnimated() {
        mainHandler.post {
            val host = hostWindow
            val card = cardView
            val scrim = scrimView
            val cfg = currentCfg
            if (host == null || card == null || cfg == null || isHiding) {
                hideImmediate()
                onDismissCallback?.invoke()
                return@post
            }
            isHiding = true
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null
            card.animate().cancel()
            scrim?.animate()?.cancel()
            cornerAnimator?.cancel()

            scrim?.animate()?.alpha(0f)?.setDuration(220L)?.setInterpolator(exitEase)?.start()

            if (isFullscreen) {
                card.pivotX = screenW / 2f
                card.pivotY = screenH / 2f
                card.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(220L)
                    .setInterpolator(exitEase)
                    .withEndAction {
                        val cb = onDismissCallback
                        hideImmediate()
                        cb?.invoke()
                    }
                    .start()
            } else {
                val pip = computePipTransform(cfg)
                val slideOutX = pip.x + edgeSlideDeltaPx(cfg) * 0.85f
                card.pivotX = 0f
                card.pivotY = 0f
                card.animate()
                    .alpha(0f)
                    .translationX(slideOutX)
                    .scaleX(pip.scaleX * 0.94f)
                    .scaleY(pip.scaleY * 0.94f)
                    .setDuration(240L)
                    .setInterpolator(exitEase)
                    .withEndAction {
                        val cb = onDismissCallback
                        hideImmediate()
                        cb?.invoke()
                    }
                    .start()
            }
        }
    }

    fun hideImmediate() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        cornerAnimator?.cancel()
        cornerAnimator = null
        dotAnimator?.cancel()
        dotAnimator = null
        onDismissCallback = null
        onKeepAliveCallback = null

        val r = renderer
        renderer = null
        runCatching { r?.release() }

        val h = hostWindow
        hostWindow = null
        scrimView = null
        cardView = null
        rootParams = null
        currentCfg = null
        isShowing = false
        isFullscreen = false
        isKeepAlive = false
        isHiding = false

        if (h != null) {
            runCatching { h.animate().cancel() }
            runCatching { h.removeAllViews() }
            runCatching { wm.removeViewImmediate(h) }
        }
    }

    private fun animateEntrance(card: FrameLayout, scrim: View, pip: PipTransform, fullscreen: Boolean) {
        val cfg = currentCfg ?: return
        if (fullscreen) {
            scrim.alpha = 0f
            scrim.animate().alpha(0.95f).setDuration(280L).setInterpolator(enterEase).start()

            card.pivotX = screenW / 2f
            card.pivotY = screenH / 2f
            card.translationX = 0f
            card.translationY = 0f
            card.scaleX = 0.95f
            card.scaleY = 0.95f
            card.alpha = 0f
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(enterEase)
                .start()
            return
        }

        // Silky Apple TV slide-and-settle from corner edge on the unclipped full-screen GPU canvas
        val startScaleX = pip.scaleX * 0.92f
        val startScaleY = pip.scaleY * 0.92f
        val startX = pip.x + edgeSlideDeltaPx(cfg)
        val startY = pip.y + (cfg.overlayHeightPx * 0.04f)

        card.pivotX = 0f
        card.pivotY = 0f
        card.translationX = startX
        card.translationY = startY
        card.scaleX = startScaleX
        card.scaleY = startScaleY
        card.alpha = 0f

        card.animate()
            .alpha(1f)
            .translationX(pip.x)
            .translationY(pip.y)
            .scaleX(pip.scaleX)
            .scaleY(pip.scaleY)
            .setDuration(360L)
            .setInterpolator(enterEase)
            .start()
    }

    private fun animateCornerRadius(card: FrameLayout, from: Float, to: Float, durationMs: Long) {
        cornerAnimator?.cancel()
        cornerAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = morphEase
            addUpdateListener { anim ->
                currentCornerRadiusPx = anim.animatedValue as Float
                card.invalidateOutline()
            }
            start()
        }
    }

    private fun buildSubtleLiveDot(pipScale: Float): View {
        // Scale up inversely so the dot looks crisp (~7 dp) when scaled down into corner PiP
        val inv = (1f / pipScale.coerceAtLeast(0.2f))
        val size = (dp(7) * inv).toInt()
        val margin = (dp(12) * inv).toInt()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF3B30"))
            }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(margin, margin, 0, 0)
            }
        }
        dotAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        return dot
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { dismissAnimated() }
        dismissRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private data class PipTransform(
        val x: Float,
        val y: Float,
        val scaleX: Float,
        val scaleY: Float,
        val unscaledCornerRadius: Float,
    )

    private fun computePipTransform(cfg: PocConfig): PipTransform {
        val m = dp(PIP_MARGIN_DP).toFloat()
        val sx = cfg.overlayWidthPx.toFloat() / screenW.toFloat()
        val sy = cfg.overlayHeightPx.toFloat() / screenH.toFloat()
        val x = when (cfg.corner) {
            OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT -> screenW - cfg.overlayWidthPx - m
            OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT -> m
        }
        val y = when (cfg.corner) {
            OverlayCorner.TOP_RIGHT, OverlayCorner.TOP_LEFT -> m
            OverlayCorner.BOTTOM_RIGHT, OverlayCorner.BOTTOM_LEFT -> screenH - cfg.overlayHeightPx - m
        }
        return PipTransform(
            x = x,
            y = y,
            scaleX = sx,
            scaleY = sy,
            unscaledCornerRadius = PIP_VISUAL_CORNER_RADIUS_PX / sx.coerceAtLeast(0.1f),
        )
    }

    private fun edgeSlideDeltaPx(cfg: PocConfig): Float {
        val isLeft = cfg.corner == OverlayCorner.TOP_LEFT || cfg.corner == OverlayCorner.BOTTOM_LEFT
        val delta = dp(48).toFloat()
        return if (isLeft) -delta else delta
    }

    private fun flagsFor(fullscreen: Boolean): Int = if (fullscreen) {
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    } else {
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    @Suppress("DEPRECATION")
    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "OverlayManager"
        private const val PIP_VISUAL_CORNER_RADIUS_PX = 20f
        private const val PIP_MARGIN_DP = 24
        private const val KEEP_ALIVE_SAFETY_CAP_MS = 5 * 60 * 1000L
    }
}
