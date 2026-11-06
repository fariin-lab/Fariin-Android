package com.fariin.app.ui.components

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fariin.app.data.AnimatedLook
import com.fariin.app.data.AnimatedWallpaper
import com.fariin.app.data.AnimatedWallpapers
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
 *    is undefined for a negative x in both shading languages;
 *  - every falling edge is written 1 - smoothstep(0, a, x): smoothstep with edge0 > edge1 is undefined
 *    in AGSL, so it may draw nothing (or garbage) on some GPUs.
 * `palette` is the look's variant (AnimatedWallpaper.variant): Aurora's colours, the particle kind.
 * Doodles is not a shader (ChatWallpaper.kt drawDoodles).
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
    // Fills a phone screen (owner 2026-11-06: "80-90% empty"). The warped noise sits around 0.31 on
    // average and the body used to begin at 0.52, so most of the screen was bare ground. The
    // thresholds now sit on the noise's real spread (iOS b20f7cdb).
    float2 uv = position / max(size.y, 1.0) * 2.0;
    float y = position.y / max(size.y, 1.0);
    float2 m1 = awCircle(phase, 0.55);
    float2 m2 = awCircle(phase + 2.1, 0.45);
    float2 q = float2(awFbm(uv + m1), awFbm(uv + float2(5.2, 1.3) - m2));
    float2 r = float2(awFbm(uv + 2.0 * q + float2(1.7, 9.2) + m2 * 0.6),
                      awFbm(uv + 2.0 * q + float2(8.3, 2.8) - m1 * 0.6));
    float f = awFbm(uv + 2.2 * r) + palette * 0.0;
    float body = smoothstep(0.33, 0.46, f);
    float glow = smoothstep(0.23, 0.52, f) * 0.5;
    float e = (f - 0.34) / 0.03;
    float edge = exp(-e * e);
    float shade = (1.0 - y) * 0.3 + 0.7;   // light from above
    float3 rgb = float3(0.0);
    if (dark > 0.5) {
        float3 ground = mix(float3(0.02, 0.03, 0.06), float3(0.04, 0.06, 0.11), y);
        rgb = ground + float3(0.78, 0.83, 0.90) * (body * 0.70 + glow * 0.35) * shade
            + float3(0.30, 0.78, 1.00) * edge * 0.90;
    } else {
        rgb = mix(float3(0.90, 0.92, 0.95), float3(0.42, 0.50, 0.62), body * 0.60 + glow * 0.25);
        rgb = mix(rgb, float3(0.05, 0.55, 0.85), edge * 0.75);
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
    // Across the whole screen (owner 2026-11-06: the aurora sat in one corner). x used to be measured
    // in screen heights, so on a tall phone only the edge of one curtain fell inside. Now x runs 0 to
    // 1 across the width, the curtains are wider, and a faint glow of their colour lifts the lower sky.
    float2 uv = float2(position.x / max(size.x, 1.0), position.y / max(size.y, 1.0));
    float y = uv.y;
    float2 s = float2(uv.x + uv.y * 0.25, uv.y - uv.x * 0.15);
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
        float wob = awFbm(float2(s.y * 1.5 + fi * 3.1, m.x * 0.8 + fi)) * 1.2;
        float x = s.x * 1.6 + wob + m.y * 0.3 + fi * 0.7;
        float c = 0.5 + 0.5 * sin(x * 3.14159);
        float curtain = c * c * c;
        float base = 0.62 + 0.10 * sin(s.x * 3.0 + phase + fi) + 0.06 * fi;
        float rise = smoothstep(base - 0.62, base - 0.04, s.y) * (1.0 - smoothstep(base, base + 0.08, s.y));
        // The rays read their noise on a circle, so the last frame of a loop is the first (no seam).
        float rays = 0.6 + 0.4 * awNoise(float2(s.x * 30.0 + fi * 7.0 + cos(phase) * 2.0, fi * 5.0 + sin(phase) * 2.0));
        float glow = curtain * rise * rays;
        light += mix(a, b, smoothstep(base - 0.5, base, s.y)) * glow * (0.9 - fi * 0.2);
    }
    float3 haze = mix(a, b, 0.5) * smoothstep(0.3, 1.0, y) * 0.10;
    return half4(half3(saturate(sky + light + haze)), 1.0);
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
    // A floating glowing liquid shape fully in view (owner 2026-11-06: the first one was 99% black on a
    // phone): a smaller body, a wider rim, a soft halo around it and a lit ground (iOS 67c895f9).
    float2 uv = position / max(size.y, 1.0);
    float aspect = size.x / max(size.y, 1.0);
    float2 m = float2(cos(phase), sin(phase));
    float2 b0 = float2(aspect * 0.55, 0.32) + m * float2(0.10, 0.08);
    float2 b1 = float2(aspect * 0.45, 0.52) + float2(m.y, -m.x) * float2(0.10, 0.08);
    float2 b2 = float2(aspect * 0.58, 0.70) + float2(-m.x, m.y) * float2(0.09, 0.08);
    float f = 0.020 / max(dot(uv - b0, uv - b0), 1e-5) + 0.028 / max(dot(uv - b1, uv - b1), 1e-5)
            + 0.018 / max(dot(uv - b2, uv - b2), 1e-5);
    f += (awFbm(uv * 3.0 + m * 0.4) - 0.5) * 0.35 + palette * 0.0;
    float inside = smoothstep(0.90, 1.15, f);
    float e = (f - 1.0) / 0.24;
    float edge = exp(-e * e);
    float halo = exp(-max(0.0, 1.0 - f) * 2.5) * (1.0 - inside);
    float y = position.y / max(size.y, 1.0);
    float3 rgb = float3(0.0);
    if (dark > 0.5) {
        float3 ground = mix(float3(0.035, 0.045, 0.095), float3(0.060, 0.045, 0.120), y);
        float3 body = mix(float3(0.16, 0.20, 0.30), float3(0.10, 0.12, 0.20), y);
        rgb = mix(ground, body, inside) + float3(0.35, 0.60, 0.95) * (edge * 0.85 + halo * 0.22);
    } else {
        float3 ground = mix(float3(0.93, 0.94, 0.97), float3(0.88, 0.90, 0.96), y);
        float3 body = mix(float3(0.80, 0.85, 0.94), float3(0.74, 0.79, 0.90), y);
        rgb = mix(mix(ground, body, inside), float3(0.25, 0.45, 0.85), saturate(edge * 0.6 + halo * 0.15));
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

// A deep sky: a faint nebula drifting on a slow loop, and stars on a grid of cells (one per cell at a
// random spot and size), each twinkling a whole number of times per loop so the loop has no seam.
private const val STARS = PRELUDE + """
half4 main(float2 position) {
    float2 uv = position / max(size.y, 1.0);
    float y = uv.y + palette * 0.0;
    float3 sky = dark > 0.5 ? mix(float3(0.010, 0.012, 0.040), float3(0.030, 0.020, 0.075), y)
                            : mix(float3(0.075, 0.085, 0.190), float3(0.150, 0.110, 0.260), y);
    float2 m = awCircle(phase, 0.25);
    float neb = awFbm(uv * 1.6 + m);
    // Brighter (owner 2026-11-06: faint on a phone): the nebula half again as strong, stars larger.
    float3 rgb = sky + float3(0.20, 0.10, 0.35) * smoothstep(0.40, 0.85, neb) * 0.65
                     + float3(0.05, 0.15, 0.30) * smoothstep(0.50, 0.90, awFbm(uv * 2.3 - m + 7.0)) * 0.50;
    for (int layer = 0; layer < 2; layer++) {
        float fl = float(layer);
        float scale = layer == 0 ? 22.0 : 40.0;
        float2 g = uv * scale;
        float2 cell = floor(g);
        float2 f = fract(g);
        float h = awHash(cell + fl * 31.0);
        float2 spot = float2(awHash(cell + 3.1), awHash(cell + 7.7)) * 0.8 + 0.1;
        float d = length(f - spot);
        float radius = (layer == 0 ? 0.10 : 0.065) * (0.5 + h);
        float star = 1.0 - smoothstep(0.0, radius, d);
        float rate = floor(1.0 + h * 4.0);
        float tw = 0.55 + 0.45 * sin(phase * rate + h * 6.2831);
        float show = step(0.55, h);
        rgb += float3(0.85, 0.90, 1.0) * star * tw * show * (layer == 0 ? 1.0 : 0.6);
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

// Snow, rain, glowing dots: one particle per cell, the cells falling a whole number of their repeating
// rows per loop (AnimatedWallpapers.particleRows), so the last frame of a loop is the first. `palette`
// is the kind (0 snow, 1 rain, 2 dots); `amount` 0..2 the intensity (how many layers are drawn).
private val PARTICLES = PRELUDE + """
uniform float amount;

half4 main(float2 position) {
    float kind = palette;
    float2 uv = position / max(size.y, 1.0);
    float y = uv.y;
    float t = phase / 6.28318;
    float3 ground = float3(0.0);
    if (kind < 0.5)      ground = dark > 0.5 ? mix(float3(0.06, 0.08, 0.14), float3(0.10, 0.12, 0.20), y)
                                             : mix(float3(0.70, 0.78, 0.88), float3(0.86, 0.90, 0.95), y);
    else if (kind < 1.5) ground = dark > 0.5 ? mix(float3(0.05, 0.07, 0.10), float3(0.09, 0.11, 0.15), y)
                                             : mix(float3(0.55, 0.62, 0.70), float3(0.72, 0.77, 0.82), y);
    else                 ground = dark > 0.5 ? mix(float3(0.03, 0.03, 0.08), float3(0.06, 0.04, 0.12), y)
                                             : mix(float3(0.92, 0.88, 0.96), float3(0.97, 0.93, 0.90), y);
    float3 rgb = ground;
    int layers = 1 + int(clamp(amount, 0.0, 2.0));
    float rows = kind < 0.5 ? ${AnimatedWallpapers.particleRows(0)}.0 : (kind < 1.5 ? ${AnimatedWallpapers.particleRows(1)}.0 : ${AnimatedWallpapers.particleRows(2)}.0);
    for (int layer = 0; layer < 3; layer++) {
        if (layer >= layers) break;
        float fl = float(layer);
        float scale = kind < 1.5 ? (9.0 + fl * 5.0) : (6.0 + fl * 3.0);
        float speed = rows * (1.0 + fl);
        float2 g = uv * scale;
        // Rain leans a little, set from the position before the fall so the lean never moves with time.
        if (kind > 0.5 && kind < 1.5) g.x += g.y * 0.12;
        g.y -= t * speed;
        float2 cell = floor(g);
        cell.y = cell.y - rows * floor(cell.y / rows);
        float2 f = fract(g);
        float h = awHash(cell + fl * 17.0);
        if (h < (kind < 1.5 ? 0.35 : 0.55)) continue;
        // Spots stay in the middle of their cell, so nothing is cut at a cell's edge.
        float2 spot = float2(awHash(cell + 1.3), awHash(cell + 5.9)) * 0.4 + 0.3;
        if (kind < 0.5) {
            spot.x += 0.12 * sin(phase * floor(1.0 + h * 2.0) * 8.0 + h * 6.2831);
            float d = length(f - spot);
            float flake = 1.0 - smoothstep(0.0, 0.10 - fl * 0.02, d);
            float3 c = dark > 0.5 ? float3(0.92, 0.95, 1.0) : float3(1.0, 1.0, 1.0);
            rgb = mix(rgb, c, flake * (0.85 - fl * 0.2));
        } else if (kind < 1.5) {
            float2 q = f - spot;
            float streak = (1.0 - smoothstep(0.0, 0.026, abs(q.x))) * (1.0 - smoothstep(0.0, 0.24, abs(q.y)));
            float3 c = dark > 0.5 ? float3(0.65, 0.75, 0.90) : float3(0.95, 0.97, 1.0);
            rgb = mix(rgb, c, streak * (0.75 - fl * 0.15));
        } else {
            float2 drift = float2(cos(phase * floor(1.0 + h * 2.0) * 8.0 + h * 9.0), sin(phase * 8.0 + h * 5.0)) * 0.08;
            float d = length(f - spot - drift);
            float pulse = 0.6 + 0.4 * sin(phase * floor(1.0 + h * 3.0) * 16.0 + h * 6.2831);
            float core = 1.0 - smoothstep(0.0, 0.06, d);
            float halo = (1.0 - smoothstep(0.0, 0.20, d)) * 0.45;
            float3 c = dark > 0.5 ? mix(float3(1.0, 0.85, 0.45), float3(0.55, 0.85, 1.0), h)
                                  : mix(float3(1.0, 0.65, 0.35), float3(0.55, 0.55, 1.0), h);
            // On a light ground adding light would only whiten it, so the dot mixes toward its colour.
            if (dark > 0.5) { rgb += c * (core + halo) * pulse * 0.9; }
            else { rgb = mix(rgb, c, saturate((core + halo) * pulse * 0.75)); }
        }
    }
    return half4(half3(saturate(rgb)), 1.0);
}
"""

/** One compiled shader per look, made on first use and reused by every chat and tile (drawing is on the main thread). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object AnimatedShaders {
    private val shaders = HashMap<AnimatedLook, RuntimeShader>()

    /** [intensity] is the particle setting (0..2), read by Snow, Rain and Glowing Dots only. Not for Doodles. */
    fun get(a: AnimatedWallpaper, width: Float, height: Float, phase: Float, dark: Boolean, intensity: Int): RuntimeShader {
        val s = shaders.getOrPut(a.look) {
            RuntimeShader(when (a.look) {
                AnimatedLook.LIQUID_METAL -> LIQUID_METAL
                AnimatedLook.WATER -> WATER
                AnimatedLook.LIQUID_GLOW -> LIQUID_GLOW
                AnimatedLook.LIQUID_EDGE -> LIQUID_EDGE
                AnimatedLook.AURORA -> AURORA
                AnimatedLook.STARS -> STARS
                AnimatedLook.PARTICLES -> PARTICLES
                // Never asked for (drawn without a shader); a cheap look if it ever is.
                AnimatedLook.DOODLES -> LIQUID_GLOW
            })
        }
        s.setFloatUniform("size", width, height)
        s.setFloatUniform("phase", phase)
        s.setFloatUniform("dark", if (dark) 1f else 0f)
        s.setFloatUniform("palette", a.variant.toFloat())
        if (a.look == AnimatedLook.PARTICLES) s.setFloatUniform("amount", intensity.coerceIn(0, 2).toFloat())
        return s
    }
}

/**
 * Below Android 13: the loop's first frame worked out on the CPU, the same maths as the shaders, into
 * a bitmap 96 pixels tall (the looks are soft, so scaled up it reads the same); Stars and the particles
 * 240 tall, or their small dots would vanish. One per look, mode, shape and intensity, made once.
 */
internal object AnimatedStill {
    private val cache = HashMap<String, ImageBitmap>()

    fun bitmap(a: AnimatedWallpaper, aspect: Float, dark: Boolean, intensity: Int): ImageBitmap {
        val sharp = a.look == AnimatedLook.STARS || a.look == AnimatedLook.PARTICLES
        val h = if (sharp) 240 else 96
        val w = (h * aspect).toInt().coerceIn(8, h * 2)
        val amount = if (a.look == AnimatedLook.PARTICLES) intensity.coerceIn(0, 2) else 0
        return synchronized(cache) {
            cache.getOrPut("${a.id}|$dark|$w|$amount") {
                val px = IntArray(w * h)
                val sw = w.toFloat(); val sh = h.toFloat()
                for (y in 0 until h) for (x in 0 until w) {
                    px[y * w + x] = color(a, x + 0.5f, y + 0.5f, sw, sh, dark, amount)
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
    private fun color(a: AnimatedWallpaper, px: Float, py: Float, w: Float, h: Float, dark: Boolean, amount: Int): Int = when (a.look) {
        AnimatedLook.STARS -> stars(px / h, py / h, dark)
        AnimatedLook.PARTICLES -> particles(a.variant, px / h, py / h, dark, amount)
        // Doodles never asks for a still (it draws its theme and sheet); its theme's flat colour if it did.
        AnimatedLook.DOODLES -> (a.doodleTheme?.flat(dark) ?: 0L).toInt() or (0xFF shl 24)
        AnimatedLook.LIQUID_METAL -> {
            val ux = px / h * 2.0f; val uy = py / h * 2.0f
            val y = py / max(h, 1f)
            val m1x = 0.55f; val m1y = 0f
            val m2x = cos(2.1f) * 0.45f; val m2y = sin(2.1f) * 0.45f
            val qx = fbm(ux + m1x, uy + m1y); val qy = fbm(ux + 5.2f - m2x, uy + 1.3f - m2y)
            val rx = fbm(ux + 2 * qx + 1.7f + m2x * 0.6f, uy + 2 * qy + 9.2f + m2y * 0.6f)
            val ry = fbm(ux + 2 * qx + 8.3f - m1x * 0.6f, uy + 2 * qy + 2.8f - m1y * 0.6f)
            val f = fbm(ux + 2.2f * rx, uy + 2.2f * ry)
            val body = smooth(0.33f, 0.46f, f); val glow = smooth(0.23f, 0.52f, f) * 0.5f
            val e = (f - 0.34f) / 0.03f; val edge = exp(-e * e)
            val shade = (1f - y) * 0.3f + 0.7f
            if (dark) {
                val k = (body * 0.70f + glow * 0.35f) * shade; val c = edge * 0.90f
                pack(mixf(0.02f, 0.04f, y) + 0.78f * k + 0.30f * c, mixf(0.03f, 0.06f, y) + 0.83f * k + 0.78f * c, mixf(0.06f, 0.11f, y) + 0.90f * k + 1.00f * c)
            } else {
                val k = body * 0.60f + glow * 0.25f; val t = edge * 0.75f
                pack(mixf(mixf(0.90f, 0.42f, k), 0.05f, t), mixf(mixf(0.92f, 0.50f, k), 0.55f, t), mixf(mixf(0.95f, 0.62f, k), 0.85f, t))
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
            val ux = px / max(w, 1f); val uy = py / max(h, 1f)
            val y = uy
            val sx = ux + uy * 0.25f; val sy = uy - ux * 0.15f
            val mx = 0.7f; val my = 0f
            val (ca, cb) = when (a.variant) {
                0 -> floatArrayOf(0.10f, 0.95f, 0.75f) to floatArrayOf(0.15f, 0.35f, 1.00f)
                1 -> floatArrayOf(0.80f, 0.30f, 1.00f) to floatArrayOf(0.35f, 0.22f, 0.95f)
                else -> floatArrayOf(1.00f, 0.28f, 0.58f) to floatArrayOf(1.00f, 0.58f, 0.38f)
            }
            val sky = if (dark) floatArrayOf(mixf(0.015f, 0.030f, y), mixf(0.020f, 0.025f, y), mixf(0.055f, 0.080f, y))
                else floatArrayOf(mixf(0.090f, 0.140f, y), mixf(0.090f, 0.120f, y), mixf(0.170f, 0.230f, y))
            val light = FloatArray(3)
            for (i in 0 until 3) {
                val fi = i.toFloat()
                val wob = fbm(sy * 1.5f + fi * 3.1f, mx * 0.8f + fi) * 1.2f
                val x = sx * 1.6f + wob + my * 0.3f + fi * 0.7f
                val c = 0.5f + 0.5f * sin(x * 3.14159f)
                val curtain = c * c * c
                val base = 0.62f + 0.10f * sin(sx * 3f + fi) + 0.06f * fi
                val rise = smooth(base - 0.62f, base - 0.04f, sy) * (1 - smooth(base, base + 0.08f, sy))
                val rays = 0.6f + 0.4f * noise(sx * 30f + fi * 7f + 2f, fi * 5f)   // cos(0) * 2, sin(0) * 2
                val glow = curtain * rise * rays * (0.9f - fi * 0.2f)
                val t = smooth(base - 0.5f, base, sy)
                for (k in 0 until 3) light[k] += mixf(ca[k], cb[k], t) * glow
            }
            val hz = smooth(0.3f, 1f, y) * 0.10f
            pack(sky[0] + light[0] + mixf(ca[0], cb[0], 0.5f) * hz, sky[1] + light[1] + mixf(ca[1], cb[1], 0.5f) * hz,
                sky[2] + light[2] + mixf(ca[2], cb[2], 0.5f) * hz)
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
            // The same shape as the shader at phase 0 (owner 2026-11-06 retune, iOS 67c895f9).
            val ux = px / h; val uy = py / h
            val aspect = w / max(h, 1f)
            val mx = 1f; val my = 0f
            fun inv(k: Float, bx: Float, by: Float): Float { val dx = ux - bx; val dy = uy - by; return k / max(dx * dx + dy * dy, 1e-5f) }
            var f = inv(0.020f, aspect * 0.55f + mx * 0.10f, 0.32f + my * 0.08f) +
                inv(0.028f, aspect * 0.45f + my * 0.10f, 0.52f - mx * 0.08f) +
                inv(0.018f, aspect * 0.58f - mx * 0.09f, 0.70f + my * 0.08f)
            f += (fbm(ux * 3f + mx * 0.4f, uy * 3f + my * 0.4f) - 0.5f) * 0.35f
            val inside = smooth(0.90f, 1.15f, f)
            val e = (f - 1f) / 0.24f; val edge = exp(-e * e)
            val halo = exp(-max(0f, 1f - f) * 2.5f) * (1f - inside)
            val y = py / max(h, 1f)
            if (dark) {
                val g = floatArrayOf(mixf(0.035f, 0.060f, y), mixf(0.045f, 0.045f, y), mixf(0.095f, 0.120f, y))
                val b = floatArrayOf(mixf(0.16f, 0.10f, y), mixf(0.20f, 0.12f, y), mixf(0.30f, 0.20f, y))
                val r = edge * 0.85f + halo * 0.22f
                pack(mixf(g[0], b[0], inside) + 0.35f * r, mixf(g[1], b[1], inside) + 0.60f * r, mixf(g[2], b[2], inside) + 0.95f * r)
            } else {
                val g = floatArrayOf(mixf(0.93f, 0.88f, y), mixf(0.94f, 0.90f, y), mixf(0.97f, 0.96f, y))
                val b = floatArrayOf(mixf(0.80f, 0.74f, y), mixf(0.85f, 0.79f, y), mixf(0.94f, 0.90f, y))
                val t = (edge * 0.6f + halo * 0.15f).coerceIn(0f, 1f)
                pack(mixf(mixf(g[0], b[0], inside), 0.25f, t), mixf(mixf(g[1], b[1], inside), 0.45f, t), mixf(mixf(g[2], b[2], inside), 0.85f, t))
            }
        }
    }

    /** The STARS shader at phase 0 for one point (uv in heights). */
    private fun stars(ux: Float, uy: Float, dark: Boolean): Int {
        val y = uy
        val rgb = if (dark) floatArrayOf(mixf(0.010f, 0.030f, y), mixf(0.012f, 0.020f, y), mixf(0.040f, 0.075f, y))
            else floatArrayOf(mixf(0.075f, 0.150f, y), mixf(0.085f, 0.110f, y), mixf(0.190f, 0.260f, y))
        val mx = 0.25f; val my = 0f
        val n1 = smooth(0.40f, 0.85f, fbm(ux * 1.6f + mx, uy * 1.6f + my)) * 0.65f
        val n2 = smooth(0.50f, 0.90f, fbm(ux * 2.3f - mx + 7f, uy * 2.3f - my + 7f)) * 0.50f
        rgb[0] += 0.20f * n1 + 0.05f * n2; rgb[1] += 0.10f * n1 + 0.15f * n2; rgb[2] += 0.35f * n1 + 0.30f * n2
        for (layer in 0 until 2) {
            val scale = if (layer == 0) 22f else 40f
            val gx = ux * scale; val gy = uy * scale
            val cx = floor(gx); val cy = floor(gy)
            val hv = hash(cx + layer * 31f, cy + layer * 31f)
            if (hv < 0.55f) continue
            val sx = hash(cx + 3.1f, cy + 3.1f) * 0.8f + 0.1f; val sy = hash(cx + 7.7f, cy + 7.7f) * 0.8f + 0.1f
            val dx = gx - cx - sx; val dy = gy - cy - sy
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val radius = (if (layer == 0) 0.10f else 0.065f) * (0.5f + hv)
            val star = 1f - smooth(0f, radius, d)
            val tw = 0.55f + 0.45f * sin(hv * 6.2831f)
            val k = star * tw * (if (layer == 0) 1f else 0.6f)
            rgb[0] += 0.85f * k; rgb[1] += 0.90f * k; rgb[2] += 1.0f * k
        }
        return pack(rgb[0], rgb[1], rgb[2])
    }

    /** The PARTICLES shader at phase 0 for one point: [kind] 0 snow, 1 rain, 2 dots; [amount] 0..2. */
    private fun particles(kind: Int, ux: Float, uy: Float, dark: Boolean, amount: Int): Int {
        val y = uy
        fun grad(a: FloatArray, b: FloatArray) = floatArrayOf(mixf(a[0], b[0], y), mixf(a[1], b[1], y), mixf(a[2], b[2], y))
        val rgb = when (kind) {
            0 -> if (dark) grad(floatArrayOf(0.06f, 0.08f, 0.14f), floatArrayOf(0.10f, 0.12f, 0.20f))
                else grad(floatArrayOf(0.70f, 0.78f, 0.88f), floatArrayOf(0.86f, 0.90f, 0.95f))
            1 -> if (dark) grad(floatArrayOf(0.05f, 0.07f, 0.10f), floatArrayOf(0.09f, 0.11f, 0.15f))
                else grad(floatArrayOf(0.55f, 0.62f, 0.70f), floatArrayOf(0.72f, 0.77f, 0.82f))
            else -> if (dark) grad(floatArrayOf(0.03f, 0.03f, 0.08f), floatArrayOf(0.06f, 0.04f, 0.12f))
                else grad(floatArrayOf(0.92f, 0.88f, 0.96f), floatArrayOf(0.97f, 0.93f, 0.90f))
        }
        fun mixTo(r: Float, g: Float, b: Float, t: Float) { rgb[0] = mixf(rgb[0], r, t); rgb[1] = mixf(rgb[1], g, t); rgb[2] = mixf(rgb[2], b, t) }
        val rows = AnimatedWallpapers.particleRows(kind)
        for (layer in 0 until AnimatedWallpapers.particleLayers(amount)) {
            val fl = layer.toFloat()
            val scale = if (kind <= 1) 9f + fl * 5f else 6f + fl * 3f
            var gx = ux * scale; val gy = uy * scale
            if (kind == 1) gx += gy * 0.12f
            val cx = floor(gx); val cy = AnimatedWallpapers.wrapRow(floor(gy), rows)
            val fx = gx - floor(gx); val fy = gy - floor(gy)
            val hv = hash(cx + fl * 17f, cy + fl * 17f)
            if (hv < (if (kind <= 1) 0.35f else 0.55f)) continue
            var sx = hash(cx + 1.3f, cy + 1.3f) * 0.4f + 0.3f; val sy = hash(cx + 5.9f, cy + 5.9f) * 0.4f + 0.3f
            when (kind) {
                0 -> {
                    sx += 0.12f * sin(hv * 6.2831f)
                    val dx = fx - sx; val dy = fy - sy
                    val flake = 1f - smooth(0f, 0.10f - fl * 0.02f, kotlin.math.sqrt(dx * dx + dy * dy))
                    if (dark) mixTo(0.92f, 0.95f, 1f, flake * (0.85f - fl * 0.2f)) else mixTo(1f, 1f, 1f, flake * (0.85f - fl * 0.2f))
                }
                1 -> {
                    val streak = (1f - smooth(0f, 0.026f, abs(fx - sx))) * (1f - smooth(0f, 0.24f, abs(fy - sy)))
                    if (dark) mixTo(0.65f, 0.75f, 0.90f, streak * (0.75f - fl * 0.15f)) else mixTo(0.95f, 0.97f, 1f, streak * (0.75f - fl * 0.15f))
                }
                else -> {
                    val dx = fx - sx - cos(hv * 9f) * 0.08f; val dy = fy - sy - sin(hv * 5f) * 0.08f
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    val pulse = 0.6f + 0.4f * sin(hv * 6.2831f)
                    val glow = (1f - smooth(0f, 0.06f, d)) + (1f - smooth(0f, 0.20f, d)) * 0.45f
                    val c = if (dark) floatArrayOf(mixf(1f, 0.55f, hv), mixf(0.85f, 0.85f, hv), mixf(0.45f, 1f, hv))
                        else floatArrayOf(mixf(1f, 0.55f, hv), mixf(0.65f, 0.55f, hv), mixf(0.35f, 1f, hv))
                    if (dark) { val k = glow * pulse * 0.9f; rgb[0] += c[0] * k; rgb[1] += c[1] * k; rgb[2] += c[2] * k }
                    else mixTo(c[0], c[1], c[2], (glow * pulse * 0.75f).coerceIn(0f, 1f))
                }
            }
        }
        return pack(rgb[0], rgb[1], rgb[2])
    }
}
