package com.builtbymac.ringshield

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import org.webrtc.EglBase

/**
 * Clean, text-free TV overlay for Nvidia Shield Pro:
 *  - Pure live video (with optional subtle 1.16x center-crop to hide Ring's burned-in dev watermark)
 *  - Minimalist pulsing red broadcast tally dot (zero noisy text)
 *  - Subtle bottom countdown progress hairline
 *  - Smooth tvOS-style slide+scale+fade entrance & exit animations
 *  - Smooth animated PiP <-> Fullscreen morph (click or Shield remote shortcut; Back closes in fullscreen)
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val easeOutExpo = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private var rootWindow: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var currentCornerRadiusPx: Float = PIP_CORNER_RADIUS_PX
    private var cardBg: GradientDrawable? = null

    private var pulseAnimator: AnimatorSet? = null
    private var countdownAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var dismissRunnable: Runnable? = null
    private var onDismissCallback: (() -> Unit)? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    @Volatile
    var isFullscreen: Boolean = false
        private set

    private var isHiding: Boolean = false

    fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun showOverlay(
        cfg: PocConfig,
        eglContext: EglBase.Context,
        startFullscreen: Boolean = false,
        onFirstFrameVisible: () -> Unit,
        onAutoDismiss: () -> Unit,
    ): TextureViewRenderer? {
        if (!canDrawOverlays()) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot display overlay")
            return null
        }

        hideImmediate()

        currentCfg = cfg
        onDismissCallback = onAutoDismiss
        isFullscreen = startFullscreen
        isShowing = true
        isHiding = false

        val (screenW, screenH) = getScreenSize()
        val initW = if (startFullscreen) screenW else cfg.overlayWidthPx
        val initH = if (startFullscreen) screenH else cfg.overlayHeightPx
        currentCornerRadiusPx = if (startFullscreen) 0f else PIP_CORNER_RADIUS_PX

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = currentCornerRadiusPx
            setColor(Color.parseColor("#FF080A0F"))
            setStroke(if (startFullscreen) 0 else 2, Color.parseColor("#3DFFFFFF"))
        }
        cardBg = bg

        val card = object : FrameLayout(context) {
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
            alpha = 0f
            elevation = 24f
            background = bg
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, currentCornerRadiusPx)
                }
            }
            clipToOutline = true
            setOnClickListener { toggleFullscreen() }
        }

        val videoView = TextureViewRenderer(context).apply {
            // Subtle 1.16x center-crop cleanly hides Ring's burned-in top/bottom dev watermarks
            val cropScale = if (cfg.cropRingWatermark) WATERMARK_CROP_SCALE else 1f
            scaleX = cropScale
            scaleY = cropScale

            init(
                eglContext = eglContext,
                onFirstFrame = {
                    mainHandler.post {
                        if (rootWindow === card && !isHiding) {
                            animateEntrance(card, cfg, startFullscreen)
                            startCountdownBar(cfg)
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
            card.addView(buildPulsingLiveDot())
        }

        if (cfg.showCountdownBar) {
            val bar = View(context).apply {
                tag = "countdown_bar"
                setBackgroundColor(Color.parseColor("#CCFF3B30"))
                pivotX = 0f
            }
            card.addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(3),
                    Gravity.BOTTOM or Gravity.START,
                )
            )
        }

        val (initX, initY) = if (startFullscreen) 0 to 0 else pipPosition(cfg, screenW, screenH)
        val flags = if (startFullscreen) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val params = WindowManager.LayoutParams(
            initW,
            initH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            title = "ring-shield-overlay"
        }

        wm.addView(card, params)
        rootWindow = card
        rootParams = params
        renderer = videoView

        if (startFullscreen) {
            card.isFocusable = true
            card.isFocusableInTouchMode = true
            card.requestFocus()
        }

        scheduleAutoDismiss(cfg.overlayDurationSec * 1000L)
        return videoView
    }

    fun toggleFullscreen() {
        mainHandler.post {
            if (!isShowing || isHiding) return@post
            setFullscreen(!isFullscreen)
        }
    }

    fun setFullscreen(expand: Boolean) {
        mainHandler.post {
            val card = rootWindow ?: return@post
            val params = rootParams ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isFullscreen == expand) return@post

            isFullscreen = expand
            morphAnimator?.cancel()

            val (screenW, screenH) = getScreenSize()
            val (pipX, pipY) = pipPosition(cfg, screenW, screenH)

            val startW = params.width
            val startH = params.height
            val startX = params.x
            val startY = params.y
            val startRadius = currentCornerRadiusPx

            val targetW = if (expand) screenW else cfg.overlayWidthPx
            val targetH = if (expand) screenH else cfg.overlayHeightPx
            val targetX = if (expand) 0 else pipX
            val targetY = if (expand) 0 else pipY
            val targetRadius = if (expand) 0f else PIP_CORNER_RADIUS_PX

            params.flags = if (expand) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            cardBg?.setStroke(if (expand) 0 else 2, Color.parseColor("#3DFFFFFF"))

            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                interpolator = easeOutExpo
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    if (rootWindow !== card) return@addUpdateListener
                    params.width = (startW + (targetW - startW) * f).toInt()
                    params.height = (startH + (targetH - startH) * f).toInt()
                    params.x = (startX + (targetX - startX) * f).toInt()
                    params.y = (startY + (targetY - startY) * f).toInt()
                    currentCornerRadiusPx = startRadius + (targetRadius - startRadius) * f
                    cardBg?.cornerRadius = currentCornerRadiusPx
                    card.invalidateOutline()
                    runCatching { wm.updateViewLayout(card, params) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (rootWindow !== card) return
                        if (expand) {
                            card.isFocusable = true
                            card.isFocusableInTouchMode = true
                            card.requestFocus()
                        } else {
                            card.clearFocus()
                            card.isFocusable = false
                            card.isFocusableInTouchMode = false
                        }
                    }
                })
                start()
            }
        }
    }

    fun dismissAnimated() {
        mainHandler.post {
            val card = rootWindow
            val cfg = currentCfg
            if (card == null || isHiding) {
                hideImmediate()
                onDismissCallback?.invoke()
                return@post
            }
            isHiding = true
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null
            morphAnimator?.cancel()

            val slideX = if (isFullscreen) 0f else edgeSlideDeltaX(cfg)
            card.animate()
                .alpha(0f)
                .scaleX(if (isFullscreen) 0.97f else 0.92f)
                .scaleY(if (isFullscreen) 0.97f else 0.92f)
                .translationX(slideX * 0.65f)
                .setDuration(220L)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .withEndAction {
                    val cb = onDismissCallback
                    hideImmediate()
                    cb?.invoke()
                }
                .start()
        }
    }

    fun hideImmediate() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        countdownAnimator?.cancel()
        countdownAnimator = null
        morphAnimator?.cancel()
        morphAnimator = null
        onDismissCallback = null

        val r = renderer
        renderer = null
        runCatching { r?.release() }

        val w = rootWindow
        rootWindow = null
        rootParams = null
        cardBg = null
        currentCfg = null
        isShowing = false
        isFullscreen = false
        isHiding = false

        if (w != null) {
            runCatching { w.animate().cancel() }
            runCatching { w.removeAllViews() }
            runCatching { wm.removeViewImmediate(w) }
        }
    }

    private fun animateEntrance(card: FrameLayout, cfg: PocConfig, fullscreen: Boolean) {
        val slideX = if (fullscreen) 0f else edgeSlideDeltaX(cfg)
        card.translationX = slideX
        card.scaleX = if (fullscreen) 0.96f else 0.90f
        card.scaleY = if (fullscreen) 0.96f else 0.90f
        card.alpha = 0f
        card.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320L)
            .setInterpolator(easeOutExpo)
            .start()
    }

    private fun buildPulsingLiveDot(): FrameLayout {
        val containerSize = dp(24)
        val dotSize = dp(10)
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                containerSize,
                containerSize,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(dp(12), dp(12), 0, 0)
            }
        }

        val halo = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3FF3B30"))
            }
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
        }

        val core = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF3B30"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
        }

        container.addView(halo)
        container.addView(core)

        val sx = ObjectAnimator.ofFloat(halo, View.SCALE_X, 1f, 2.1f).apply {
            repeatCount = ValueAnimator.INFINITE
            duration = 1200L
        }
        val sy = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1f, 2.1f).apply {
            repeatCount = ValueAnimator.INFINITE
            duration = 1200L
        }
        val al = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.85f, 0f).apply {
            repeatCount = ValueAnimator.INFINITE
            duration = 1200L
        }
        val corePulse = ObjectAnimator.ofFloat(core, View.ALPHA, 1f, 0.55f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE
            duration = 1200L
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(sx, sy, al, corePulse)
            interpolator = easeOutExpo
            start()
        }
        return container
    }

    private fun startCountdownBar(cfg: PocConfig) {
        val bar = rootWindow?.findViewWithTag<View>("countdown_bar") ?: return
        countdownAnimator?.cancel()
        bar.scaleX = 1f
        countdownAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = cfg.overlayDurationSec * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                bar.scaleX = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { dismissAnimated() }
        dismissRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun pipPosition(cfg: PocConfig, screenW: Int, screenH: Int): Pair<Int, Int> {
        val m = dp(PIP_MARGIN_DP)
        return when (cfg.corner) {
            OverlayCorner.TOP_RIGHT -> (screenW - cfg.overlayWidthPx - m) to m
            OverlayCorner.TOP_LEFT -> m to m
            OverlayCorner.BOTTOM_RIGHT -> (screenW - cfg.overlayWidthPx - m) to (screenH - cfg.overlayHeightPx - m)
            OverlayCorner.BOTTOM_LEFT -> m to (screenH - cfg.overlayHeightPx - m)
        }
    }

    private fun edgeSlideDeltaX(cfg: PocConfig?): Float {
        val isLeft = cfg?.corner == OverlayCorner.TOP_LEFT || cfg?.corner == OverlayCorner.BOTTOM_LEFT
        return if (isLeft) -dp(52).toFloat() else dp(52).toFloat()
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
        private const val PIP_CORNER_RADIUS_PX = 24f
        private const val PIP_MARGIN_DP = 20
        private const val WATERMARK_CROP_SCALE = 1.16f
    }
}
