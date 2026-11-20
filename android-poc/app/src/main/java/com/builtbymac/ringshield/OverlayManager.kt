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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import org.webrtc.EglBase

/**
 * Minimal, pure-video TV overlay for Nvidia Shield Pro.
 *
 * Animation discipline for Android WindowManager + TextureView:
 *  - Never clip scale/translate animations against a tight WindowManager bounding box:
 *    we pad the outer transparent host FrameLayout so the rounded card scales cleanly at 60 fps.
 *  - Never call wm.updateViewLayout() per frame during PiP <-> Fullscreen transitions
 *    (which forces EGL surface reallocations and janks); perform a single atomic layout swap
 *    paired with a fast GPU alpha settle.
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decel = DecelerateInterpolator(2.0f)

    private var hostWindow: FrameLayout? = null
    private var cardView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var currentCornerRadiusPx: Float = PIP_CORNER_RADIUS_PX
    private var cardBg: GradientDrawable? = null

    private var dotAnimator: ObjectAnimator? = null
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
        val pad = if (startFullscreen) 0 else dp(HOST_PADDING_DP)
        val winW = if (startFullscreen) screenW else cfg.overlayWidthPx + pad * 2
        val winH = if (startFullscreen) screenH else cfg.overlayHeightPx + pad * 2
        currentCornerRadiusPx = if (startFullscreen) 0f else PIP_CORNER_RADIUS_PX

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = currentCornerRadiusPx
            setColor(Color.BLACK)
        }
        cardBg = bg

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
            setPadding(pad, pad, pad, pad)
        }

        val card = FrameLayout(context).apply {
            alpha = 0f
            elevation = if (startFullscreen) 0f else dp(12).toFloat()
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
            init(
                eglContext = eglContext,
                onFirstFrame = {
                    mainHandler.post {
                        if (hostWindow === host && !isHiding) {
                            card.scaleX = if (startFullscreen) 1f else 0.96f
                            card.scaleY = if (startFullscreen) 1f else 0.96f
                            card.alpha = 0f
                            card.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(200L)
                                .setInterpolator(decel)
                                .start()
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
            card.addView(buildSubtleLiveDot())
        }

        host.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        val (initX, initY) = if (startFullscreen) 0 to 0 else pipPosition(cfg, screenW, screenH, pad)
        val flags = if (startFullscreen) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val params = WindowManager.LayoutParams(
            winW,
            winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
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
            val host = hostWindow ?: return@post
            val card = cardView ?: return@post
            val params = rootParams ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isFullscreen == expand) return@post

            isFullscreen = expand
            val (screenW, screenH) = getScreenSize()
            val pad = if (expand) 0 else dp(HOST_PADDING_DP)
            val (pipX, pipY) = pipPosition(cfg, screenW, screenH, pad)

            // Single atomic WindowManager layout update (zero EGL reallocation stutter)
            params.width = if (expand) screenW else cfg.overlayWidthPx + pad * 2
            params.height = if (expand) screenH else cfg.overlayHeightPx + pad * 2
            params.x = if (expand) 0 else pipX
            params.y = if (expand) 0 else pipY
            params.flags = if (expand) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            currentCornerRadiusPx = if (expand) 0f else PIP_CORNER_RADIUS_PX
            cardBg?.cornerRadius = currentCornerRadiusPx
            card.elevation = if (expand) 0f else dp(12).toFloat()
            host.setPadding(pad, pad, pad, pad)
            card.invalidateOutline()

            card.alpha = 0.85f
            runCatching { wm.updateViewLayout(host, params) }
            card.animate()
                .alpha(1f)
                .setDuration(140L)
                .setInterpolator(decel)
                .start()

            if (expand) {
                host.isFocusable = true
                host.isFocusableInTouchMode = true
                host.requestFocus()
            } else {
                host.clearFocus()
                host.isFocusable = false
                host.isFocusableInTouchMode = false
            }
        }
    }

    fun dismissAnimated() {
        mainHandler.post {
            val host = hostWindow
            val card = cardView
            if (host == null || card == null || isHiding) {
                hideImmediate()
                onDismissCallback?.invoke()
                return@post
            }
            isHiding = true
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null

            card.animate()
                .alpha(0f)
                .scaleX(if (isFullscreen) 1f else 0.96f)
                .scaleY(if (isFullscreen) 1f else 0.96f)
                .setDuration(160L)
                .setInterpolator(decel)
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
        dotAnimator?.cancel()
        dotAnimator = null
        onDismissCallback = null

        val r = renderer
        renderer = null
        runCatching { r?.release() }

        val h = hostWindow
        hostWindow = null
        cardView = null
        rootParams = null
        cardBg = null
        currentCfg = null
        isShowing = false
        isFullscreen = false
        isHiding = false

        if (h != null) {
            runCatching { h.animate().cancel() }
            runCatching { h.removeAllViews() }
            runCatching { wm.removeViewImmediate(h) }
        }
    }

    private fun buildSubtleLiveDot(): View {
        val size = dp(7)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF3B30"))
            }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(12), dp(12), 0, 0)
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

    private fun pipPosition(cfg: PocConfig, screenW: Int, screenH: Int, pad: Int): Pair<Int, Int> {
        val m = dp(PIP_MARGIN_DP) - pad
        return when (cfg.corner) {
            OverlayCorner.TOP_RIGHT -> (screenW - cfg.overlayWidthPx - pad * 2 - m) to m
            OverlayCorner.TOP_LEFT -> m to m
            OverlayCorner.BOTTOM_RIGHT -> (screenW - cfg.overlayWidthPx - pad * 2 - m) to (screenH - cfg.overlayHeightPx - pad * 2 - m)
            OverlayCorner.BOTTOM_LEFT -> m to (screenH - cfg.overlayHeightPx - pad * 2 - m)
        }
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
        private const val PIP_CORNER_RADIUS_PX = 18f
        private const val PIP_MARGIN_DP = 24
        private const val HOST_PADDING_DP = 16
    }
}
