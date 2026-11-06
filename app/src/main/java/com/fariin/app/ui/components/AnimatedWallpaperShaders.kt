package com.fariin.app.ui.components

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fariin.app.data.AnimatedLook
import com.fariin.app.data.AnimatedWallpaper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/*
 * The animated wallpapers' shaders, ported line for line from iOS AnimatedWallpapers.metal to AGSL
 * (Android 13+). Same noise, same colours, same phase circle. Where AGSL differs:
 *  - no arrays with initialisers, so Liquid Glow's five blobs are written out one by one;
 *  - the thin edge lines (Liquid Metal, Liquid Edge) square the distance instead of pow(x, 2.0), which
 *    is undefined for a negative x in both shading languages.
 * Below Android 13 there are no runtime shaders: [AnimatedStill] runs the same maths once on the CPU
 * into a small bitmap, the first frame of the loop, scaled up.
 */

private const val PRELUDE = """
uniform float2 size;
uniform float phase;
uniform float dark;
uniform float palette;

float awHash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float awNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = awHash(i);
    float b = awHash(i + float2(1.0, 0.0));
    float c = awHash(i + float2(0.0, 1.0));
    float d = awHash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Four octaves, rotated between them so no grid shows.
float awFbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    float2x2 rot = float2x2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        v += amp * awNoise(p);
        p = rot * p * 2.03 + float2(17.1, 9.7);
        amp *= 0.5;
    }
    return v;
}

float2 awCircle(float ph, float r) { return float2(cos(ph), sin(ph)) * r; }
"""

// Every shader reads `palette` once so the uniform is never dropped by the compiler.
private const val LIQUID_METAL = PRELUDE + """
half4 main(float2 position) {
    float2 uv = position / max(size.y, 1.0) * 1.7;
    float2 m1 = awCircle(phase, 0.55);
    float2 m2 = awCircle(phase + 2.1, 0.45);
    float2 q = float2(awFbm(uv + m1), awFbm(uv + float2(5.2, 1.3) - m2));
    float2 r = float2(awFbm(uv + 2.0 * q + float2(1.7, 9.2) + m2 * 0.6),
                      awFbm(uv + 2.0 * q + float2(8.3, 2.8) - m1 * 0.6));
    float f = awFbm(uv + 2.2 * r) + palette * 0.0;
    float body = smoothstep(0.52, 0.78, f);
    float glow = smoothstep(0.40, 0.80, f) * 0.35;
    float e = (f - 0.53) / 0.016;
    float edge = exp(-e * e);
    float3 rgb = float3(0.0);
    if (dark > 0.5) {
        rgb = float3(0.012, 0.020, 0.040) + float3(0.78, 0.83, 0.90) * (body * 0.80 + glow * 0.30)
            + float3(0.30, 0.78, 1.00) * edge * 0.85;
    } else {
        rgb = mix(float3(0.90, 0.92, 0.95), float3(0.40, 0.48, 0.60), body * 0.55 + glow * 0.20);
        rgb = mix(rgb, float3(0.05, 0.55, 0.85), edge * 0.70);
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

private const val WATER = PRELUDE + """
half4 main(float2 position) {
    float2 uv = position / max(size.y, 1.0);
    float y = position.y / max(size.y, 1.0);
    float2 p = float2(uv.x * 1.3, uv.y * 4.5);
    float2 m = awCircle(phase, 0.9);
    float w = awFbm(float2(p.x * 0.9 + m.x, p.y * 0.8 + m.y));
    float w2 = awFbm(float2(p.x * 1.7 - m.y * 0.7, p.y * 1.4 + m.x * 0.7) + 4.0);
    float bands = pow(1.0 - abs(sin((p.y + w * 2.6) * 3.14159)), 9.0);
    float fine = pow(1.0 - abs(sin((p.y * 1.9 + w2 * 3.2) * 3.14159)), 14.0);
    float light = bands * 0.75 + fine * 0.45 + palette * 0.0;
    float3 rgb = float3(0.0);
    if (dark > 0.5) {
        float3 ground = mix(float3(0.14, 0.20, 0.26), float3(0.08, 0.11, 0.15), y);
        rgb = ground + float3(0.50, 0.57, 0.65) * light * 0.42;
    } else {
        float3 top = float3(0.16, 0.66, 0.74);
        float3 mid = float3(0.62, 0.84, 0.88);
        float3 bottom = float3(0.93, 0.94, 0.95);
        float3 ground = y < 0.45 ? mix(top, mid, y / 0.45) : mix(mid, bottom, (y - 0.45) / 0.55);
        rgb = ground + float3(1.0) * light * 0.34;
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

private const val AURORA = PRELUDE + """
half4 main(float2 position) {
    float2 uv = position / max(size.y, 1.0);
    float y = position.y / max(size.y, 1.0);
    float2 s = float2(uv.x * 0.94 + uv.y * 0.34, uv.y * 0.94 - uv.x * 0.34);
    float2 m = awCircle(phase, 0.7);
    float3 a = float3(0.0); float3 b = float3(0.0);
    if (palette < 0.5)      { a = float3(0.10, 0.95, 0.75); b = float3(0.15, 0.35, 1.00); }
    else if (palette < 1.5) { a = float3(0.80, 0.30, 1.00); b = float3(0.35, 0.22, 0.95); }
    else                    { a = float3(1.00, 0.28, 0.58); b = float3(1.00, 0.58, 0.38); }
    float3 sky = dark > 0.5
        ? mix(float3(0.015, 0.020, 0.055), float3(0.030, 0.025, 0.080), y)
        : mix(float3(0.090, 0.090, 0.170), float3(0.140, 0.120, 0.230), y);
    float3 light = float3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float wob = awFbm(float2(s.y * 1.2 + fi * 3.1, m.x * 0.8 + fi)) * 1.6;
        float x = s.x * 2.4 + wob + m.y * 0.35 + fi * 1.9;
        float curtain = pow(0.5 + 0.5 * sin(x * 3.14159), 7.0);
        float base = 0.55 + 0.18 * sin(s.x * 2.0 + phase + fi) + 0.08 * fi;
        float rise = smoothstep(base - 0.75, base - 0.05, s.y) * (1.0 - smoothstep(base, base + 0.06, s.y));
        float rays = 0.55 + 0.45 * awNoise(float2(s.x * 38.0 + fi * 7.0, phase * 0.6 + fi));
        float glow = curtain * rise * rays;
        light += mix(a, b, smoothstep(base - 0.6, base, s.y)) * glow * (0.85 - fi * 0.2);
    }
    return half4(half3(saturate(sky + light * 0.9)), 1.0);
}
"""

private const val LIQUID_GLOW = PRELUDE + """
float blob(float2 uv, float2 c, float r, float aspect) {
    float2 d = (uv - c) * float2(aspect, 1.0);
    return exp(-dot(d, d) / (r * r));
}

half4 main(float2 position) {
    float2 uv = position / max(size, float2(1.0));
    float3 ground = float3(0.0); float3 k0 = ground; float3 k1 = ground; float3 k2 = ground; float3 k3 = ground; float3 k4 = ground;
    if (dark > 0.5) {
        ground = float3(0.03, 0.06, 0.20);
        k0 = float3(0.08, 0.18, 0.62); k1 = float3(0.16, 0.36, 0.78); k2 = float3(0.30, 0.42, 0.22);
        k3 = float3(0.05, 0.10, 0.40); k4 = float3(0.20, 0.30, 0.60);
    } else {
        ground = float3(0.42, 0.62, 0.98);
        k0 = float3(0.16, 0.32, 0.92); k1 = float3(0.50, 0.74, 1.00); k2 = float3(0.90, 0.95, 0.70);
        k3 = float3(0.22, 0.42, 0.95); k4 = float3(0.84, 0.92, 0.98);
    }
    float2 c0 = float2(0.25, 0.25) + float2(cos(phase), sin(phase)) * float2(0.18, 0.10);
    float2 c1 = float2(0.75, 0.35) + float2(cos(phase * 2.0 + 1.0), sin(phase + 2.0)) * float2(0.15, 0.14);
    float2 c2 = float2(0.50, 0.85) + float2(cos(phase + 3.0), sin(phase * 2.0)) * float2(0.25, 0.08);
    float2 c3 = float2(0.20, 0.65) + float2(sin(phase + 0.5), cos(phase * 3.0)) * float2(0.12, 0.12);
    float2 c4 = float2(0.80, 0.70) + float2(sin(phase * 2.0), cos(phase + 4.0)) * float2(0.10, 0.18);
    float aspect = size.x / max(size.y, 1.0) + palette * 0.0;
    float w0 = blob(uv, c0, 0.30, aspect);
    float w1 = blob(uv, c1, 0.26, aspect);
    float w2 = blob(uv, c2, 0.34, aspect);
    float w3 = blob(uv, c3, 0.22, aspect);
    float w4 = blob(uv, c4, 0.24, aspect);
    float3 sum = ground * 0.35 + k0 * w0 + k1 * w1 + k2 * w2 + k3 * w3 + k4 * w4;
    float wsum = 0.35 + w0 + w1 + w2 + w3 + w4;
    return half4(half3(saturate(sum / wsum)), 1.0);
}
"""

private const val LIQUID_EDGE = PRELUDE + """
half4 main(float2 position) {
    float2 uv = position / max(size.y, 1.0);
    float aspect = size.x / max(size.y, 1.0);
    float2 m = float2(cos(phase), sin(phase));
    float2 b0 = float2(aspect * 0.55, 0.30) + m * float2(0.06, 0.05);
    float2 b1 = float2(aspect * 0.45, 0.60) + float2(m.y, -m.x) * float2(0.07, 0.06);
    float2 b2 = float2(aspect * 0.60, 0.88) + float2(-m.x, m.y) * float2(0.05, 0.05);
    float f = 0.055 / max(dot(uv - b0, uv - b0), 1e-5) + 0.075 / max(dot(uv - b1, uv - b1), 1e-5)
            + 0.05 / max(dot(uv - b2, uv - b2), 1e-5);
    f += (awFbm(uv * 3.0 + m * 0.4) - 0.5) * 0.35 + palette * 0.0;
    float inside = smoothstep(0.95, 1.10, f);
    float e = (f - 1.0) / 0.06;
    float edge = exp(-e * e);
    float3 rgb = float3(0.0);
    if (dark > 0.5) {
        rgb = mix(float3(0.020, 0.022, 0.026), float3(0.075, 0.080, 0.090), inside) + float3(0.42, 0.52, 0.68) * edge * 0.75;
    } else {
        rgb = mix(mix(float3(0.93, 0.94, 0.96), float3(0.86, 0.88, 0.92), inside), float3(0.30, 0.45, 0.75), edge * 0.55);
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

/** One compiled shader per look, made on first use and reused by every chat and tile (drawing is on the main thread). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object AnimatedShaders {
    private val shaders = HashMap<AnimatedLook, RuntimeShader>()

    fun get(a: AnimatedWallpaper, width: Float, height: Float, phase: Float, dark: Boolean): RuntimeShader {
        val s = shaders.getOrPut(a.look) {
            RuntimeShader(when (a.look) {
                AnimatedLook.LIQUID_METAL -> LIQUID_METAL
                AnimatedLook.WATER -> WATER
                AnimatedLook.LIQUID_GLOW -> LIQUID_GLOW
                AnimatedLook.LIQUID_EDGE -> LIQUID_EDGE
                AnimatedLook.AURORA -> AURORA
            })
        }
        s.setFloatUniform("size", width, height)
        s.setFloatUniform("phase", phase)
        s.setFloatUniform("dark", if (dark) 1f else 0f)
        s.setFloatUniform("palette", a.palette.toFloat())
        return s
    }
}

/**
 * Below Android 13: the loop's first frame worked out on the CPU, the same maths as the shaders, into
 * a bitmap 96 pixels tall (the looks are soft, so scaled up it reads the same). One per look, mode and
 * shape, made once.
 */
internal object AnimatedStill {
    private val cache = HashMap<String, ImageBitmap>()

    fun bitmap(a: AnimatedWallpaper, aspect: Float, dark: Boolean): ImageBitmap {
        val h = 96
        val w = (h * aspect).toInt().coerceIn(8, 192)
        return synchronized(cache) {
            cache.getOrPut("${a.id}|$dark|$w") {
                val px = IntArray(w * h)
                val sw = w.toFloat(); val sh = h.toFloat()
                for (y in 0 until h) for (x in 0 until w) {
                    px[y * w + x] = color(a, x + 0.5f, y + 0.5f, sw, sh, dark)
                }
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }

    private fun fract(x: Float) = x - floor(x)
    private fun mixf(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun hash(x: Float, y: Float): Float {
        var px = fract(x * 123.34f); var py = fract(y * 456.21f)
        val d = px * (px + 45.32f) + py * (py + 45.32f)
        px += d; py += d
        return fract(px * py)
    }

    private fun noise(x: Float, y: Float): Float {
        val ix = floor(x); val iy = floor(y)
        val fx = x - ix; val fy = y - iy
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        return mixf(mixf(hash(ix, iy), hash(ix + 1, iy), ux), mixf(hash(ix, iy + 1), hash(ix + 1, iy + 1), ux), uy)
    }

    private fun fbm(x0: Float, y0: Float): Float {
        var x = x0; var y = y0; var v = 0f; var amp = 0.5f
        repeat(4) {
            v += amp * noise(x, y)
            // float2x2(0.8, -0.6, 0.6, 0.8) * p, columns first: (0.8x + 0.6y, -0.6x + 0.8y).
            val nx = (0.8f * x + 0.6f * y) * 2.03f + 17.1f
            val ny = (-0.6f * x + 0.8f * y) * 2.03f + 9.7f
            x = nx; y = ny; amp *= 0.5f
        }
        return v
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    /** The colour at one pixel with the phase at 0, as the matching shader. */
    private fun color(a: AnimatedWallpaper, px: Float, py: Float, w: Float, h: Float, dark: Boolean): Int = when (a.look) {
        AnimatedLook.LIQUID_METAL -> {
            val ux = px / h * 1.7f; val uy = py / h * 1.7f
            val m1x = 0.55f; val m1y = 0f
            val m2x = cos(2.1f) * 0.45f; val m2y = sin(2.1f) * 0.45f
            val qx = fbm(ux + m1x, uy + m1y); val qy = fbm(ux + 5.2f - m2x, uy + 1.3f - m2y)
            val rx = fbm(ux + 2 * qx + 1.7f + m2x * 0.6f, uy + 2 * qy + 9.2f + m2y * 0.6f)
            val ry = fbm(ux + 2 * qx + 8.3f - m1x * 0.6f, uy + 2 * qy + 2.8f - m1y * 0.6f)
            val f = fbm(ux + 2.2f * rx, uy + 2.2f * ry)
            val body = smooth(0.52f, 0.78f, f); val glow = smooth(0.40f, 0.80f, f) * 0.35f
            val e = (f - 0.53f) / 0.016f; val edge = exp(-e * e)
            if (dark) {
                val k = body * 0.80f + glow * 0.30f
                pack(0.012f + 0.78f * k + 0.30f * edge * 0.85f, 0.020f + 0.83f * k + 0.78f * edge * 0.85f, 0.040f + 0.90f * k + 1.00f * edge * 0.85f)
            } else {
                val k = body * 0.55f + glow * 0.20f; val t = edge * 0.70f
                pack(mixf(mixf(0.90f, 0.40f, k), 0.05f, t), mixf(mixf(0.92f, 0.48f, k), 0.55f, t), mixf(mixf(0.95f, 0.60f, k), 0.85f, t))
            }
        }
        AnimatedLook.WATER -> {
            val y = py / h
            val p0 = px / h * 1.3f; val p1 = py / h * 4.5f
            val mx = 0.9f; val my = 0f
            val wv = fbm(p0 * 0.9f + mx, p1 * 0.8f + my)
            val w2 = fbm(p0 * 1.7f - my * 0.7f + 4f, p1 * 1.4f + mx * 0.7f + 4f)
            val bands = (1 - abs(sin((p1 + wv * 2.6f) * 3.14159f))).pow(9)
            val fine = (1 - abs(sin((p1 * 1.9f + w2 * 3.2f) * 3.14159f))).pow(14)
            val light = bands * 0.75f + fine * 0.45f
            if (dark) {
                val l = light * 0.42f
                pack(mixf(0.14f, 0.08f, y) + 0.50f * l, mixf(0.20f, 0.11f, y) + 0.57f * l, mixf(0.26f, 0.15f, y) + 0.65f * l)
            } else {
                val g = if (y < 0.45f) { val t = y / 0.45f; floatArrayOf(mixf(0.16f, 0.62f, t), mixf(0.66f, 0.84f, t), mixf(0.74f, 0.88f, t)) }
                    else { val t = (y - 0.45f) / 0.55f; floatArrayOf(mixf(0.62f, 0.93f, t), mixf(0.84f, 0.94f, t), mixf(0.88f, 0.95f, t)) }
                val l = light * 0.34f
                pack(g[0] + l, g[1] + l, g[2] + l)
            }
        }
        AnimatedLook.AURORA -> {
            val y = py / h
            val ux = px / h; val uy = py / h
            val sx = ux * 0.94f + uy * 0.34f; val sy = uy * 0.94f - ux * 0.34f
            val mx = 0.7f; val my = 0f
            val (ca, cb) = when (a.palette) {
                0 -> floatArrayOf(0.10f, 0.95f, 0.75f) to floatArrayOf(0.15f, 0.35f, 1.00f)
                1 -> floatArrayOf(0.80f, 0.30f, 1.00f) to floatArrayOf(0.35f, 0.22f, 0.95f)
                else -> floatArrayOf(1.00f, 0.28f, 0.58f) to floatArrayOf(1.00f, 0.58f, 0.38f)
            }
            val sky = if (dark) floatArrayOf(mixf(0.015f, 0.030f, y), mixf(0.020f, 0.025f, y), mixf(0.055f, 0.080f, y))
                else floatArrayOf(mixf(0.090f, 0.140f, y), mixf(0.090f, 0.120f, y), mixf(0.170f, 0.230f, y))
            val light = FloatArray(3)
            for (i in 0 until 3) {
                val fi = i.toFloat()
                val wob = fbm(sy * 1.2f + fi * 3.1f, mx * 0.8f + fi) * 1.6f
                val x = sx * 2.4f + wob + my * 0.35f + fi * 1.9f
                val curtain = (0.5f + 0.5f * sin(x * 3.14159f)).pow(7)
                val base = 0.55f + 0.18f * sin(sx * 2f + fi) + 0.08f * fi
                val rise = smooth(base - 0.75f, base - 0.05f, sy) * (1 - smooth(base, base + 0.06f, sy))
                val rays = 0.55f + 0.45f * noise(sx * 38f + fi * 7f, fi)
                val glow = curtain * rise * rays * (0.85f - fi * 0.2f)
                val t = smooth(base - 0.6f, base, sy)
                for (k in 0 until 3) light[k] += mixf(ca[k], cb[k], t) * glow
            }
            pack(sky[0] + light[0] * 0.9f, sky[1] + light[1] * 0.9f, sky[2] + light[2] * 0.9f)
        }
        AnimatedLook.LIQUID_GLOW -> {
            val ux = px / w; val uy = py / h
            val aspect = w / max(h, 1f)
            val ground: FloatArray; val ks: Array<FloatArray>
            if (dark) {
                ground = floatArrayOf(0.03f, 0.06f, 0.20f)
                ks = arrayOf(floatArrayOf(0.08f, 0.18f, 0.62f), floatArrayOf(0.16f, 0.36f, 0.78f), floatArrayOf(0.30f, 0.42f, 0.22f),
                    floatArrayOf(0.05f, 0.10f, 0.40f), floatArrayOf(0.20f, 0.30f, 0.60f))
            } else {
                ground = floatArrayOf(0.42f, 0.62f, 0.98f)
                ks = arrayOf(floatArrayOf(0.16f, 0.32f, 0.92f), floatArrayOf(0.50f, 0.74f, 1.00f), floatArrayOf(0.90f, 0.95f, 0.70f),
                    floatArrayOf(0.22f, 0.42f, 0.95f), floatArrayOf(0.84f, 0.92f, 0.98f))
            }
            // The blob centres at phase 0.
            val cs = arrayOf(
                floatArrayOf(0.25f + 0.18f, 0.25f), floatArrayOf(0.75f + cos(1f) * 0.15f, 0.35f + sin(2f) * 0.14f),
                floatArrayOf(0.50f + cos(3f) * 0.25f, 0.85f), floatArrayOf(0.20f + sin(0.5f) * 0.12f, 0.65f + 0.12f),
                floatArrayOf(0.80f, 0.70f + cos(4f) * 0.18f),
            )
            val rs = floatArrayOf(0.30f, 0.26f, 0.34f, 0.22f, 0.24f)
            val sum = floatArrayOf(ground[0] * 0.35f, ground[1] * 0.35f, ground[2] * 0.35f)
            var wsum = 0.35f
            for (i in 0 until 5) {
                val dx = (ux - cs[i][0]) * aspect; val dy = uy - cs[i][1]
                val wt = exp(-(dx * dx + dy * dy) / (rs[i] * rs[i]))
                for (k in 0 until 3) sum[k] += ks[i][k] * wt
                wsum += wt
            }
            pack(sum[0] / wsum, sum[1] / wsum, sum[2] / wsum)
        }
        AnimatedLook.LIQUID_EDGE -> {
            val ux = px / h; val uy = py / h
            val aspect = w / max(h, 1f)
            val mx = 1f; val my = 0f
            fun inv(k: Float, bx: Float, by: Float): Float { val dx = ux - bx; val dy = uy - by; return k / max(dx * dx + dy * dy, 1e-5f) }
            var f = inv(0.055f, aspect * 0.55f + mx * 0.06f, 0.30f + my * 0.05f) +
                inv(0.075f, aspect * 0.45f + my * 0.07f, 0.60f - mx * 0.06f) +
                inv(0.05f, aspect * 0.60f - mx * 0.05f, 0.88f + my * 0.05f)
            f += (fbm(ux * 3f + mx * 0.4f, uy * 3f + my * 0.4f) - 0.5f) * 0.35f
            val inside = smooth(0.95f, 1.10f, f)
            val e = (f - 1f) / 0.06f; val edge = exp(-e * e)
            if (dark) pack(mixf(0.020f, 0.075f, inside) + 0.42f * edge * 0.75f, mixf(0.022f, 0.080f, inside) + 0.52f * edge * 0.75f,
                mixf(0.026f, 0.090f, inside) + 0.68f * edge * 0.75f)
            else { val t = edge * 0.55f
                pack(mixf(mixf(0.93f, 0.86f, inside), 0.30f, t), mixf(mixf(0.94f, 0.88f, inside), 0.45f, t), mixf(mixf(0.96f, 0.92f, inside), 0.75f, t)) }
        }
    }
}
