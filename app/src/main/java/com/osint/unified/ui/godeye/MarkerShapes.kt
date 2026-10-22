package com.osint.unified.ui.godeye

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable

/**
 * What each kind of marker looks like, beyond its colour.
 *
 * Twenty-nine layers were drawn as identical circles in a palette holding
 * four reds (fire, volcano, conflict, restricted airspace) and four yellows
 * (military aircraft, power, airport status, and more). They were
 * indistinguishable side by side even with normal colour vision, and colour
 * was the only encoding, which fails roughly one man in twelve outright.
 *
 * Shape carries the category — moving things, hazards, fixed infrastructure,
 * measurements — so the map is readable in greyscale and at a glance.
 */
enum class MarkerShape {
    /** Aircraft, satellites: something travelling. */
    ARROW,

    /** Vessels: something travelling on water. */
    CHEVRON,

    /** Hazards: quake, fire, volcano, cyclone, tsunami, conflict, alert. */
    TRIANGLE,

    /** Fixed infrastructure: airports, power, cables, cameras, airspace. */
    DIAMOND,

    /** Measurements and stations: buoys, METAR, air quality, radiation. */
    SQUARE,

    /** Findings from an investigation, and anything uncategorised. */
    CIRCLE
}

/** The shape for a layer, by the kind string the globe already carries. */
fun shapeFor(kind: String): MarkerShape = when (kind) {
    "Aircraft", "Military aircraft", "Satellite" -> MarkerShape.ARROW
    "Vessel" -> MarkerShape.CHEVRON
    "Earthquake", "Wildfire", "Volcano", "Tropical cyclone", "Tsunami bulletin",
    "Conflict", "Disaster alert", "Weather alert", "Natural event" -> MarkerShape.TRIANGLE
    "Airport", "Power plant", "Cable landing", "Traffic camera",
    "Restricted airspace", "Airport status" -> MarkerShape.DIAMOND
    "Ocean buoy", "Airfield weather", "Air quality", "Radiation",
    "Seismic station", "Aurora forecast" -> MarkerShape.SQUARE
    else -> MarkerShape.CIRCLE
}

/**
 * A marker icon drawn at runtime.
 *
 * Deliberately not a vector resource per layer: the colour and size vary
 * per marker and the set is cached by both, so drawing them is simpler than
 * shipping and tinting ninety drawables.
 */
fun markerIcon(shape: MarkerShape, color: Int, sizePx: Int): Drawable {
    val size = sizePx.coerceAtLeast(8)
    return object : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // A dark edge keeps a bright marker legible over bright imagery
            // — snow, desert and cloud all washed the old dots out.
            this.color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = (size / 8f).coerceAtLeast(1f)
        }

        override fun draw(canvas: Canvas) {
            val w = size.toFloat()
            val h = size.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            when (shape) {
                MarkerShape.CIRCLE -> {
                    canvas.drawCircle(cx, cy, cx * 0.9f, fill)
                    canvas.drawCircle(cx, cy, cx * 0.9f, edge)
                }
                MarkerShape.SQUARE -> {
                    val i = w * 0.14f
                    canvas.drawRect(i, i, w - i, h - i, fill)
                    canvas.drawRect(i, i, w - i, h - i, edge)
                }
                else -> {
                    val path = Path().apply {
                        when (shape) {
                            MarkerShape.TRIANGLE -> {
                                moveTo(cx, h * 0.08f)
                                lineTo(w * 0.94f, h * 0.9f)
                                lineTo(w * 0.06f, h * 0.9f)
                            }
                            MarkerShape.DIAMOND -> {
                                moveTo(cx, h * 0.04f)
                                lineTo(w * 0.96f, cy)
                                lineTo(cx, h * 0.96f)
                                lineTo(w * 0.04f, cy)
                            }
                            MarkerShape.ARROW -> {
                                moveTo(cx, h * 0.02f)
                                lineTo(w * 0.92f, h * 0.96f)
                                lineTo(cx, h * 0.72f)
                                lineTo(w * 0.08f, h * 0.96f)
                            }
                            else -> {
                                // CHEVRON: a hull seen from above.
                                moveTo(cx, h * 0.05f)
                                lineTo(w * 0.9f, h * 0.95f)
                                lineTo(cx, h * 0.62f)
                                lineTo(w * 0.1f, h * 0.95f)
                            }
                        }
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, edge)
                }
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable, still required by the interface")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = size
        override fun getIntrinsicHeight(): Int = size
    }.apply { setBounds(0, 0, size, size) }
}
