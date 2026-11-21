package org.webrtc

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import com.builtbymac.ringshield.R
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Real-time 60 fps OpenGL ES 2.0 GPU watermark eraser (0% crop, < 0.4 ms/frame on Shield Pro):
 *  - Top-left 'ring' logo: bit-exact stroke mask + log-sum-exp 4-direction structure-preserving
 *    inpainter using a precomputed LUT atlas on GL_TEXTURE3..5 (res/raw/wm_lut_gz.bin).
 *  - Top-right 'Device-ID / Partner' & bottom-right 'UTC': 9-slope adaptive stroke-only erasers
 *    that follow slanted roof/gate edges and leave clean pixels between glyphs untouched.
 *
 * Placed in `package org.webrtc` to extend package-private `GlGenericDrawer` while returning the
 * public `RendererCommon.GlDrawer` interface.
 */
object WatermarkInpaintDrawerFactory {

    private const val TAG = "WatermarkInpaintDrawer"
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

    fun create(context: Context): RendererCommon.GlDrawer {
        val appContext = context.applicationContext
        val texIds = IntArray(3)
        var lutLoaded = false

        var locLut0 = -1
        var locLut1 = -1
        var locMask = -1

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
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                }
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
        "\n" +
        "vec4 samplePx(highp float px, highp float py) {\n" +
        "  highp float cx = clamp(px, 0.5, 1279.5);\n" +
        "  highp float cy = clamp(py, 0.5, 719.5);\n" +
        "  return sample(tc + (cx - v_px.x) * v_dx + (cy - v_px.y) * v_dy);\n" +
        "}\n" +
        "\n" +
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
        "void main() {\n" +
        "  vec4 base = sample(tc);\n" +
        "  vec3 col = base.rgb;\n" +
        "  highp float x = v_px.x;\n" +
        "  highp float y = v_px.y;\n" +
        "\n" +
        "  if (x >= 14.0 && x < 142.0 && y >= 14.0 && y < 102.0) {\n" +
        "    col = inpaintRingLut(col, x, y, x - 14.0, y - 14.0);\n" +
        "  } else if (x >= 952.0 && x <= 1165.0 && y >= 15.0 && y <= 41.0) {\n" +
        "    col = inpaintTextBandAdaptive(col, x, y, 952.0, 1165.0, 15.0, 41.0, 13.0, 42.0);\n" +
        "  } else if (x >= 952.0 && x <= 1279.0 && y >= 41.0 && y <= 72.0) {\n" +
        "    col = inpaintTextBandAdaptive(col, x, y, 952.0, 1279.0, 41.0, 72.0, 40.0, 74.0);\n" +
        "  } else if (x >= 1014.0 && x <= 1266.0 && y >= 679.0 && y <= 705.0) {\n" +
        "    col = inpaintTextBandAdaptive(col, x, y, 1014.0, 1266.0, 679.0, 705.0, 677.0, 706.0);\n" +
        "  }\n" +
        "\n" +
        "  gl_FragColor = vec4(col, base.a);\n" +
        "}\n"
}
