package com.builtbymac.ringshield

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Lightweight TextureView-backed WebRTC VideoSink using org.webrtc.EglRenderer directly.
 *
 * Why TextureView instead of SurfaceViewRenderer?
 * SurfaceView allocates a dedicated hardware video overlay plane that collides with
 * foreground streaming apps (Netflix, YouTube, Dolby Vision) on Android TV / Shield,
 * leaving a transparent hole. TextureView is GPU-composited into the overlay window
 * via OpenGL and renders reliably on top of active video playback.
 */
class TextureViewRenderer(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener, VideoSink {

    private val eglRenderer = EglRenderer("RingOverlayRenderer")
    private var initialized = false
    private var firstFrameNotified = false
    private var onFirstFrame: (() -> Unit)? = null
    private var onResolutionChanged: ((Int, Int) -> Unit)? = null

    init {
        surfaceTextureListener = this
        // Opaque TextureView skips per-frame alpha compositing over playing YouTube/HDR video
        isOpaque = true
    }

    fun init(
        eglContext: EglBase.Context,
        onFirstFrame: () -> Unit,
        onResolutionChanged: ((Int, Int) -> Unit)? = null,
    ) {
        if (initialized) return
        initialized = true
        firstFrameNotified = false
        this.onFirstFrame = onFirstFrame
        this.onResolutionChanged = onResolutionChanged

        eglRenderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        if (isAvailable && surfaceTexture != null) {
            eglRenderer.createEglSurface(surfaceTexture)
            eglRenderer.clearImage(0f, 0f, 0f, 1f)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!firstFrameNotified) {
            firstFrameNotified = true
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            Log.i(TAG, "First WebRTC video frame received (${w}x${h})")
            onResolutionChanged?.invoke(w, h)
            onFirstFrame?.invoke()
        }
        eglRenderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) {
            eglRenderer.createEglSurface(surface)
            eglRenderer.clearImage(0f, 0f, 0f, 1f)
            if (height > 0) {
                eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized && height > 0) {
            eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized) {
            eglRenderer.releaseEglSurface { /* surface released cleanly before texture destruction */ }
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        if (!initialized) return
        initialized = false
        onFirstFrame = null
        onResolutionChanged = null
        eglRenderer.release()
    }

    companion object {
        private const val TAG = "TextureViewRenderer"
    }
}
