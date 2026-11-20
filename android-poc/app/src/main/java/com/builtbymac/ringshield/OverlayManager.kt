package com.builtbymac.ringshield

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.EglBase

/**
 * Apple TV-grade, zero-glitch 60 fps interactive PiP & 88% Theater Card overlay for Nvidia Shield Pro.
 *
 * Interaction model:
 *  - Instant response (<30 ms): The rounded obsidian card glides onto screen immediately when
 *    triggered with a subtle "Connecting…" indicator while WebRTC negotiates, then cross-fades
 *    smoothly to the live video on the exact SurfaceTexture swap of frame 1.
 *  - Un-hovered PiP mode: Starts clean (pure video only) with a 20-second auto-dismiss timer.
 *    If the user does not navigate to the PiP, it auto-dismisses after 20 seconds.
 *  - Hovered PiP mode: Navigating with the D-pad (or pointer hover) onto the PiP cancels the
 *    20-second auto-dismiss timer ("stays on until dismissed") and animates in a sleek top-right
 *    `✕` dismiss button, a focus border, and a bottom-left `⤢ Fullscreen` pill.
 *      • Clicking the PiP card expands into the 88% Rounded Theater Fullscreen view.
 *      • Navigating to the `✕` button (Right / Up) and clicking it dismisses the PiP.
 *      • Pressing Left / Down un-hovers back to a clean video card (still staying on until dismissed).
 *  - 88% Rounded Theater Fullscreen mode: Stays on until dismissed and presents two sleek floating
 *    glass pill options at the bottom-center of the card:
 *      • `↙  Picture in Picture`: Returns to corner PiP mode and keeps the PiP on screen until dismissed.
 *      • `✕  Dismiss`: Smoothly dismisses the overlay and closes the stream.
 */
class OverlayManager(private val context: Context) {

    private enum class PipFocusTarget {
        CARD,
        DISMISS_BTN,
    }

    private enum class FullscreenFocusTarget {
        PIP_BTN,
        DISMISS_BTN,
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterEase = PathInterpolator(0.25f, 0.9f, 0.25f, 1f)
    private val morphEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val exitEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val popSpring = OvershootInterpolator(1.35f)

    private var hostWindow: FrameLayout? = null
    private var scrimView: View? = null
    private var cardView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var screenW: Int = 1920
    private var screenH: Int = 1080
    private var currentCornerRadiusPx: Float = 0f

    // Connecting indicator inside card before frame 1 swaps in
    private var connectingContainer: View? = null
    private var connectingPulseAnimator: ObjectAnimator? = null

    // PiP hover HUD views (scaled inversely to pip.scaleX for razor-sharp 1:1 1080p rendering)
    private var pipBorderView: View? = null
    private var pipBorderDrawable: GradientDrawable? = null
    private var pipCloseBtn: TextView? = null
    private var pipCloseDrawable: GradientDrawable? = null
    private var pipExpandPill: TextView? = null
    private var pipExpandDrawable: GradientDrawable? = null

    // Fullscreen HUD views (scaled inversely to exp.scaleX for 1:1 1080p rendering)
    private var fsHudDock: LinearLayout? = null
    private var fsPipBtn: TextView? = null
    private var fsPipDrawable: GradientDrawable? = null
    private var fsDismissBtn: TextView? = null
    private var fsDismissDrawable: GradientDrawable? = null

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

    private var isPipHovered: Boolean = false
    private var pipFocusTarget: PipFocusTarget = PipFocusTarget.CARD
    private var fsFocusTarget: FullscreenFocusTarget = FullscreenFocusTarget.PIP_BTN
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
        isPipHovered = false
        pipFocusTarget = PipFocusTarget.CARD
        fsFocusTarget = FullscreenFocusTarget.PIP_BTN
        isShowing = true
        isHiding = false

        val pip = computePipTransform(cfg)
        val exp = computeExpandedTransform()
        currentCornerRadiusPx = if (startFullscreen) exp.unscaledCornerRadius else pip.unscaledCornerRadius

        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && !isHiding) {
                    if (handleOverlayKeyDown(event.keyCode)) {
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val scrim = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener {
                if (isFullscreen) {
                    setFullscreen(false)
                }
            }
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
            setBackgroundColor(Color.parseColor("#FF10151E"))
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
            elevation = if (startFullscreen) dp(24).toFloat() else dp(18).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, currentCornerRadiusPx)
                }
            }
            clipToOutline = true
            setOnHoverListener { _, ev ->
                if (!isFullscreen && !isHiding) {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                            if (!isPipHovered) {
                                setPipHovered(true, PipFocusTarget.CARD)
                            }
                        }
                    }
                }
                false
            }
            setOnClickListener {
                if (!isHiding && !isFullscreen) {
                    setFullscreen(true)
                }
            }
        }

        val videoView = TextureViewRenderer(context).apply {
            alpha = 0f
            init(
                eglContext = eglContext,
                onFirstFrame = {
                    mainHandler.post {
                        if (hostWindow === host && !isHiding) {
                            revealVideoOnFirstFrame(this)
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

        val connectingView = buildConnectingIndicator(if (startFullscreen) exp.scaleX else pip.scaleX)
        connectingContainer = connectingView
        card.addView(connectingView)

        if (cfg.showLiveDot) {
            card.addView(buildSubtleLiveDot(pip.scaleX))
        }

        // Build PiP hover HUD & Fullscreen HUD inside card
        buildPipHoverHud(card, pip)
        buildFullscreenHud(card, exp, startFullscreen)

        host.addView(card, FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START))

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsForOverlay(),
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

        host.requestFocus()

        // Glide card in immediately (<30 ms) so pressing the toggle feels instant!
        animateCardEntrance(card, scrim, pip, startFullscreen)

        // Start initial auto-dismiss timer (will be refreshed on first video frame so wake latency
        // never steals viewing time, or cancelled immediately if the user hovers / enters fullscreen)
        scheduleAutoDismiss(
            if (isKeepAlive) KEEP_ALIVE_SAFETY_CAP_MS
            else (cfg.overlayDurationSec * 1000L) + 4_000L
        )
        return videoView
    }

    private fun handleOverlayKeyDown(keyCode: Int): Boolean {
        if (!isFullscreen) {
            // Corner PiP Mode
            if (!isPipHovered) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        setPipHovered(true, PipFocusTarget.CARD)
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        dismissAnimated()
                        true
                    }
                    else -> false
                }
            } else {
                // Hovered on PiP
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (pipFocusTarget == PipFocusTarget.CARD) {
                            setPipFocusTarget(PipFocusTarget.DISMISS_BTN)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (pipFocusTarget == PipFocusTarget.DISMISS_BTN) {
                            setPipFocusTarget(PipFocusTarget.CARD)
                        } else {
                            // Un-hover PiP controls back to clean video (stays alive until dismissed)
                            setPipHovered(false)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (pipFocusTarget == PipFocusTarget.DISMISS_BTN) {
                            dismissAnimated()
                        } else {
                            setFullscreen(true)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        dismissAnimated()
                        true
                    }
                    else -> false
                }
            }
        } else {
            // 88% Rounded Theater Fullscreen Mode
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    setFullscreenFocusTarget(FullscreenFocusTarget.PIP_BTN)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    setFullscreenFocusTarget(FullscreenFocusTarget.DISMISS_BTN)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Ensure dock is highlighted
                    updateFullscreenHudStyles(animated = true)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    when (fsFocusTarget) {
                        FullscreenFocusTarget.PIP_BTN -> setFullscreen(false)
                        FullscreenFocusTarget.DISMISS_BTN -> dismissAnimated()
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    // Return to Picture-in-Picture (which stays alive until dismissed)
                    setFullscreen(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun engageKeepAlive() {
        if (!isKeepAlive) {
            isKeepAlive = true
            Log.i(TAG, "Keep-alive engaged (overlay hovered or fullscreen) — auto-dismiss cancelled")
            scheduleAutoDismiss(KEEP_ALIVE_SAFETY_CAP_MS)
            onKeepAliveCallback?.invoke()
        }
    }

    private fun setPipHovered(hovered: Boolean, target: PipFocusTarget = PipFocusTarget.CARD) {
        if (isFullscreen || isHiding) return
        if (hovered) {
            engageKeepAlive()
        }
        val changed = isPipHovered != hovered
        isPipHovered = hovered
        pipFocusTarget = target
        updatePipHudStyles(animated = true, visibilityChanged = changed)
    }

    private fun setPipFocusTarget(target: PipFocusTarget) {
        if (!isPipHovered || pipFocusTarget == target) return
        pipFocusTarget = target
        updatePipHudStyles(animated = true, visibilityChanged = false)
    }

    private fun setFullscreenFocusTarget(target: FullscreenFocusTarget) {
        if (!isFullscreen || fsFocusTarget == target) return
        fsFocusTarget = target
        updateFullscreenHudStyles(animated = true)
    }

    fun toggleFullscreen() {
        mainHandler.post {
            if (!isShowing || isHiding) return@post
            setFullscreen(!isFullscreen)
        }
    }

    /**
     * Pure 60 fps GPU transform-matrix PiP <-> 88% Rounded Theater Fullscreen morph.
     */
    fun setFullscreen(expand: Boolean) {
        mainHandler.post {
            val host = hostWindow ?: return@post
            val card = cardView ?: return@post
            val scrim = scrimView ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isFullscreen == expand) return@post

            isFullscreen = expand
            // Either entering fullscreen OR returning from fullscreen to PiP keeps stream alive until dismissed!
            engageKeepAlive()

            card.animate().cancel()
            scrim.animate().cancel()
            cornerAnimator?.cancel()
            host.requestFocus()

            val pip = computePipTransform(cfg)
            val exp = computeExpandedTransform()
            card.pivotX = 0f
            card.pivotY = 0f

            if (expand) {
                isPipHovered = false
                updatePipHudStyles(animated = true, visibilityChanged = true)

                fsFocusTarget = FullscreenFocusTarget.PIP_BTN
                updateFullscreenHudStyles(animated = false)
                fsHudDock?.apply {
                    visibility = View.VISIBLE
                    animate().cancel()
                    translationY = expDp(18, exp.scaleX).toFloat()
                    animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(320L)
                        .setInterpolator(enterEase)
                        .start()
                }

                card.elevation = dp(24).toFloat()
                scrim.animate()
                    .alpha(EXPANDED_SCRIM_ALPHA)
                    .setDuration(380L)
                    .setInterpolator(morphEase)
                    .start()

                animateCornerRadius(card, currentCornerRadiusPx, exp.unscaledCornerRadius, 400L)

                card.animate()
                    .scaleX(exp.scaleX)
                    .scaleY(exp.scaleY)
                    .translationX(exp.x)
                    .translationY(exp.y)
                    .setDuration(420L)
                    .setInterpolator(morphEase)
                    .start()
            } else {
                // Returning from Fullscreen to PiP mode: stays on until dismissed!
                fsHudDock?.apply {
                    animate().cancel()
                    animate()
                        .alpha(0f)
                        .translationY(expDp(14, exp.scaleX).toFloat())
                        .setDuration(200L)
                        .setInterpolator(exitEase)
                        .withEndAction { visibility = View.GONE }
                        .start()
                }

                isPipHovered = false
                updatePipHudStyles(animated = false, visibilityChanged = true)

                card.elevation = dp(18).toFloat()
                scrim.animate()
                    .alpha(0f)
                    .setDuration(320L)
                    .setInterpolator(morphEase)
                    .start()

                animateCornerRadius(card, currentCornerRadiusPx, pip.unscaledCornerRadius, 360L)

                card.animate()
                    .scaleX(pip.scaleX)
                    .scaleY(pip.scaleY)
                    .translationX(pip.x)
                    .translationY(pip.y)
                    .setDuration(380L)
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
            val video = renderer
            isHiding = true
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null
            card.animate().cancel()
            video?.animate()?.cancel()
            scrim?.animate()?.cancel()
            cornerAnimator?.cancel()

            scrim?.animate()?.alpha(0f)?.setDuration(240L)?.setInterpolator(exitEase)?.start()
            video?.animate()?.alpha(0f)?.setDuration(240L)?.setInterpolator(exitEase)?.start()

            if (isFullscreen) {
                val exp = computeExpandedTransform()
                val shrinkScale = exp.scaleX * 0.95f
                val shrinkX = (screenW - screenW * shrinkScale) / 2f
                val shrinkY = (screenH - screenH * shrinkScale) / 2f + dp(12)
                card.pivotX = 0f
                card.pivotY = 0f
                card.animate()
                    .alpha(0f)
                    .scaleX(shrinkScale)
                    .scaleY(shrinkScale)
                    .translationX(shrinkX)
                    .translationY(shrinkY)
                    .setDuration(260L)
                    .setInterpolator(exitEase)
                    .withEndAction {
                        val cb = onDismissCallback
                        hideImmediate()
                        cb?.invoke()
                    }
                    .start()
            } else {
                val pip = computePipTransform(cfg)
                val slideOutX = pip.x + edgeSlideDeltaPx(cfg) * 0.9f
                card.pivotX = 0f
                card.pivotY = 0f
                card.animate()
                    .alpha(0f)
                    .translationX(slideOutX)
                    .scaleX(pip.scaleX * 0.93f)
                    .scaleY(pip.scaleY * 0.93f)
                    .setDuration(260L)
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
        connectingPulseAnimator?.cancel()
        connectingPulseAnimator = null
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
        connectingContainer = null
        pipBorderView = null
        pipBorderDrawable = null
        pipCloseBtn = null
        pipCloseDrawable = null
        pipExpandPill = null
        pipExpandDrawable = null
        fsHudDock = null
        fsPipBtn = null
        fsPipDrawable = null
        fsDismissBtn = null
        fsDismissDrawable = null
        isShowing = false
        isFullscreen = false
        isKeepAlive = false
        isPipHovered = false
        isHiding = false

        if (h != null) {
            runCatching { h.animate().cancel() }
            runCatching { h.removeAllViews() }
            runCatching { wm.removeViewImmediate(h) }
        }
    }

    private fun animateCardEntrance(
        card: FrameLayout,
        scrim: View,
        pip: PipTransform,
        fullscreen: Boolean,
    ) {
        val cfg = currentCfg ?: return
        if (fullscreen) {
            val exp = computeExpandedTransform()
            val startScale = exp.scaleX * 0.94f
            val startX = (screenW - screenW * startScale) / 2f
            val startY = (screenH - screenH * startScale) / 2f + dp(16)
            card.elevation = dp(24).toFloat()
            scrim.alpha = 0f
            scrim.animate().alpha(EXPANDED_SCRIM_ALPHA).setDuration(300L).setInterpolator(enterEase).start()

            card.pivotX = 0f
            card.pivotY = 0f
            card.translationX = startX
            card.translationY = startY
            card.scaleX = startScale
            card.scaleY = startScale
            card.alpha = 0f
            card.animate()
                .alpha(1f)
                .translationX(exp.x)
                .translationY(exp.y)
                .scaleX(exp.scaleX)
                .scaleY(exp.scaleY)
                .setDuration(320L)
                .setInterpolator(enterEase)
                .start()
            return
        }

        val startScaleX = pip.scaleX * 0.92f
        val startScaleY = pip.scaleY * 0.92f
        val startX = pip.x + edgeSlideDeltaPx(cfg)
        val startY = pip.y + (cfg.overlayHeightPx * 0.04f)

        card.elevation = dp(18).toFloat()
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
            .setDuration(320L)
            .setInterpolator(enterEase)
            .start()
    }

    private fun revealVideoOnFirstFrame(videoView: TextureViewRenderer) {
        val cfg = currentCfg ?: return
        connectingPulseAnimator?.cancel()
        connectingContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150L)
            ?.withEndAction { connectingContainer?.visibility = View.GONE }
            ?.start()

        videoView.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(enterEase)
            .start()

        // Reset the 20-second auto-dismiss timer from the moment video actually appears
        if (!isKeepAlive) {
            scheduleAutoDismiss(cfg.overlayDurationSec * 1000L)
        }
    }

    private fun buildConnectingIndicator(activeScale: Float): View {
        val pillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = expDp(999, activeScale).toFloat()
            setColor(Color.parseColor("#B318202C"))
            setStroke(expDp(1, activeScale), Color.parseColor("#33FFFFFF"))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable
            val padH = expDp(16, activeScale)
            val padV = expDp(9, activeScale)
            setPadding(padH, padV, padH, padV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        val dotSize = expDp(8, activeScale)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF453A"))
            }
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = expDp(10, activeScale)
            }
        }
        connectingPulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        val label = TextView(context).apply {
            text = "Connecting…"
            setTextColor(Color.parseColor("#D9FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, expDp(13, activeScale).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(dot)
        row.addView(label)
        return row
    }

    private fun buildPipHoverHud(card: FrameLayout, pip: PipTransform) {
        val s = pip.scaleX.coerceAtLeast(0.15f)

        // 1. Rounded hover border around the PiP card
        val borderDraw = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pip.unscaledCornerRadius
            setColor(Color.TRANSPARENT)
            setStroke(pipDp(2, s), Color.parseColor("#E6FFFFFF"))
        }
        pipBorderDrawable = borderDraw
        val borderView = View(context).apply {
            background = borderDraw
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        pipBorderView = borderView
        card.addView(borderView)

        // 2. Sleek top-right circular `✕` dismiss button (animates in on PiP hover)
        val closeDraw = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D90F141C"))
            setStroke(pipDp(1, s), Color.parseColor("#77FFFFFF"))
        }
        pipCloseDrawable = closeDraw
        val closeSize = pipDp(34, s)
        val closeMargin = pipDp(12, s)
        val closeBtn = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pipDp(15, s).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = closeDraw
            alpha = 0f
            scaleX = 0.65f
            scaleY = 0.65f
            layoutParams = FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, closeMargin, closeMargin, 0)
            }
            setOnHoverListener { _, ev ->
                if (!isFullscreen && !isHiding && ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    setPipHovered(true, PipFocusTarget.DISMISS_BTN)
                }
                false
            }
            setOnClickListener {
                if (!isHiding) {
                    dismissAnimated()
                }
            }
        }
        pipCloseBtn = closeBtn
        card.addView(closeBtn)

        // 3. Sleek bottom-left `⤢ Fullscreen` pill (indicates clicking the hovered PiP opens Fullscreen)
        val expandDraw = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pipDp(999, s).toFloat()
            setColor(Color.parseColor("#F0FFFFFF"))
        }
        pipExpandDrawable = expandDraw
        val expandPill = TextView(context).apply {
            text = "⤢  Fullscreen"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF0F141C"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pipDp(12, s).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = expandDraw
            val padH = pipDp(12, s)
            val padV = pipDp(6, s)
            setPadding(padH, padV, padH, padV)
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                setMargins(closeMargin, 0, 0, closeMargin)
            }
            setOnClickListener {
                if (!isHiding && !isFullscreen) {
                    setFullscreen(true)
                }
            }
        }
        pipExpandPill = expandPill
        card.addView(expandPill)
    }

    private fun updatePipHudStyles(animated: Boolean, visibilityChanged: Boolean) {
        val cfg = currentCfg ?: return
        val pip = computePipTransform(cfg)
        val s = pip.scaleX.coerceAtLeast(0.15f)
        val borderView = pipBorderView ?: return
        val closeBtn = pipCloseBtn ?: return
        val expandPill = pipExpandPill ?: return

        if (!isPipHovered || isFullscreen) {
            borderView.animate().cancel()
            closeBtn.animate().cancel()
            expandPill.animate().cancel()
            if (animated) {
                borderView.animate().alpha(0f).setDuration(160L).start()
                closeBtn.animate()
                    .alpha(0f)
                    .scaleX(0.68f)
                    .scaleY(0.68f)
                    .setDuration(170L)
                    .setInterpolator(exitEase)
                    .start()
                expandPill.animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(160L)
                    .setInterpolator(exitEase)
                    .start()
            } else {
                borderView.alpha = 0f
                closeBtn.alpha = 0f
                closeBtn.scaleX = 0.68f
                closeBtn.scaleY = 0.68f
                expandPill.alpha = 0f
                expandPill.scaleX = 0.85f
                expandPill.scaleY = 0.85f
            }
            return
        }

        val onDismiss = pipFocusTarget == PipFocusTarget.DISMISS_BTN

        // Update border & button fills
        pipBorderDrawable?.setStroke(
            pipDp(2, s),
            if (onDismiss) Color.parseColor("#66FFFFFF") else Color.parseColor("#E6FFFFFF")
        )
        pipCloseDrawable?.apply {
            setColor(if (onDismiss) Color.parseColor("#FFFF453A") else Color.parseColor("#D90F141C"))
            setStroke(
                pipDp(if (onDismiss) 2 else 1, s),
                if (onDismiss) Color.WHITE else Color.parseColor("#80FFFFFF")
            )
        }
        pipExpandDrawable?.apply {
            setColor(if (!onDismiss) Color.parseColor("#F2FFFFFF") else Color.parseColor("#BF0F141C"))
            setStroke(
                pipDp(1, s),
                if (!onDismiss) Color.WHITE else Color.parseColor("#44FFFFFF")
            )
        }
        expandPill.setTextColor(
            if (!onDismiss) Color.parseColor("#FF0F141C") else Color.parseColor("#CCFFFFFF")
        )

        val closeTargetScale = if (onDismiss) 1.14f else 1.0f
        val expandTargetScale = if (!onDismiss) 1.0f else 0.94f

        borderView.animate().cancel()
        closeBtn.animate().cancel()
        expandPill.animate().cancel()

        borderView.animate().alpha(1f).setDuration(180L).start()
        closeBtn.animate()
            .alpha(1f)
            .scaleX(closeTargetScale)
            .scaleY(closeTargetScale)
            .setDuration(if (visibilityChanged) 230L else 160L)
            .setInterpolator(popSpring)
            .start()
        expandPill.animate()
            .alpha(1f)
            .scaleX(expandTargetScale)
            .scaleY(expandTargetScale)
            .setDuration(200L)
            .setInterpolator(enterEase)
            .start()
    }

    private fun buildFullscreenHud(card: FrameLayout, exp: PipTransform, startFullscreen: Boolean) {
        val s = exp.scaleX.coerceAtLeast(0.5f)

        val dockDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = expDp(999, s).toFloat()
            setColor(Color.parseColor("#D90D121A"))
            setStroke(expDp(1, s), Color.parseColor("#38FFFFFF"))
        }

        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = dockDrawable
            val pad = expDp(6, s)
            setPadding(pad, pad, pad, pad)
            alpha = if (startFullscreen) 1f else 0f
            visibility = if (startFullscreen) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = expDp(28, s)
            }
        }
        fsHudDock = dock

        val pipDraw = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = expDp(999, s).toFloat()
        }
        fsPipDrawable = pipDraw
        val pipBtn = TextView(context).apply {
            text = "↙   Picture in Picture"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, expDp(15, s).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pipDraw
            val padH = expDp(20, s)
            val padV = expDp(10, s)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = expDp(6, s)
            }
            setOnHoverListener { _, ev ->
                if (isFullscreen && ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    setFullscreenFocusTarget(FullscreenFocusTarget.PIP_BTN)
                }
                false
            }
            setOnClickListener {
                if (isFullscreen && !isHiding) {
                    setFullscreen(false)
                }
            }
        }
        fsPipBtn = pipBtn

        val dismissDraw = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = expDp(999, s).toFloat()
        }
        fsDismissDrawable = dismissDraw
        val dismissBtn = TextView(context).apply {
            text = "✕   Dismiss"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, expDp(15, s).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = dismissDraw
            val padH = expDp(20, s)
            val padV = expDp(10, s)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnHoverListener { _, ev ->
                if (isFullscreen && ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    setFullscreenFocusTarget(FullscreenFocusTarget.DISMISS_BTN)
                }
                false
            }
            setOnClickListener {
                if (!isHiding) {
                    dismissAnimated()
                }
            }
        }
        fsDismissBtn = dismissBtn

        dock.addView(pipBtn)
        dock.addView(dismissBtn)
        card.addView(dock)

        updateFullscreenHudStyles(animated = false)
    }

    private fun updateFullscreenHudStyles(animated: Boolean) {
        val pipBtn = fsPipBtn ?: return
        val dismissBtn = fsDismissBtn ?: return
        val onPip = fsFocusTarget == FullscreenFocusTarget.PIP_BTN

        fsPipDrawable?.setColor(
            if (onPip) Color.parseColor("#F2FFFFFF") else Color.TRANSPARENT
        )
        pipBtn.setTextColor(
            if (onPip) Color.parseColor("#FF0D121A") else Color.parseColor("#B3FFFFFF")
        )

        fsDismissDrawable?.setColor(
            if (!onPip) Color.parseColor("#FFFF453A") else Color.TRANSPARENT
        )
        dismissBtn.setTextColor(
            if (!onPip) Color.WHITE else Color.parseColor("#B3FFFFFF")
        )

        if (animated) {
            pipBtn.animate()
                .scaleX(if (onPip) 1.03f else 0.97f)
                .scaleY(if (onPip) 1.03f else 0.97f)
                .setDuration(150L)
                .start()
            dismissBtn.animate()
                .scaleX(if (!onPip) 1.03f else 0.97f)
                .scaleY(if (!onPip) 1.03f else 0.97f)
                .setDuration(150L)
                .start()
        } else {
            pipBtn.scaleX = if (onPip) 1.03f else 0.97f
            pipBtn.scaleY = if (onPip) 1.03f else 0.97f
            dismissBtn.scaleX = if (!onPip) 1.03f else 0.97f
            dismissBtn.scaleY = if (!onPip) 1.03f else 0.97f
        }
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

    private fun computeExpandedTransform(): PipTransform {
        val s = EXPANDED_SCREEN_FRACTION
        val x = (screenW - screenW * s) / 2f
        val y = (screenH - screenH * s) / 2f
        return PipTransform(
            x = x,
            y = y,
            scaleX = s,
            scaleY = s,
            unscaledCornerRadius = EXPANDED_VISUAL_CORNER_RADIUS_PX / s,
        )
    }

    private fun edgeSlideDeltaPx(cfg: PocConfig): Float {
        val isLeft = cfg.corner == OverlayCorner.TOP_LEFT || cfg.corner == OverlayCorner.BOTTOM_LEFT
        val delta = dp(48).toFloat()
        return if (isLeft) -delta else delta
    }

    private fun flagsForOverlay(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

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

    private fun pipDp(value: Int, pipScale: Float): Int =
        (dp(value) / pipScale.coerceAtLeast(0.15f)).toInt().coerceAtLeast(1)

    private fun expDp(value: Int, expScale: Float): Int =
        (dp(value) / expScale.coerceAtLeast(0.5f)).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "OverlayManager"
        private const val PIP_VISUAL_CORNER_RADIUS_PX = 20f
        private const val EXPANDED_VISUAL_CORNER_RADIUS_PX = 28f
        private const val EXPANDED_SCREEN_FRACTION = 0.88f
        private const val EXPANDED_SCRIM_ALPHA = 0.76f
        private const val PIP_MARGIN_DP = 24
        private const val KEEP_ALIVE_SAFETY_CAP_MS = 5 * 60 * 1000L
    }
}
