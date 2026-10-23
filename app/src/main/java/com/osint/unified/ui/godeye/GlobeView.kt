package com.osint.unified.ui.godeye

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.osint.unified.ui.theme.Intel
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Earth as a sphere you can turn.
 *
 * The map underneath this app is a Mercator projection on a flat plane,
 * which is the right tool for looking at a street and the wrong one for
 * a thing called a God's Eye View. A globe shows where everything is in
 * relation to everything else, hides the half of the planet that is
 * behind it, and turns under a thumb — which is the point.
 *
 * Everything here is drawn rather than fetched: a bundled Blue Marble
 * sampled into the sphere a pixel at a time, city lights on the night
 * side, a graticule computed on the fly, the day/night line from the
 * clock, and the same live markers the flat map plots. It works with
 * the network off and it never asks anyone for a tile.
 *
 * The imagery is optional. If the build could not fetch it the globe
 * falls back to the coastline outlines it drew before, which is worse
 * but is not nothing.
 */
@Composable
fun GlobeView(
    points: List<GlobePoint>,
    camera: GlobeCamera,
    onCamera: (GlobeCamera) -> Unit,
    onSize: (Int, Int) -> Unit,
    onInspect: (GlobePoint) -> Unit,
    onPress: (Double, Double) -> Unit,
    onLongPress: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rings = remember { Coastlines.rings(context) }

    // Decoded once, off the main thread, and kept for the life of the
    // process: twenty megabytes of Earth is not something to re-read
    // every time somebody opens the globe.
    val textured by produceState(initialValue = false, context) {
        withContext(Dispatchers.IO) { EarthTexture.load(context) }
        value = EarthTexture.ready()
    }

    // Where the globe is looking, and how close. Held by the screen
    // rather than here, because the flat map has to be able to pick
    // the same camera up and carry on with it — the two are one view
    // at different distances, not two places.
    val centreLat = camera.lat
    val centreLon = camera.lon
    val zoom = camera.zoom
    var spin by remember { mutableFloatStateOf(0f) }

    // A flick keeps turning and slows down, the way a globe does. It
    // stops at zero rather than drifting for ever: a sphere redrawing
    // ten thousand coastline points sixty times a second, all night, to
    // look alive, is a flat battery.
    LaunchedEffect(spin != 0f) {
        while (abs(spin) > SPIN_FLOOR) {
            withFrameNanos { }
            onCamera(camera.copy(lon = wrapLon(camera.lon - spin)))
            spin *= SPIN_DECAY
        }
        spin = 0f
    }

    // The sun moves a degree every four minutes, so this is recomputed
    // on that scale rather than per frame.
    var sun by remember { mutableStateOf(Orthographic.subsolar(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            sun = Orthographic.subsolar(System.currentTimeMillis())
            kotlinx.coroutines.delay(4 * 60_000L)
        }
    }
    val terminator = remember(sun) { Orthographic.terminator(sun.first, sun.second) }

    // True while a thumb is on the glass. The sphere is rendered at a
    // third of its size during a drag and scaled up: a hundred
    // thousand pixels is a frame's worth of work and a million is not,
    // and nobody has ever noticed the difference in something that is
    // moving.
    var moving by remember { mutableStateOf(false) }
    var gestures by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestures) {
        if (gestures == 0) return@LaunchedEffect
        moving = true
        delay(SETTLE_MS)
        moving = false
    }

    // What the renderer is being asked for, quantised. Without the
    // rounding every frame of a drag is a different camera and the
    // renderer never finishes one before being asked for the next.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val diameter = remember(viewport, zoom, moving) {
        val full = (globeRadius(viewport.width, viewport.height, zoom) * 2f).roundToInt()
        if (moving) (full / 3).coerceAtLeast(1) else full
    }.coerceIn(0, MAX_RENDER_PX)
    val camera = remember(centreLat, centreLon, diameter, sun, moving) {
        Camera(
            lat = (centreLat / CAMERA_STEP).roundToInt() * CAMERA_STEP.toDouble(),
            lon = (centreLon / CAMERA_STEP).roundToInt() * CAMERA_STEP.toDouble(),
            d = diameter,
            sunLat = sun.first,
            sunLon = sun.second,
            fine = !moving
        )
    }
    // One array, reused. At a thousand pixels across this is four
    // megabytes, and allocating that per frame is how a smooth thing
    // becomes a stuttering one.
    val scratch = remember { arrayOfNulls<IntArray>(1) }
    val globe: ImageBitmap? by produceState<ImageBitmap?>(null, camera, textured) {
        if (!textured || camera.d <= 8) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val need = camera.d * camera.d
            var buffer = scratch[0]
            if (buffer == null || buffer.size < need) {
                buffer = IntArray(need)
                scratch[0] = buffer
            }
            EarthTexture.image(
                buffer, camera.d, camera.lat, camera.lon,
                camera.sunLat, camera.sunLon, camera.fine
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Measured here, not read inside the draw lambda: writing
            // state during a draw schedules the next frame, which draws,
            // which writes, and the globe spins the CPU instead of
            // itself.
            .onSizeChanged {
                viewport = it
                onSize(it.width, it.height)
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val radius = globeRadius(size.width, size.height, zoom)
                    // A drag turns the globe by the angle it subtends,
                    // so the point under the thumb stays under the thumb
                    // — dragging by a fixed number of degrees per pixel
                    // feels like a slider, not a sphere.
                    val perPixel = (90f / radius).coerceAtMost(1f)
                    spin = (pan.x * perPixel).coerceIn(-4f, 4f)
                    onCamera(
                        GlobeCamera(
                            lat = (camera.lat + pan.y * perPixel).coerceIn(-89f, 89f),
                            lon = wrapLon(camera.lon - pan.x * perPixel),
                            zoom = (camera.zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        )
                    )
                    gestures++
                }
            }
            .pointerInput(points) {
                detectTapGestures(
                    onTap = { tap ->
                        val radius = globeRadius(size.width, size.height, zoom)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        // Projected here rather than remembered from the
                        // last frame: writing the hit list during the
                        // draw would make every frame schedule another
                        // one, and projecting a few thousand points once
                        // per tap costs nothing anybody can feel.
                        val hit = nearest(
                            points, tap, centre, radius,
                            centreLat.toDouble(), centreLon.toDouble()
                        )
                        if (hit != null) {
                            onInspect(hit)
                        } else {
                            Orthographic.unproject(
                                tap.x, tap.y, centreLat.toDouble(), centreLon.toDouble(),
                                radius, centre.x, centre.y
                            )?.let { (lat, lon) -> onPress(lat, lon) }
                        }
                    },
                    onLongPress = { tap ->
                        val radius = globeRadius(size.width, size.height, zoom)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        Orthographic.unproject(
                            tap.x, tap.y, centreLat.toDouble(), centreLon.toDouble(),
                            radius, centre.x, centre.y
                        )?.let { (lat, lon) ->
                            // The flat map reports a zoom level with a
                            // long press so the area report can size
                            // itself; the globe's equivalent is how much
                            // of the Earth is on screen.
                            onLongPress(lat, lon, (2.0 + zoom).coerceAtMost(12.0))
                        }
                    }
                )
            }
    ) {
        val radius = globeRadius(size.width.toInt(), size.height.toInt(), zoom)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val lat0 = centreLat.toDouble()
        val lon0 = centreLon.toDouble()

        // Behind everything, and turning with the globe rather than
        // with the screen, so the sphere reads as an object in a place
        // instead of a disc on a background.
        drawStars(size.width, size.height, lon0, centreLat.toDouble())

        val sphere = globe
        if (sphere != null) {
            drawImage(
                image = sphere,
                dstOffset = IntOffset(
                    (cx - radius).roundToInt(), (cy - radius).roundToInt()
                ),
                dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt())
            )
            // No terminator line over the imagery: the shading already
            // is the terminator, and a stroke on top of a soft one
            // looks like a crack.
            drawGraticule(cx, cy, radius, lat0, lon0, faint = true)
        } else {
            drawOcean(cx, cy, radius)
            drawGraticule(cx, cy, radius, lat0, lon0)
            drawLand(rings, cx, cy, radius, lat0, lon0)
            drawTerminator(terminator, cx, cy, radius, lat0, lon0)
        }
        drawPoints(points, cx, cy, radius, lat0, lon0, sun)
        drawLimb(cx, cy, radius)
    }
}

/**
 * What the renderer has been asked for.
 *
 * A value, so an unchanged camera compares equal and no work is done;
 * quantised, because otherwise every frame of a drag is a new camera
 * and the renderer never finishes one before being asked for the next.
 */
private data class Camera(
    val lat: Double,
    val lon: Double,
    val d: Int,
    val sunLat: Double,
    val sunLon: Double,
    val fine: Boolean
)

/**
 * Where the globe is looking and how close.
 *
 * A value held by the screen, so the flat map can be handed the same
 * place rather than starting over in the middle of the Atlantic.
 */
data class GlobeCamera(val lat: Float, val lon: Float, val zoom: Float)

/** One thing to put on the globe. */
data class GlobePoint(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val color: Int,
    val sizeDp: Int,
    val title: String?,
    val clickable: Boolean,
    val url: String? = null
)

private fun globeRadius(width: Int, height: Int, zoom: Float): Float =
    min(width, height) / 2f * 0.92f * zoom

/**
 * The clickable marker nearest a tap, if one is close enough.
 *
 * Nearest in screen space rather than in degrees, because that is what
 * the thumb aimed at: two aircraft a hundred miles apart are the same
 * pixel when the whole Earth is on screen.
 */
private fun nearest(
    points: List<GlobePoint>,
    tap: Offset,
    centre: Offset,
    radius: Float,
    lat0: Double,
    lon0: Double
): GlobePoint? {
    var best: GlobePoint? = null
    var bestDistance = TAP_SLOP_PX * TAP_SLOP_PX
    points.forEach { point ->
        if (!point.clickable) return@forEach
        val p = Orthographic.project(
            point.lat, point.lon, lat0, lon0, radius, centre.x, centre.y
        )
        if (!p.visible) return@forEach
        val dx = p.x - tap.x
        val dy = p.y - tap.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = point
        }
    }
    return best
}

private fun wrapLon(lon: Float): Float {
    var v = lon
    while (v > 180f) v -= 360f
    while (v < -180f) v += 360f
    return v
}

private fun DrawScope.drawOcean(cx: Float, cy: Float, r: Float) {
    // Lit from the upper left, which is what makes a flat disc read as a
    // sphere. Without the gradient this is a circle.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(OCEAN_LIT, OCEAN, OCEAN_DARK),
            center = Offset(cx - r * 0.35f, cy - r * 0.35f),
            radius = r * 1.6f
        ),
        radius = r,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawLimb(cx: Float, cy: Float, r: Float) {
    drawCircle(
        color = Intel.Cyan.copy(alpha = 0.55f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f)
    )
    // A thin haze outside the edge: the atmosphere, and the thing that
    // stops the globe looking like a sticker.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Intel.Cyan.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(cx, cy),
            radius = r * 1.12f
        ),
        radius = r * 1.12f,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawGraticule(
    cx: Float, cy: Float, r: Float, lat0: Double, lon0: Double, faint: Boolean = false
) {
    // Over photography the grid is orientation, not decoration, and at
    // full strength it draws more attention than the continents under
    // it.
    val colour = Intel.Hairline.copy(alpha = if (faint) 0.22f else 0.7f)
    // Parallels every thirty degrees, meridians every thirty.
    for (lat in -60..60 step 30) {
        strokeArcOfPoints(colour, cx, cy, r, lat0, lon0) { step ->
            lat.toDouble() to (-180.0 + step * 2.0)
        }
    }
    for (lon in -180..150 step 30) {
        strokeArcOfPoints(colour, cx, cy, r, lat0, lon0) { step ->
            (-90.0 + step) to lon.toDouble()
        }
    }
    // The equator, slightly brighter, because it is the one line people
    // orient by.
    strokeArcOfPoints(
        Intel.Hairline.copy(alpha = if (faint) 0.34f else 1f), cx, cy, r, lat0, lon0
    ) { step ->
        0.0 to (-180.0 + step * 2.0)
    }
}

/**
 * A sky to hang the Earth in.
 *
 * Deterministic rather than random: the same star is in the same place
 * on every frame and after every rotation, because a sky that
 * reshuffles itself sixty times a second is a snowstorm. They drift
 * with the camera, which is what makes the globe read as an object
 * somewhere rather than a disc on a background.
 */
private fun DrawScope.drawStars(width: Float, height: Float, lon0: Double, lat0: Double) {
    val shiftX = (-lon0 * 1.6).toFloat()
    val shiftY = (lat0 * 1.1).toFloat()
    for (i in 0 until STAR_COUNT) {
        // One hash per star, mixed hard enough that the three values
        // taken out of it are not visibly correlated — otherwise the
        // bright stars all end up in one corner.
        var h = i * -0x61c88647
        h = h xor (h ushr 15); h *= -0x7ee3623b
        h = h xor (h ushr 13)
        val ux = ((h ushr 8) and 0xFFF) / 4095f
        val uy = ((h ushr 20) and 0xFFF) / 4095f
        val mag = (h and 0xFF) / 255f
        var x = (ux * (width + 240f) + shiftX) % (width + 240f)
        if (x < 0) x += width + 240f
        var y = (uy * (height + 240f) + shiftY) % (height + 240f)
        if (y < 0) y += height + 240f
        drawCircle(
            color = Color.White.copy(alpha = 0.10f + mag * 0.45f),
            radius = 0.6f + mag * 1.1f,
            center = Offset(x - 120f, y - 120f)
        )
    }
}

/** Walks 181 samples of a line, drawing only the segments on this side. */
private inline fun DrawScope.strokeArcOfPoints(
    colour: Color,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    place: (Int) -> Pair<Double, Double>
) {
    var previous: Orthographic.Point? = null
    for (step in 0..180) {
        val (lat, lon) = place(step)
        val p = Orthographic.project(lat, lon, lat0, lon0, r, cx, cy)
        val last = previous
        if (last != null && last.visible && p.visible) {
            drawLine(colour, Offset(last.x, last.y), Offset(p.x, p.y), strokeWidth = 1f)
        }
        previous = p
    }
}

private fun DrawScope.drawLand(
    rings: List<DoubleArray>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double
) {
    rings.forEach { ring ->
        // A ring is filled only while it stays on the near side. The
        // moment it goes round the back the path is closed and a new one
        // begins, which is what keeps Asia from being drawn across the
        // Pacific when the globe is turned to the Americas.
        val path = Path()
        var open = false
        var i = 0
        while (i + 1 < ring.size) {
            val p = Orthographic.project(ring[i + 1], ring[i], lat0, lon0, r, cx, cy)
            if (p.visible) {
                if (open) path.lineTo(p.x, p.y) else { path.moveTo(p.x, p.y); open = true }
            } else if (open) {
                path.close()
                drawPath(path, LAND)
                drawPath(path, COAST, style = Stroke(width = 1f))
                path.reset()
                open = false
            }
            i += 2
        }
        if (open) {
            path.close()
            drawPath(path, LAND)
            drawPath(path, COAST, style = Stroke(width = 1f))
        }
    }
}

private fun DrawScope.drawTerminator(
    line: List<Pair<Double, Double>>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double
) {
    var previous: Orthographic.Point? = null
    line.forEach { (lat, lon) ->
        val p = Orthographic.project(lat, lon, lat0, lon0, r, cx, cy)
        val last = previous
        if (last != null && last.visible && p.visible) {
            drawLine(
                TERMINATOR,
                Offset(last.x, last.y),
                Offset(p.x, p.y),
                strokeWidth = 2f
            )
        }
        previous = p
    }
}

/**
 * The live markers, and where each one landed so a tap can find it.
 *
 * Things on the night side are drawn dimmer. That is not decoration: a
 * camera in darkness, an aircraft over an unlit ocean and a fire at
 * night are different propositions from the same things in daylight,
 * and the globe is the only view that can say so at a glance.
 */
private fun DrawScope.drawPoints(
    points: List<GlobePoint>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    sun: Pair<Double, Double>
) {
    points.forEach { point ->
        val p = Orthographic.project(point.lat, point.lon, lat0, lon0, r, cx, cy)
        if (!p.visible) return@forEach
        val night = Orthographic.isNight(point.lat, point.lon, sun.first, sun.second)
        val colour = Color(point.color).let { if (night) it.copy(alpha = 0.55f) else it }
        val radius = (point.sizeDp.coerceIn(5, 16) / 2f) * density
        drawCircle(colour, radius = radius, center = Offset(p.x, p.y))
    }
}

private val OCEAN = Color(0xFF0B1A2A)
private val OCEAN_LIT = Color(0xFF14304C)
private val OCEAN_DARK = Color(0xFF050C14)
private val LAND = Color(0xFF1C2E24)
private val COAST = Color(0xFF3E6B58)
private val TERMINATOR = Color(0x99FFC14D)

private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 8f
private const val SPIN_DECAY = 0.94f

/** Below this a flick has stopped, and the globe stops redrawing. */
private const val SPIN_FLOOR = 0.02f
private const val TAP_SLOP_PX = 44f

/** How finely the camera is rounded before the renderer is asked again. */
private const val CAMERA_STEP = 0.35f

/** How long after the last touch before the sphere is drawn properly. */
private const val SETTLE_MS = 140L

/** The largest sphere worth rendering by hand, in pixels across. A
 *  tablet in landscape would otherwise ask for four million pixels. */
private const val MAX_RENDER_PX = 1400

private const val STAR_COUNT = 220
