package com.osint.unified.ui.godeye

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The same sphere as [SphereShading], as a fragment shader.
 *
 * [SphereShading] computes one pixel at a time on the CPU: a square
 * root, an arcsine, an arctangent, two bilinear texture fetches and a
 * lighting term, for up to two million pixels. That is why the globe
 * could only be redrawn off the main thread, only after the camera had
 * stopped moving, and only at a quarter size while a thumb was down —
 * every stutter and every pop came from that one decision.
 *
 * A GPU runs those same instructions on thousands of pixels at once.
 * The arithmetic below is a line-for-line translation of the CPU
 * renderer, so the two agree on what the planet looks like; what
 * changes is that this one finishes inside a frame, at full size,
 * every frame, which deletes the off-thread render, the reused
 * scratch buffer, the settle delay and the lag between the imagery
 * and the coastlines drawn over it.
 *
 * AGSL is Android 13. Below that the CPU renderer is still the
 * renderer — a four-year-old phone would not have enjoyed the GPU
 * path's memory either.
 */
object GlobeShader {

    /** Whether this device can run the shader at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * A shader bound to the two mosaics, or null on a device that
     * cannot run one.
     *
     * Typed as the base [Shader] so the rest of the app never names an
     * API 33 class: the version check lives here, once.
     */
    fun bind(day: Bitmap, night: Bitmap?): Shader? {
        if (!supported) return null
        return runCatching { bound(day, night) }.getOrNull()
    }

    /**
     * Points an already-bound shader at a camera.
     *
     * Called once per frame, immediately before the draw. Twelve
     * uniform writes against two million pixels of work is the whole
     * trade this change makes.
     */
    fun aim(
        shader: Shader,
        centreX: Float,
        centreY: Float,
        radius: Float,
        lat: Double,
        lon: Double,
        sunLat: Double,
        sunLon: Double
    ) {
        if (!supported) return
        runCatching { point(shader, centreX, centreY, radius, lat, lon, sunLat, sunLon) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun bound(day: Bitmap, night: Bitmap?): Shader? {
        val shader = RuntimeShader(SOURCE)
        shader.setInputShader("dayTex", textureShader(day))
        shader.setFloatUniform("dayDim", day.width.toFloat(), day.height.toFloat())
        // Every sampler a shader declares must have something bound to
        // it or the program will not link, so when the build shipped
        // no night mosaic the day one is bound in its place and
        // hasNight switches it off.
        val lights = night ?: day
        shader.setInputShader("nightTex", textureShader(lights))
        shader.setFloatUniform("nightDim", lights.width.toFloat(), lights.height.toFloat())
        shader.setFloatUniform("hasNight", if (night != null) 1f else 0f)
        return shader
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun point(
        shader: Shader,
        centreX: Float,
        centreY: Float,
        radius: Float,
        lat: Double,
        lon: Double,
        sunLat: Double,
        sunLon: Double
    ) {
        val rs = shader as? RuntimeShader ?: return
        rs.setFloatUniform("center", centreX, centreY)
        rs.setFloatUniform("radius", radius)
        rs.setFloatUniform("sinLat0", kotlin.math.sin(Math.toRadians(lat)).toFloat())
        rs.setFloatUniform("cosLat0", kotlin.math.cos(Math.toRadians(lat)).toFloat())
        rs.setFloatUniform("lon0", Math.toRadians(lon).toFloat())
        rs.setFloatUniform("sinSun", kotlin.math.sin(Math.toRadians(sunLat)).toFloat())
        rs.setFloatUniform("cosSun", kotlin.math.cos(Math.toRadians(sunLat)).toFloat())
        rs.setFloatUniform("sunLon", Math.toRadians(sunLon).toFloat())
    }

    /**
     * Longitude wraps and latitude clamps, which is the texture's job
     * rather than the shader's: REPEAT across the antimeridian means
     * the bilinear filter blends the two edges of the mosaic instead
     * of painting a bright seam down the Pacific, and CLAMP at the
     * poles means the top row is simply the top row.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun textureShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
            // Bilinear, for the same reason the CPU renderer does four
            // taps on a still frame: at this magnification nearest
            // neighbour is visibly blocky at the limb.
            setFilterMode(Shader.FilterMode.LINEAR)
        }

    /**
     * The shader itself.
     *
     * Coordinates arrive in the canvas's own pixel space, so the
     * centre and the radius of the disc are uniforms and zoom costs
     * nothing: a wider disc is the same picture drawn over more
     * pixels, which is the one useful property of an orthographic
     * projection and the reason this app has never re-rendered on
     * zoom.
     */
    private val SOURCE = """
        uniform float2 center;
        uniform float  radius;
        uniform float  sinLat0;
        uniform float  cosLat0;
        uniform float  lon0;
        uniform float  sinSun;
        uniform float  cosSun;
        uniform float  sunLon;
        uniform float2 dayDim;
        uniform float2 nightDim;
        uniform float  hasNight;
        uniform shader dayTex;
        uniform shader nightTex;

        const float PI = 3.1415926535897932;

        half4 main(float2 frag) {
            float2 p = (frag - center) / radius;
            float x = p.x;
            // Screen y grows downward; the projection's y grows north.
            float y = -p.y;
            float rho2 = x * x + y * y;
            // One pixel of feather at the limb, in the disc's own
            // units. The CPU renderer cut the circle off hard and the
            // edge of the planet was visibly stepped; this is the one
            // place the two renderers deliberately disagree.
            float feather = 2.0 / max(radius, 1.0);
            if (rho2 > 1.0 + feather) { return half4(0.0); }

            float cosC = sqrt(max(1.0 - rho2, 0.0));
            float sinLat = clamp(cosC * sinLat0 + y * cosLat0, -1.0, 1.0);
            float lat = asin(sinLat);
            float lon = lon0 + atan(x, cosC * cosLat0 - y * sinLat0);

            float u = lon / (2.0 * PI) + 0.5;
            float v = 0.5 - lat / PI;

            half4 base = dayTex.eval(float2(u * dayDim.x, v * dayDim.y));

            // Lambert on a sphere, from the real subsolar point.
            float cosLat = sqrt(max(1.0 - sinLat * sinLat, 0.0));
            float lit = sinLat * sinSun + cosLat * cosSun * cos(lon - sunLon);
            float dayMix = smoothstep(-0.09, 0.13, lit);
            float lambert = 0.28 + 0.72 * clamp(lit, 0.0, 1.0);

            half3 c = base.rgb * half(lambert * dayMix);

            float dark = 1.0 - dayMix;
            if (dark > 0.0) {
                // The night side is not black: the continents stay
                // faintly visible under the lights, the way they do
                // from orbit.
                c += base.rgb * half3(0.055, 0.055, 0.075) * half(dark);
                if (hasNight > 0.5) {
                    half4 lights =
                        nightTex.eval(float2(u * nightDim.x, v * nightDim.y));
                    // Added rather than mixed: city light is light, and
                    // a mix would dim the land it sits on.
                    c += lights.rgb * half3(0.95, 0.80, 0.55) * half(dark);
                }
            }

            // Limb darkening: a sphere is dimmer at the edge because
            // you are looking through it at a glancing angle.
            c *= half(1.0 - 0.38 * rho2 * rho2);

            float alpha = 1.0 - smoothstep(1.0 - feather, 1.0 + feather, rho2);
            // Premultiplied, which is what AGSL returns and expects.
            return half4(clamp(c, 0.0, 1.0) * half(alpha), half(alpha));
        }
    """.trimIndent()
}
