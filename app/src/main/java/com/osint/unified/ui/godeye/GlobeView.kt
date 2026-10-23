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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.osint.unified.ui.theme.Intel
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    onInspect: (GlobePoint) -> Unit,
    onPress: (Double, Double) -> Unit,
    onLongPress: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Read off the main thread.
    //
    // These are three bundled JSON files — Natural Earth coastlines and
    // borders, and the gazetteer — and they were opened, read whole and
    // deserialised inside `remember`, which is to say on the main
    // thread during the first composition of the default screen. The
    // globe simply does not draw them until they arrive, which is a
    // frame or two of bare sphere instead of a stalled launch.
    val vectors by produceState(EMPTY_VECTORS, context) {
        value = withContext(Dispatchers.IO) {
            Vectors(
                Coastlines.rings(context),
                Coastlines.borders(context),
                Places.all(context)
            )
        }
    }
    val rings = vectors.rings
    val borders = vectors.borders
    val places = vectors.places
    // One Paint, reused. Laying out a label is cheap; allocating the
    // thing that lays it out, three hundred times a frame, is not.
    val ink = remember { labelInk() }
    val halo = remember { labelHalo() }

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
    // The loops below run for as long as a flick or a flight lasts and
    // are not restarted while they do. Without this they would keep
    // computing from the camera as it was when they started, write
    // that back every frame, and pin the globe in place — which is
    // exactly what "the globe will not move" looks like.
    val live by rememberUpdatedState(camera)
    val emit by rememberUpdatedState(onCamera)
    // How fast the globe is coasting, and how fast the last drag was
    // going. The second is a plain holder rather than state on purpose:
    // it is written on every frame of a gesture and nothing should
    // recompose because of it.
    var spin by remember { mutableFloatStateOf(0f) }
    val thrown = remember { floatArrayOf(0f) }
    // Whether a finger is on the glass at all.
    var touching by remember { mutableStateOf(false) }

    // Where a double tap is taking the camera, if one is in flight.
    var flight by remember { mutableStateOf<GlobeCamera?>(null) }
    LaunchedEffect(flight) {
        val target = flight ?: return@LaunchedEffect
        val from = live
        var t = 0f
        while (t < 1f) {
            withFrameNanos { }
            t = (t + FLY_STEP).coerceAtMost(1f)
            // Ease out: a camera that arrives at full speed and stops
            // dead reads as a jump cut rather than a move.
            val e = 1f - (1f - t) * (1f - t)
            emit(
                GlobeCamera(
                    lat = from.lat + (target.lat - from.lat) * e,
                    // Wrapped. shortestTurn is right about the
                    // direction but the sum runs past 180 on a flight
                    // across the date line, and an unwrapped longitude
                    // reaches the map handoff, the stars and the area
                    // report before anything folds it back.
                    lon = wrapLon(from.lon + shortestTurn(from.lon, target.lon) * e),
                    zoom = from.zoom + (target.zoom - from.zoom) * e
                )
            )
        }
        flight = null
    }

    // A flick keeps turning and slows down, the way a globe does. It
    // stops at zero rather than drifting for ever: a sphere redrawing
    // ten thousand coastline points sixty times a second, all night, to
    // look alive, is a flat battery.
    //
    // It starts when the finger comes off, and not before. It used to
    // start on the first sideways pixel of the drag, because the drag
    // was what set the speed — so for the whole gesture the globe was
    // being turned twice, once by the thumb and once by the coast, and
    // it ran at double speed sideways. Worse, this loop writes a whole
    // camera: `live` is only refreshed at recomposition, and the frame
    // clock resumes before that, so every frame it also wrote back the
    // latitude and zoom from *before* the drag — which is why dragging
    // up and down and pinching did nothing at all while a flick was in
    // progress.
    LaunchedEffect(touching) {
        if (touching) {
            spin = 0f
            return@LaunchedEffect
        }
        var v = thrown[0]
        thrown[0] = 0f
        while (abs(v) > SPIN_FLOOR) {
            withFrameNanos { }
            val now = live
            spin = v
            emit(now.copy(lon = wrapLon(now.lon - v)))
            v *= SPIN_DECAY
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

    // Moving under a thumb, or still coasting from one.
    //
    // The two want exactly the same treatment — a smaller render, no
    // labels — and only the first was getting it. A flick runs for
    // well over a second after the finger leaves, and for all of that
    // the globe was being resampled at full size, two megapixels of
    // trigonometry per frame, with three hundred place names laid out
    // over the top. The most expensive moment in the app was the one
    // immediately after the gesture that made it cheap.
    val drifting = moving || spin != 0f

    // What the renderer is being asked for, quantised. Without the
    // rounding every frame of a drag is a different camera and the
    // renderer never finishes one before being asked for the next.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Zoom is deliberately not part of this.
    //
    // An orthographic view of a sphere is the same picture at every
    // scale — zooming changes how big the disc is drawn and nothing
    // about which half of the Earth is facing you. So the render is
    // keyed to a coarse bucket and then simply scaled to whatever the
    // live zoom asks for, which makes a pinch cost nothing at all and
    // removes a whole category of lag.
    //
    // Rounded up, in half-octaves. Up rather than to-nearest because a
    // bitmap scaled down stays sharp and one scaled up does not, so the
    // render is always at least as big as the disc it will be painted
    // into; half-octaves rather than whole ones because the alternative
    // is rendering four times the pixels needed at the worst point in
    // each step, and MAX_RENDER_PX is a real ceiling.
    val bucket = remember(zoom) {
        val z = zoom.toDouble().coerceIn(0.25, 8.0)
        val steps = kotlin.math.ceil(kotlin.math.log2(z) * 2.0)
        Math.pow(2.0, steps / 2.0).toFloat().coerceIn(0.5f, 8f)
    }
    val diameter = remember(viewport, bucket, drifting) {
        val full = (globeRadius(viewport.width, viewport.height, bucket) * 2f).roundToInt()
        if (drifting) (full / MOVING_DIVISOR).coerceAtLeast(1) else full
    }.coerceIn(0, MAX_RENDER_PX)
    val frame = remember(centreLat, centreLon, diameter, sun, drifting) {
        RenderRequest(
            lat = (centreLat / CAMERA_STEP).roundToInt() * CAMERA_STEP.toDouble(),
            lon = (centreLon / CAMERA_STEP).roundToInt() * CAMERA_STEP.toDouble(),
            d = diameter,
            sunLat = sun.first,
            sunLon = sun.second,
            fine = !drifting
        )
    }
    // One array, reused. At a thousand pixels across this is four
    // megabytes, and allocating that per frame is how a smooth thing
    // becomes a stuttering one.
    val scratch = remember { arrayOfNulls<IntArray>(1) }
    // One render at a time, because they all write the same array.
    //
    // produceState cancels the previous coroutine on a key change but
    // does not wait for it, and the resampler has no suspension point
    // in it, so cancellation does nothing until it has finished. The
    // next render would start immediately and fill the first corner of
    // the same buffer while the old one was still painting the rest of
    // it, and the bitmap came out of the two mixed together. Easy to
    // reach: settle on a full-size frame, then touch the screen —
    // `drifting` drops the size to a quarter and starts a small render
    // into the middle of a two-megapixel one.
    val painter = remember { Mutex() }
    val globe: Rendered? by produceState<Rendered?>(null, frame, textured) {
        if (!textured || frame.d <= 8) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            painter.withLock {
            val need = frame.d * frame.d
            var buffer = scratch[0]
            if (buffer == null || buffer.size < need) {
                buffer = IntArray(need)
                scratch[0] = buffer
            }
            EarthTexture.image(
                buffer, frame.d, frame.lat, frame.lon,
                frame.sunLat, frame.sunLon, frame.fine
            )?.let { Rendered(it, frame.lat, frame.lon) }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Measured here, not read inside the draw lambda: writing
            // state during a draw schedules the next frame, which draws,
            // which writes, and the globe spins the CPU instead of
            // itself.
            .onSizeChanged { viewport = it }
            // Nothing but watching. It reads the pointers on the
            // Initial pass and consumes none of them, so the gesture
            // detectors below still see every event — all this decides
            // is whether a finger is currently down, which is what
            // tells the coast when to start.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.any { it.pressed }
                        if (down != touching) touching = down
                    }
                }
            }
            .pointerInput(Unit) {
                // Every value read in here comes from `live`, not from
                // the parameter. These lambdas are created once and
                // then handle gestures for the life of the screen, so
                // a captured camera is the camera as it was when the
                // screen opened — which made a drag snap back to the
                // middle of the Atlantic on every frame.
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val radius = globeRadius(size.width, size.height, live.zoom)
                    // A drag turns the globe by the angle it subtends,
                    // so the point under the thumb stays under the thumb
                    // — dragging by a fixed number of degrees per pixel
                    // feels like a slider, not a sphere. The two axes
                    // differ: longitude lines converge, so the same
                    // sideways drag is more degrees the nearer a pole.
                    val perLat = GlobeGestures.perPixelLat(radius)
                    val perLon = GlobeGestures.perPixelLon(radius, live.lat)
                    // Recorded, not applied. The coast starts when the
                    // finger lifts; applying it here turned the globe
                    // twice over for every pixel of the drag.
                    thrown[0] = (pan.x * perLon).toFloat().coerceIn(-4f, 4f)
                    val zoomed = (live.zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val dragged = GlobeCamera(
                        lat = (live.lat + pan.y * perLat).toFloat().coerceIn(-89f, 89f),
                        lon = wrapLon((live.lon - pan.x * perLon).toFloat()),
                        zoom = zoomed
                    )
                    // And a pinch zooms about the fingers rather than
                    // about the middle of the screen, so the thing being
                    // pinched on does not slide away from under them.
                    val (anchoredLat, anchoredLon) = GlobeGestures.anchorPinch(
                        lat = dragged.lat,
                        lon = dragged.lon,
                        oldRadius = globeRadius(size.width, size.height, live.zoom),
                        newRadius = globeRadius(size.width, size.height, zoomed),
                        focusX = centroid.x,
                        focusY = centroid.y,
                        centreX = size.width / 2f,
                        centreY = size.height / 2f
                    )
                    emit(dragged.copy(lat = anchoredLat, lon = anchoredLon))
                    gestures++
                }
            }
            .pointerInput(points) {
                detectTapGestures(
                    // The gesture everybody tries first on a globe.
                    // Without it, closing in means pinching with two
                    // thumbs on a phone held in one hand.
                    onDoubleTap = { tap ->
                        val radius = globeRadius(size.width, size.height, live.zoom)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        Orthographic.unproject(
                            tap.x, tap.y, live.lat.toDouble(), live.lon.toDouble(),
                            radius, centre.x, centre.y
                        )?.let { (lat, lon) ->
                            spin = 0f
                            thrown[0] = 0f
                            flight = GlobeCamera(
                                lat = lat.toFloat().coerceIn(-89f, 89f),
                                lon = lon.toFloat(),
                                zoom = (live.zoom * DOUBLE_TAP_ZOOM)
                                    .coerceIn(MIN_ZOOM, MAX_ZOOM)
                            )
                        }
                    },
                    onTap = { tap ->
                        val radius = globeRadius(size.width, size.height, live.zoom)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        // Projected here rather than remembered from the
                        // last frame: writing the hit list during the
                        // draw would make every frame schedule another
                        // one, and projecting a few thousand points once
                        // per tap costs nothing anybody can feel.
                        val hit = nearest(
                            points, tap, centre, radius,
                            live.lat.toDouble(), live.lon.toDouble()
                        )
                        if (hit != null) {
                            onInspect(hit)
                        } else {
                            Orthographic.unproject(
                                tap.x, tap.y, live.lat.toDouble(), live.lon.toDouble(),
                                radius, centre.x, centre.y
                            )?.let { (lat, lon) -> onPress(lat, lon) }
                        }
                    },
                    onLongPress = { tap ->
                        val radius = globeRadius(size.width, size.height, live.zoom)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        Orthographic.unproject(
                            tap.x, tap.y, live.lat.toDouble(), live.lon.toDouble(),
                            radius, centre.x, centre.y
                        )?.let { (lat, lon) ->
                            // The flat map reports a zoom level with a
                            // long press so the area report can size
                            // itself; the globe's equivalent is how much
                            // of the Earth is on screen.
                            // live, not the captured zoom. This block is
                        // rebuilt only when the marker list changes
                        // identity, so a plain read here is whatever
                        // the zoom was at the last feed poll — and the
                        // report's radius is computed from it.
                        onLongPress(lat, lon, (2.0 + live.zoom).coerceAtMost(12.0))
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

        // Everything on the sphere is drawn at the camera the picture
        // under it was rendered at, not at the live one.
        //
        // The imagery is produced off the main thread and arrives a
        // frame or two late. The borders and the markers are drawn
        // here and now, so they used to track the thumb while the
        // photograph trailed behind — and a globe whose coastlines
        // slide across its own continents reads as broken, where the
        // same globe lagging as one piece just reads as heavy.
        val sphere = globe
        val shownLat = sphere?.lat ?: lat0
        val shownLon = sphere?.lon ?: lon0
        if (sphere != null) {
            // Scaled to the live radius rather than re-rendered for
            // it: the projection does not change with zoom, only the
            // size of the disc does.
            drawImage(
                image = sphere.image,
                dstOffset = IntOffset(
                    (cx - radius).roundToInt(), (cy - radius).roundToInt()
                ),
                dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt())
            )
            // No terminator line over the imagery: the shading already
            // is the terminator, and a stroke on top of a soft one
            // looks like a crack.
            drawGraticule(cx, cy, radius, shownLat, shownLon, faint = true)
            drawLines(borders, cx, cy, radius, shownLat, shownLon, BORDER_OVER_IMAGERY)
        } else {
            drawOcean(cx, cy, radius)
            drawGraticule(cx, cy, radius, lat0, lon0)
            drawLand(rings, cx, cy, radius, lat0, lon0)
            drawLines(borders, cx, cy, radius, lat0, lon0, BORDER_OVER_OUTLINE)
            drawTerminator(terminator, cx, cy, radius, lat0, lon0)
        }
        // Names only when the camera has settled. Text is the most
        // expensive thing on this canvas and the least useful while
        // the planet is moving under a thumb.
        if (!drifting && places.isNotEmpty()) {
            drawPlaces(
                places.take(Places.countFor(zoom)),
                cx, cy, radius, shownLat, shownLon, ink, halo
            )
        }
        drawPoints(points, cx, cy, radius, shownLat, shownLon, sun)
        // Labels on the markers too, once there are few enough that
        // labelling them is reading rather than overprinting.
        if (!drifting) {
            val named = points.filter { it.clickable && !it.title.isNullOrBlank() }
            if (named.size <= MAX_MARKER_LABELS) {
                drawMarkerLabels(named, cx, cy, radius, shownLat, shownLon, ink, halo)
            }
        }
        drawLimb(cx, cy, radius, textured)
    }
}

/**
 * What the renderer has been asked for.
 *
 * A value, so an unchanged camera compares equal and no work is done;
 * quantised, because otherwise every frame of a drag is a new camera
 * and the renderer never finishes one before being asked for the next.
 */
/**
 * A finished picture of the globe and the camera it was taken at.
 *
 * Kept together on purpose. The two were separate and the drawing
 * code used whichever camera was current, which is how the borders
 * came to slide across the continents they describe.
 */
/** The three bundled vector sets, loaded together off the main thread. */
private data class Vectors(
    val rings: List<DoubleArray>,
    val borders: List<DoubleArray>,
    val places: List<Places.Place>
)

private val EMPTY_VECTORS = Vectors(emptyList(), emptyList(), emptyList())

private data class Rendered(
    val image: ImageBitmap,
    val lat: Double,
    val lon: Double
)

private data class RenderRequest(
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
) {
    /**
     * What this point is, as opposed to where it is.
     *
     * Used to decide which points survive thinning, so that the same
     * aircraft keeps its place in the subset as it moves rather than
     * blinking in and out while the feed reorders itself.
     */
    val identity: String
        get() = kind + "\u0000" + (
            title?.takeIf { it.isNotBlank() }
                ?: "${(lat * 1000).toInt()}:${(lon * 1000).toInt()}"
            )
}

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

/**
 * The short way round.
 *
 * Flying from 170°E to 170°W is twenty degrees east, not three
 * hundred and forty degrees west, and interpolating the raw numbers
 * takes the long way across the entire Pacific.
 */
private fun shortestTurn(from: Float, to: Float): Float {
    var delta = to - from
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    return delta
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

private fun DrawScope.drawLimb(cx: Float, cy: Float, r: Float, textured: Boolean = false) {
    // Over a gradient the hard cyan edge is what made the disc read as
    // a planet. Over a photograph it reads as a sticker on one, so the
    // edge nearly disappears and the haze outside it does the work —
    // which is what an atmosphere actually looks like from orbit.
    drawCircle(
        color = Intel.Cyan.copy(alpha = if (textured) 0.16f else 0.55f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = if (textured) 1f else 1.5f)
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

/**
 * Open lines on the sphere: borders, which enclose nothing.
 *
 * Broken whenever a segment goes round the back, for the same reason
 * the coastlines are — otherwise a border in Asia is drawn straight
 * across the Pacific when the globe is turned to the Americas.
 */
private fun DrawScope.drawLines(
    lines: List<DoubleArray>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    colour: Color
) {
    lines.forEach { line ->
        var previous: Orthographic.Point? = null
        var i = 0
        while (i + 1 < line.size) {
            val p = Orthographic.project(line[i + 1], line[i], lat0, lon0, r, cx, cy)
            val last = previous
            if (last != null && last.visible && p.visible) {
                drawLine(colour, Offset(last.x, last.y), Offset(p.x, p.y), strokeWidth = 1f)
            }
            previous = p
            i += 2
        }
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
 *
 * Each marker gets a dark ring. Over a gradient a coloured dot was
 * always legible; over a photograph of the Earth a yellow dot on a
 * desert and a blue one on the sea are not, and the ring is what
 * separates the thing being reported from the ground it is on.
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
        // Dimmed less than before: the night side of a photograph is
        // genuinely dark, and the old fade took markers there from
        // "quieter" to "invisible".
        val colour = Color(point.color).let { if (night) it.copy(alpha = 0.78f) else it }
        val radius = (point.sizeDp.coerceIn(5, 16) / 2f) * density
        drawCircle(
            HALO,
            radius = radius + 1.2f * density,
            center = Offset(p.x, p.y)
        )
        drawCircle(colour, radius = radius, center = Offset(p.x, p.y))
    }
}

/**
 * Place names on the sphere.
 *
 * Drawn with the platform's own text, not Compose's: these are short
 * single lines and a hundred of them a frame, and the layout machinery
 * that makes paragraphs work is overhead paid for nothing here.
 *
 * Each name gets a dark outline. White text over a photograph of the
 * Earth is invisible over Greenland and over the Sahara, which is most
 * of where the names are.
 */
private fun DrawScope.drawPlaces(
    places: List<Places.Place>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    ink: android.graphics.Paint,
    halo: android.graphics.Paint
) {
    // Sized here rather than when the Paint was made: a text size in
    // raw pixels is a different size on every phone, and the only
    // place the density is known is inside the draw.
    sizeLabels(ink, halo, density)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        places.forEach { place ->
            val p = Orthographic.project(place.lat, place.lon, lat0, lon0, r, cx, cy)
            if (!p.visible) return@forEach
            // Nothing within a few pixels of the limb: a label there
            // is half off the edge of the planet and reads as a smear.
            val dx = p.x - cx
            val dy = p.y - cy
            if (dx * dx + dy * dy > (r * 0.94f) * (r * 0.94f)) return@forEach
            native.drawCircle(p.x, p.y, (if (place.capital) 2.2f else 1.6f) * density, halo)
            native.drawCircle(p.x, p.y, (if (place.capital) 1.4f else 1.0f) * density, ink)
            val x = p.x + 4f * density
            val y = p.y + 3.5f * density
            native.drawText(place.name, x, y, halo)
            native.drawText(place.name, x, y, ink)
        }
    }
}

/** The same, for the things the feeds found. */
private fun DrawScope.drawMarkerLabels(
    points: List<GlobePoint>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    ink: android.graphics.Paint,
    halo: android.graphics.Paint
) {
    sizeLabels(ink, halo, density)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        points.forEach { point ->
            val p = Orthographic.project(point.lat, point.lon, lat0, lon0, r, cx, cy)
            if (!p.visible) return@forEach
            val dx = p.x - cx
            val dy = p.y - cy
            if (dx * dx + dy * dy > (r * 0.94f) * (r * 0.94f)) return@forEach
            // The first segment only. A label is a glance; the rest of
            // it is in the inspector, which is one tap away.
            val text = point.title.orEmpty().substringBefore(" \u00b7 ").take(28)
            if (text.isBlank()) return@forEach
            val x = p.x + 6f * density
            val y = p.y - 5f * density
            native.drawText(text, x, y, halo)
            native.drawText(text, x, y, ink)
        }
    }
}

/** The one place the screen density is known is inside a draw. */
private fun sizeLabels(
    ink: android.graphics.Paint,
    halo: android.graphics.Paint,
    density: Float
) {
    val size = 10.5f * density
    if (ink.textSize != size) {
        ink.textSize = size
        halo.textSize = size
        halo.strokeWidth = 2.6f * density
    }
}

private fun labelInk() = android.graphics.Paint().apply {
    isAntiAlias = true
    color = android.graphics.Color.WHITE
    style = android.graphics.Paint.Style.FILL
    typeface = android.graphics.Typeface.create(
        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL
    )
}

private fun labelHalo() = android.graphics.Paint().apply {
    isAntiAlias = true
    color = android.graphics.Color.argb(205, 2, 6, 10)
    style = android.graphics.Paint.Style.STROKE
    typeface = android.graphics.Typeface.create(
        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL
    )
}

private val OCEAN = Color(0xFF0B1A2A)
private val OCEAN_LIT = Color(0xFF14304C)
private val OCEAN_DARK = Color(0xFF050C14)
private val LAND = Color(0xFF1C2E24)
private val COAST = Color(0xFF3E6B58)
private val TERMINATOR = Color(0x99FFC14D)

/** What separates a marker from the ground it is standing on. */
private val HALO = Color(0x9905080C)

private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 8f
private const val SPIN_DECAY = 0.94f

/** Below this a flick has stopped, and the globe stops redrawing. */
private const val SPIN_FLOOR = 0.02f
private const val TAP_SLOP_PX = 44f

/** How finely the camera is rounded before the renderer is asked
 *  again. Small, because this is now the size of the step the whole
 *  globe moves in while a thumb is down. */
private const val CAMERA_STEP = 0.12f

/** How much smaller the sphere is rendered while it is moving. A
 *  quarter is sixteen times fewer pixels, which is the difference
 *  between arriving next frame and arriving late. */
private const val MOVING_DIVISOR = 4

/** How long after the last touch before the sphere is drawn properly. */
private const val SETTLE_MS = 140L

/** The largest sphere worth rendering by hand, in pixels across. A
 *  tablet in landscape would otherwise ask for four million pixels. */
private const val MAX_RENDER_PX = 1400

private const val STAR_COUNT = 220

/** Above this, labelling every marker is overprinting rather than
 *  reading, and the inspector is one tap away. */
private const val MAX_MARKER_LABELS = 30

/** How much closer a double tap takes you. */
private const val DOUBLE_TAP_ZOOM = 2.2f

/** A quarter of a second at sixty frames. */
private const val FLY_STEP = 0.066f

/** Over imagery a border is a hint; the eye finds it without help and
 *  anything stronger competes with the ground it is describing. */
private val BORDER_OVER_IMAGERY = Color(0x66FFE9A8)

/** Over the drawn fallback there is nothing else to compete with. */
private val BORDER_OVER_OUTLINE = Color(0x99FFE9A8)
