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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.EglBase

/**
 * Apple tvOS-grade 60 fps interactive PiP & 88% Rounded Theater Card overlay.
 *
 * Visual & interaction design:
 *  - Connecting Preview: Silky frosted Gaussian blur of the last captured doorbell snapshot
 *    behind a compact translucent Liquid Glass "Connecting…" pill with a rotating glass arc spinner.
 *  - Corner PiP (Un-hovered): 100% pure live video card with 20s auto-dismiss timer.
 *  - Corner PiP (Hovered):
 *      • Auto-dismiss cancelled (keep-alive engaged).
 *      • Focusing the PiP card subtly swells it (1.032x) with a specular rim highlight (click -> Fullscreen).
 *      • A sleek dark smoked-glass Apple tvOS control capsule (`[ Audio | ✕ ]`) glides smoothly into
 *        the top-right corner with a 60 fps sliding white focus pill and crisp dark-icon inversion.
 *  - 88% Rounded Theater Fullscreen:
 *      • Zero background dimming.
 *      • Sleek dark smoked-glass Apple tvOS control capsule (`[ Audio | PiP | ✕ ]`) in the top-right
 *        corner with razor contrast over both blazing white daytime sky and pitch-black night.
 *      • Returning to PiP fades the fullscreen dock out cleanly before the card shrinks (zero glitches)
 *        and preserves keep-alive so the PiP stays on screen until dismissed.
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

    private enum class DockIconType {
        AUDIO_TOGGLE,
        PIP_MINIMIZE,
        CLOSE_X,
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val morphEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val exitEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val popSpring = OvershootInterpolator(1.25f)

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

    private var glassRimView: LiquidGlassRimView? = null

    // Sleek dark smoked-glass Apple tvOS control docks (PiP: 2 slots, Fullscreen: 3 slots)
    private var pipDock: SmokedGlassControlDock? = null
    private var fsDock: SmokedGlassControlDock? = null
    private var pipDockSlidePx: Float = 0f
    private var fsDockSlidePx: Float = 0f

    private var dotAnimator: ObjectAnimator? = null
    private var cornerAnimator: ValueAnimator? = null
    private var dismissRunnable: Runnable? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var onKeepAliveCallback: (() -> Unit)? = null
    private var onToggleAudioCallback: (() -> Boolean)? = null
    private var onOverlayStateChangedCallback: ((Boolean, Boolean) -> Unit)? = null

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
    private var fsFocusTarget: FullscreenFocusTarget = FullscreenFocusTarget.DISMISS_BTN
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
        onOverlayStateChanged: (showing: Boolean, fullscreen: Boolean) -> Unit = { _, _ -> },
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
        onOverlayStateChangedCallback = onOverlayStateChanged
        isFullscreen = startFullscreen
        isKeepAlive = keepAliveUntilDismissed || startFullscreen
        isAudioActive = false
        isPipHovered = false
        pipFocusTarget = PipFocusTarget.CARD
        fsFocusTarget = FullscreenFocusTarget.DISMISS_BTN
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
            // Keep elevation at 0f to avoid Android 11 Tegra Skia's rectangular spot-shadow artifact
            // underneath scaled rounded TYPE_APPLICATION_OVERLAY cards
            elevation = 0f
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

        // 1. Frosted Liquid Glass connecting backdrop showing blurred last doorbell thumbnail
        val backdrop = buildConnectingBackdrop()
        connectingBackdrop = backdrop
        card.addView(
            backdrop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        // 2. Live WebRTC GPU video surface
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

        // 3. Compact translucent Liquid Glass "Connecting…" pill
        val connectingView = buildConnectingIndicator(if (startFullscreen) exp.scaleX else pip.scaleX)
        connectingContainer = connectingView
        card.addView(connectingView)

        if (cfg.showLiveDot) {
            card.addView(buildSubtleLiveDot(pip.scaleX))
        }

        // 4. Apple tvOS dark smoked-glass HUD docks & specular card rim
        buildControlHud(card, pip, exp, startFullscreen)

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
        onOverlayStateChangedCallback?.invoke(true, startFullscreen)

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
                            PipFocusTarget.AUDIO_BTN -> {
                                pipDock?.triggerPressBounce()
                                performAudioToggle()
                            }
                            PipFocusTarget.DISMISS_BTN -> {
                                pipDock?.triggerPressBounce()
                                dismissAnimated()
                            }
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
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    when (fsFocusTarget) {
                        FullscreenFocusTarget.AUDIO_BTN -> {
                            fsDock?.triggerPressBounce()
                            performAudioToggle()
                        }
                        FullscreenFocusTarget.PIP_BTN -> {
                            fsDock?.triggerPressBounce()
                            setFullscreen(false)
                        }
                        FullscreenFocusTarget.DISMISS_BTN -> {
                            fsDock?.triggerPressBounce()
                            dismissAnimated()
                        }
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
        pipDock?.setAudioActive(active)
        fsDock?.setAudioActive(active)
    }

    fun updateFrostedPreview(frostedBitmap: Bitmap) {
        mainHandler.post {
            val img = connectingPreviewImage ?: return@post
            if (!isShowing || isHiding || hasFirstFrame) return@post
            img.setImageBitmap(frostedBitmap)
            img.alpha = 0f
            img.animate().alpha(1f).setDuration(220L).setInterpolator(enterEase).start()
        }
    }

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
            onOverlayStateChangedCallback?.invoke(true, expand)

            card.animate().cancel()
            cornerAnimator?.cancel()
            host.requestFocus()

            val pip = computePipTransform(cfg)
            val exp = computeExpandedTransform()
            card.pivotX = 0f
            card.pivotY = 0f

            if (expand) {
                // 1. Cleanly dismiss PiP dock immediately before card grows (prevents scale distortion)
                isPipHovered = false
                pipFocusTarget = PipFocusTarget.CARD
                pipDock?.let { dock ->
                    dock.animate().cancel()
                    dock.setFocusedSlot(-1, animated = false)
                    if (dock.visibility == View.VISIBLE) {
                        dock.animate()
                            .alpha(0f)
                            .setDuration(80L)
                            .setInterpolator(exitEase)
                            .withEndAction {
                                if (isFullscreen) {
                                    dock.visibility = View.GONE
                                    dock.translationY = -pipDockSlidePx
                                }
                            }
                            .start()
                    }
                }

                // 2. Stage Fullscreen dock to glide in gracefully with Close (`✕`, slot 2) focused by default
                fsFocusTarget = FullscreenFocusTarget.DISMISS_BTN
                fsDock?.let { dock ->
                    dock.animate().cancel()
                    dock.setAudioActive(isAudioActive)
                    dock.setFocusedSlot(2, animated = false)
                    dock.alpha = 0f
                    dock.translationY = -fsDockSlidePx
                    dock.visibility = View.VISIBLE
                    dock.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(150L)
                        .setDuration(220L)
                        .setInterpolator(enterEase)
                        .start()
                }

                glassRimView?.apply {
                    setStrokeState(expDp(1, exp.scaleX).toFloat(), bright = false)
                    animate().cancel()
                    animate().alpha(0.70f).setDuration(260L).start()
                }

                card.elevation = 0f
                animateCornerRadius(card, currentCornerRadiusPx, exp.unscaledCornerRadius, 400L)

                card.animate()
                    .scaleX(exp.scaleX)
                    .scaleY(exp.scaleY)
                    .translationX(exp.x)
                    .translationY(exp.y)
                    .setDuration(400L)
                    .setInterpolator(morphEase)
                    .start()
            } else {
                // 1. Fade Fullscreen dock out crisply BEFORE the card shrinks (no shrinking-ant glitch!)
                fsDock?.let { dock ->
                    dock.animate().cancel()
                    dock.animate()
                        .alpha(0f)
                        .setStartDelay(0L)
                        .setDuration(85L)
                        .setInterpolator(exitEase)
                        .withEndAction {
                            if (!isFullscreen) {
                                dock.visibility = View.GONE
                                dock.translationY = -fsDockSlidePx
                            }
                        }
                        .start()
                }

                // 2. Reset PiP dock cleanly to ready hidden state (next D-pad tap reveals it cleanly)
                isPipHovered = false
                pipFocusTarget = PipFocusTarget.CARD
                pipDock?.let { dock ->
                    dock.animate().cancel()
                    dock.setAudioActive(isAudioActive)
                    dock.setFocusedSlot(-1, animated = false)
                    dock.alpha = 0f
                    dock.translationY = -pipDockSlidePx
                    dock.visibility = View.GONE
                }

                glassRimView?.apply {
                    animate().cancel()
                    animate().alpha(0f).setDuration(160L).start()
                }

                card.elevation = 0f
                animateCornerRadius(card, currentCornerRadiusPx, pip.unscaledCornerRadius, 360L)

                card.animate()
                    .scaleX(pip.scaleX)
                    .scaleY(pip.scaleY)
                    .translationX(pip.x)
                    .translationY(pip.y)
                    .setDuration(360L)
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
            pipDock?.animate()?.cancel()
            fsDock?.animate()?.cancel()

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
        pipDock?.cancelAnimations()
        fsDock?.cancelAnimations()
        val stateCb = onOverlayStateChangedCallback
        val wasShowing = isShowing
        onDismissCallback = null
        onKeepAliveCallback = null
        onToggleAudioCallback = null
        onOverlayStateChangedCallback = null

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
        pipDock = null
        fsDock = null
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
        if (wasShowing) {
            stateCb?.invoke(false, false)
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
            card.elevation = 0f

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

        card.elevation = 0f
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
        connectingPulseAnimator?.cancel()
        connectingContainer?.animate()
            ?.alpha(0f)
            ?.scaleX(0.88f)
            ?.scaleY(0.88f)
            ?.setDuration(240L)
            ?.setInterpolator(exitEase)
            ?.withEndAction { connectingContainer?.visibility = View.GONE }
            ?.start()

        // Optical lens focus-settle reveal: video gently settles from 1.026x -> 1.0x as frost dissolves
        videoView.scaleX = 1.026f
        videoView.scaleY = 1.026f
        videoView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320L)
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

    private fun buildControlHud(
        card: FrameLayout,
        pip: PipTransform,
        exp: PipTransform,
        startFullscreen: Boolean,
    ) {
        val pipS = pip.scaleX.coerceAtLeast(0.15f)
        val expS = exp.scaleX.coerceAtLeast(0.5f)

        // 1. Subtle specular rim around the card
        val rim = LiquidGlassRimView(context).apply {
            setStrokeState(
                strokeWidthPx = if (startFullscreen) expDp(1, expS).toFloat() else pipDp(2, pipS).toFloat(),
                bright = false,
            )
            alpha = if (startFullscreen) 0.70f else 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        glassRimView = rim
        card.addView(rim)

        // 2. Corner PiP Top-Right Dark Smoked Glass Capsule Dock (`[ Audio | ✕ ]`)
        val pipMargin = pipDp(12, pipS)
        pipDockSlidePx = pipDp(6, pipS).toFloat()
        val pDock = SmokedGlassControlDock(
            context = context,
            icons = listOf(DockIconType.AUDIO_TOGGLE, DockIconType.CLOSE_X),
            slotSizePx = pipDp(34, pipS).toFloat(),
            slotGapPx = pipDp(4, pipS).toFloat(),
            dockPadPx = pipDp(4, pipS).toFloat(),
            strokeUnitPx = pipDp(1, pipS).toFloat(),
            onSlotHovered = { idx ->
                if (!isFullscreen && !isHiding) {
                    val target = if (idx == 0) PipFocusTarget.AUDIO_BTN else PipFocusTarget.DISMISS_BTN
                    setPipHovered(true, target)
                }
            },
            onSlotClicked = { idx ->
                if (!isHiding) {
                    if (idx == 0) performAudioToggle() else dismissAnimated()
                }
            },
        ).apply {
            alpha = 0f
            translationY = -pipDockSlidePx
            visibility = View.GONE
            elevation = pipDp(6, pipS).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, pipMargin, pipMargin, 0)
            }
        }
        pipDock = pDock
        card.addView(pDock)

        // 3. Fullscreen Top-Right Dark Smoked Glass Capsule Dock (`[ Audio | PiP | ✕ ]`)
        val fsMargin = expDp(20, expS)
        fsDockSlidePx = expDp(8, expS).toFloat()
        val fDock = SmokedGlassControlDock(
            context = context,
            icons = listOf(DockIconType.AUDIO_TOGGLE, DockIconType.PIP_MINIMIZE, DockIconType.CLOSE_X),
            slotSizePx = expDp(42, expS).toFloat(),
            slotGapPx = expDp(5, expS).toFloat(),
            dockPadPx = expDp(5, expS).toFloat(),
            strokeUnitPx = expDp(1, expS).toFloat(),
            onSlotHovered = { idx ->
                if (isFullscreen && !isHiding) {
                    val target = when (idx) {
                        0 -> FullscreenFocusTarget.AUDIO_BTN
                        1 -> FullscreenFocusTarget.PIP_BTN
                        else -> FullscreenFocusTarget.DISMISS_BTN
                    }
                    setFullscreenFocusTarget(target)
                }
            },
            onSlotClicked = { idx ->
                if (isFullscreen && !isHiding) {
                    when (idx) {
                        0 -> performAudioToggle()
                        1 -> setFullscreen(false)
                        else -> dismissAnimated()
                    }
                }
            },
        ).apply {
            setFocusedSlot(2, animated = false)
            alpha = if (startFullscreen) 1f else 0f
            translationY = if (startFullscreen) 0f else -fsDockSlidePx
            visibility = if (startFullscreen) View.VISIBLE else View.GONE
            elevation = expDp(8, expS).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, fsMargin, fsMargin, 0)
            }
        }
        fsDock = fDock
        card.addView(fDock)
    }

    private fun updatePipHudStyles(animated: Boolean, visibilityChanged: Boolean) {
        val cfg = currentCfg ?: return
        val card = cardView ?: return
        val pip = computePipTransform(cfg)
        val pipS = pip.scaleX.coerceAtLeast(0.15f)
        val rim = glassRimView ?: return
        val dock = pipDock ?: return

        if (!isPipHovered || isFullscreen) {
            rim.animate().cancel()
            dock.animate().cancel()
            dock.setFocusedSlot(-1, animated = animated)
            if (animated) {
                rim.animate().alpha(0f).setDuration(160L).start()
                if (dock.visibility == View.VISIBLE) {
                    dock.animate()
                        .alpha(0f)
                        .translationY(-pipDockSlidePx)
                        .setDuration(150L)
                        .setInterpolator(exitEase)
                        .withEndAction {
                            if (!isPipHovered || isFullscreen) {
                                dock.visibility = View.GONE
                            }
                        }
                        .start()
                }
                if (!isFullscreen) {
                    card.elevation = 0f
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
                dock.alpha = 0f
                dock.translationY = -pipDockSlidePx
                dock.visibility = View.GONE
            }
            return
        }

        val onCard = pipFocusTarget == PipFocusTarget.CARD
        val focusedSlot = when (pipFocusTarget) {
            PipFocusTarget.CARD -> -1
            PipFocusTarget.AUDIO_BTN -> 0
            PipFocusTarget.DISMISS_BTN -> 1
        }
        dock.setFocusedSlot(focusedSlot, animated = animated && !visibilityChanged)

        val cardHoverFactor = if (onCard) 1.032f else 1.0f
        val targetScaleX = pip.scaleX * cardHoverFactor
        val targetScaleY = pip.scaleY * cardHoverFactor
        val targetX = pip.x - (cfg.overlayWidthPx * (cardHoverFactor - 1f) * 0.5f)
        val targetY = pip.y - (cfg.overlayHeightPx * (cardHoverFactor - 1f) * 0.5f)

        rim.setStrokeState(pipDp(2, pipS).toFloat(), bright = onCard)
        rim.animate().cancel()
        rim.animate().alpha(if (onCard) 1f else 0.50f).setDuration(170L).start()

        card.elevation = 0f
        card.animate().cancel()
        card.animate()
            .scaleX(targetScaleX)
            .scaleY(targetScaleY)
            .translationX(targetX)
            .translationY(targetY)
            .setDuration(210L)
            .setInterpolator(popSpring)
            .start()

        dock.animate().cancel()
        if (dock.visibility != View.VISIBLE) {
            dock.alpha = 0f
            dock.translationY = -pipDockSlidePx
            dock.visibility = View.VISIBLE
        }
        dock.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(if (visibilityChanged) 200L else 130L)
            .setInterpolator(enterEase)
            .start()
    }

    private fun updateFullscreenHudStyles(animated: Boolean) {
        val dock = fsDock ?: return
        val slot = when (fsFocusTarget) {
            FullscreenFocusTarget.AUDIO_BTN -> 0
            FullscreenFocusTarget.PIP_BTN -> 1
            FullscreenFocusTarget.DISMISS_BTN -> 2
        }
        dock.setFocusedSlot(slot, animated = animated)
    }

    /**
     * Sleek Apple tvOS dark smoked-glass capsule control dock.
     *
     * Features:
     *  - Deep translucent obsidian capsule with specular top sheen & crisp 1px glass hair-rim
     *    (effortless high contrast over both pure white daytime sky and pitch-black night).
     *  - Silky 60 fps sliding pure-white Apple tvOS focus pill with crisp dark-charcoal icon inversion.
     *  - Luminous Apple Blue (#0A84FF) circular badge when doorbell Audio is active ON.
     *  - 100% hardware-accelerated single-View rendering (zero layout jitter or software-layer clipping).
     */
    private inner class SmokedGlassControlDock(
        context: Context,
        private val icons: List<DockIconType>,
        private val slotSizePx: Float,
        private val slotGapPx: Float,
        private val dockPadPx: Float,
        private val strokeUnitPx: Float,
        private val onSlotHovered: (Int) -> Unit,
        private val onSlotClicked: (Int) -> Unit,
    ) : View(context) {

        private var focusedSlot: Int = -1
        private var audioActive: Boolean = false

        // Smooth 60 fps animated focus highlight, press-bounce, audio mute<->unmute morph, and wave phase
        private var focusSlidePos: Float = 0f
        private var focusAlpha: Float = 0f
        private var focusStretch: Float = 0f
        private var pressScale: Float = 1f
        private var audioMorph: Float = 0f
        private var audioWavePhase: Float = 0f
        private var slideAnim: ValueAnimator? = null
        private var alphaAnim: ValueAnimator? = null
        private var pressAnim: ValueAnimator? = null
        private var audioMorphAnim: ValueAnimator? = null
        private var waveAnim: ValueAnimator? = null
        private val slideEase = PathInterpolator(0.22f, 1.07f, 0.36f, 1f)

        private val dockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dockSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dockRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeUnitPx * 1.15f
        }
        private val focusFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FAFFFFFF")
        }
        private val activeBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF0A84FF")
        }
        private val activeBlueFocusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeUnitPx * 2.1f
            color = Color.WHITE
        }
        private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val rect = RectF()
        private val speakerPath = Path()
        private val arrowPath = Path()

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = view.height * 0.5f
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = false
        }

        fun setAudioActive(active: Boolean, animated: Boolean = true) {
            if (audioActive == active && (!animated || audioMorph == (if (active) 1f else 0f))) return
            audioActive = active
            val target = if (active) 1f else 0f
            audioMorphAnim?.cancel()
            if (animated && visibility == View.VISIBLE) {
                audioMorphAnim = ValueAnimator.ofFloat(audioMorph, target).apply {
                    duration = 340L
                    interpolator = enterEase
                    addUpdateListener {
                        audioMorph = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                audioMorph = target
            }
            if (active) {
                startAudioWaveLoop()
            } else {
                stopAudioWaveLoop()
            }
            invalidate()
        }

        fun triggerPressBounce() {
            pressAnim?.cancel()
            pressAnim = ValueAnimator.ofFloat(1f, 0.86f, 1f).apply {
                duration = 150L
                interpolator = enterEase
                addUpdateListener {
                    pressScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun startAudioWaveLoop() {
            if (waveAnim?.isRunning == true) return
            waveAnim?.cancel()
            waveAnim = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 1350L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    audioWavePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopAudioWaveLoop() {
            waveAnim?.cancel()
            waveAnim = null
            audioWavePhase = 0f
        }

        fun setFocusedSlot(slot: Int, animated: Boolean) {
            val prev = focusedSlot
            focusedSlot = slot
            if (slot < 0) {
                alphaAnim?.cancel()
                if (animated && focusAlpha > 0f) {
                    alphaAnim = ValueAnimator.ofFloat(focusAlpha, 0f).apply {
                        duration = 140L
                        interpolator = exitEase
                        addUpdateListener {
                            focusAlpha = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                } else {
                    focusAlpha = 0f
                    invalidate()
                }
                return
            }

            val targetX = slot.toFloat()
            slideAnim?.cancel()
            alphaAnim?.cancel()

            if (!animated || prev < 0 || focusAlpha <= 0.05f) {
                focusSlidePos = targetX
                if (animated) {
                    alphaAnim = ValueAnimator.ofFloat(focusAlpha, 1f).apply {
                        duration = 150L
                        interpolator = slideEase
                        addUpdateListener {
                            focusAlpha = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                } else {
                    focusAlpha = 1f
                    invalidate()
                }
            } else {
                focusAlpha = 1f
                slideAnim = ValueAnimator.ofFloat(focusSlidePos, targetX).apply {
                    duration = 190L
                    interpolator = slideEase
                    addUpdateListener {
                        focusSlidePos = it.animatedValue as Float
                        // Subtle Dynamic-Island horizontal stretch peaking mid-glide
                        val frac = it.animatedFraction.coerceIn(0f, 1f)
                        focusStretch = kotlin.math.sin(frac * Math.PI).toFloat() * 0.14f
                        invalidate()
                    }
                    start()
                }
            }
        }

        fun cancelAnimations() {
            slideAnim?.cancel()
            slideAnim = null
            alphaAnim?.cancel()
            alphaAnim = null
            pressAnim?.cancel()
            pressAnim = null
            audioMorphAnim?.cancel()
            audioMorphAnim = null
            stopAudioWaveLoop()
        }

        private fun slotCenterX(slotPos: Float): Float =
            dockPadPx + slotSizePx * 0.5f + slotPos * (slotSizePx + slotGapPx)

        private fun hitSlot(x: Float): Int {
            for (i in icons.indices) {
                val cx = slotCenterX(i.toFloat())
                if (kotlin.math.abs(x - cx) <= (slotSizePx + slotGapPx) * 0.5f) {
                    return i
                }
            }
            return -1
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
            ) {
                val idx = hitSlot(event.x)
                if (idx >= 0) onSlotHovered(idx)
            }
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val idx = hitSlot(event.x)
                if (idx >= 0) onSlotClicked(idx)
            }
            return true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val totalW = (dockPadPx * 2f + icons.size * slotSizePx + (icons.size - 1) * slotGapPx).toInt()
            val totalH = (dockPadPx * 2f + slotSizePx).toInt()
            setMeasuredDimension(totalW, totalH)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val hf = h.toFloat()
            // Rich Apple tvOS dark smoked obsidian glass fill (crisp contrast over white sky & dark night)
            dockBgPaint.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(
                    Color.parseColor("#D41D232E"),
                    Color.parseColor("#DE12161E"),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
            // Subtle glossy top glass highlight sheen
            dockSheenPaint.shader = LinearGradient(
                0f, 0f, 0f, hf * 0.48f,
                intArrayOf(Color.parseColor("#24FFFFFF"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            // Crisp hairline specular border
            dockRimPaint.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(
                    Color.parseColor("#54FFFFFF"),
                    Color.parseColor("#20FFFFFF"),
                    Color.parseColor("#30FFFFFF"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val wf = width.toFloat()
            val hf = height.toFloat()
            if (wf <= 0f || hf <= 0f) return

            val radius = hf * 0.5f
            val rimInset = dockRimPaint.strokeWidth * 0.55f

            // 1. Dark smoked glass capsule body + subtle top sheen + hairline specular rim
            rect.set(rimInset, rimInset, wf - rimInset, hf - rimInset)
            canvas.drawRoundRect(rect, radius, radius, dockBgPaint)
            canvas.drawRoundRect(rect, radius, radius, dockSheenPaint)
            canvas.drawRoundRect(rect, radius, radius, dockRimPaint)

            val cy = hf * 0.5f
            val slotR = slotSizePx * 0.5f

            // 2. Animated Apple Blue badge bloom when Audio morphs ON/OFF
            val audioSlotIdx = icons.indexOf(DockIconType.AUDIO_TOGGLE)
            if (audioMorph > 0.01f && audioSlotIdx >= 0) {
                val ax = slotCenterX(audioSlotIdx.toFloat())
                val bloomScale = 0.42f + 0.58f * audioMorph
                val blueR = (slotR - strokeUnitPx * 0.45f) * bloomScale
                activeBluePaint.alpha = (242f * audioMorph).toInt().coerceIn(0, 255)
                canvas.drawCircle(ax, cy, blueR, activeBluePaint)
            }

            // 3. Silky 60 fps sliding Apple tvOS focus pill (with subtle glide stretch + press bounce)
            if (focusAlpha > 0.01f) {
                val fx = slotCenterX(focusSlidePos)
                val overAudioSlot = audioSlotIdx >= 0 &&
                    kotlin.math.abs(focusSlidePos - audioSlotIdx.toFloat()) < 0.35f
                val blueWeight = if (overAudioSlot) audioMorph else 0f
                val rx = (slotR - strokeUnitPx * 0.35f) * (1f + focusStretch) * pressScale
                val ry = (slotR - strokeUnitPx * 0.35f) * (1f - focusStretch * 0.25f) * pressScale
                rect.set(fx - rx, cy - ry, fx + rx, cy + ry)

                if (blueWeight < 0.99f) {
                    focusFillPaint.alpha = (focusAlpha * (1f - blueWeight) * 250f).toInt().coerceIn(0, 255)
                    canvas.drawRoundRect(rect, ry, ry, focusFillPaint)
                }
                if (blueWeight > 0.01f) {
                    activeBluePaint.alpha = (focusAlpha * blueWeight * 255f).toInt().coerceIn(0, 255)
                    canvas.drawRoundRect(rect, ry, ry, activeBluePaint)
                    activeBlueFocusRingPaint.alpha = (focusAlpha * blueWeight * 255f).toInt().coerceIn(0, 255)
                    val ri = activeBlueFocusRingPaint.strokeWidth * 0.5f
                    rect.inset(ri, ri)
                    canvas.drawRoundRect(rect, ry, ry, activeBlueFocusRingPaint)
                }
            }

            // 4. Crisp SF-Symbols-grade icons with smooth focus & audio-morph color inversion
            for (i in icons.indices) {
                val cx = slotCenterX(i.toFloat())
                val distToFocus = kotlin.math.abs(focusSlidePos - i.toFloat())
                val focusWeight = if (focusAlpha > 0.01f) {
                    (1f - (distToFocus / 0.65f).coerceIn(0f, 1f)) * focusAlpha
                } else 0f

                val baseFocusColor = blendArgb(Color.parseColor("#F0FFFFFF"), Color.parseColor("#FF12161E"), focusWeight)
                val iconColor = if (icons[i] == DockIconType.AUDIO_TOGGLE) {
                    blendArgb(baseFocusColor, Color.WHITE, audioMorph)
                } else {
                    baseFocusColor
                }

                drawDockIcon(canvas, icons[i], cx, cy, slotR, iconColor)
            }
        }

        private fun drawDockIcon(
            canvas: Canvas,
            type: DockIconType,
            cx: Float,
            cy: Float,
            slotR: Float,
            fgColor: Int,
        ) {
            iconStrokePaint.color = fgColor
            iconFillPaint.color = fgColor

            when (type) {
                DockIconType.CLOSE_X -> {
                    iconStrokePaint.strokeWidth = strokeUnitPx * 1.95f
                    val d = slotR * 0.30f
                    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconStrokePaint)
                    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconStrokePaint)
                }
                DockIconType.PIP_MINIMIZE -> {
                    iconStrokePaint.strokeWidth = strokeUnitPx * 1.65f
                    val ow = slotR * 0.94f
                    val oh = slotR * 0.70f
                    val cr = strokeUnitPx * 2.5f
                    val left = cx - ow * 0.5f
                    val top = cy - oh * 0.5f
                    val right = cx + ow * 0.5f
                    val bottom = cy + oh * 0.5f
                    rect.set(left, top, right, bottom)
                    canvas.drawRoundRect(rect, cr, cr, iconStrokePaint)

                    // Top-right solid rounded PiP window
                    val pw = ow * 0.42f
                    val ph = oh * 0.42f
                    val inset = strokeUnitPx * 2.0f
                    val pLeft = right - inset - pw
                    val pTop = top + inset
                    rect.set(pLeft, pTop, right - inset, pTop + ph)
                    canvas.drawRoundRect(rect, strokeUnitPx * 1.3f, strokeUnitPx * 1.3f, iconFillPaint)

                    // Subtle diagonal arrow pointing into top-right PiP corner
                    val ax0 = left + ow * 0.21f
                    val ay0 = bottom - oh * 0.23f
                    val ax1 = pLeft - strokeUnitPx * 1.5f
                    val ay1 = pTop + ph + strokeUnitPx * 1.1f
                    iconStrokePaint.strokeWidth = strokeUnitPx * 1.4f
                    canvas.drawLine(ax0, ay0, ax1, ay1, iconStrokePaint)
                    val head = strokeUnitPx * 2.7f
                    arrowPath.reset()
                    arrowPath.moveTo(ax1 - head, ay1)
                    arrowPath.lineTo(ax1, ay1)
                    arrowPath.lineTo(ax1, ay1 + head)
                    canvas.drawPath(arrowPath, iconStrokePaint)
                }
                DockIconType.AUDIO_TOGGLE -> {
                    val m = audioMorph.coerceIn(0f, 1f)
                    // Subtle acoustic recoil + pop mid-transition as soundwaves burst out
                    val punch = kotlin.math.sin(m * Math.PI).toFloat()
                    val recoilX = -slotR * 0.055f * punch
                    val spkScale = 1f + 0.11f * punch
                    val baseShiftX = -slotR * 0.06f

                    // Plump all-corners-rounded speaker silhouette (matches Apple SF Symbols / reference)
                    canvas.save()
                    canvas.translate(cx + baseShiftX + recoilX, cy)
                    canvas.scale(spkScale, spkScale)

                    val spkLeft = -slotR * 0.38f
                    val spkMid = -slotR * 0.17f
                    val spkRight = slotR * 0.06f
                    val boxH = slotR * 0.17f
                    val coneH = slotR * 0.36f
                    val rBack = slotR * 0.085f
                    val rTip = slotR * 0.08f

                    speakerPath.reset()
                    // Start top edge of box after rounded top-left corner
                    speakerPath.moveTo(spkLeft + rBack, -boxH)
                    speakerPath.lineTo(spkMid - rTip * 0.3f, -boxH)
                    // Smooth flare up to rounded top-right cone tip
                    speakerPath.lineTo(spkRight - rTip * 1.15f, -coneH + rTip * 0.55f)
                    speakerPath.quadTo(spkRight, -coneH - rTip * 0.15f, spkRight, -coneH + rTip * 1.15f)
                    // Straight front face of speaker cone down to rounded bottom-right cone tip
                    speakerPath.lineTo(spkRight, coneH - rTip * 1.15f)
                    speakerPath.quadTo(spkRight, coneH + rTip * 0.15f, spkRight - rTip * 1.15f, coneH - rTip * 0.55f)
                    // Smooth flare back to bottom edge of box
                    speakerPath.lineTo(spkMid - rTip * 0.3f, boxH)
                    speakerPath.lineTo(spkLeft + rBack, boxH)
                    speakerPath.quadTo(spkLeft, boxH, spkLeft, boxH - rBack)
                    speakerPath.lineTo(spkLeft, -boxH + rBack)
                    speakerPath.quadTo(spkLeft, -boxH, spkLeft + rBack, -boxH)
                    speakerPath.close()
                    canvas.drawPath(speakerPath, iconFillPaint)
                    canvas.restore()

                    val spkFaceX = cx + baseShiftX + slotR * 0.06f
                    val baseAlpha = Color.alpha(fgColor)

                    // 1. Muted '×' on right side: twists 50° & collapses away cleanly during 0.0 -> 0.46
                    if (m < 0.48f) {
                        val xProgress = (1f - (m / 0.46f)).coerceIn(0f, 1f)
                        val xScale = xProgress * (0.85f + 0.15f * xProgress)
                        val xRotDeg = (1f - xProgress) * 50f
                        val xCx = cx + slotR * 0.25f
                        val xHalf = slotR * 0.165f * xScale
                        iconStrokePaint.strokeWidth = strokeUnitPx * 1.85f
                        iconStrokePaint.alpha = (baseAlpha * xProgress).toInt().coerceIn(0, 255)
                        canvas.save()
                        canvas.translate(xCx, cy)
                        canvas.rotate(xRotDeg)
                        canvas.drawLine(-xHalf, -xHalf, xHalf, xHalf, iconStrokePaint)
                        canvas.drawLine(xHalf, -xHalf, -xHalf, xHalf, iconStrokePaint)
                        canvas.restore()
                    }

                    // 2. Unmuted soundwaves: cascade outward from speaker face (inner 0.28..0.78, outer 0.46..1.0)
                    if (m > 0.28f) {
                        iconStrokePaint.strokeWidth = strokeUnitPx * 1.65f
                        val t1 = ((m - 0.28f) / 0.50f).coerceIn(0f, 1f)
                        val t2 = ((m - 0.46f) / 0.54f).coerceIn(0f, 1f)

                        // Calm live acoustic breathing ripple once settled at m == 1
                        val waveBreath1 = if (m > 0.95f) {
                            (185 + 70 * kotlin.math.sin(audioWavePhase.toDouble())).toFloat() / 255f
                        } else 1f
                        val waveBreath2 = if (m > 0.95f) {
                            (165 + 90 * kotlin.math.sin((audioWavePhase - 1.1f).toDouble())).toFloat() / 255f
                        } else 1f

                        if (t1 > 0.01f) {
                            val r1 = slotR * 0.24f * (0.55f + 0.45f * t1)
                            val sweep1 = 80f * t1
                            iconStrokePaint.alpha = (baseAlpha * t1 * waveBreath1).toInt().coerceIn(0, 255)
                            rect.set(spkFaceX - r1 * 0.25f, cy - r1, spkFaceX + r1 * 1.35f, cy + r1)
                            canvas.drawArc(rect, -sweep1 * 0.5f, sweep1, false, iconStrokePaint)
                        }
                        if (t2 > 0.01f) {
                            val r2 = slotR * 0.42f * (0.64f + 0.36f * t2)
                            val sweep2 = 88f * t2
                            iconStrokePaint.alpha = (baseAlpha * t2 * waveBreath2).toInt().coerceIn(0, 255)
                            rect.set(spkFaceX - r2 * 0.25f, cy - r2, spkFaceX + r2 * 1.35f, cy + r2)
                            canvas.drawArc(rect, -sweep2 * 0.5f, sweep2, false, iconStrokePaint)
                        }
                    }
                    iconStrokePaint.alpha = 255
                }
            }
        }

        private fun blendArgb(c0: Int, c1: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * k).toInt()
            val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * k).toInt()
            val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * k).toInt()
            val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * k).toInt()
            return Color.argb(a, r, g, b)
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
