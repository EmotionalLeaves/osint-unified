package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Where crisis reporting is coming from right now.
 *
 * GDELT's geo endpoint, which returned mapped hotspots, has been retired —
 * it answers 404 to every request, which is why this layer sat at zero. The
 * article API still works but carries no coordinates, only the country each
 * outlet publishes from.
 *
 * So the layer plots reporting volume at country level and says so. That is
 * a weaker claim than "the event happened here", and labelling it as the
 * event location would be a lie the map would tell convincingly.
 */
class NewsDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(
                "(protest OR conflict OR strike OR evacuation OR airstrike OR earthquake OR explosion)",
                "UTF-8"
            )
            val request = Request.Builder()
                .url(
                    "https://api.gdeltproject.org/api/v2/doc/doc?query=$query" +
                        "&mode=artlist&format=json&maxrecords=250&sort=datedesc&timespan=1d"
                )
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                // Before the status, not after it.
                //
                // The comment here used to say GDELT rate limits with
                // a plain-text body "rather than a code". It does
                // both: the "Please limit requests to one every 5
                // seconds" text arrives with a 429, so the status
                // check above threw first and the one source that
                // says plainly why it refused had that sentence
                // discarded. The person saw "GDELT HTTP 429" and no
                // reason.
                if (body != null && body.trimStart().startsWith("Please limit")) {
                    throw IOException(RATE_LIMITED)
                }
                if (!resp.isSuccessful) throw IOException("GDELT HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("GDELT returned nothing")

                val articles = json.parseToJsonElement(body).jsonObject["articles"] as? JsonArray
                    ?: throw IOException("GDELT answered in an unexpected shape")

                val counts = LinkedHashMap<String, Int>()
                val headline = LinkedHashMap<String, String>()
                val link = LinkedHashMap<String, String>()
                articles.forEach { a ->
                    val o = a as? JsonObject ?: return@forEach
                    fun f(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                    val country = f("sourcecountry")?.trim().orEmpty()
                    if (country.isBlank()) return@forEach
                    counts[country] = (counts[country] ?: 0) + 1
                    if (!headline.containsKey(country)) {
                        f("title")?.let { headline[country] = it.trim() }
                        f("url")?.let { link[country] = it }
                    }
                }

                counts.mapNotNull { (country, n) ->
                    val c = CENTROIDS[country] ?: return@mapNotNull null
                    val top = headline[country]?.take(110)
                    GeoBlip(
                        c.first, c.second,
                        "$country · $n report${if (n == 1) "" else "s"}" +
                            if (top != null) " · $top" else "",
                        link[country]
                    )
                }
            }
        } catch (e: com.osint.unified.network.RateLimited) {
            // The same explanation on every poll of the wait, not only
            // the first.
            throw IOException("$RATE_LIMITED Asking again in ${e.waitSeconds}s.", e)
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("GDELT unavailable: ${e.message}", e)
        }
    }

    private companion object {
        const val RATE_LIMITED =
            "GDELT is rate limiting this address — it allows one request every " +
                "five seconds across everyone sharing it. Nothing is wrong with " +
                "the news; this phone is in the queue."

        /**
         * Country centroids for the names GDELT publishes outlets under.
         *
         * Held as text and parsed once rather than as a map literal: a
         * literal this size makes the Kotlin compiler's control-flow pass
         * run out of heap, which fails the build rather than the runtime.
         */
        private const val CENTROID_TABLE = """
            United States|39.8|-98.6,
            United Kingdom|54.0|-2.0,
            Canada|56.1|-106.3,
            Australia|-25.3|133.8,
            India|21.1|78.7,
            China|35.9|104.2,
            Russia|61.5|105.3,
            Ukraine|48.4|31.2,
            Germany|51.2|10.5,
            France|46.2|2.2,
            Spain|40.5|-3.7,
            Italy|41.9|12.6,
            Netherlands|52.1|5.3,
            Belgium|50.5|4.5,
            Switzerland|46.8|8.2,
            Austria|47.5|14.6,
            Poland|51.9|19.1,
            Sweden|60.1|18.6,
            Norway|60.5|8.5,
            Denmark|56.3|9.5,
            Finland|61.9|25.7,
            Ireland|53.4|-8.2,
            Portugal|39.4|-8.2,
            Greece|39.1|21.8,
            Turkey|38.9|35.2,
            Israel|31.0|34.9,
            Palestinian Territory|31.9|35.2,
            Lebanon|33.9|35.9,
            Syria|34.8|39.0,
            Iraq|33.2|43.7,
            Iran|32.4|53.7,
            Saudi Arabia|23.9|45.1,
            United Arab Emirates|23.4|53.8,
            Qatar|25.4|51.2,
            Kuwait|29.3|47.5,
            Jordan|30.6|36.2,
            Egypt|26.8|30.8,
            Libya|26.3|17.2,
            Tunisia|33.9|9.6,
            Algeria|28.0|1.7,
            Morocco|31.8|-7.1,
            Nigeria|9.1|8.7,
            Ghana|7.9|-1.0,
            Kenya|-0.0|37.9,
            Ethiopia|9.1|40.5,
            Somalia|5.2|46.2,
            Sudan|12.9|30.2,
            South Africa|-30.6|22.9,
            Zimbabwe|-19.0|29.2,
            Uganda|1.4|32.3,
            Tanzania|-6.4|34.9,
            Congo|-4.0|21.8,
            Japan|36.2|138.3,
            South Korea|35.9|127.8,
            North Korea|40.3|127.5,
            Taiwan|23.7|121.0,
            Hong Kong|22.3|114.2,
            Singapore|1.35|103.8,
            Malaysia|4.2|101.98,
            Indonesia|-0.8|113.9,
            Thailand|15.9|101.0,
            Vietnam|14.1|108.3,
            Philippines|12.9|121.8,
            Myanmar|21.9|95.96,
            Bangladesh|23.7|90.4,
            Pakistan|30.4|69.3,
            Afghanistan|33.9|67.7,
            Sri Lanka|7.9|80.8,
            Nepal|28.4|84.1,
            Kazakhstan|48.0|66.9,
            Uzbekistan|41.4|64.6,
            Azerbaijan|40.1|47.6,
            Armenia|40.1|45.0,
            Georgia|42.3|43.4,
            Belarus|53.7|27.95,
            Romania|45.9|25.0,
            Bulgaria|42.7|25.5,
            Hungary|47.2|19.5,
            Czech Republic|49.8|15.5,
            Slovakia|48.7|19.7,
            Serbia|44.0|21.0,
            Croatia|45.1|15.2,
            Bosnia and Herzegovina|43.9|17.7,
            Albania|41.2|20.2,
            Mexico|23.6|-102.6,
            Brazil|-14.2|-51.9,
            Argentina|-38.4|-63.6,
            Chile|-35.7|-71.5,
            Colombia|4.6|-74.3,
            Venezuela|6.4|-66.6,
            Peru|-9.2|-75.0,
            Ecuador|-1.8|-78.2,
            Bolivia|-16.3|-63.6,
            Uruguay|-32.5|-55.8,
            Paraguay|-23.4|-58.4,
            Cuba|21.5|-77.8,
            Haiti|19.0|-72.3,
            Dominican Republic|18.7|-70.2,
            Jamaica|18.1|-77.3,
            Panama|8.5|-80.8,
            Costa Rica|9.7|-83.8,
            Guatemala|15.8|-90.2,
            Honduras|15.2|-86.2,
            El Salvador|13.8|-88.9,
            Nicaragua|12.9|-85.2,
            New Zealand|-40.9|174.9,
            Papua New Guinea|-6.3|143.9,
            Fiji|-17.7|178.1,
            Iceland|65.0|-18.6,
            Estonia|58.6|25.0,
            Latvia|56.9|24.6,
            Lithuania|55.2|23.9,
            Slovenia|46.2|15.0,
            Moldova|47.4|28.4,
            Cyprus|35.1|33.4,
            Malta|35.9|14.4,
            Luxembourg|49.8|6.1,
            Yemen|15.6|48.5,
            Oman|21.5|56.0,
            Bahrain|26.0|50.6,
            Mali|17.6|-4.0,
            Niger|17.6|8.1,
            Chad|15.5|18.7,
            Cameroon|7.4|12.4,
            Senegal|14.5|-14.5,
            Ivory Coast|7.5|-5.5,
            Mozambique|-18.7|35.5,
            Zambia|-13.1|27.8,
            Angola|-11.2|17.9,
            Madagascar|-18.8|46.9,
            Rwanda|-1.9|29.9,
            Burkina Faso|12.2|-1.6,
            Mauritania|21.0|-10.9,
            Mongolia|46.9|103.8,
            Cambodia|12.6|104.9,
            Laos|19.9|102.5,
        """

        val CENTROIDS: Map<String, Pair<Double, Double>> by lazy {
            CENTROID_TABLE.lineSequence()
                .map { it.trim().trimEnd(',') }
                .filter { it.isNotBlank() }
                .mapNotNull { row ->
                    val parts = row.split('|')
                    val name = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
                    val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    name to (lat to lon)
                }
                .toMap()
        }
    }
}
