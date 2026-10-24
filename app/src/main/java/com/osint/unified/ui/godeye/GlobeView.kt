package com.osint.unified.ui.godeye

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.lifecycle.repeatOnLifecycle

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
    val credit = remember { creditInk() }

    // Decoded once, off the main thread, and kept for the life of the
    // process: twenty megabytes of Earth is not something to re-read
    // every time somebody opens the globe.
    val textured by produceState(initialValue = false, context) {
        withContext(Dispatchers.IO) { EarthTexture.load(context) }
        value = EarthTexture.ready()
    }

    // The sphere on the GPU, when the phone has one this app can
    // address.
    //
    // This is the whole of the globe's performance story. The CPU
    // renderer below produces one frame of planet in tens of
    // milliseconds, which is why it had to run off the main thread,
    // only at a quarter size while a thumb was down, and only once the
    // camera had been still for a seventh of a second — the stutter,
    // the pop when it settled and the imagery trailing behind its own
    // coastlines were all consequences of that one number. A fragment
    // shader does the same arithmetic on every pixel at once and
    // finishes inside a frame, so none of those compromises are needed
    // and none of them are made on this path.
    //
    // Null below Android 13, or on a build that shipped no imagery.
    // Both fall through to exactly what was there before.
    // The sunlight over the tiles: same geometry, same sun, darkening and
    // city lights only. Null below Android 13, where the tiles go unlit.
    val shade = remember(textured) {
        if (!textured) null
        else GlobeShader.bindShade(EarthTexture.nightMap())?.let { GpuSphere(it, ShaderBrush(it)) }
    }
    val gpuSphere = remember(textured) {
        if (!textured) return@remember null
        val day = EarthTexture.dayMap() ?: return@remember null
        GlobeShader.bind(day, EarthTexture.nightMap())
            ?.let { GpuSphere(it, ShaderBrush(it)) }
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
    // The marker list, read at tap time rather than captured. See the
    // note on the tap detector below.
    val hittable = rememberUpdatedState(points)
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
    val sunOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // And only while the screen is there to show it. The composition
    // survives the Activity stopping, so this went on waking every
    // four minutes to move a terminator nobody could see.
    LaunchedEffect(sunOwner) {
        sunOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                sun = Orthographic.subsolar(System.currentTimeMillis())
                kotlinx.coroutines.delay(4 * 60_000L)
            }
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
    // Asked for at exactly the camera, not at a rounded one.
    //
    // The rounding was meant to stop the renderer being asked for a
    // new picture it would never finish. What it actually did, once
    // everything on the sphere was drawn at the camera the picture was
    // taken at, was stop the globe moving at all between steps: drag
    // slowly enough and the camera changes by less than the step for
    // two or three frames together, nothing is re-rendered, nothing
    // moves, and then the whole thing jumps. Smooth motion made of
    // discrete jumps is worse than either.
    //
    // There is no need for it. The renderer already draws at a
    // quarter size while anything is moving, and the lock below means
    // at most one render is ever in flight — so asking every frame
    // costs nothing more than asking every third one did, and every
    // picture that arrives is of where the globe is now.
    val frame = remember(gpuSphere, centreLat, centreLon, diameter, sun, drifting) {
        // Held constant while the GPU is drawing the planet, so the
        // off-thread renderer's produceState is not relaunched sixty
        // times a second to immediately return nothing.
        if (gpuSphere != null) IDLE_REQUEST else RenderRequest(
            lat = centreLat.toDouble(),
            lon = centreLon.toDouble(),
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
    // Buffers the draw path reuses between frames. See GlobeScratch.
    val drawScratch = remember { GlobeScratch() }
    // Built only if the CPU path is the one being used.
    //
    // `textured` is true as soon as the shader path has its Bitmaps,
    // and on Android 13 and up that is all `load` builds — so a
    // failed shader bind left `gpuSphere` null and `textured` true,
    // and this relaunched on every camera move to allocate a
    // multi-megabyte buffer and get null back from `image()`. The
    // picture was right; the way it was reached was not.
    val cpuTexture by produceState(initialValue = false, gpuSphere, textured, context) {
        value = if (gpuSphere != null || !textured) false
        else withContext(Dispatchers.IO) {
            EarthTexture.loadCpuFallback(context)
            EarthTexture.hasCpuTexture()
        }
    }
    val globe: Rendered? by produceState<Rendered?>(null, frame, cpuTexture, gpuSphere) {
        if (gpuSphere != null || !cpuTexture || frame.d <= 8) {
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

    // Imagery as tiles, at every zoom.
    //
    // This used to start only past a large country across, and above
    // that the globe was one 4096-pixel photograph of the planet — at its
    // limit with the whole Earth in view and two to four times magnified
    // as soon as anyone zoomed. Reported as "very blurry at all levels of
    // zoom", which it was. Now the zoom-0-to-5 tiles are bundled (NASA
    // Blue Marble, no data, no wait) and live satellite tiles take over
    // beyond, so every zoom is drawn at the detail its pixels can show.
    val tiles = remember(context) { TileStore(context) }
    DisposableEffect(tiles) { onDispose { tiles.clear() } }
    val tileVersion by tiles.version.collectAsStateWithLifecycle()

    val tileSpan = if (viewport.width > 0) {
        GlobeHandoff.spanDegrees(viewport.width, viewport.height, zoom)
    } else {
        360.0
    }
    val tileZoom = if (viewport.width > 0) {
        Tiles.zoomFor(tileSpan, viewport.width, centreLat.toDouble())
    } else {
        0
    }
    // What is on screen, found by looking rather than estimated from a
    // rectangle — which missed the corners near the poles and drew them
    // black.
    val tileRefs = remember(viewport, centreLat, centreLon, zoom, tileZoom) {
        if (tileZoom == 0) emptyList()
        else Tiles.visible(
            tileZoom, viewport.width, viewport.height,
            viewport.width / 2f, viewport.height / 2f,
            globeRadius(viewport.width, viewport.height, zoom),
            centreLat.toDouble(), centreLon.toDouble()
        )
    }
    val drawnZoom = tileRefs.firstOrNull()?.z ?: 0
    // Coarse to fine: the bundled level always, the level above the one
    // wanted when it differs, then the one wanted. A tile that has not
    // arrived shows its parent instead of the stretched photograph or
    // black — which is what a change of zoom used to flash to.
    val baseLevel = minOf(drawnZoom, Tiles.BUNDLED_MAX_ZOOM)
    val baseRefs = remember(tileRefs) {
        if (tileRefs.isEmpty() || drawnZoom == baseLevel) emptyList()
        else Tiles.ancestors(tileRefs, baseLevel)
    }
    val parentRefs = remember(tileRefs) {
        if (drawnZoom - 1 > baseLevel) Tiles.ancestors(tileRefs, drawnZoom - 1) else emptyList()
    }
    // Asked for from here, never from the draw. Bundled tiles whenever —
    // they cost nothing — and live ones only once the globe stops moving,
    // so ground under a thumb for a sixtieth of a second is not paid for.
    LaunchedEffect(baseRefs) { tiles.request(baseRefs) }
    LaunchedEffect(tileRefs, drifting) { tiles.request(tileRefs, network = !drifting) }

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
            // Keyed on nothing, with the marker list read live.
            //
            // It used to be keyed on `points` — up to two and a half
            // thousand data classes compared field by field on every
            // recomposition, which is every frame of a drag, and when
            // the comparison did come out different the block
            // restarted and cancelled whatever gesture was in flight.
            // A long press was being dropped every time the aircraft
            // feed polled.
            .pointerInput(Unit) {
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
                            hittable.value, tap, centre, radius,
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
                            // Converted, not invented.
                        //
                        // This used to be `2.0 + live.zoom`, an
                        // additive fudge on a multiplicative zoom,
                        // and the report's radius comes straight off
                        // it: at the top of the globe's usable range
                        // the long press asked for 150 km where the
                        // same view's real equivalent was 400, and in
                        // landscape it asked for 60 km over a view
                        // spanning three thousand. The screen already
                        // computes the honest conversion for its own
                        // WHAT'S HERE button; this is the same call.
                        onLongPress(
                            lat, lon,
                            GlobeHandoff.tileZoom(
                                GlobeHandoff.spanDegrees(
                                    size.width, size.height, live.zoom
                                ),
                                size.width,
                                lat
                            )
                        )
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
        drawStars(size.width, size.height)

        // Everything on the sphere is drawn at the camera the picture
        // under it was rendered at, not at the live one.
        //
        // The imagery is produced off the main thread and arrives a
        // frame or two late. The borders and the markers are drawn
        // here and now, so they used to track the thumb while the
        // photograph trailed behind — and a globe whose coastlines
        // slide across its own continents reads as broken, where the
        // same globe lagging as one piece just reads as heavy.
        // The tiles for this frame, and whether any of them are here
        // yet.
        //
        // Worked out before the sphere is drawn because it decides
        // whether the sphere is drawn at all. Once the imagery is
        // fully opaque the mosaic beneath it is invisible, and at
        // these zooms it would be a bitmap blown up to forty million
        // pixels across — the cost of painting something nobody can
        // see. The vectors go with it: a coastline is the tiles'
        // own job from here, and a hand-drawn one over a photograph
        // of the same coast is just a second, worse coastline.
        //
        // Unless nothing has arrived. Opening the screen already
        // zoomed in would otherwise be a black rectangle, so the
        // mosaic keeps the seat warm until the first tile lands.
        //
        // `tileVersion` is read here so the draw depends on it:
        // without naming it, a tile arriving would not repaint the
        // canvas and the imagery would appear only on the next
        // gesture.
        @Suppress("UNUSED_EXPRESSION") tileVersion
        val baseComplete = (if (baseRefs.isEmpty()) tileRefs else baseRefs)
            .let { refs -> refs.isNotEmpty() && refs.all { tiles.peek(it) != null } }
        // The photograph underneath only when something could show
        // through: the sphere smaller than the screen (space, the limb,
        // the poles past the tiles' 85°), or the bundled level not yet
        // read. Otherwise it would be a bitmap blown up to forty million
        // pixels across, painted for nobody.
        val sphereFills = radius * radius >= (size.width * size.width + size.height * size.height) / 4f
        val tilesCoverIt = sphereFills && baseComplete
        val sphere = globe
        // On the GPU path there is nothing to lag behind: the sphere is
        // drawn in this frame, at this camera, so the coastlines and
        // the markers are too.
        val shownLat = if (gpuSphere != null) lat0 else sphere?.lat ?: lat0
        val shownLon = if (gpuSphere != null) lon0 else sphere?.lon ?: lon0
        if (tilesCoverIt) {
            // The void the tiles sit on, for any gap at the edges.
            drawRect(color = Color(0xFF0B0E11), size = size)
        } else if (gpuSphere != null) {
            GlobeShader.aim(
                gpuSphere.shader, cx, cy, radius, lat0, lon0, sun.first, sun.second
            )
            // Over the whole canvas rather than the disc's box: the
            // shader returns nothing outside the circle, and a
            // full-size rect keeps the fragment coordinates in the
            // canvas's own space, which is what the uniforms are in.
            drawRect(brush = gpuSphere.brush, size = size)
        } else if (sphere != null) {
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
        } else {
            drawOcean(cx, cy, radius)
            drawGraticule(drawScratch, cx, cy, radius, lat0, lon0)
            drawLand(rings, cx, cy, radius, lat0, lon0)
            drawLines(
                borders, cx, cy, radius, lat0, lon0,
                BORDER_OVER_OUTLINE, stride = if (drifting) 2 else 1
            )
            drawTerminator(terminator, cx, cy, radius, lat0, lon0)
        }
        drawTiles(tiles, baseRefs, cx, cy, radius, lat0, lon0)
        drawTiles(tiles, parentRefs, cx, cy, radius, lat0, lon0)
        drawTiles(tiles, tileRefs, cx, cy, radius, lat0, lon0)
        // The sun, over the tiles. Full strength with the planet in view,
        // gone by street level, where a low sun dimming a photograph
        // reads as a bad photograph.
        if (shade != null) {
            GlobeShader.aim(shade.shader, cx, cy, radius, lat0, lon0, sun.first, sun.second)
            GlobeShader.strength(
                shade.shader,
                (((tileSpan - SHADE_GONE_SPAN) / (SHADE_FULL_SPAN - SHADE_GONE_SPAN))
                    .coerceIn(0.0, 1.0)).toFloat()
            )
            drawRect(brush = shade.brush, size = size)
        }
        // Borders over the imagery while there is a country's worth or
        // more in view; closer than that the photograph is the map.
        if (tileSpan > BORDERS_BELOW_SPAN) {
            if (tileSpan > GRATICULE_BELOW_SPAN) {
                drawGraticule(drawScratch, cx, cy, radius, lat0, lon0, faint = true)
            }
            drawLines(
                borders, cx, cy, radius, lat0, lon0,
                BORDER_OVER_IMAGERY, stride = if (drifting) 2 else 1
            )
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
        drawMarkers(drawScratch, points, cx, cy, radius, shownLat, shownLon, sun, halos = !drifting)
        // Labels on the markers too, once there are few enough that
        // labelling them is reading rather than overprinting.
        if (!drifting) {
            // Counted among the ones on screen, not among everything
            // the sphere was handed.
            //
            // The cap was tested against the whole thinned list, which
            // with any real set of layers is in the hundreds — so the
            // branch never ran and markers were never named at any
            // zoom, which is the one time you would want them. The
            // test is the same one the label draw already applies:
            // on the near hemisphere and inside the disc.
            val named = ArrayList<GlobePoint>(MAX_MARKER_LABELS + 1)
            val edge = (radius * 0.94f) * (radius * 0.94f)
            for (point in points) {
                if (!point.clickable || point.title.isNullOrBlank()) continue
                val p = Orthographic.project(point.lat, point.lon, shownLat, shownLon, radius, cx, cy)
                if (!p.visible) continue
                val dx = p.x - cx
                val dy = p.y - cy
                if (dx * dx + dy * dy > edge) continue
                named.add(point)
                if (named.size > MAX_MARKER_LABELS) break
            }
            if (named.size <= MAX_MARKER_LABELS) {
                drawMarkerLabels(named, cx, cy, radius, shownLat, shownLon, ink, halo)
            }
        }
        // No limb once the imagery covers the screen: the horizon
        // of a sphere you are standing on is not on the screen, and
        // a ring drawn at forty million pixels is nowhere near it.
        if (!tilesCoverIt) drawLimb(cx, cy, radius, textured)
        // Esri's terms require the credit wherever the imagery is
        // shown. It used to be an osmdroid overlay on the other
        // view; there is no other view, so it is drawn here, and
        // only while there is imagery to credit.
        if (tileRefs.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    if (drawnZoom > Tiles.BUNDLED_MAX_ZOOM) TileStore.ESRI_ATTRIBUTION
                    else TileStore.NASA_ATTRIBUTION,
                    10f, size.height - 10f, credit
                )
            }
        }
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

/**
 * The GPU sphere: the shader and the brush that paints with it.
 *
 * Held together because the brush is a thin wrapper that must be made
 * once — rebuilding it per frame would defeat the point — while the
 * shader's uniforms are rewritten immediately before every draw.
 */
private class GpuSphere(
    val shader: android.graphics.Shader,
    val brush: ShaderBrush
)

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

/** A request for nothing: the camera the GPU path parks on. */
private val IDLE_REQUEST = RenderRequest(0.0, 0.0, 0, 0.0, 0.0, false)

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
            // Without the age on the end, or the point becomes a
            // different point at every minute boundary and the
            // thinning it feeds reshuffles under a still globe.
            title?.let(::withoutAge)?.takeIf { it.isNotBlank() }
                ?: "${floor(lat * 1000).toInt()}:${floor(lon * 1000).toInt()}"
            )
}

/**
 * Each tile bent onto the sphere.
 *
 * At street level a tile is flat for every practical purpose, but with
 * the whole planet in view one tile spans tens of degrees, and drawing
 * it as one quadrilateral — what this did when tiles only began at a
 * country across — would paste a flat square onto a ball. So each tile
 * is a mesh: a grid of points along its edges and through its middle,
 * every one projected, and the bitmap stretched over the grid. Finer
 * where the tiles are large, a single quad where they are small.
 *
 * A grid point behind the horizon is pulled onto the rim along its own
 * direction. Orthographic projection folds the far side back over the
 * near one, so drawn as projected a tile straddling the limb would be
 * smeared across the disc; on the rim, it ends where the planet does.
 */
private fun DrawScope.drawTiles(
    store: TileStore,
    refs: List<Tiles.Ref>,
    cx: Float,
    cy: Float,
    radius: Float,
    lat0: Double,
    lon0: Double
) {
    if (refs.isEmpty()) return
    val paint = TILE_PAINT
    val corner = FloatArray(2)
    val verts = FloatArray((MAX_MESH + 1) * (MAX_MESH + 1) * 2)
    val sinLat0 = kotlin.math.sin(Math.toRadians(lat0))
    val cosLat0 = kotlin.math.cos(Math.toRadians(lat0))
    drawIntoCanvas { canvas ->
        // Drawn in reverse of fetch order — far first, centre last — so
        // where two tiles' meshes overlap by a hair, the one being looked
        // at wins.
        for (index in refs.indices.reversed()) {
            val ref = refs[index]
            val bitmap = store.peek(ref) ?: continue
            val n = meshFor(ref.z)
            var front = 0
            var k = 0
            for (j in 0..n) {
                val lat = Tiles.latOfRow(ref.y + j.toDouble() / n, ref.z)
                for (i in 0..n) {
                    val lon = Tiles.lonOfTile(ref.x, ref.z) + 360.0 / (1 shl ref.z) * i / n
                    val near = Orthographic.projectInto(
                        lat, lon, sinLat0, cosLat0, lon0, radius, cx, cy, corner
                    )
                    var x = corner[0]
                    var y = corner[1]
                    if (near) {
                        front++
                    } else {
                        val dx = x - cx
                        val dy = y - cy
                        val len = kotlin.math.hypot(dx, dy)
                        if (len > 1e-3f) {
                            x = cx + dx / len * radius
                            y = cy + dy / len * radius
                        }
                    }
                    verts[k++] = x
                    verts[k++] = y
                }
            }
            if (front == 0) continue
            canvas.nativeCanvas.drawBitmapMesh(bitmap, n, n, verts, 0, null, 0, paint)
        }
    }
}

/** Grid cells per tile side: enough to follow the curve, and no more. */
private fun meshFor(z: Int): Int = when {
    z <= 1 -> MAX_MESH
    z == 2 -> 12
    z == 3 -> 10
    z == 4 -> 8
    z == 5 -> 6
    z == 6 -> 4
    z == 7 -> 3
    z <= 9 -> 2
    else -> 1
}

private const val MAX_MESH = 16

private val TILE_PAINT = android.graphics.Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
}

/** The sunlight is at full strength with this much or more in view… */
private const val SHADE_FULL_SPAN = 40.0

/** …and gone by this much, where the photograph should just be clear. */
private const val SHADE_GONE_SPAN = 4.0

/** Country borders drawn over the imagery while more than this is in view. */
private const val BORDERS_BELOW_SPAN = 3.0

/** And the grid of latitude and longitude while more than this is. */
private const val GRATICULE_BELOW_SPAN = 30.0

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

/**
 * Buffers the globe's draw path reuses between frames.
 *
 * The coastlines had this done for them, with a comment explaining
 * why — one hoisted Path and one projection array instead of tens of
 * thousands of short-lived objects a second. The markers and the
 * graticule are drawn from the same DrawScope on the same thread and
 * did not: drawMarkers allocated three HashMaps, an ArrayList per
 * colour group, and per visible point an Orthographic.Point, an
 * Offset, a boxed Long, a boxed Color and a boxed Float — about ten
 * thousand objects a frame over a full budget, which during a drag is
 * most of a megabyte a second of garbage. strokeArcOfPoints allocated
 * a Path per arc and a Pair per sample, another four or five thousand
 * a frame for a grid of straight lines.
 *
 * Held by the composable and handed to the draw lambda, so its
 * lifetime is the composition's and nothing here has to be a global.
 * Compose draws on one thread, which is what makes a shared buffer
 * safe.
 */
internal class GlobeScratch {
    /** One arc at a time, reset rather than rebuilt. */
    val arc = Path()

    /** Two floats, written by Orthographic.projectInto. */
    val xy = FloatArray(2)

    /** Colour-and-size buckets for the markers, reused each frame. */
    var keys = LongArray(128)
    var colours = IntArray(128)
    var widths = FloatArray(128)
    var nights = BooleanArray(128)
    val buckets = ArrayList<ArrayList<Offset>>(128)
    var used = 0
        private set

    fun reset() {
        for (i in 0 until used) buckets[i].clear()
        used = 0
    }

    fun add(key: Long, colour: Int, width: Float, night: Boolean, x: Float, y: Float) {
        var at = -1
        for (i in 0 until used) {
            if (keys[i] == key) { at = i; break }
        }
        if (at < 0) {
            if (used == keys.size) grow()
            at = used++
            keys[at] = key
            colours[at] = colour
            widths[at] = width
            nights[at] = night
            if (buckets.size <= at) buckets.add(ArrayList(64))
        }
        buckets[at].add(Offset(x, y))
    }

    private fun grow() {
        val n = keys.size * 2
        keys = keys.copyOf(n)
        colours = colours.copyOf(n)
        widths = widths.copyOf(n)
        nights = nights.copyOf(n)
    }
}

private fun DrawScope.drawGraticule(
    scratch: GlobeScratch,
    cx: Float, cy: Float, r: Float, lat0: Double, lon0: Double, faint: Boolean = false
) {
    // Hoisted: the camera's own trig is the same for every sample of
    // every arc, and projecting through Orthographic.project
    // recomputed it 181 times per arc, twenty-six arcs per frame.
    val sinLat0 = kotlin.math.sin(Math.toRadians(lat0))
    val cosLat0 = kotlin.math.cos(Math.toRadians(lat0))
    // Over photography the grid is orientation, not decoration, and at
    // full strength it draws more attention than the continents under
    // it.
    val colour = Intel.Hairline.copy(alpha = if (faint) 0.22f else 0.7f)
    // Parallels every thirty degrees, meridians every thirty.
    for (lat in -60..60 step 30) {
        strokeArcOfPoints(
            scratch, colour, cx, cy, r, sinLat0, cosLat0, lon0,
            { lat.toDouble() }, { step -> -180.0 + step * 2.0 }
        )
    }
    for (lon in -180..150 step 30) {
        strokeArcOfPoints(
            scratch, colour, cx, cy, r, sinLat0, cosLat0, lon0,
            { step -> -90.0 + step }, { lon.toDouble() }
        )
    }
    // The equator, slightly brighter, because it is the one line people
    // orient by.
    strokeArcOfPoints(
        scratch, Intel.Hairline.copy(alpha = if (faint) 0.34f else 1f),
        cx, cy, r, sinLat0, cosLat0, lon0,
        { 0.0 }, { step -> -180.0 + step * 2.0 }
    )
}

/**
 * A sky to hang the Earth in.
 *
 * Deterministic rather than random: the same star is in the same place
 * on every frame, because a sky that reshuffles itself sixty times a
 * second is a snowstorm.
 *
 * Fixed, too. It used to slide with the camera — a degree of longitude
 * moved the field about a sixth as far as it moved the ground — on the
 * theory that the parallax would make the globe read as an object
 * somewhere rather than a disc on a background. It does the opposite:
 * the eye reads anything moving alongside the Earth as attached to it,
 * so the sky came along for the ride and the whole screen slid
 * together. Stars are the one thing in view far enough away to stay
 * put, and holding them still is what makes the Earth look like it is
 * the thing turning.
 */
/**
 * The sky, worked out once per screen size.
 *
 * Batching the stars by brightness band stopped them being drawn
 * one at a time, which was the expensive half. The other half
 * stayed: the bands and every Offset in them were rebuilt on every
 * frame of every drag, from a hash that gives the same answer each
 * time, for a star field that cannot move. Two hundred and twenty
 * objects and five lists, sixty times a second, all identical to
 * the last set.
 */
private var starCacheKey: Long = -1L
private var starCache: Array<ArrayList<Offset>>? = null

private fun starsFor(width: Float, height: Float): Array<ArrayList<Offset>> {
    val key = (width.toInt().toLong() shl 32) or (height.toInt().toLong() and 0xFFFFFFFFL)
    starCache?.let { if (starCacheKey == key) return it }
    val bands = Array(STAR_BANDS) { ArrayList<Offset>(STAR_COUNT / STAR_BANDS + 8) }
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
        val x = ux * (width + 240f)
        val y = uy * (height + 240f)
        val band = (mag * STAR_BANDS).toInt().coerceIn(0, STAR_BANDS - 1)
        bands[band].add(Offset(x - 120f, y - 120f))
    }
    starCache = bands
    starCacheKey = key
    return bands
}

private fun DrawScope.drawStars(width: Float, height: Float) {
    val bands = starsFor(width, height)
    for (band in 0 until STAR_BANDS) {
        val stars = bands[band]
        if (stars.isEmpty()) continue
        val mag = (band + 0.5f) / STAR_BANDS
        drawPoints(
            points = stars,
            pointMode = PointMode.Points,
            color = Color.White.copy(alpha = 0.10f + mag * 0.45f),
            strokeWidth = (0.6f + mag * 1.1f) * 2f,
            cap = StrokeCap.Round
        )
    }
}

/** How many brightnesses the sky is drawn in. Enough that it still
 *  looks like a sky, few enough to be a handful of draw calls. */
private const val STAR_BANDS = 5

/**
 * Walks 181 samples of a line, drawing only the parts on this side.
 *
 * One path per arc rather than a drawLine per sample. The graticule is
 * a couple of dozen of these and the terminator is another, so this
 * was three or four thousand canvas calls a frame on its own.
 */
private inline fun DrawScope.strokeArcOfPoints(
    scratch: GlobeScratch,
    colour: Color,
    cx: Float, cy: Float, r: Float,
    sinLat0: Double, cosLat0: Double, lon0: Double,
    latOf: (Int) -> Double,
    lonOf: (Int) -> Double
) {
    // Two lambdas rather than one returning a Pair: this is inline,
    // but inlining does not remove the Pair, and that was one
    // allocation per sample — 181 of them per arc, twenty-six arcs a
    // frame, on top of an Orthographic.Point each.
    val path = scratch.arc
    path.reset()
    val xy = scratch.xy
    var wasVisible = false
    var any = false
    for (step in 0..180) {
        val visible = Orthographic.projectInto(
            latOf(step), lonOf(step), sinLat0, cosLat0, lon0, r, cx, cy, xy
        )
        if (visible) {
            if (wasVisible) path.lineTo(xy[0], xy[1]) else path.moveTo(xy[0], xy[1])
            any = true
        }
        wasVisible = visible
    }
    if (any) drawPath(path, colour, style = Stroke(width = 1f))
}

/**
 * Open lines on the sphere: borders, which enclose nothing.
 *
 * Broken whenever a segment goes round the back, for the same reason
 * the coastlines are — otherwise a border in Asia is drawn straight
 * across the Pacific when the globe is turned to the Americas.
 */
/**
 * Coastlines and borders, as one path rather than ten thousand lines.
 *
 * This drew each segment with its own drawLine. Natural Earth's
 * coarsest land and border sets are together some tens of thousands of
 * points, so a frame was tens of thousands of separate canvas calls on
 * the main thread — which is most of why the app felt heavy, and all
 * of it paid again sixty times a second.
 *
 * One Path, with a moveTo wherever the line goes round the back of the
 * sphere and reappears, is one call. [stride] skips points while the
 * globe is moving: at two the line is still a coastline and the work
 * halves, and nobody has ever studied a border that is sliding.
 */
/**
 * One Path and one array, reused.
 *
 * This is the last per-frame trigonometry on the main thread now
 * that the sphere itself is on the GPU, and it was spending more
 * of it than it needed to: a Point object allocated for every
 * vertex of every outline on every frame, a fresh Path each call,
 * and sin and cos of the camera's latitude recomputed per vertex
 * for a value that does not change across the whole shape.
 *
 * Not thread-safe, and does not need to be: a DrawScope runs on
 * the one thread that draws.
 */
private val linePath = Path()
private val projected = FloatArray(2)

private fun DrawScope.drawLines(
    lines: List<DoubleArray>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    colour: Color,
    stride: Int = 1
) {
    val path = linePath
    path.reset()
    val step = (stride.coerceAtLeast(1)) * 2
    val la0 = Math.toRadians(lat0)
    val sinLat0 = kotlin.math.sin(la0)
    val cosLat0 = kotlin.math.cos(la0)
    lines.forEach { line ->
        var wasVisible = false
        var started = false
        var i = 0
        while (i + 1 < line.size) {
            val visible = Orthographic.projectInto(
                line[i + 1], line[i], sinLat0, cosLat0, lon0, r, cx, cy, projected
            )
            if (visible) {
                if (started && wasVisible) path.lineTo(projected[0], projected[1])
                else path.moveTo(projected[0], projected[1])
                started = true
            }
            wasVisible = visible
            i += step
        }
    }
    drawPath(path, colour, style = Stroke(width = 1f))
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
/**
 * The markers, batched.
 *
 * Two drawCircle per point, and the globe is handed thousands of them:
 * a frame was up to sixteen thousand separate canvas calls before a
 * single border had been drawn. Compose will draw a whole list of
 * points in one call if they share a colour and a size, and markers
 * only have as many colours as there are layers — so the same picture
 * comes out of a few dozen calls instead.
 *
 * The halo behind each one is dropped while the globe is moving. It is
 * there so a marker stays legible against a photograph, which matters
 * when you are reading the thing and not when it is sliding.
 */
private fun DrawScope.drawMarkers(
    scratch: GlobeScratch,
    points: List<GlobePoint>,
    cx: Float, cy: Float, r: Float,
    lat0: Double, lon0: Double,
    sun: Pair<Double, Double>,
    halos: Boolean
) {
    if (points.isEmpty()) return
    // Bucketed by what makes two markers drawable together: the
    // colour they end up and the size they are drawn at. Three
    // HashMaps keyed on a boxed Long, rebuilt every frame over up to
    // GLOBE_BUDGET points, was most of the garbage this path made.
    scratch.reset()
    val xy = scratch.xy
    val sinLat0 = kotlin.math.sin(Math.toRadians(lat0))
    val cosLat0 = kotlin.math.cos(Math.toRadians(lat0))
    points.forEach { point ->
        val visible = Orthographic.projectInto(
            point.lat, point.lon, sinLat0, cosLat0, lon0, r, cx, cy, xy
        )
        if (!visible) return@forEach
        val night = Orthographic.isNight(point.lat, point.lon, sun.first, sun.second)
        val size = point.sizeDp.coerceIn(5, 16)
        val key = (point.color.toLong() shl 8) or
            (size.toLong() shl 1) or (if (night) 1L else 0L)
        scratch.add(key, point.color, size * density, night, xy[0], xy[1])
    }
    if (halos) {
        for (i in 0 until scratch.used) {
            drawPoints(
                points = scratch.buckets[i],
                pointMode = PointMode.Points,
                color = HALO,
                strokeWidth = scratch.widths[i] + 2.4f * density,
                cap = StrokeCap.Round
            )
        }
    }
    for (i in 0 until scratch.used) {
        // Dimmed less than before: the night side of a photograph is
        // genuinely dark, and the old fade took markers there from
        // "quieter" to "invisible". Built once per bucket now rather
        // than once per point.
        val base = Color(scratch.colours[i])
        drawPoints(
            points = scratch.buckets[i],
            pointMode = PointMode.Points,
            color = if (scratch.nights[i]) base.copy(alpha = 0.78f) else base,
            strokeWidth = scratch.widths[i],
            cap = StrokeCap.Round
        )
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

/** Small, legible over photography, and out of the way. */
private fun creditInk() = android.graphics.Paint().apply {
    isAntiAlias = true
    color = 0xB3FFFFFF.toInt()
    textSize = 22f
    setShadowLayer(3f, 0f, 0f, 0xCC000000.toInt())
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
/**
 * Far enough in to read a street name.
 *
 * It was 8, which is about a large country across the screen —
 * the point where the bundled mosaic runs out of pixels and the
 * app used to hand the view to a separate map. There is no second
 * view to hand it to now, so the globe goes the whole way: zoom 19
 * of the tile imagery, which on a phone-width screen is a little
 * under this. Past that Esri has no more photographs and zooming
 * further would only magnify the last ones.
 */
private const val MAX_ZOOM = 48_000f
private const val SPIN_DECAY = 0.94f

/** Below this a flick has stopped, and the globe stops redrawing. */
private const val SPIN_FLOOR = 0.02f
private const val TAP_SLOP_PX = 44f

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
