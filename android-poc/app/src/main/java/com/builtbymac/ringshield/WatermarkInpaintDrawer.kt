package org.webrtc

/**
 * Real-time 60 fps OpenGL ES 2.0 Coons-patch fragment shader inpainter for Nvidia Shield Pro.
 *
 * Placed in `package org.webrtc` to access package-private `GlGenericDrawer` while returning
 * the public `RendererCommon.GlDrawer` interface.
 */
object WatermarkInpaintDrawerFactory {

    fun create(): RendererCommon.GlDrawer = object : GlGenericDrawer(
        INPAINT_VERTEX_SHADER,
        INPAINT_FRAGMENT_SHADER,
        object : ShaderCallbacks {
            override fun onNewShader(shader: GlShader) = Unit

            override fun onPrepareShader(
                shader: GlShader,
                texMatrix: FloatArray,
                frameWidth: Int,
                frameHeight: Int,
                viewportWidth: Int,
                viewportHeight: Int,
            ) = Unit
        }
    ) {}

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
        "vec4 samplePx(highp float px, highp float py) {\n" +
        "  return sample(tc + (px - v_px.x) * v_dx + (py - v_px.y) * v_dy);\n" +
        "}\n" +
        "vec4 sample3X(highp float px, highp float py) {\n" +
        "  return (samplePx(px - 3.0, py) + samplePx(px, py) + samplePx(px + 3.0, py)) * 0.3333333;\n" +
        "}\n" +
        "vec4 sample3Y(highp float px, highp float py) {\n" +
        "  return (samplePx(px, py - 3.0) + samplePx(px, py) + samplePx(px, py + 3.0)) * 0.3333333;\n" +
        "}\n" +
        "highp float boxWeight(highp float x, highp float y, highp float x0, highp float y0, highp float x1, highp float y1, highp float feather) {\n" +
        "  highp float dx = min(x - x0, x1 - x);\n" +
        "  highp float dy = min(y - y0, y1 - y);\n" +
        "  highp float d = min(dx, dy);\n" +
        "  return clamp(d / feather, 0.0, 1.0);\n" +
        "}\n" +
        "void main() {\n" +
        "  vec4 base = sample(tc);\n" +
        "  highp float x = v_px.x;\n" +
        "  highp float y = v_px.y;\n" +
        "  if (x >= 18.0 && x <= 131.0 && y >= 18.0 && y <= 94.0) {\n" +
        "    highp float ty = clamp((y - 16.0) / (96.0 - 16.0), 0.0, 1.0);\n" +
        "    highp float tx = clamp((x - 16.0) / (134.0 - 16.0), 0.0, 1.0);\n" +
        "    vec4 vCol = mix(sample3X(x, 16.0), sample3X(x, 96.0), ty);\n" +
        "    vec4 hCol = mix(sample3Y(16.0, y), sample3Y(134.0, y), tx);\n" +
        "    vec4 bg = mix(vCol, hCol, 0.5);\n" +
        "    highp float w = boxWeight(x, y, 18.0, 18.0, 131.0, 94.0, 4.0);\n" +
        "    gl_FragColor = mix(base, bg, w);\n" +
        "    return;\n" +
        "  }\n" +
        "  if (x >= 954.0 && x <= 1148.0 && y >= 17.0 && y <= 40.0) {\n" +
        "    highp float ty = clamp((y - 16.0) / (41.0 - 16.0), 0.0, 1.0);\n" +
        "    vec4 vCol = mix(sample3X(x, 16.0), sample3X(x, 41.0), ty);\n" +
        "    highp float w = boxWeight(x, y, 954.0, 17.0, 1148.0, 40.0, 2.5);\n" +
        "    gl_FragColor = mix(base, vCol, w);\n" +
        "    return;\n" +
        "  }\n" +
        "  if (x >= 954.0 && x <= 1265.0 && y >= 42.0 && y <= 68.0) {\n" +
        "    highp float ty = clamp((y - 41.0) / (70.0 - 41.0), 0.0, 1.0);\n" +
        "    vec4 vCol = mix(sample3X(x, 41.0), sample3X(x, 70.0), ty);\n" +
        "    highp float w = boxWeight(x, y, 954.0, 42.0, 1265.0, 68.0, 2.5);\n" +
        "    gl_FragColor = mix(base, vCol, w);\n" +
        "    return;\n" +
        "  }\n" +
        "  if (x >= 1016.0 && x <= 1264.0 && y >= 679.0 && y <= 704.0) {\n" +
        "    highp float ty = clamp((y - 677.0) / (706.0 - 677.0), 0.0, 1.0);\n" +
        "    vec4 vCol = mix(sample3X(x, 677.0), sample3X(x, 706.0), ty);\n" +
        "    highp float w = boxWeight(x, y, 1016.0, 679.0, 1264.0, 704.0, 2.5);\n" +
        "    gl_FragColor = mix(base, vCol, w);\n" +
        "    return;\n" +
        "  }\n" +
        "  gl_FragColor = base;\n" +
        "}\n"
}
