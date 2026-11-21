package com.builtbymac.ringshield

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.EglBase

/**
 * Apple visionOS / tvOS Liquid Glass 60 fps interactive PiP & 88% Theater Card overlay.
 *
 * Interaction model:
 *  - Corner PiP (Un-hovered): 100% pure video card with 20s auto-dismiss timer.
 *  - Corner PiP (Hovered):
 *      • Auto-dismiss is cancelled (stays on until dismissed).
 *      • When hovering the PiP card itself (CARD target), the card subtly swells (1.036x) with a
 *        directional Liquid Glass specular rim highlight — indicating it can be clicked to open
 *        Fullscreen with zero bottom-left button clutter.
 *      • Two circular Liquid Glass orbs (`Audio Mute/Unmute` and `✕ Close`) animate into the
 *        top-right corner. Pressing Right/Up moves focus onto `Audio`, then `✕`.
 *  - 88% Rounded Theater Fullscreen:
 *      • Background content stays at 100% full brightness (zero background dimming).
 *      • Three compact circular Liquid Glass icon orbs (`Audio Mute/Unmute`, `PiP Minimize`, and
 *        `✕ Close`) sit in the top-right corner of the card and expand smoothly when focused.
 */
class OverlayManager(private val context: Context) {

    private enum class PipFocusTarget {
        CARD,
        AUDIO_BTN,
        DISMISS_BTN,
    }

    private enum class FullscreenFocusTarget {
        AUDIO_BTN,
        PIP_BTN,
        DISMISS_BTN,
    }

    private enum class GlassIconType {
        AUDIO_TOGGLE,
        PIP_MINIMIZE,
        CLOSE_X,
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterEase = PathInterpolator(0.25f, 0.9f, 0.25f, 1f)
    private val morphEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val exitEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val popSpring = OvershootInterpolator(1.45f)

    private var hostWindow: FrameLayout? = null
    private var cardView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var renderer: TextureViewRenderer? = null
    private var currentCfg: PocConfig? = null
    private var screenW: Int = 1920
    private var screenH: Int = 1080
    private var currentCornerRadiusPx: Float = 0f

    private var connectingBackdrop: FrameLayout? = null
    private var connectingPreviewImage: ImageView? = null
    private var connectingContainer: View? = null
    private var connectingPulseAnimator: ValueAnimator? = null
    private var hasFirstFrame: Boolean = false

    private var glassSyncUntilMs: Long = 0L
    private var glassSyncRunning: Boolean = false
    private val glassSyncFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isShowing || hostWindow == null) {
                glassSyncRunning = false
                return
            }
            syncGlassOrbUniforms()
            if (SystemClock.uptimeMillis() < glassSyncUntilMs) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                glassSyncRunning = false
            }
        }
    }

    private var glassRimView: LiquidGlassRimView? = null

    // PiP top-right Liquid Glass orbs (`Audio` + `✕`)
    private var pipTopRightRow: LinearLayout? = null
    private var pipAudioOrb: LiquidGlassOrbButton? = null
    private var pipCloseOrb: LiquidGlassOrbButton? = null

    // Fullscreen top-right Liquid Glass orbs (`Audio` + `PiP Minimize` + `✕`)
    private var fsTopRightRow: LinearLayout? = null
    private var fsAudioOrb: LiquidGlassOrbButton? = null
    private var fsPipOrb: LiquidGlassOrbButton? = null
    private var fsCloseOrb: LiquidGlassOrbButton? = null

    private var dotAnimator: ObjectAnimator? = null
    private var cornerAnimator: ValueAnimator? = null
    private var dismissRunnable: Runnable? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var onKeepAliveCallback: (() -> Unit)? = null
    private var onToggleAudioCallback: (() -> Boolean)? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    @Volatile
    var isFullscreen: Boolean = false
        private set

    @Volatile
    var isKeepAlive: Boolean = false
        private set

    @Volatile
    var isAudioActive: Boolean = false
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
        onToggleAudio: () -> Boolean = { false },
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
        onToggleAudioCallback = onToggleAudio
        isFullscreen = startFullscreen
        isKeepAlive = keepAliveUntilDismissed || startFullscreen
        isAudioActive = false
        isPipHovered = false
        pipFocusTarget = PipFocusTarget.CARD
        fsFocusTarget = FullscreenFocusTarget.PIP_BTN
        isShowing = true
        isHiding = false
        hasFirstFrame = false

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

        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#FF0E131B"))
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
            elevation = if (startFullscreen) dp(28).toFloat() else dp(18).toFloat()
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

        // Frosted Liquid Glass connecting backdrop showing the blurred last doorbell thumbnail
        val backdrop = buildConnectingBackdrop()
        connectingBackdrop = backdrop
        card.addView(
            backdrop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        val videoView = TextureViewRenderer(context).apply {
            alpha = 0f
            init(
                eglContext = eglContext,
                inpaintWatermarks = cfg.cropRingWatermark,
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

        buildLiquidGlassHud(card, pip, exp, startFullscreen)

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
        card.post { startGlassOrbSyncBurst(450L) }

        animateCardEntrance(card, pip, startFullscreen)

        scheduleAutoDismiss(
            if (isKeepAlive) KEEP_ALIVE_SAFETY_CAP_MS
            else (cfg.overlayDurationSec * 1000L) + 4_000L
        )
        return videoView
    }

    private fun handleOverlayKeyDown(keyCode: Int): Boolean {
        if (!isFullscreen) {
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
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (pipFocusTarget) {
                            PipFocusTarget.CARD -> setPipFocusTarget(PipFocusTarget.AUDIO_BTN)
                            PipFocusTarget.AUDIO_BTN -> setPipFocusTarget(PipFocusTarget.DISMISS_BTN)
                            PipFocusTarget.DISMISS_BTN -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (pipFocusTarget == PipFocusTarget.CARD) {
                            setPipFocusTarget(PipFocusTarget.AUDIO_BTN)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when (pipFocusTarget) {
                            PipFocusTarget.DISMISS_BTN -> setPipFocusTarget(PipFocusTarget.AUDIO_BTN)
                            PipFocusTarget.AUDIO_BTN -> setPipFocusTarget(PipFocusTarget.CARD)
                            PipFocusTarget.CARD -> setPipHovered(false)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        when (pipFocusTarget) {
                            PipFocusTarget.DISMISS_BTN, PipFocusTarget.AUDIO_BTN -> {
                                setPipFocusTarget(PipFocusTarget.CARD)
                            }
                            PipFocusTarget.CARD -> setPipHovered(false)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        when (pipFocusTarget) {
                            PipFocusTarget.CARD -> setFullscreen(true)
                            PipFocusTarget.AUDIO_BTN -> performAudioToggle()
                            PipFocusTarget.DISMISS_BTN -> dismissAnimated()
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
                    when (fsFocusTarget) {
                        FullscreenFocusTarget.DISMISS_BTN -> setFullscreenFocusTarget(FullscreenFocusTarget.PIP_BTN)
                        FullscreenFocusTarget.PIP_BTN -> setFullscreenFocusTarget(FullscreenFocusTarget.AUDIO_BTN)
                        FullscreenFocusTarget.AUDIO_BTN -> Unit
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (fsFocusTarget) {
                        FullscreenFocusTarget.AUDIO_BTN -> setFullscreenFocusTarget(FullscreenFocusTarget.PIP_BTN)
                        FullscreenFocusTarget.PIP_BTN -> setFullscreenFocusTarget(FullscreenFocusTarget.DISMISS_BTN)
                        FullscreenFocusTarget.DISMISS_BTN -> Unit
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    updateFullscreenHudStyles(animated = true)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    when (fsFocusTarget) {
                        FullscreenFocusTarget.AUDIO_BTN -> performAudioToggle()
                        FullscreenFocusTarget.PIP_BTN -> setFullscreen(false)
                        FullscreenFocusTarget.DISMISS_BTN -> dismissAnimated()
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    setFullscreen(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun performAudioToggle() {
        engageKeepAlive()
        val active = onToggleAudioCallback?.invoke() ?: !isAudioActive
        isAudioActive = active
        pipAudioOrb?.setActiveToggleState(active)
        fsAudioOrb?.setActiveToggleState(active)
        startGlassOrbSyncBurst(220L)
    }

    /** Updates the frosted connecting preview if a fresh Ring activity thumbnail arrives before frame 1. */
    fun updateFrostedPreview(frostedBitmap: Bitmap) {
        mainHandler.post {
            val img = connectingPreviewImage ?: return@post
            if (!isShowing || isHiding || hasFirstFrame) return@post
            img.setImageBitmap(frostedBitmap)
            img.alpha = 0f
            img.animate().alpha(1f).setDuration(220L).setInterpolator(enterEase).start()
        }
    }

    /** Captures a clean 640x360 snapshot from the live video TextureView (only once video is visible). */
    fun captureSnapshotBitmap(): Bitmap? {
        val r = renderer ?: return null
        if (!isShowing || !hasFirstFrame || !r.isAvailable) return null
        return runCatching { r.getBitmap(640, 360) }.getOrNull()
    }

    private fun engageKeepAlive() {
        if (!isKeepAlive) {
            isKeepAlive = true
            Log.i(TAG, "Keep-alive engaged — auto-dismiss cancelled")
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

    fun setFullscreen(expand: Boolean) {
        mainHandler.post {
            val host = hostWindow ?: return@post
            val card = cardView ?: return@post
            val cfg = currentCfg ?: return@post
            if (!isShowing || isHiding || isFullscreen == expand) return@post

            isFullscreen = expand
            engageKeepAlive()

            card.animate().cancel()
            cornerAnimator?.cancel()
            host.requestFocus()

            val pip = computePipTransform(cfg)
            val exp = computeExpandedTransform()
            card.pivotX = 0f
            card.pivotY = 0f

            if (expand) {
                isPipHovered = false
                pipTopRightRow?.animate()?.cancel()
                pipTopRightRow?.animate()
                    ?.alpha(0f)
                    ?.scaleX(0.7f)
                    ?.scaleY(0.7f)
                    ?.setDuration(150L)
                    ?.start()

                fsFocusTarget = FullscreenFocusTarget.PIP_BTN
                updateFullscreenHudStyles(animated = false)
                fsTopRightRow?.apply {
                    visibility = View.VISIBLE
                    animate().cancel()
                    scaleX = 0.82f
                    scaleY = 0.82f
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300L)
                        .setInterpolator(popSpring)
                        .start()
                }

                glassRimView?.apply {
                    setStrokeState(pipDp(1, exp.scaleX).toFloat(), bright = false)
                    animate().alpha(0.75f).setDuration(260L).start()
                }

                card.elevation = dp(28).toFloat()
                animateCornerRadius(card, currentCornerRadiusPx, exp.unscaledCornerRadius, 400L)

                card.animate()
                    .scaleX(exp.scaleX)
                    .scaleY(exp.scaleY)
                    .translationX(exp.x)
                    .translationY(exp.y)
                    .setDuration(420L)
                    .setInterpolator(morphEase)
                    .start()
                startGlassOrbSyncBurst(460L)
            } else {
                fsTopRightRow?.apply {
                    animate().cancel()
                    animate()
                        .alpha(0f)
                        .scaleX(0.8f)
                        .scaleY(0.8f)
                        .setDuration(180L)
                        .setInterpolator(exitEase)
                        .withEndAction { visibility = View.GONE }
                        .start()
                }

                isPipHovered = false
                pipTopRightRow?.alpha = 0f
                glassRimView?.animate()?.alpha(0f)?.setDuration(200L)?.start()

                card.elevation = dp(18).toFloat()
                animateCornerRadius(card, currentCornerRadiusPx, pip.unscaledCornerRadius, 360L)

                card.animate()
                    .scaleX(pip.scaleX)
                    .scaleY(pip.scaleY)
                    .translationX(pip.x)
                    .translationY(pip.y)
                    .setDuration(380L)
                    .setInterpolator(morphEase)
                    .start()
                startGlassOrbSyncBurst(420L)
            }
        }
    }

    fun dismissAnimated() {
        mainHandler.post {
            val host = hostWindow
            val card = cardView
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
            cornerAnimator?.cancel()

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
        glassSyncRunning = false
        glassSyncUntilMs = 0L
        runCatching { Choreographer.getInstance().removeFrameCallback(glassSyncFrameCallback) }
        onDismissCallback = null
        onKeepAliveCallback = null
        onToggleAudioCallback = null

        val r = renderer
        renderer = null
        runCatching { r?.release() }

        val h = hostWindow
        hostWindow = null
        cardView = null
        rootParams = null
        currentCfg = null
        connectingBackdrop = null
        connectingPreviewImage = null
        connectingContainer = null
        hasFirstFrame = false
        glassRimView = null
        pipTopRightRow = null
        pipAudioOrb = null
        pipCloseOrb = null
        fsTopRightRow = null
        fsAudioOrb = null
        fsPipOrb = null
        fsCloseOrb = null
        isShowing = false
        isFullscreen = false
        isKeepAlive = false
        isAudioActive = false
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
        pip: PipTransform,
        fullscreen: Boolean,
    ) {
        val cfg = currentCfg ?: return
        if (fullscreen) {
            val exp = computeExpandedTransform()
            val startScale = exp.scaleX * 0.94f
            val startX = (screenW - screenW * startScale) / 2f
            val startY = (screenH - screenH * startScale) / 2f + dp(16)
            card.elevation = dp(28).toFloat()

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
        hasFirstFrame = true
        syncGlassOrbUniforms()
        connectingPulseAnimator?.cancel()
        connectingContainer?.animate()
            ?.alpha(0f)
            ?.scaleX(0.94f)
            ?.scaleY(0.94f)
            ?.setDuration(220L)
            ?.setInterpolator(exitEase)
            ?.withEndAction { connectingContainer?.visibility = View.GONE }
            ?.start()

        videoView.animate()
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(enterEase)
            .withEndAction {
                connectingBackdrop?.visibility = View.GONE
            }
            .start()

        if (!isKeepAlive) {
            scheduleAutoDismiss(cfg.overlayDurationSec * 1000L)
        }
    }

    private fun buildConnectingBackdrop(): FrameLayout {
        val container = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#FF19202B"),
                    Color.parseColor("#FF11161F"),
                    Color.parseColor("#FF161D28"),
                ),
            )
        }

        val frostedBitmap = SnapshotStore.loadFrostedPreview(context)
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (frostedBitmap != null) {
                setImageBitmap(frostedBitmap)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        connectingPreviewImage = img
        container.addView(img)

        // Subtle Apple Liquid Glass frost veil (specular diagonal light + gentle glass sheen)
        val veil = LiquidGlassFrostVeilView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        container.addView(veil)
        return container
    }

    private fun buildConnectingIndicator(activeScale: Float): View {
        // Sleek compact translucent Apple Liquid Glass pill tightly wrapping the spinner + label
        val row = LiquidGlassPillLayout(
            context = context,
            strokeWidthPx = expDp(1, activeScale).toFloat() * 1.45f,
        ).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = expDp(20, activeScale)
            val padV = expDp(10, activeScale)
            setPadding(padH, padV, padH, padV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        val spinnerSize = expDp(15, activeScale)
        val spinner = AppleGlassSpinnerView(
            context = context,
            strokePx = expDp(2, activeScale).toFloat() * 0.9f,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                rightMargin = expDp(10, activeScale)
            }
        }
        connectingPulseAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                spinner.sweepAngleDeg = anim.animatedValue as Float
                spinner.invalidate()
            }
            start()
        }

        val label = TextView(context).apply {
            text = "Connecting…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, expDp(14, activeScale).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
            setShadowLayer(expDp(4, activeScale).toFloat(), 0f, expDp(1, activeScale).toFloat(), Color.parseColor("#78000000"))
        }

        row.addView(spinner)
        row.addView(label)
        return row
    }

    private fun buildLiquidGlassHud(
        card: FrameLayout,
        pip: PipTransform,
        exp: PipTransform,
        startFullscreen: Boolean,
    ) {
        val pipS = pip.scaleX.coerceAtLeast(0.15f)
        val expS = exp.scaleX.coerceAtLeast(0.5f)

        // 1. Directional Liquid Glass Specular Rim around card
        val rim = LiquidGlassRimView(context).apply {
            setStrokeState(
                strokeWidthPx = if (startFullscreen) expDp(1, expS).toFloat() else pipDp(2, pipS).toFloat(),
                bright = false,
            )
            alpha = if (startFullscreen) 0.75f else 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        glassRimView = rim
        card.addView(rim)

        // 2. Corner PiP Top-Right Liquid Glass Orbs (`Audio Mute/Unmute` + `✕ Close`)
        val pipOrbSize = pipDp(36, pipS)
        val pipOrbGap = pipDp(10, pipS)
        val pipOrbMargin = pipDp(10, pipS)
        val pipPad = pipDp(5, pipS)

        val pipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(pipPad, pipPad, pipPad, pipPad)
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, pipOrbMargin, pipOrbMargin, 0)
            }
        }
        pipTopRightRow = pipRow

        val pAudioOrb = LiquidGlassOrbButton(
            context = context,
            iconType = GlassIconType.AUDIO_TOGGLE,
            strokeScalePx = pipDp(1, pipS).toFloat(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(pipOrbSize, pipOrbSize).apply {
                rightMargin = pipOrbGap
            }
            setOnHoverListener { _, ev ->
                if (!isFullscreen && !isHiding && ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    setPipHovered(true, PipFocusTarget.AUDIO_BTN)
                }
                false
            }
            setOnClickListener {
                if (!isHiding) {
                    performAudioToggle()
                }
            }
        }
        pipAudioOrb = pAudioOrb

        val pCloseOrb = LiquidGlassOrbButton(
            context = context,
            iconType = GlassIconType.CLOSE_X,
            strokeScalePx = pipDp(1, pipS).toFloat(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(pipOrbSize, pipOrbSize)
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
        pipCloseOrb = pCloseOrb

        pipRow.addView(pAudioOrb)
        pipRow.addView(pCloseOrb)
        card.addView(pipRow)

        // 3. Fullscreen Top-Right Liquid Glass Icon Orbs (`Audio` + `PiP Minimize` + `✕ Close`)
        val fsRowMargin = expDp(18, expS)
        val fsOrbSize = expDp(42, expS)
        val fsOrbGap = expDp(12, expS)

        val topRightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            val pad = expDp(6, expS)
            setPadding(pad, pad, pad, pad)
            alpha = if (startFullscreen) 1f else 0f
            visibility = if (startFullscreen) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, fsRowMargin, fsRowMargin, 0)
            }
        }
        fsTopRightRow = topRightRow

        val fAudioOrb = LiquidGlassOrbButton(
            context = context,
            iconType = GlassIconType.AUDIO_TOGGLE,
            strokeScalePx = expDp(1, expS).toFloat(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(fsOrbSize, fsOrbSize).apply {
                rightMargin = fsOrbGap
            }
            setOnHoverListener { _, ev ->
                if (isFullscreen && ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    setFullscreenFocusTarget(FullscreenFocusTarget.AUDIO_BTN)
                }
                false
            }
            setOnClickListener {
                if (isFullscreen && !isHiding) {
                    performAudioToggle()
                }
            }
        }
        fsAudioOrb = fAudioOrb

        val pipMinOrb = LiquidGlassOrbButton(
            context = context,
            iconType = GlassIconType.PIP_MINIMIZE,
            strokeScalePx = expDp(1, expS).toFloat(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(fsOrbSize, fsOrbSize).apply {
                rightMargin = fsOrbGap
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
        fsPipOrb = pipMinOrb

        val fsDismissOrb = LiquidGlassOrbButton(
            context = context,
            iconType = GlassIconType.CLOSE_X,
            strokeScalePx = expDp(1, expS).toFloat(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(fsOrbSize, fsOrbSize)
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
        fsCloseOrb = fsDismissOrb

        topRightRow.addView(fAudioOrb)
        topRightRow.addView(pipMinOrb)
        topRightRow.addView(fsDismissOrb)
        card.addView(topRightRow)

        updateFullscreenHudStyles(animated = false)
    }

    private fun updatePipHudStyles(animated: Boolean, visibilityChanged: Boolean) {
        val cfg = currentCfg ?: return
        val card = cardView ?: return
        val pip = computePipTransform(cfg)
        val pipS = pip.scaleX.coerceAtLeast(0.15f)
        val rim = glassRimView ?: return
        val pipRow = pipTopRightRow ?: return
        val audioOrb = pipAudioOrb ?: return
        val closeOrb = pipCloseOrb ?: return

        if (!isPipHovered || isFullscreen) {
            rim.animate().cancel()
            pipRow.animate().cancel()
            if (animated) {
                rim.animate().alpha(0f).setDuration(160L).start()
                pipRow.animate()
                    .alpha(0f)
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(170L)
                    .setInterpolator(exitEase)
                    .start()
                if (!isFullscreen) {
                    card.animate()
                        .scaleX(pip.scaleX)
                        .scaleY(pip.scaleY)
                        .translationX(pip.x)
                        .translationY(pip.y)
                        .setDuration(200L)
                        .setInterpolator(morphEase)
                        .start()
                }
            } else {
                rim.alpha = 0f
                pipRow.alpha = 0f
                pipRow.scaleX = 0.7f
                pipRow.scaleY = 0.7f
            }
            startGlassOrbSyncBurst(220L)
            return
        }

        val onCard = pipFocusTarget == PipFocusTarget.CARD
        val onAudio = pipFocusTarget == PipFocusTarget.AUDIO_BTN
        val onDismiss = pipFocusTarget == PipFocusTarget.DISMISS_BTN

        audioOrb.setFocusedState(onAudio)
        closeOrb.setFocusedState(onDismiss)

        val cardHoverFactor = if (onCard) 1.036f else 1.0f
        val targetScaleX = pip.scaleX * cardHoverFactor
        val targetScaleY = pip.scaleY * cardHoverFactor
        val targetX = pip.x - (cfg.overlayWidthPx * (cardHoverFactor - 1f) * 0.5f)
        val targetY = pip.y - (cfg.overlayHeightPx * (cardHoverFactor - 1f) * 0.5f)

        rim.setStrokeState(pipDp(2, pipS).toFloat(), bright = onCard)
        rim.animate().cancel()
        rim.animate().alpha(if (onCard) 1f else 0.55f).setDuration(180L).start()

        card.animate().cancel()
        card.animate()
            .scaleX(targetScaleX)
            .scaleY(targetScaleY)
            .translationX(targetX)
            .translationY(targetY)
            .setDuration(220L)
            .setInterpolator(popSpring)
            .start()

        pipRow.animate().cancel()
        pipRow.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(if (visibilityChanged) 230L else 150L)
            .setInterpolator(popSpring)
            .start()

        val audioScale = if (onAudio) 1.15f else 0.98f
        val closeScale = if (onDismiss) 1.15f else 0.98f
        audioOrb.animate().scaleX(audioScale).scaleY(audioScale).setDuration(170L).setInterpolator(popSpring).start()
        closeOrb.animate().scaleX(closeScale).scaleY(closeScale).setDuration(170L).setInterpolator(popSpring).start()
        startGlassOrbSyncBurst(300L)
    }

    private fun updateFullscreenHudStyles(animated: Boolean) {
        val audioOrb = fsAudioOrb ?: return
        val pipOrb = fsPipOrb ?: return
        val closeOrb = fsCloseOrb ?: return

        val onAudio = fsFocusTarget == FullscreenFocusTarget.AUDIO_BTN
        val onPip = fsFocusTarget == FullscreenFocusTarget.PIP_BTN
        val onClose = fsFocusTarget == FullscreenFocusTarget.DISMISS_BTN

        audioOrb.setFocusedState(onAudio)
        pipOrb.setFocusedState(onPip)
        closeOrb.setFocusedState(onClose)

        val audioScale = if (onAudio) 1.14f else 0.97f
        val pipScale = if (onPip) 1.14f else 0.97f
        val closeScale = if (onClose) 1.14f else 0.97f

        if (animated) {
            audioOrb.animate().scaleX(audioScale).scaleY(audioScale).setDuration(180L).setInterpolator(popSpring).start()
            pipOrb.animate().scaleX(pipScale).scaleY(pipScale).setDuration(180L).setInterpolator(popSpring).start()
            closeOrb.animate().scaleX(closeScale).scaleY(closeScale).setDuration(180L).setInterpolator(popSpring).start()
            startGlassOrbSyncBurst(260L)
        } else {
            audioOrb.scaleX = audioScale
            audioOrb.scaleY = audioScale
            pipOrb.scaleX = pipScale
            pipOrb.scaleY = pipScale
            closeOrb.scaleX = closeScale
            closeOrb.scaleY = closeScale
            syncGlassOrbUniforms()
        }
    }

    private fun startGlassOrbSyncBurst(durationMs: Long) {
        syncGlassOrbUniforms()
        glassSyncUntilMs = maxOf(glassSyncUntilMs, SystemClock.uptimeMillis() + durationMs)
        if (!glassSyncRunning) {
            glassSyncRunning = true
            Choreographer.getInstance().postFrameCallback(glassSyncFrameCallback)
        }
    }

    private fun syncGlassOrbUniforms() {
        val rend = renderer ?: return
        val card = cardView ?: return
        val cw = card.width.toFloat()
        val ch = card.height.toFloat()
        if (cw <= 1f || ch <= 1f) return

        val row: LinearLayout?
        val o0: LiquidGlassOrbButton?
        val o1: LiquidGlassOrbButton?
        val o2: LiquidGlassOrbButton?

        if (isFullscreen) {
            row = fsTopRightRow
            o0 = fsAudioOrb
            o1 = fsPipOrb
            o2 = fsCloseOrb
        } else {
            row = pipTopRightRow
            o0 = pipAudioOrb
            o1 = pipCloseOrb
            o2 = null
        }

        if (row == null || row.visibility != View.VISIBLE || row.alpha <= 0.01f) {
            rend.glassOrbState.clearOrbs()
            return
        }

        fun mapOrb(orb: LiquidGlassOrbButton?): FloatArray {
            if (orb == null || orb.width <= 0 || orb.height <= 0) {
                return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }
            val cxRow = orb.left + orb.width * 0.5f + orb.translationX
            val cyRow = orb.top + orb.height * 0.5f + orb.translationY
            val cxCard = row.left + row.pivotX + (cxRow - row.pivotX) * row.scaleX + row.translationX
            val cyCard = row.top + row.pivotY + (cyRow - row.pivotY) * row.scaleY + row.translationY
            val rCard = orb.visualRadiusPx() * orb.scaleX * row.scaleX
            val vidCx = (cxCard / cw) * 1280f
            val vidCy = (cyCard / ch) * 720f
            val vidR = (rCard / cw) * 1280f
            val alpha = (row.alpha * orb.alpha).coerceIn(0f, 1f)
            val focused = if (orb.isFocusedState) 1f else 0f
            val active = if (orb.isActiveToggleState) 1f else 0f
            return floatArrayOf(vidCx, vidCy, vidR, alpha, focused, active)
        }

        val m0 = mapOrb(o0)
        val m1 = mapOrb(o1)
        val m2 = mapOrb(o2)
        rend.glassOrbState.setOrbs(
            m0[0], m0[1], m0[2], m0[3], m0[4], m0[5],
            m1[0], m1[1], m1[2], m1[3], m1[4], m1[5],
            m2[0], m2[1], m2[2], m2[3], m2[4], m2[5],
        )
    }

    private inner class LiquidGlassRimView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()
        private var strokeW = 4f
        private var isBright = true

        fun setStrokeState(strokeWidthPx: Float, bright: Boolean) {
            strokeW = strokeWidthPx
            isBright = bright
            paint.strokeWidth = strokeWidthPx
            updateGradient(width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateGradient(w, h)
        }

        private fun updateGradient(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            val colors = if (isBright) {
                intArrayOf(
                    Color.parseColor("#F5FFFFFF"),
                    Color.parseColor("#66D8ECFF"),
                    Color.parseColor("#33FFFFFF"),
                    Color.parseColor("#B3FFFFFF"),
                )
            } else {
                intArrayOf(
                    Color.parseColor("#88FFFFFF"),
                    Color.parseColor("#28FFFFFF"),
                    Color.parseColor("#18FFFFFF"),
                    Color.parseColor("#55FFFFFF"),
                )
            }
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                colors,
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val half = strokeW * 0.5f
            rect.set(half, half, width - half, height - half)
            canvas.drawRoundRect(rect, currentCornerRadiusPx, currentCornerRadiusPx, paint)
        }
    }

    /**
     * Subtle Apple Liquid Glass frost veil over the blurred connecting thumbnail.
     */
    private inner class LiquidGlassFrostVeilView(context: Context) : View(context) {
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            sheenPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.parseColor("#24FFFFFF"),
                    Color.parseColor("#0D0F1824"),
                    Color.parseColor("#180C121C"),
                    Color.parseColor("#1EFFFFFF"),
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sheenPaint)
        }
    }

    /**
     * Translucent Apple Liquid Glass pill LinearLayout (tightly wraps content, matching iOS 26 Button pill).
     */
    private inner class LiquidGlassPillLayout(
        context: Context,
        private val strokeWidthPx: Float,
    ) : LinearLayout(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        private val rect = RectF()

        init {
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val wf = w.toFloat()
            val hf = h.toFloat()
            fillPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                intArrayOf(
                    Color.parseColor("#42FFFFFF"),
                    Color.parseColor("#2A101824"),
                    Color.parseColor("#34FFFFFF"),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
            causticPaint.shader = LinearGradient(
                0f, 0f, 0f, hf * 0.52f,
                intArrayOf(Color.parseColor("#4CFFFFFF"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            rimPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                intArrayOf(
                    Color.parseColor("#FAFFFFFF"),
                    Color.parseColor("#66D8EEFF"),
                    Color.parseColor("#40FFFFFF"),
                    Color.parseColor("#E6FFFFFF"),
                ),
                floatArrayOf(0f, 0.38f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val wf = width.toFloat()
            val hf = height.toFloat()
            if (wf > 0f && hf > 0f) {
                val inset = strokeWidthPx * 0.6f
                val r = hf * 0.5f
                rect.set(inset, inset, wf - inset, hf - inset)
                canvas.drawRoundRect(rect, r, r, fillPaint)
                canvas.drawRoundRect(rect, r, r, causticPaint)
                canvas.drawRoundRect(rect, r, r, rimPaint)
            }
            super.onDraw(canvas)
        }
    }

    /**
     * Smooth 60 fps thin rounded-cap Apple-style arc spinner for the Liquid Glass connecting pill.
     */
    private inner class AppleGlassSpinnerView(
        context: Context,
        private val strokePx: Float,
    ) : View(context) {
        var sweepAngleDeg: Float = 0f
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = Color.parseColor("#38FFFFFF")
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val wf = width.toFloat()
            val hf = height.toFloat()
            if (wf <= 0f || hf <= 0f) return
            val pad = strokePx * 0.7f
            rect.set(pad, pad, wf - pad, hf - pad)
            canvas.drawOval(rect, trackPaint)
            canvas.drawArc(rect, sweepAngleDeg - 90f, 235f, false, arcPaint)
        }
    }

    /**
     * True Apple iOS 26 / visionOS see-through Liquid Glass circular orb button.
     *
     * The underlying live video is optically refracted + frosted directly in the OpenGL ES 2.0
     * fragment shader (WatermarkInpaintDrawer.kt). This View renders only the razor-crisp
     * specular glass rim, subtle meniscus highlight, and SF-Symbols-grade minimalist vector icon
     * so you genuinely see the live refracted scene behind each button.
     */
    private inner class LiquidGlassOrbButton(
        context: Context,
        private val iconType: GlassIconType,
        private val strokeScalePx: Float,
    ) : View(context) {

        var isFocusedState: Boolean = false
            private set
        var isActiveToggleState: Boolean = false
            private set

        private val glassTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val meniscusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val rect = RectF()
        private val speakerPath = Path()
        private val arrowPath = Path()

        init {
            // Software layer only for crisp icon/rim drop-shadow readability over blazing white daylight sky
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun visualRadiusPx(): Float {
            val wf = width.toFloat()
            val hf = height.toFloat()
            return (minOf(wf, hf) * 0.5f - rimPaint.strokeWidth * 0.7f).coerceAtLeast(1f)
        }

        fun setFocusedState(focused: Boolean) {
            if (isFocusedState == focused) return
            isFocusedState = focused
            rebuildShaders(width, height)
            invalidate()
        }

        fun setActiveToggleState(active: Boolean) {
            if (isActiveToggleState == active) return
            isActiveToggleState = active
            rebuildShaders(width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShaders(w, h)
        }

        private fun rebuildShaders(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            val wf = w.toFloat()
            val hf = h.toFloat()

            // 1. Ultra-light crystal surface wash (completely see-through so GPU refracted video shines through!)
            val tintColors = when {
                isActiveToggleState && isFocusedState -> intArrayOf(
                    Color.parseColor("#385AC8FA"),
                    Color.parseColor("#1A0A84FF"),
                    Color.parseColor("#2E64D2FF"),
                )
                isActiveToggleState -> intArrayOf(
                    Color.parseColor("#2838A8FF"),
                    Color.parseColor("#120A84FF"),
                    Color.parseColor("#205AC8FA"),
                )
                isFocusedState -> intArrayOf(
                    Color.parseColor("#38FFFFFF"),
                    Color.parseColor("#14FFFFFF"),
                    Color.parseColor("#28FFFFFF"),
                )
                else -> intArrayOf(
                    Color.parseColor("#20FFFFFF"),
                    Color.parseColor("#0A0D141F"),
                    Color.parseColor("#18FFFFFF"),
                )
            }
            glassTintPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                tintColors,
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )

            // 2. Curved top-left glass meniscus sheen (authentic Apple Liquid Glass dome light)
            val topAlpha = if (isFocusedState || isActiveToggleState) "#58FFFFFF" else "#38FFFFFF"
            val midAlpha = if (isFocusedState || isActiveToggleState) "#18E6F7FF" else "#0CFFFFFF"
            meniscusPaint.shader = RadialGradient(
                wf * 0.30f,
                hf * 0.22f,
                wf * 0.62f,
                intArrayOf(
                    Color.parseColor(topAlpha),
                    Color.parseColor(midAlpha),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )

            // 3. Soft inner luminous Fresnel ring just inside the crisp outer rim
            innerRimPaint.strokeWidth = strokeScalePx * (if (isFocusedState) 2.6f else 1.8f)
            innerRimPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                intArrayOf(
                    Color.parseColor(if (isFocusedState) "#66FFFFFF" else "#38FFFFFF"),
                    Color.parseColor("#10FFFFFF"),
                    Color.parseColor("#0CFFFFFF"),
                    Color.parseColor(if (isFocusedState) "#55FFFFFF" else "#2EFFFFFF"),
                ),
                floatArrayOf(0f, 0.38f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )

            // 4. Razor-crisp Apple double-catchlight diagonal specular rim (bright top-left & bottom-right)
            rimPaint.strokeWidth = strokeScalePx * (if (isFocusedState) 2.15f else 1.45f)
            val rimColors = if (isFocusedState || isActiveToggleState) {
                intArrayOf(
                    Color.parseColor("#FFFFFFFF"),
                    Color.parseColor("#78D8F0FF"),
                    Color.parseColor("#48FFFFFF"),
                    Color.parseColor("#F0FFFFFF"),
                )
            } else {
                intArrayOf(
                    Color.parseColor("#E6FFFFFF"),
                    Color.parseColor("#48FFFFFF"),
                    Color.parseColor("#32FFFFFF"),
                    Color.parseColor("#C2FFFFFF"),
                )
            }
            rimPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                rimColors,
                floatArrayOf(0f, 0.38f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )

            val iconColor = if (isFocusedState || isActiveToggleState) Color.WHITE else Color.parseColor("#F2FFFFFF")
            iconPaint.color = iconColor
            iconFillPaint.color = iconColor
            // Subtle crisp drop-shadow keeps icons razor-sharp over both daylight sky and night shadows
            val shadowRadius = strokeScalePx * 3.2f
            val shadowDy = strokeScalePx * 0.9f
            val shadowColor = Color.parseColor("#88000000")
            iconPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
            iconFillPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
            rimPaint.setShadowLayer(strokeScalePx * 2.2f, 0f, strokeScalePx * 0.6f, Color.parseColor("#40000000"))
        }

        override fun onDraw(canvas: Canvas) {
            val wf = width.toFloat()
            val hf = height.toFloat()
            if (wf <= 0f || hf <= 0f) return

            val cx = wf * 0.5f
            val cy = hf * 0.5f
            val radius = visualRadiusPx()

            canvas.drawCircle(cx, cy, radius, glassTintPaint)
            canvas.drawCircle(cx, cy, radius, meniscusPaint)
            canvas.drawCircle(cx, cy, (radius - innerRimPaint.strokeWidth * 0.55f).coerceAtLeast(1f), innerRimPaint)
            canvas.drawCircle(cx, cy, radius, rimPaint)

            when (iconType) {
                GlassIconType.CLOSE_X -> {
                    // Refined slender SF-Symbol `xmark`
                    iconPaint.strokeWidth = strokeScalePx * 1.85f
                    val d = radius * 0.30f
                    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
                    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconPaint)
                }
                GlassIconType.PIP_MINIMIZE -> {
                    // Refined SF-Symbol `pip.enter`: outer screen + sleek corner PiP + subtle inward arrow
                    iconPaint.strokeWidth = strokeScalePx * 1.55f
                    val ow = radius * 0.92f
                    val oh = radius * 0.68f
                    val cr = strokeScalePx * 2.6f
                    val left = cx - ow * 0.5f
                    val top = cy - oh * 0.5f
                    val right = cx + ow * 0.5f
                    val bottom = cy + oh * 0.5f
                    rect.set(left, top, right, bottom)
                    canvas.drawRoundRect(rect, cr, cr, iconPaint)

                    // Solid rounded PiP inset in top-right corner (matching user's TOP_RIGHT PiP position!)
                    val pw = ow * 0.42f
                    val ph = oh * 0.42f
                    val inset = strokeScalePx * 2.1f
                    val pLeft = right - inset - pw
                    val pTop = top + inset
                    rect.set(pLeft, pTop, right - inset, pTop + ph)
                    canvas.drawRoundRect(rect, strokeScalePx * 1.4f, strokeScalePx * 1.4f, iconFillPaint)

                    // Crisp delicate diagonal arrow from bottom-left pointing toward the PiP corner
                    val ax0 = left + ow * 0.22f
                    val ay0 = bottom - oh * 0.24f
                    val ax1 = pLeft - strokeScalePx * 1.6f
                    val ay1 = pTop + ph + strokeScalePx * 1.2f
                    iconPaint.strokeWidth = strokeScalePx * 1.35f
                    canvas.drawLine(ax0, ay0, ax1, ay1, iconPaint)
                    val head = strokeScalePx * 2.8f
                    arrowPath.reset()
                    arrowPath.moveTo(ax1 - head, ay1)
                    arrowPath.lineTo(ax1, ay1)
                    arrowPath.lineTo(ax1, ay1 + head)
                    canvas.drawPath(arrowPath, iconPaint)
                }
                GlassIconType.AUDIO_TOGGLE -> {
                    // Refined SF-Symbol `speaker.wave.2.fill` / `speaker.slash.fill`
                    val shiftX = if (isActiveToggleState) -radius * 0.08f else 0f
                    val spkLeft = cx + shiftX - radius * 0.36f
                    val spkMid = cx + shiftX - radius * 0.18f
                    val spkRight = cx + shiftX + radius * 0.05f
                    val boxHalfH = radius * 0.15f
                    val coneHalfH = radius * 0.34f

                    // Rounded speaker silhouette
                    speakerPath.reset()
                    speakerPath.moveTo(spkLeft + strokeScalePx, cy - boxHalfH)
                    speakerPath.lineTo(spkMid, cy - boxHalfH)
                    speakerPath.lineTo(spkRight, cy - coneHalfH)
                    speakerPath.lineTo(spkRight, cy + coneHalfH)
                    speakerPath.lineTo(spkMid, cy + boxHalfH)
                    speakerPath.lineTo(spkLeft + strokeScalePx, cy + boxHalfH)
                    speakerPath.quadTo(spkLeft, cy + boxHalfH, spkLeft, cy + boxHalfH - strokeScalePx)
                    speakerPath.lineTo(spkLeft, cy - boxHalfH + strokeScalePx)
                    speakerPath.quadTo(spkLeft, cy - boxHalfH, spkLeft + strokeScalePx, cy - boxHalfH)
                    speakerPath.close()
                    canvas.drawPath(speakerPath, iconFillPaint)

                    iconPaint.strokeWidth = strokeScalePx * 1.65f
                    if (isActiveToggleState) {
                        // Two graceful concentric Apple soundwave arcs
                        val r1 = radius * 0.23f
                        rect.set(spkRight - r1 * 0.25f, cy - r1, spkRight + r1 * 1.35f, cy + r1)
                        canvas.drawArc(rect, -40f, 80f, false, iconPaint)

                        val r2 = radius * 0.41f
                        rect.set(spkRight - r2 * 0.25f, cy - r2, spkRight + r2 * 1.35f, cy + r2)
                        canvas.drawArc(rect, -44f, 88f, false, iconPaint)
                    } else {
                        // Iconic clean SF-Symbol diagonal mute slash across speaker
                        val sd = radius * 0.35f
                        canvas.drawLine(cx - sd, cy - sd, cx + sd, cy + sd, iconPaint)
                    }
                }
            }
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
                glassRimView?.invalidate()
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
        private const val PIP_MARGIN_DP = 24
        private const val KEEP_ALIVE_SAFETY_CAP_MS = 5 * 60 * 1000L
    }
}
