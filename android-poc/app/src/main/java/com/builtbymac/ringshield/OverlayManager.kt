package com.builtbymac.ringshield

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.webrtc.EglBase

/**
 * Displays a non-focusable, GPU-composited TextureView overlay in the top-right corner
 * of Android TV without interrupting foreground apps or stealing remote D-pad focus.
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootWindow: FrameLayout? = null
    private var renderer: TextureViewRenderer? = null
    private var dismissRunnable: Runnable? = null

    fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * Attaches the TextureView overlay window (initially invisible at alpha 0 until the
     * first decoded WebRTC frame renders, or immediately visible if preferred) and returns
     * the VideoSink renderer ready to attach to the incoming VideoTrack.
     */
    fun showOverlay(
        cfg: PocConfig,
        eglContext: EglBase.Context,
        onFirstFrameVisible: () -> Unit,
        onAutoDismiss: () -> Unit,
    ): TextureViewRenderer? {
        if (!canDrawOverlays()) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot display overlay")
            return null
        }

        hideImmediate()

        val card = FrameLayout(context).apply {
            alpha = 0f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(Color.parseColor("#E610141A"))
                setStroke(3, Color.parseColor("#FF2979FF"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 18f)
                }
            }
            clipToOutline = true
        }

        val videoView = TextureViewRenderer(context).apply {
            init(
                eglContext = eglContext,
                onFirstFrame = {
                    mainHandler.post {
                        if (rootWindow === card) {
                            card.animate().alpha(1f).setDuration(120L).start()
                            onFirstFrameVisible()
                        }
                    }
                },
            )
        }

        val badge = TextView(context).apply {
            text = "LIVE • DOORBELL"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(16, 6, 16, 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor("#CC1976D2"))
            }
        }

        card.addView(
            videoView,
            FrameLayout.LayoutParams(cfg.overlayWidthPx, cfg.overlayHeightPx, Gravity.CENTER)
        )
        card.addView(
            badge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(14, 14, 14, 14) }
        )

        val params = WindowManager.LayoutParams(
            cfg.overlayWidthPx,
            cfg.overlayHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 28
            y = 28
            title = "ring-shield-overlay"
        }

        wm.addView(card, params)
        rootWindow = card
        renderer = videoView

        val dismiss = Runnable {
            hideImmediate()
            onAutoDismiss()
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, cfg.overlayDurationSec * 1000L)

        return videoView
    }

    fun hideImmediate() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null

        val r = renderer
        renderer = null
        runCatching { r?.release() }

        val w = rootWindow
        rootWindow = null
        if (w != null) {
            runCatching { w.removeAllViews() }
            runCatching { wm.removeViewImmediate(w) }
        }
    }

    companion object {
        private const val TAG = "OverlayManager"
    }
}
