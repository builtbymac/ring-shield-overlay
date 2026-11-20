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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import org.webrtc.EglBase

/**
 * Apple TV HomeKit-style pure-video overlay for Nvidia Shield Pro.
 *
 * Zero-glitch animation architecture:
 *  - Entrance / Exit: Synchronized single ViewPropertyAnimator (gentle 20 dp vertical lift +
 *    subtle 0.96->1.0 scale + alpha) with Apple ease-out curve (0.16, 1, 0.3, 1), running inside
 *    a padded transparent WindowManager host so nothing ever clips.
 *  - PiP <-> Fullscreen: Waits for OnPreDrawListener AFTER WindowManager finishes resizing
 *    before setting the exact initial GPU transform matrix (scaleX/scaleY/translationX/translationY)
 *    — guaranteeing pixel-exact frame 1 with zero jump, zero postage-stamp shrink, and locked 60 fps!
 *  - Continuous viewing: On-demand or expanded fullscreen cancels auto-dismiss until Back/Dismiss.
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appleEaseOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val morphEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val exitEase = PathInterpolator(0.4f, 0f, 1f, 1f)

    private var hostWindow: FrameLayout? = null
    private var scrimView: View? = null
    private var cardView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var currentCornerRadiusPx: Float = PIP_CORNER_RADIUS_PX
    private var cardBg: GradientDrawable? = null

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
    private var isMorphing: Boolean = false

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

        currentCfg = cfg
        onDismissCallback = onAutoDismiss
        onKeepAliveCallback = onKeepAliveActivated
        isFullscreen = startFullscreen
        isKeepAlive = keepAliveUntilDismissed || startFullscreen
        isShowing = true
        isHiding = false
        isMorphing = false

        val (screenW, screenH) = getScreenSize()
        val pad = if (startFullscreen) 0 else dp(HOST_PADDING_DP)
        val winW = if (startFullscreen) screenW else cfg.overlayWidthPx + pad * 2
        val winH = if (startFullscreen) screenH else cfg.overlayHeightPx + pad * 2
        currentCornerRadiusPx = if (startFullscreen) 0f else PIP_CORNER_RADIUS_PX

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = currentCornerRadiusPx
            setColor(Color.BLACK)
            setStroke(if (startFullscreen) 0 else dp(1), Color.parseColor("#33FFFFFF"))
        }
        cardBg = bg

        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if ((isFullscreen || isKeepAlive) && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            dismissAnimated()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (isFullscreen) {
                                setFullscreen(false)
                                return true
                            }
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
            visibility = if (startFullscreen) View.VISIBLE else View.GONE
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
            alpha = 0f
            elevation = if (startFullscreen) 0f else dp(16).toFloat()
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
                            animateEntrance(card, scrim, startFullscreen)
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

        val cardLp = if (startFullscreen) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        } else {
            FrameLayout.LayoutParams(cfg.overlayWidthPx, cfg.overlayHeightPx, Gravity.CENTER)
        }
        host.addView(card, cardLp)

        val (initX, initY) = if (startFullscreen) 0 to 0 else pipWindowOrigin(cfg, screenW, screenH, pad)
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

        if (!isKeepAlive) {
            scheduleAutoDismiss(cfg.overlayDurationSec * 1000L)
        } else {
            scheduleAutoDismiss(KEEP_ALIVE_SAFETY_CAP_MS)
        }

        return videoView
    }

    fun toggleFullscreen() {
        mainHandler.post {
            if (!isShowing || isHiding || isMorphing) return@post
            setFullscreen(!isFullscreen)
        }
    }

    /**
     * Pixel-exact 60 fps GPU PiP <-> Fullscreen morph.
     * Uses OnPreDrawListener so WindowManager layout finishes BEFORE frame 1 draws —
     * eliminating any postage-stamp shrink or coordinate jump!
     */
    fun setFullscreen(expand: Boolean) {
        mainHandler.post {
            val host = hostWindow ?: return@post
            val card = cardView ?: return@post
            val scrim = scrimView ?: return@post
            val params = rootParams ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isMorphing || isFullscreen == expand) return@post

            isMorphing = true
            isFullscreen = expand
            card.animate().cancel()
            scrim.animate().cancel()
            cornerAnimator?.cancel()

            if (expand && !isKeepAlive) {
                isKeepAlive = true
                scheduleAutoDismiss(KEEP_ALIVE_SAFETY_CAP_MS)
                onKeepAliveCallback?.invoke()
            }

            val (screenW, screenH) = getScreenSize()
            val (pipScreenX, pipScreenY) = pipCardScreenPosition(cfg, screenW, screenH)
            val sx = cfg.overlayWidthPx.toFloat() / screenW.toFloat()
            val sy = cfg.overlayHeightPx.toFloat() / screenH.toFloat()

            if (expand) {
                // 1. Resize WindowManager host ONCE to full-screen
                params.width = screenW
                params.height = screenH
                params.x = 0
                params.y = 0
                params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                scrim.visibility = View.VISIBLE
                scrim.alpha = 0f
                card.layoutParams = FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START)
                card.elevation = 0f
                cardBg?.setStroke(0, Color.TRANSPARENT)

                // 2. Intercept the EXACT first pre-draw AFTER card measures at screenW x screenH
                runOnceOnPreDraw(card) {
                    if (hostWindow !== host || isHiding) {
                        isMorphing = false
                        return@runOnceOnPreDraw
                    }
                    card.pivotX = 0f
                    card.pivotY = 0f
                    card.scaleX = sx
                    card.scaleY = sy
                    card.translationX = pipScreenX.toFloat()
                    card.translationY = pipScreenY.toFloat()

                    scrim.animate()
                        .alpha(0.95f)
                        .setDuration(300L)
                        .setInterpolator(morphEase)
                        .start()

                    animateCornerRadius(card, PIP_CORNER_RADIUS_PX, 0f, 320L)

                    card.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(340L)
                        .setInterpolator(morphEase)
                        .withEndAction {
                            isMorphing = false
                            if (hostWindow === host) {
                                host.isFocusable = true
                                host.isFocusableInTouchMode = true
                                host.requestFocus()
                            }
                        }
                        .start()
                }

                runCatching { wm.updateViewLayout(host, params) }
            } else {
                // 1. Glide card smoothly back onto the exact PiP corner rectangle at 60 fps
                host.clearFocus()
                host.isFocusable = false
                host.isFocusableInTouchMode = false

                card.pivotX = 0f
                card.pivotY = 0f

                scrim.animate()
                    .alpha(0f)
                    .setDuration(240L)
                    .setInterpolator(morphEase)
                    .start()

                animateCornerRadius(card, 0f, PIP_CORNER_RADIUS_PX, 280L)

                card.animate()
                    .scaleX(sx)
                    .scaleY(sy)
                    .translationX(pipScreenX.toFloat())
                    .translationY(pipScreenY.toFloat())
                    .setDuration(300L)
                    .setInterpolator(morphEase)
                    .withEndAction {
                        if (hostWindow !== host) {
                            isMorphing = false
                            return@withEndAction
                        }
                        // 2. Resize WindowManager back to corner PiP and reset transforms in OnPreDraw (zero jump!)
                        val pad = dp(HOST_PADDING_DP)
                        val (winX, winY) = pipWindowOrigin(cfg, screenW, screenH, pad)
                        params.width = cfg.overlayWidthPx + pad * 2
                        params.height = cfg.overlayHeightPx + pad * 2
                        params.x = winX
                        params.y = winY
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

                        scrim.visibility = View.GONE
                        card.layoutParams = FrameLayout.LayoutParams(cfg.overlayWidthPx, cfg.overlayHeightPx, Gravity.CENTER)

                        runOnceOnPreDraw(card) {
                            card.pivotX = card.width / 2f
                            card.pivotY = card.height / 2f
                            card.scaleX = 1f
                            card.scaleY = 1f
                            card.translationX = 0f
                            card.translationY = 0f
                            card.elevation = dp(16).toFloat()
                            cardBg?.setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                            currentCornerRadiusPx = PIP_CORNER_RADIUS_PX
                            cardBg?.cornerRadius = currentCornerRadiusPx
                            card.invalidateOutline()
                            isMorphing = false
                        }

                        runCatching { wm.updateViewLayout(host, params) }
                    }
                    .start()
            }
        }
    }

    fun dismissAnimated() {
        mainHandler.post {
            val host = hostWindow
            val card = cardView
            val scrim = scrimView
            if (host == null || card == null || isHiding) {
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

            scrim?.animate()?.alpha(0f)?.setDuration(180L)?.start()

            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.animate()
                .alpha(0f)
                .scaleX(if (isFullscreen) 0.98f else 0.95f)
                .scaleY(if (isFullscreen) 0.98f else 0.95f)
                .translationY(if (isFullscreen) 0f else dp(12).toFloat())
                .setDuration(200L)
                .setInterpolator(exitEase)
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
        cardBg = null
        currentCfg = null
        isShowing = false
        isFullscreen = false
        isKeepAlive = false
        isHiding = false
        isMorphing = false

        if (h != null) {
            runCatching { h.animate().cancel() }
            runCatching { h.removeAllViews() }
            runCatching { wm.removeViewImmediate(h) }
        }
    }

    private fun animateEntrance(card: FrameLayout, scrim: View, fullscreen: Boolean) {
        if (fullscreen) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(0.95f).setDuration(260L).setInterpolator(appleEaseOut).start()

            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.alpha = 0f
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .setInterpolator(appleEaseOut)
                .start()
            return
        }

        // Apple TV HomeKit style: gentle 20 dp upward rise + subtle scale + crisp fade (zero bounce/clip!)
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.translationX = 0f
        card.translationY = dp(20).toFloat()
        card.scaleX = 0.95f
        card.scaleY = 0.95f
        card.alpha = 0f

        card.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(appleEaseOut)
            .start()
    }

    private fun runOnceOnPreDraw(view: View, action: () -> Unit) {
        val vto = view.viewTreeObserver
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val currentVto = view.viewTreeObserver
                if (currentVto.isAlive) currentVto.removeOnPreDrawListener(this)
                action()
                return true
            }
        })
    }

    private fun animateCornerRadius(card: FrameLayout, from: Float, to: Float, durationMs: Long) {
        cornerAnimator?.cancel()
        cornerAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = morphEase
            addUpdateListener { anim ->
                currentCornerRadiusPx = anim.animatedValue as Float
                cardBg?.cornerRadius = currentCornerRadiusPx
                card.invalidateOutline()
            }
            start()
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

    private fun pipCardScreenPosition(cfg: PocConfig, screenW: Int, screenH: Int): Pair<Int, Int> {
        val m = dp(PIP_MARGIN_DP)
        return when (cfg.corner) {
            OverlayCorner.TOP_RIGHT -> (screenW - cfg.overlayWidthPx - m) to m
            OverlayCorner.TOP_LEFT -> m to m
            OverlayCorner.BOTTOM_RIGHT -> (screenW - cfg.overlayWidthPx - m) to (screenH - cfg.overlayHeightPx - m)
            OverlayCorner.BOTTOM_LEFT -> m to (screenH - cfg.overlayHeightPx - m)
        }
    }

    private fun pipWindowOrigin(cfg: PocConfig, screenW: Int, screenH: Int, pad: Int): Pair<Int, Int> {
        val (cardX, cardY) = pipCardScreenPosition(cfg, screenW, screenH)
        return (cardX - pad) to (cardY - pad)
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
        private const val PIP_CORNER_RADIUS_PX = 20f
        private const val PIP_MARGIN_DP = 24
        private const val HOST_PADDING_DP = 32
        private const val KEEP_ALIVE_SAFETY_CAP_MS = 5 * 60 * 1000L
    }
}
