package org.webrtc

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import com.builtbymac.ringshield.R
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Thread-safe uniform state shared between OverlayManager (UI thread) and the OpenGL ES 2.0
 * fragment shader (EglRenderer thread) to drive pixel-locked 60 fps Apple Liquid Glass
 * optical refraction + frosted backdrop blur directly behind floating HUD orb buttons.
 *
 * All orb coordinates are in 1280x720 video pixel space (origin top-left).
 */
class GlassOrbState {
    // 3 orbs x 4 floats: [cx, cy, radiusPx, alpha]
    @Volatile
    var orbs: FloatArray = FloatArray(12)

    // 3 floats: focused strength [0..1] per orb
    @Volatile
    var focus: FloatArray = FloatArray(3)

    // 3 floats: active toggle strength [0..1] per orb (e.g. Audio ON blue glass)
    @Volatile
    var active: FloatArray = FloatArray(3)

    @Volatile
    var inpaintEnabled: Boolean = true

    fun setOrbs(
        orb0Cx: Float, orb0Cy: Float, orb0R: Float, orb0Alpha: Float, orb0Focus: Float, orb0Active: Float,
        orb1Cx: Float, orb1Cy: Float, orb1R: Float, orb1Alpha: Float, orb1Focus: Float, orb1Active: Float,
        orb2Cx: Float, orb2Cy: Float, orb2R: Float, orb2Alpha: Float, orb2Focus: Float, orb2Active: Float,
    ) {
        orbs = floatArrayOf(
            orb0Cx, orb0Cy, orb0R, orb0Alpha,
            orb1Cx, orb1Cy, orb1R, orb1Alpha,
            orb2Cx, orb2Cy, orb2R, orb2Alpha,
        )
        focus = floatArrayOf(orb0Focus, orb1Focus, orb2Focus)
        active = floatArrayOf(orb0Active, orb1Active, orb2Active)
    }

    fun clearOrbs() {
        orbs = FloatArray(12)
        focus = FloatArray(3)
        active = FloatArray(3)
    }
}

/**
 * Real-time 60 fps OpenGL ES 2.0 GPU shader combining:
 *  1. Mask-guided 4-direction structure-preserving watermark eraser (0% crop, razor-sharp edges
 *     in both bright daylight and nighttime) using a precomputed bit-exact LUT atlas on
 *     GL_TEXTURE3..5 (res/raw/wm_lut_gz.bin), plus 9-slope adaptive stroke-only UTC erasure.
 *  2. True Apple iOS 26 / visionOS Liquid Glass optical refraction + 9-tap frosted backdrop blur
 *     + rim chromatic prism dispersion + Fresnel inner glow + daytime luminance adaptation
 *     directly behind the floating HUD orbs.
 *
 * Placed in `package org.webrtc` to extend package-private `GlGenericDrawer` while returning the
 * public `RendererCommon.GlDrawer` interface.
 */
object WatermarkInpaintDrawerFactory {

    private const val TAG = "WatermarkGlassDrawer"
    private const val LUT_W = 324
    private const val LUT_H = 160
    private const val LUT_TEX_BYTES = LUT_W * LUT_H * 4

    @Volatile
    private var cachedLutBytes: ByteArray? = null

    private fun loadLutBytes(appContext: Context): ByteArray? {
        cachedLutBytes?.let { return it }
        return runCatching {
            GZIPInputStream(appContext.resources.openRawResource(R.raw.wm_lut_gz)).use { it.readBytes() }
        }.onSuccess { bytes ->
            if (bytes.size >= LUT_TEX_BYTES * 3) {
                cachedLutBytes = bytes
            }
        }.onFailure {
            Log.e(TAG, "Failed to decompress wm_lut_gz: ${it.message}", it)
        }.getOrNull()
    }

    fun create(
        context: Context,
        glassState: GlassOrbState,
        inpaintWatermarks: Boolean = true,
    ): RendererCommon.GlDrawer {
        glassState.inpaintEnabled = inpaintWatermarks
        val appContext = context.applicationContext
        val texIds = IntArray(3)
        var lutLoaded = false

        var locLut0 = -1
        var locLut1 = -1
        var locMask = -1
        var locInpaint = -1
        var locOrb0 = -1
        var locOrb1 = -1
        var locOrb2 = -1
        var locOrbFocus = -1
        var locOrbActive = -1

        fun ensureLutTexturesLoaded() {
            if (lutLoaded) return
            lutLoaded = true
            runCatching {
                val rawBytes = loadLutBytes(appContext) ?: return
                if (rawBytes.size < LUT_TEX_BYTES * 3) {
                    Log.e(TAG, "Unexpected wm_lut_gz size: ${rawBytes.size}")
                    return
                }
                GLES20.glGenTextures(3, texIds, 0)
                for (i in 0 until 3) {
                    val buf = ByteBuffer.allocateDirect(LUT_TEX_BYTES)
                    buf.put(rawBytes, i * LUT_TEX_BYTES, LUT_TEX_BYTES)
                    buf.position(0)

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE3 + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        LUT_W, LUT_H, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
                    )
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                Log.i(TAG, "Uploaded 3 bit-exact watermark LUT textures (${LUT_W}x${LUT_H}) to GL_TEXTURE3..5")
            }.onFailure {
                Log.e(TAG, "Failed to load watermark LUT textures: ${it.message}", it)
            }
        }

        val callbacks = object : GlGenericDrawer.ShaderCallbacks {
            override fun onNewShader(shader: GlShader) {
                ensureLutTexturesLoaded()
                locLut0 = shader.getUniformLocation("u_wm_lut0")
                locLut1 = shader.getUniformLocation("u_wm_lut1")
                locMask = shader.getUniformLocation("u_wm_mask")
                locInpaint = shader.getUniformLocation("u_inpaint_enabled")
                locOrb0 = shader.getUniformLocation("u_orb0")
                locOrb1 = shader.getUniformLocation("u_orb1")
                locOrb2 = shader.getUniformLocation("u_orb2")
                locOrbFocus = shader.getUniformLocation("u_orb_focus")
                locOrbActive = shader.getUniformLocation("u_orb_active")

                if (locLut0 >= 0) GLES20.glUniform1i(locLut0, 3)
                if (locLut1 >= 0) GLES20.glUniform1i(locLut1, 4)
                if (locMask >= 0) GLES20.glUniform1i(locMask, 5)
            }

            override fun onPrepareShader(
                shader: GlShader,
                texMatrix: FloatArray,
                frameWidth: Int,
                frameHeight: Int,
                viewportWidth: Int,
                viewportHeight: Int,
            ) {
                ensureLutTexturesLoaded()
                if (texIds[0] != 0) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1])
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[2])
                    // Always restore GL_TEXTURE0 for WebRTC's video frame binding
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                }

                if (locInpaint >= 0) {
                    GLES20.glUniform1f(locInpaint, if (glassState.inpaintEnabled) 1f else 0f)
                }
                val o = glassState.orbs
                val f = glassState.focus
                val a = glassState.active
                if (locOrb0 >= 0) GLES20.glUniform4f(locOrb0, o[0], o[1], o[2], o[3])
                if (locOrb1 >= 0) GLES20.glUniform4f(locOrb1, o[4], o[5], o[6], o[7])
                if (locOrb2 >= 0) GLES20.glUniform4f(locOrb2, o[8], o[9], o[10], o[11])
                if (locOrbFocus >= 0) GLES20.glUniform3f(locOrbFocus, f[0], f[1], f[2])
                if (locOrbActive >= 0) GLES20.glUniform3f(locOrbActive, a[0], a[1], a[2])
            }
        }

        return object : GlGenericDrawer(
            INPAINT_VERTEX_SHADER,
            INPAINT_FRAGMENT_SHADER,
            callbacks,
        ) {
            override fun release() {
                if (texIds[0] != 0) {
                    runCatching { GLES20.glDeleteTextures(3, texIds, 0) }
                    texIds.fill(0)
                }
                super.release()
            }
        }
    }

    private const val INPAINT_VERTEX_SHADER =
        "varying vec2 tc;\n" +
        "varying highp vec2 v_px;\n" +
        "varying highp vec2 v_dx;\n" +
        "varying highp vec2 v_dy;\n" +
        "attribute vec4 in_pos;\n" +
        "attribute vec4 in_tc;\n" +
        "uniform mat4 tex_mat;\n" +
        "void main() {\n" +
        "  gl_Position = in_pos;\n" +
        "  tc = (tex_mat * in_tc).xy;\n" +
        "  v_px = vec2(in_tc.x * 1280.0, (1.0 - in_tc.y) * 720.0);\n" +
        "  highp vec2 tc_tl = (tex_mat * vec4(0.0, 1.0, 0.0, 1.0)).xy;\n" +
        "  highp vec2 tc_tr = (tex_mat * vec4(1.0, 1.0, 0.0, 1.0)).xy;\n" +
        "  highp vec2 tc_bl = (tex_mat * vec4(0.0, 0.0, 0.0, 1.0)).xy;\n" +
        "  v_dx = (tc_tr - tc_tl) / 1280.0;\n" +
        "  v_dy = (tc_bl - tc_tl) / 720.0;\n" +
        "}\n"

    private const val INPAINT_FRAGMENT_SHADER =
        "varying highp vec2 v_px;\n" +
        "varying highp vec2 v_dx;\n" +
        "varying highp vec2 v_dy;\n" +
        "uniform sampler2D u_wm_lut0;\n" +
        "uniform sampler2D u_wm_lut1;\n" +
        "uniform sampler2D u_wm_mask;\n" +
        "uniform highp float u_inpaint_enabled;\n" +
        "uniform highp vec4 u_orb0;\n" +
        "uniform highp vec4 u_orb1;\n" +
        "uniform highp vec4 u_orb2;\n" +
        "uniform highp vec3 u_orb_focus;\n" +
        "uniform highp vec3 u_orb_active;\n" +
        "\n" +
        "vec4 samplePx(highp float px, highp float py) {\n" +
        "  highp float cx = clamp(px, 0.5, 1279.5);\n" +
        "  highp float cy = clamp(py, 0.5, 719.5);\n" +
        "  return sample(tc + (cx - v_px.x) * v_dx + (cy - v_px.y) * v_dy);\n" +
        "}\n" +
        "\n" +
        "// Numerically stable 4-direction structure-preserving inpainter for 'ring' logo.\n" +
        "// Uses log-sum-exp shifting (subtract minCost before exp) so high-contrast edges\n" +
        "// (e.g. black corner <-> sky on 'r', sky <-> brick gutter on 'g') NEVER underflow!\n" +
        "vec3 inpaintRingLut(vec3 base, highp float x, highp float y, highp float ax, highp float ay) {\n" +
        "  highp vec2 lutUv = vec2((ax + 0.5) / 324.0, (ay + 0.5) / 160.0);\n" +
        "  highp float maskA = texture2D(u_wm_mask, lutUv).r;\n" +
        "  if (maskA <= 0.004) return base;\n" +
        "  highp vec4 l0 = texture2D(u_wm_lut0, lutUv) * 255.0;\n" +
        "  highp vec4 l1 = texture2D(u_wm_lut1, lutUv) * 255.0;\n" +
        "\n" +
        "  vec3 col0 = base; highp float cost0 = 999.0;\n" +
        "  if (l0.r >= 0.5 && l0.g >= 0.5) {\n" +
        "    vec3 a = 0.55 * samplePx(x + l0.r, y).rgb + 0.45 * samplePx(x + l0.r + 2.0, y).rgb;\n" +
        "    vec3 b = 0.55 * samplePx(x - l0.g, y).rgb + 0.45 * samplePx(x - l0.g - 2.0, y).rgb;\n" +
        "    col0 = mix(b, a, l0.g / (l0.r + l0.g));\n" +
        "    cost0 = length(a - b) + (l0.r + l0.g) * 0.003;\n" +
        "  }\n" +
        "  vec3 col1 = base; highp float cost1 = 999.0;\n" +
        "  if (l0.b >= 0.5 && l0.a >= 0.5) {\n" +
        "    vec3 a = 0.55 * samplePx(x + 0.643 * l0.b, y - 0.766 * l0.b).rgb\n" +
        "           + 0.45 * samplePx(x + 0.643 * (l0.b + 2.0), y - 0.766 * (l0.b + 2.0)).rgb;\n" +
        "    vec3 b = 0.55 * samplePx(x - 0.643 * l0.a, y + 0.766 * l0.a).rgb\n" +
        "           + 0.45 * samplePx(x - 0.643 * (l0.a + 2.0), y + 0.766 * (l0.a + 2.0)).rgb;\n" +
        "    col1 = mix(b, a, l0.a / (l0.b + l0.a));\n" +
        "    cost1 = length(a - b) + (l0.b + l0.a) * 0.002;\n" +
        "  }\n" +
        "  vec3 col2 = base; highp float cost2 = 999.0;\n" +
        "  if (l1.r >= 0.5 && l1.g >= 0.5) {\n" +
        "    vec3 a = 0.55 * samplePx(x, y + l1.r).rgb + 0.45 * samplePx(x, y + l1.r + 2.0).rgb;\n" +
        "    vec3 b = 0.55 * samplePx(x, y - l1.g).rgb + 0.45 * samplePx(x, y - l1.g - 2.0).rgb;\n" +
        "    col2 = mix(b, a, l1.g / (l1.r + l1.g));\n" +
        "    cost2 = length(a - b) + (l1.r + l1.g) * 0.003;\n" +
        "  }\n" +
        "  vec3 col3 = base; highp float cost3 = 999.0;\n" +
        "  if (l1.b >= 0.5 && l1.a >= 0.5) {\n" +
        "    vec3 a = 0.55 * samplePx(x + 0.819 * l1.b, y + 0.574 * l1.b).rgb\n" +
        "           + 0.45 * samplePx(x + 0.819 * (l1.b + 2.0), y + 0.574 * (l1.b + 2.0)).rgb;\n" +
        "    vec3 b = 0.55 * samplePx(x - 0.819 * l1.a, y - 0.574 * l1.a).rgb\n" +
        "           + 0.45 * samplePx(x - 0.819 * (l1.a + 2.0), y - 0.574 * (l1.a + 2.0)).rgb;\n" +
        "    col3 = mix(b, a, l1.a / (l1.b + l1.a));\n" +
        "    cost3 = length(a - b) + (l1.b + l1.a) * 0.003;\n" +
        "  }\n" +
        "  highp float minC = min(min(cost0, cost1), min(cost2, cost3));\n" +
        "  if (minC > 900.0) return base;\n" +
        "  highp float w0 = (cost0 < 900.0) ? exp(-(cost0 - minC) * 22.0) : 0.0;\n" +
        "  highp float w1 = (cost1 < 900.0) ? exp(-(cost1 - minC) * 22.0) : 0.0;\n" +
        "  highp float w2 = (cost2 < 900.0) ? exp(-(cost2 - minC) * 22.0) : 0.0;\n" +
        "  highp float w3 = (cost3 < 900.0) ? exp(-(cost3 - minC) * 22.0) : 0.0;\n" +
        "  vec3 filled = (col0 * w0 + col1 * w1 + col2 * w2 + col3 * w3) / max(w0 + w1 + w2 + w3, 0.0001);\n" +
        "  return mix(base, filled, maskA);\n" +
        "}\n" +
        "\n" +
        "// Numerically stable 9-slope directional clean-background estimator for any text band.\n" +
        "void evalBandSlope(highp float x, highp float ty, highp float yTop, highp float yBot,\n" +
        "                   highp float s, inout vec3 bestCol, inout highp float bestCost,\n" +
        "                   inout vec3 accCol, inout highp float accW) {\n" +
        "  highp float xt = x - s * ty;\n" +
        "  highp float xb = x + s * (1.0 - ty);\n" +
        "  vec3 ct = 0.6 * samplePx(xt, yTop).rgb + 0.4 * samplePx(xt, yTop - 2.0).rgb;\n" +
        "  vec3 cb = 0.6 * samplePx(xb, yBot).rgb + 0.4 * samplePx(xb, yBot + 2.0).rgb;\n" +
        "  highp float cost = length(ct - cb) + abs(s) * 0.0008;\n" +
        "  vec3 ci = mix(ct, cb, ty);\n" +
        "  if (cost < bestCost) {\n" +
        "    bestCost = cost;\n" +
        "    bestCol = ci;\n" +
        "  }\n" +
        "  highp float w = exp(-cost * 24.0);\n" +
        "  accCol += ci * w;\n" +
        "  accW += w;\n" +
        "}\n" +
        "\n" +
        "// Adaptive stroke-only text-band eraser (works for Device-ID, Partner, and UTC at any width!).\n" +
        "vec3 inpaintTextBandAdaptive(vec3 base, highp float x, highp float y,\n" +
        "                             highp float x0, highp float x1, highp float y0, highp float y1,\n" +
        "                             highp float yTop, highp float yBot) {\n" +
        "  highp float ty = clamp((y - yTop) / max(yBot - yTop, 1.0), 0.0, 1.0);\n" +
        "  vec3 bestCol = base;\n" +
        "  highp float bestCost = 999.0;\n" +
        "  vec3 accCol = vec3(0.0);\n" +
        "  highp float accW = 0.0;\n" +
        "  evalBandSlope(x, ty, yTop, yBot, -22.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot, -15.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,  -9.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,  -4.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,   0.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,   4.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,   9.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,  15.0, bestCol, bestCost, accCol, accW);\n" +
        "  evalBandSlope(x, ty, yTop, yBot,  22.0, bestCol, bestCost, accCol, accW);\n" +
        "  vec3 bg = (accW > 0.0001) ? (accCol / accW) : bestCol;\n" +
        "  highp float d0 = length(base - bg);\n" +
        "  highp float d1 = length(samplePx(x - 2.0, y).rgb - bg);\n" +
        "  highp float d2 = length(samplePx(x + 2.0, y).rgb - bg);\n" +
        "  highp float d3 = length(samplePx(x, y - 2.0).rgb - bg);\n" +
        "  highp float d4 = length(samplePx(x, y + 2.0).rgb - bg);\n" +
        "  highp float d5 = length(samplePx(x - 1.5, y - 1.5).rgb - bg);\n" +
        "  highp float d6 = length(samplePx(x + 1.5, y + 1.5).rgb - bg);\n" +
        "  highp float maxD = max(max(max(d0, d1), max(d2, d3)), max(d4, max(d5, d6)));\n" +
        "  highp float edgeX = clamp(min(x - x0, x1 - x) / 3.0, 0.0, 1.0);\n" +
        "  highp float edgeY = clamp(min(y - y0, y1 - y) / 2.5, 0.0, 1.0);\n" +
        "  highp float strokeMask = smoothstep(0.08, 0.17, maxD) * min(edgeX, edgeY);\n" +
        "  return mix(base, bg, strokeMask);\n" +
        "}\n" +
        "\n" +
        "// Fast clean sample for Liquid Glass blur taps: avoids inlining the 4-dir LUT inside every tap\n" +
        "// by deflecting any tap landing on Partner text (y=15..72) vertically to clean sky/eave at y=76.\n" +
        "vec3 sampleFastForGlass(highp float px, highp float py) {\n" +
        "  highp float sy = py;\n" +
        "  if (u_inpaint_enabled > 0.5 && px >= 950.0 && px < 1274.0 && py >= 14.0 && py < 74.0) {\n" +
        "    sy = (py < 36.0) ? 12.0 : 76.0;\n" +
        "  }\n" +
        "  return samplePx(px, sy).rgb;\n" +
        "}\n" +
        "\n" +
        "// True Apple Liquid Glass optical refraction + 5-tap frosted blur + rim prism + Fresnel glow\n" +
        "// Compact instruction count so Tegra GLSL compiles in <150ms and renders in ~1ms!\n" +
        "vec3 applyLiquidGlassOrb(vec3 curCol, highp float x, highp float y,\n" +
        "                         highp vec4 orb, highp float focused, highp float active) {\n" +
        "  highp float alpha = orb.w;\n" +
        "  highp float radius = orb.z;\n" +
        "  if (alpha <= 0.01 || radius <= 1.0) return curCol;\n" +
        "  highp vec2 d = vec2(x - orb.x, y - orb.y);\n" +
        "  highp float dist = length(d);\n" +
        "  if (dist > radius + 6.0) return curCol;\n" +
        "\n" +
        "  highp float rNorm = dist / radius;\n" +
        "  if (rNorm >= 1.0) {\n" +
        "    highp float shadow = exp(-((dist - radius) * 0.42)) * 0.16 * alpha;\n" +
        "    return curCol * (1.0 - shadow);\n" +
        "  }\n" +
        "\n" +
        "  highp vec2 dir = d / max(dist, 0.001);\n" +
        "  // Authentic convex Liquid Glass rim refraction (bends live video strongly near curved edge)\n" +
        "  highp float r2 = rNorm * rNorm;\n" +
        "  highp float refractScale = 0.90 - 0.34 * r2 * rNorm;\n" +
        "  highp vec2 refrPx = orb.xy + d * refractScale;\n" +
        "\n" +
        "  // Fast 5-tap silky frosted backdrop blur (crystal-clear see-through!)\n" +
        "  highp float blurR = radius * 0.17;\n" +
        "  vec3 blurCol = sampleFastForGlass(refrPx.x, refrPx.y) * 0.32;\n" +
        "  blurCol += sampleFastForGlass(refrPx.x + blurR, refrPx.y + blurR * 0.6) * 0.17;\n" +
        "  blurCol += sampleFastForGlass(refrPx.x - blurR, refrPx.y - blurR * 0.6) * 0.17;\n" +
        "  blurCol += sampleFastForGlass(refrPx.x - blurR * 0.6, refrPx.y + blurR) * 0.17;\n" +
        "  blurCol += sampleFastForGlass(refrPx.x + blurR * 0.6, refrPx.y - blurR) * 0.17;\n" +
        "\n" +
        "  // Whisper-subtle chromatic prism dispersion along outer curved glass rim\n" +
        "  highp float prismAmt = smoothstep(0.62, 0.98, rNorm) * 1.8;\n" +
        "  if (prismAmt > 0.05) {\n" +
        "    highp float rChan = sampleFastForGlass(refrPx.x + dir.x * prismAmt, refrPx.y + dir.y * prismAmt).r;\n" +
        "    highp float bChan = sampleFastForGlass(refrPx.x - dir.x * prismAmt, refrPx.y - dir.y * prismAmt).b;\n" +
        "    blurCol.r = mix(blurCol.r, rChan, 0.38);\n" +
        "    blurCol.b = mix(blurCol.b, bChan, 0.38);\n" +
        "  }\n" +
        "\n" +
        "  // Adaptive daytime/nighttime crystal glass contrast:\n" +
        "  // Uniform crystal-clear interior + razor-thin Fresnel edge glow (matches Apple Liquid Glass!)\n" +
        "  highp float lum = dot(blurCol, vec3(0.299, 0.587, 0.114));\n" +
        "  highp float dayShade = mix(1.0, 0.80, smoothstep(0.55, 0.96, lum));\n" +
        "  vec3 glassCol = blurCol * dayShade;\n" +
        "  highp float nightLift = (1.0 - smoothstep(0.05, 0.55, lum)) * (0.12 + 0.08 * focused);\n" +
        "  glassCol = mix(glassCol, vec3(0.84, 0.92, 1.0), nightLift);\n" +
        "\n" +
        "  // Translucent Apple Liquid Glass sky-blue tint when Audio is active ON\n" +
        "  if (active > 0.01) {\n" +
        "    vec3 appleBlue = vec3(0.04, 0.52, 1.0);\n" +
        "    glassCol = mix(glassCol, appleBlue, (0.42 + 0.10 * focused) * active);\n" +
        "  }\n" +
        "\n" +
        "  // Razor-thin Fresnel inner glass rim glow + double-crescent diagonal specular catchlights\n" +
        "  highp float fresnel = smoothstep(0.82, 0.995, rNorm);\n" +
        "  highp float diagLight = abs(dot(dir, vec2(-0.707, -0.707)));\n" +
        "  highp float topLeftBoost = smoothstep(-0.2, 0.95, dot(dir, vec2(-0.707, -0.707)));\n" +
        "  highp float rimGlow = fresnel * (0.12 + 0.24 * diagLight + 0.12 * topLeftBoost + 0.16 * focused);\n" +
        "  glassCol += vec3(0.94, 0.97, 1.0) * rimGlow;\n" +
        "\n" +
        "  // Soft top-left internal specular caustic dome highlight\n" +
        "  highp float caustic = exp(-dot(d / radius - vec2(-0.34, -0.38), d / radius - vec2(-0.34, -0.38)) * 5.0);\n" +
        "  glassCol += vec3(1.0) * caustic * (0.08 + 0.08 * focused);\n" +
        "\n" +
        "  highp float edgeAA = 1.0 - smoothstep(radius - 1.2, radius, dist);\n" +
        "  return mix(curCol, clamp(glassCol, 0.0, 1.0), edgeAA * alpha);\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "  vec4 base = sample(tc);\n" +
        "  vec3 col = base.rgb;\n" +
        "  highp float x = v_px.x;\n" +
        "  highp float y = v_px.y;\n" +
        "\n" +
        "  if (u_inpaint_enabled > 0.5) {\n" +
        "    if (x >= 14.0 && x < 142.0 && y >= 14.0 && y < 102.0) {\n" +
        "      col = inpaintRingLut(col, x, y, x - 14.0, y - 14.0);\n" +
        "    } else if (x >= 952.0 && x <= 1165.0 && y >= 15.0 && y <= 41.0) {\n" +
        "      col = inpaintTextBandAdaptive(col, x, y, 952.0, 1165.0, 15.0, 41.0, 13.0, 42.0);\n" +
        "    } else if (x >= 952.0 && x <= 1279.0 && y >= 41.0 && y <= 72.0) {\n" +
        "      col = inpaintTextBandAdaptive(col, x, y, 952.0, 1279.0, 41.0, 72.0, 40.0, 74.0);\n" +
        "    } else if (x >= 1014.0 && x <= 1266.0 && y >= 679.0 && y <= 705.0) {\n" +
        "      col = inpaintTextBandAdaptive(col, x, y, 1014.0, 1266.0, 679.0, 705.0, 677.0, 706.0);\n" +
        "    }\n" +
        "  }\n" +
        "\n" +
        "  // Render real-time GPU Apple Liquid Glass refraction lenses behind active HUD orbs\n" +
        "  col = applyLiquidGlassOrb(col, x, y, u_orb0, u_orb_focus.x, u_orb_active.x);\n" +
        "  col = applyLiquidGlassOrb(col, x, y, u_orb1, u_orb_focus.y, u_orb_active.y);\n" +
        "  col = applyLiquidGlassOrb(col, x, y, u_orb2, u_orb_focus.z, u_orb_active.z);\n" +
        "\n" +
        "  gl_FragColor = vec4(col, base.a);\n" +
        "}\n"
}
