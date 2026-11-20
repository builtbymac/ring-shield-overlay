package com.builtbymac.ringshield

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
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

    private var connectingContainer: View? = null
    private var connectingPulseAnimator: ObjectAnimator? = null

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
            setBackgroundColor(Color.parseColor("#FF10151E"))
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
        connectingContainer = null
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

        if (!isKeepAlive) {
            scheduleAutoDismiss(cfg.overlayDurationSec * 1000L)
        }
    }

    private fun buildConnectingIndicator(activeScale: Float): View {
        val pillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = expDp(999, activeScale).toFloat()
            setColor(Color.parseColor("#88161E2B"))
            setStroke(expDp(1, activeScale), Color.parseColor("#44FFFFFF"))
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
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, expDp(13, activeScale).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(dot)
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

        val audioScale = if (onAudio) 1.16f else 0.98f
        val closeScale = if (onDismiss) 1.16f else 0.98f
        audioOrb.animate().scaleX(audioScale).scaleY(audioScale).setDuration(170L).setInterpolator(popSpring).start()
        closeOrb.animate().scaleX(closeScale).scaleY(closeScale).setDuration(170L).setInterpolator(popSpring).start()
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

        val audioScale = if (onAudio) 1.15f else 0.96f
        val pipScale = if (onPip) 1.15f else 0.96f
        val closeScale = if (onClose) 1.15f else 0.96f

        if (animated) {
            audioOrb.animate().scaleX(audioScale).scaleY(audioScale).setDuration(180L).setInterpolator(popSpring).start()
            pipOrb.animate().scaleX(pipScale).scaleY(pipScale).setDuration(180L).setInterpolator(popSpring).start()
            closeOrb.animate().scaleX(closeScale).scaleY(closeScale).setDuration(180L).setInterpolator(popSpring).start()
        } else {
            audioOrb.scaleX = audioScale
            audioOrb.scaleY = audioScale
            pipOrb.scaleX = pipScale
            pipOrb.scaleY = pipScale
            closeOrb.scaleX = closeScale
            closeOrb.scaleY = closeScale
        }
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
     * Custom-rendered Apple visionOS / iOS Liquid Glass circular orb button.
     */
    private inner class LiquidGlassOrbButton(
        context: Context,
        private val iconType: GlassIconType,
        private val strokeScalePx: Float,
    ) : View(context) {

        private var isFocusedOrb = false
        private var isActiveToggle = false
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

        fun setFocusedState(focused: Boolean) {
            if (isFocusedOrb == focused) return
            isFocusedOrb = focused
            rebuildShaders(width, height)
            invalidate()
        }

        fun setActiveToggleState(active: Boolean) {
            if (isActiveToggle == active) return
            isActiveToggle = active
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

            // 1. Translucent Liquid Glass body fill (luminous cyan-glass tint when audio toggle is active)
            val bodyColors = when {
                isActiveToggle && isFocusedOrb -> intArrayOf(
                    Color.parseColor("#B02D6A9F"),
                    Color.parseColor("#961E4870"),
                    Color.parseColor("#A6265C8C"),
                )
                isActiveToggle -> intArrayOf(
                    Color.parseColor("#8C22537D"),
                    Color.parseColor("#78163754"),
                    Color.parseColor("#851D476B"),
                )
                isFocusedOrb -> intArrayOf(
                    Color.parseColor("#9434465E"),
                    Color.parseColor("#801C2636"),
                    Color.parseColor("#8C2A384D"),
                )
                else -> intArrayOf(
                    Color.parseColor("#6E161E2B"),
                    Color.parseColor("#5C0D121A"),
                    Color.parseColor("#66131A26"),
                )
            }
            bodyPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                bodyColors,
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )

            // 2. Top-left internal specular lens caustic refraction
            val causticTopAlpha = if (isFocusedOrb || isActiveToggle) "#66FFFFFF" else "#2EFFFFFF"
            val causticMidAlpha = if (isFocusedOrb || isActiveToggle) "#22D4F0FF" else "#0CFFFFFF"
            lensPaint.shader = RadialGradient(
                wf * 0.30f,
                hf * 0.24f,
                wf * 0.68f,
                intArrayOf(
                    Color.parseColor(causticTopAlpha),
                    Color.parseColor(causticMidAlpha),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )

            // 3. Directional specular glass rim
            rimPaint.strokeWidth = strokeScalePx * (if (isFocusedOrb) 1.85f else 1.25f)
            val rimColors = if (isFocusedOrb || isActiveToggle) {
                intArrayOf(
                    Color.parseColor("#FFFFFFFF"),
                    Color.parseColor("#80B8E8FF"),
                    Color.parseColor("#40FFFFFF"),
                    Color.parseColor("#D9FFFFFF"),
                )
            } else {
                intArrayOf(
                    Color.parseColor("#99FFFFFF"),
                    Color.parseColor("#33FFFFFF"),
                    Color.parseColor("#1EFFFFFF"),
                    Color.parseColor("#66FFFFFF"),
                )
            }
            rimPaint.shader = LinearGradient(
                0f, 0f, wf, hf,
                rimColors,
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )

            iconPaint.color = if (isFocusedOrb || isActiveToggle) Color.WHITE else Color.parseColor("#D9FFFFFF")
            iconFillPaint.color = iconPaint.color
        }

        override fun onDraw(canvas: Canvas) {
            val wf = width.toFloat()
            val hf = height.toFloat()
            if (wf <= 0f || hf <= 0f) return

            val cx = wf * 0.5f
            val cy = hf * 0.5f
            val radius = minOf(wf, hf) * 0.5f - rimPaint.strokeWidth * 0.6f

            canvas.drawCircle(cx, cy, radius, bodyPaint)
            canvas.drawCircle(cx, cy, radius, lensPaint)
            canvas.drawCircle(cx, cy, radius, rimPaint)

            when (iconType) {
                GlassIconType.CLOSE_X -> {
                    iconPaint.strokeWidth = strokeScalePx * 1.95f
                    val d = radius * 0.34f
                    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
                    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconPaint)
                }
                GlassIconType.PIP_MINIMIZE -> {
                    iconPaint.strokeWidth = strokeScalePx * 1.65f
                    val ow = radius * 0.88f
                    val oh = radius * 0.66f
                    val cr = strokeScalePx * 2.2f
                    rect.set(cx - ow * 0.5f, cy - oh * 0.5f, cx + ow * 0.5f, cy + oh * 0.5f)
                    canvas.drawRoundRect(rect, cr, cr, iconPaint)

                    val pw = ow * 0.42f
                    val ph = oh * 0.42f
                    val inset = strokeScalePx * 2.1f
                    rect.set(
                        cx + ow * 0.5f - inset - pw,
                        cy - oh * 0.5f + inset,
                        cx + ow * 0.5f - inset,
                        cy - oh * 0.5f + inset + ph,
                    )
                    val pcr = strokeScalePx * 1.2f
                    canvas.drawRoundRect(rect, pcr, pcr, iconFillPaint)
                }
                GlassIconType.AUDIO_TOGGLE -> {
                    // Geometric SF-Symbol style Speaker Cone + Sound Waves (+ Mute Slash when muted)
                    val spkLeft = cx - radius * 0.42f
                    val spkMid = cx - radius * 0.24f
                    val spkRight = cx - radius * 0.02f
                    val boxHalfH = radius * 0.16f
                    val coneHalfH = radius * 0.36f

                    speakerPath.reset()
                    speakerPath.moveTo(spkLeft, cy - boxHalfH)
                    speakerPath.lineTo(spkMid, cy - boxHalfH)
                    speakerPath.lineTo(spkRight, cy - coneHalfH)
                    speakerPath.lineTo(spkRight, cy + coneHalfH)
                    speakerPath.lineTo(spkMid, cy + boxHalfH)
                    speakerPath.lineTo(spkLeft, cy + boxHalfH)
                    speakerPath.close()
                    canvas.drawPath(speakerPath, iconFillPaint)

                    iconPaint.strokeWidth = strokeScalePx * 1.65f
                    if (isActiveToggle) {
                        // Active audio waves: two crisp arcs radiating right
                        val r1 = radius * 0.24f
                        rect.set(spkRight - r1 * 0.3f, cy - r1, spkRight + r1 * 1.3f, cy + r1)
                        canvas.drawArc(rect, -42f, 84f, false, iconPaint)

                        val r2 = radius * 0.42f
                        rect.set(spkRight - r2 * 0.3f, cy - r2, spkRight + r2 * 1.3f, cy + r2)
                        canvas.drawArc(rect, -45f, 90f, false, iconPaint)
                    } else {
                        // Muted speaker: small x mark to the right of the speaker cone
                        val mx = cx + radius * 0.23f
                        val md = radius * 0.16f
                        canvas.drawLine(mx - md, cy - md, mx + md, cy + md, iconPaint)
                        canvas.drawLine(mx + md, cy - md, mx - md, cy + md, iconPaint)
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
