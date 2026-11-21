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
                val rawBytes = GZIPInputStream(appContext.resources.openRawResource(R.raw.wm_lut_gz)).use {
                    it.readBytes()
                }
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

        val callbacks = object : ShaderCallbacks {
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
        "// Evaluates one precomputed clean-boundary ray pair (dPos, dNeg) along direction dir.\n" +
        "void evalDirRay(highp float x, highp float y, highp vec2 dir, highp float dPos, highp float dNeg,\n" +
        "                inout vec3 accCol, inout highp float accW) {\n" +
        "  if (dPos < 0.5 || dNeg < 0.5) return;\n" +
        "  // Average two clean taps slightly past the mask edge to suppress H.264 ringing\n" +
        "  vec3 c1 = 0.55 * samplePx(x + dir.x * dPos, y + dir.y * dPos).rgb\n" +
        "          + 0.45 * samplePx(x + dir.x * (dPos + 2.0), y + dir.y * (dPos + 2.0)).rgb;\n" +
        "  vec3 c2 = 0.55 * samplePx(x - dir.x * dNeg, y - dir.y * dNeg).rgb\n" +
        "          + 0.45 * samplePx(x - dir.x * (dNeg + 2.0), y - dir.y * (dNeg + 2.0)).rgb;\n" +
        "  highp float t = dNeg / (dPos + dNeg);\n" +
        "  vec3 ci = mix(c2, c1, t);\n" +
        "  highp float diff = length(c1 - c2);\n" +
        "  highp float cost = diff + (dPos + dNeg) * 0.0022;\n" +
        "  highp float w = exp(-cost * 26.0);\n" +
        "  accCol += ci * w;\n" +
        "  accW += w;\n" +
        "}\n" +
        "\n" +
        "// Static mask-guided 4-direction structure-preserving inpainter for 'ring' & 'Device-ID / Partner'.\n" +
        "vec3 inpaintLutRegion(vec3 base, highp float x, highp float y, highp float ax, highp float ay) {\n" +
        "  highp vec2 lutUv = vec2((ax + 0.5) / 324.0, (ay + 0.5) / 160.0);\n" +
        "  highp float maskA = texture2D(u_wm_mask, lutUv).r;\n" +
        "  if (maskA <= 0.004) return base;\n" +
        "  highp vec4 l0 = texture2D(u_wm_lut0, lutUv) * 255.0;\n" +
        "  highp vec4 l1 = texture2D(u_wm_lut1, lutUv) * 255.0;\n" +
        "  vec3 accCol = vec3(0.0);\n" +
        "  highp float accW = 0.0;\n" +
        "  evalDirRay(x, y, vec2(1.0, 0.0),      l0.r, l0.g, accCol, accW);\n" +
        "  evalDirRay(x, y, vec2(0.643, -0.766), l0.b, l0.a, accCol, accW);\n" +
        "  evalDirRay(x, y, vec2(0.0, 1.0),      l1.r, l1.g, accCol, accW);\n" +
        "  evalDirRay(x, y, vec2(0.819, 0.574),  l1.b, l1.a, accCol, accW);\n" +
        "  if (accW <= 0.00001) return base;\n" +
        "  return mix(base, accCol / accW, maskA);\n" +
        "}\n" +
        "\n" +
        "// Slope-matched background estimator for bottom-right UTC band (follows slanted fence/gate edges).\n" +
        "void evalUtcSlope(highp float x, highp float ty, highp float s, inout vec3 accCol, inout highp float accW) {\n" +
        "  highp float xt = x - s * ty;\n" +
        "  highp float xb = x + s * (1.0 - ty);\n" +
        "  vec3 ct = 0.6 * samplePx(xt, 677.0).rgb + 0.4 * samplePx(xt, 675.0).rgb;\n" +
        "  vec3 cb = 0.6 * samplePx(xb, 706.0).rgb + 0.4 * samplePx(xb, 708.0).rgb;\n" +
        "  highp float diff = length(ct - cb);\n" +
        "  highp float w = exp(-(diff + abs(s) * 0.0008) * 30.0);\n" +
        "  accCol += mix(ct, cb, ty) * w;\n" +
        "  accW += w;\n" +
        "}\n" +
        "\n" +
        "vec3 utcCleanBg(highp float x, highp float y) {\n" +
        "  highp float ty = clamp((y - 677.0) / 29.0, 0.0, 1.0);\n" +
        "  vec3 accCol = vec3(0.0);\n" +
        "  highp float accW = 0.0;\n" +
        "  evalUtcSlope(x, ty, -20.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty, -14.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,  -8.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,  -3.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,   0.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,   3.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,   8.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,  14.0, accCol, accW);\n" +
        "  evalUtcSlope(x, ty,  20.0, accCol, accW);\n" +
        "  return accCol / max(accW, 0.00001);\n" +
        "}\n" +
        "\n" +
        "// Adaptive stroke-only UTC eraser: leaves clean background between digits untouched!\n" +
        "vec3 inpaintUtcAdaptive(vec3 base, highp float x, highp float y) {\n" +
        "  vec3 bg = utcCleanBg(x, y);\n" +
        "  // Measure 5-tap cross max deviation from clean bg so 2px anti-aliased glyph halo is caught\n" +
        "  highp float d0 = length(base - bg);\n" +
        "  highp float d1 = length(samplePx(x - 2.0, y).rgb - bg);\n" +
        "  highp float d2 = length(samplePx(x + 2.0, y).rgb - bg);\n" +
        "  highp float d3 = length(samplePx(x, y - 2.0).rgb - bg);\n" +
        "  highp float d4 = length(samplePx(x, y + 2.0).rgb - bg);\n" +
        "  highp float d5 = length(samplePx(x - 1.5, y - 1.5).rgb - bg);\n" +
        "  highp float d6 = length(samplePx(x + 1.5, y + 1.5).rgb - bg);\n" +
        "  highp float maxD = max(max(max(d0, d1), max(d2, d3)), max(d4, max(d5, d6)));\n" +
        "  highp float edgeX = clamp(min(x - 1014.0, 1266.0 - x) / 4.0, 0.0, 1.0);\n" +
        "  highp float edgeY = clamp(min(y - 679.0, 705.0 - y) / 3.0, 0.0, 1.0);\n" +
        "  highp float strokeMask = smoothstep(0.085, 0.175, maxD) * min(edgeX, edgeY);\n" +
        "  return mix(base, bg, strokeMask);\n" +
        "}\n" +
        "\n" +
        "// Sample clean inpainted color at any nearby video coordinate (used by Liquid Glass refraction)\n" +
        "vec3 sampleCleanForGlass(highp float px, highp float py) {\n" +
        "  vec3 c = samplePx(px, py).rgb;\n" +
        "  if (u_inpaint_enabled > 0.5 && px >= 950.0 && px < 1274.0 && py >= 14.0 && py < 78.0) {\n" +
        "    return inpaintLutRegion(c, px, py, px - 950.0, (py - 14.0) + 88.0);\n" +
        "  }\n" +
        "  return c;\n" +
        "}\n" +
        "\n" +
        "// True Apple Liquid Glass optical refraction + 9-tap frosted blur + rim prism + Fresnel glow\n" +
        "vec3 applyLiquidGlassOrb(vec3 curCol, highp float x, highp float y,\n" +
        "                         highp vec4 orb, highp float focused, highp float active) {\n" +
        "  highp float alpha = orb.w;\n" +
        "  highp float radius = orb.z;\n" +
        "  if (alpha <= 0.01 || radius <= 1.0) return curCol;\n" +
        "  highp vec2 d = vec2(x - orb.x, y - orb.y);\n" +
        "  highp float dist = length(d);\n" +
        "  if (dist > radius + 6.0) return curCol;\n" +
        "\n" +
        "  // Soft subtle ambient drop-shadow halo immediately outside the glass edge (for bright sky legibility)\n" +
        "  highp float rNorm = dist / radius;\n" +
        "  if (rNorm >= 1.0) {\n" +
        "    highp float shadow = exp(-((dist - radius) * 0.42)) * 0.14 * alpha;\n" +
        "    return curCol * (1.0 - shadow);\n" +
        "  }\n" +
        "\n" +
        "  highp vec2 dir = d / max(dist, 0.001);\n" +
        "  // Spherical convex rim refraction: bends live video gently inward along the curved glass rim\n" +
        "  highp float r2 = rNorm * rNorm;\n" +
        "  highp float refractScale = 0.94 - 0.28 * r2 * rNorm;\n" +
        "  highp vec2 refrPx = orb.xy + d * refractScale;\n" +
        "\n" +
        "  // 9-tap circular Gaussian frosted backdrop blur (crystal-clear see-through, silky smooth)\n" +
        "  highp float blurR = radius * 0.16;\n" +
        "  vec3 blurCol = sampleCleanForGlass(refrPx.x, refrPx.y) * 0.24;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x + blurR,          refrPx.y) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x - blurR,          refrPx.y) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x,                  refrPx.y + blurR) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x,                  refrPx.y - blurR) * 0.095;\n" +
        "  highp float bDiag = blurR * 0.72;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x + bDiag, refrPx.y + bDiag) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x - bDiag, refrPx.y + bDiag) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x + bDiag, refrPx.y - bDiag) * 0.095;\n" +
        "  blurCol += sampleCleanForGlass(refrPx.x - bDiag, refrPx.y - bDiag) * 0.095;\n" +
        "\n" +
        "  // Whisper-subtle optical chromatic dispersion along the outer curved rim\n" +
        "  highp float prismAmt = smoothstep(0.62, 0.98, rNorm) * 1.6;\n" +
        "  if (prismAmt > 0.05) {\n" +
        "    highp float rChan = sampleCleanForGlass(refrPx.x + dir.x * prismAmt, refrPx.y + dir.y * prismAmt).r;\n" +
        "    highp float bChan = sampleCleanForGlass(refrPx.x - dir.x * prismAmt, refrPx.y - dir.y * prismAmt).b;\n" +
        "    blurCol.r = mix(blurCol.r, rChan, 0.35);\n" +
        "    blurCol.b = mix(blurCol.b, bChan, 0.35);\n" +
        "  }\n" +
        "\n" +
        "  // Daytime/nighttime optical luminance balance:\n" +
        "  // Keeps bright white sky crystal-clear yet slightly tone-compressed so white icons + rims pop!\n" +
        "  highp float lum = dot(blurCol, vec3(0.299, 0.587, 0.114));\n" +
        "  highp float dayShade = mix(1.0, 0.74, smoothstep(0.50, 0.95, lum));\n" +
        "  vec3 glassCol = blurCol * dayShade;\n" +
        "  // Subtle luminous glass lift over dark nighttime backgrounds\n" +
        "  highp float nightLift = (1.0 - smoothstep(0.05, 0.55, lum)) * (0.11 + 0.07 * focused);\n" +
        "  glassCol = mix(glassCol, vec3(0.84, 0.91, 1.0), nightLift);\n" +
        "\n" +
        "  // Translucent Apple Liquid Glass blue tint when Audio toggle is active ON\n" +
        "  if (active > 0.01) {\n" +
        "    vec3 appleBlue = vec3(0.05, 0.52, 1.0);\n" +
        "    glassCol = mix(glassCol, appleBlue, (0.38 + 0.10 * focused) * active);\n" +
        "  }\n" +
        "\n" +
        "  // Fresnel inner glass rim glow + double-crescent diagonal specular catchlights\n" +
        "  highp float fresnel = smoothstep(0.64, 0.99, rNorm);\n" +
        "  highp float diagLight = abs(dot(dir, vec2(-0.707, -0.707)));\n" +
        "  highp float topLeftBoost = smoothstep(-0.2, 0.95, dot(dir, vec2(-0.707, -0.707)));\n" +
        "  highp float rimGlow = fresnel * (0.12 + 0.24 * diagLight + 0.12 * topLeftBoost + 0.16 * focused);\n" +
        "  glassCol += vec3(0.92, 0.96, 1.0) * rimGlow;\n" +
        "\n" +
        "  // Soft top-left internal specular caustic highlight\n" +
        "  highp float caustic = exp(-dot(d / radius - vec2(-0.34, -0.38), d / radius - vec2(-0.34, -0.38)) * 4.5);\n" +
        "  glassCol += vec3(1.0) * caustic * (0.09 + 0.09 * focused);\n" +
        "\n" +
        "  // Anti-aliased circular edge blend\n" +
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
        "      col = inpaintLutRegion(col, x, y, x - 14.0, y - 14.0);\n" +
        "    } else if (x >= 950.0 && x < 1274.0 && y >= 14.0 && y < 78.0) {\n" +
        "      col = inpaintLutRegion(col, x, y, x - 950.0, (y - 14.0) + 88.0);\n" +
        "    } else if (x >= 1014.0 && x <= 1266.0 && y >= 679.0 && y <= 705.0) {\n" +
        "      col = inpaintUtcAdaptive(col, x, y);\n" +
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
