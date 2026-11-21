package com.builtbymac.ringshield

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlassOrbState
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Lightweight TextureView-backed WebRTC VideoSink using org.webrtc.EglRenderer directly.
 *
 * Uses WatermarkInpaintDrawerFactory to combine:
 *  - Mask-guided 4-direction structure-preserving GPU watermark erasure (0% crop)
 *  - Real-time 60 fps Apple Liquid Glass optical refraction + frosted backdrop blur behind HUD orbs
 */
class TextureViewRenderer(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener, VideoSink {

    private val eglRenderer = EglRenderer("RingOverlayRenderer")
    val glassOrbState = GlassOrbState()

    private var initialized = false
    @Volatile private var hasReceivedWebRtcFrame = false
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
        inpaintWatermarks: Boolean = true,
        onFirstFrame: () -> Unit,
        onResolutionChanged: ((Int, Int) -> Unit)? = null,
    ) {
        if (initialized) return
        initialized = true
        hasReceivedWebRtcFrame = false
        firstFrameNotified = false
        this.onFirstFrame = onFirstFrame
        this.onResolutionChanged = onResolutionChanged

        Log.i(TAG, "Initializing WatermarkGlassDrawer (mask-guided inpaint=$inpaintWatermarks + GPU Liquid Glass orbs)")
        val drawer: RendererCommon.GlDrawer = org.webrtc.WatermarkInpaintDrawerFactory.create(
            context = context,
            glassState = glassOrbState,
            inpaintWatermarks = inpaintWatermarks,
        )
        eglRenderer.init(eglContext, EglBase.CONFIG_PLAIN, drawer)
        if (isAvailable && surfaceTexture != null) {
            eglRenderer.createEglSurface(surfaceTexture)
            eglRenderer.clearImage(0f, 0f, 0f, 1f)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!hasReceivedWebRtcFrame) {
            hasReceivedWebRtcFrame = true
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            Log.i(TAG, "First WebRTC video frame received (${w}x${h})")
            onResolutionChanged?.invoke(w, h)
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

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (hasReceivedWebRtcFrame && !firstFrameNotified) {
            firstFrameNotified = true
            Log.i(TAG, "First WebRTC video frame swapped to SurfaceTexture")
            onFirstFrame?.invoke()
        }
    }

    fun release() {
        if (!initialized) return
        initialized = false
        onFirstFrame = null
        onResolutionChanged = null
        glassOrbState.clearOrbs()
        eglRenderer.release()
    }

    companion object {
        private const val TAG = "TextureViewRenderer"
    }
}
