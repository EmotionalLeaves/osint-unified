package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Air-quality (European AQI) at major world cities via Open-Meteo (keyless),
 * fetched in one multi-location request and plotted as the AQI layer.
 */
class AirQualityDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Cities sampled for air quality.
     *
     * This was thirty hardcoded points, which is why the layer always read
     * exactly "AQI 30" — that was the list length, not a measurement count.
     * Held as text and parsed once, because a literal this size makes the
     * Kotlin compiler's control-flow pass run out of heap.
     */
    private val CITY_TABLE = """
            New York|40.71|-74.01,
            Los Angeles|34.05|-118.24,
            Chicago|41.88|-87.63,
            Houston|29.76|-95.37,
            Phoenix|33.45|-112.07,
            Philadelphia|39.95|-75.17,
            San Antonio|29.42|-98.49,
            San Diego|32.72|-117.16,
            Dallas|32.78|-96.80,
            San Jose|37.34|-121.89,
            Austin|30.27|-97.74,
            Seattle|47.61|-122.33,
            Denver|39.74|-104.99,
            Boston|42.36|-71.06,
            Detroit|42.33|-83.05,
            Atlanta|33.75|-84.39,
            Miami|25.76|-80.19,
            Toronto|43.65|-79.38,
            Montreal|45.50|-73.57,
            Vancouver|49.28|-123.12,
            Mexico City|19.43|-99.13,
            Guadalajara|20.66|-103.35,
            Bogota|4.71|-74.07,
            Lima|-12.05|-77.04,
            Santiago|-33.45|-70.67,
            Buenos Aires|-34.60|-58.38,
            Sao Paulo|-23.55|-46.63,
            Rio de Janeiro|-22.91|-43.17,
            Brasilia|-15.79|-47.88,
            Caracas|10.49|-66.88,
            Quito|-0.18|-78.47,
            La Paz|-16.49|-68.15,
            Havana|23.11|-82.37,
            London|51.51|-0.13,
            Manchester|53.48|-2.24,
            Dublin|53.35|-6.26,
            Paris|48.86|2.35,
            Marseille|43.30|5.37,
            Madrid|40.42|-3.70,
            Barcelona|41.39|2.17,
            Lisbon|38.72|-9.14,
            Rome|41.90|12.50,
            Milan|45.46|9.19,
            Naples|40.85|14.27,
            Berlin|52.52|13.40,
            Munich|48.14|11.58,
            Hamburg|53.55|9.99,
            Frankfurt|50.11|8.68,
            Amsterdam|52.37|4.90,
            Brussels|50.85|4.35,
            Zurich|47.38|8.54,
            Vienna|48.21|16.37,
            Prague|50.08|14.44,
            Warsaw|52.23|21.01,
            Krakow|50.06|19.94,
            Budapest|47.50|19.04,
            Bucharest|44.43|26.10,
            Sofia|42.70|23.32,
            Belgrade|44.79|20.45,
            Zagreb|45.81|15.98,
            Athens|37.98|23.73,
            Istanbul|41.01|28.98,
            Ankara|39.93|32.86,
            Kyiv|50.45|30.52,
            Moscow|55.75|37.62,
            Saint Petersburg|59.93|30.34,
            Minsk|53.90|27.57,
            Stockholm|59.33|18.07,
            Oslo|59.91|10.75,
            Copenhagen|55.68|12.57,
            Helsinki|60.17|24.94,
            Riga|56.95|24.11,
            Vilnius|54.69|25.28,
            Tallinn|59.44|24.75,
            Cairo|30.04|31.24,
            Alexandria|31.20|29.92,
            Casablanca|33.57|-7.59,
            Algiers|36.75|3.06,
            Tunis|36.81|10.18,
            Tripoli|32.89|13.19,
            Lagos|6.52|3.38,
            Abuja|9.06|7.49,
            Accra|5.60|-0.19,
            Dakar|14.72|-17.47,
            Abidjan|5.36|-4.01,
            Nairobi|-1.29|36.82,
            Addis Ababa|9.01|38.76,
            Khartoum|15.50|32.56,
            Kampala|0.35|32.58,
            Dar es Salaam|-6.79|39.21,
            Luanda|-8.84|13.23,
            Kinshasa|-4.44|15.27,
            Johannesburg|-26.20|28.05,
            Cape Town|-33.92|18.42,
            Durban|-29.86|31.03,
            Harare|-17.83|31.05,
            Tel Aviv|32.09|34.78,
            Jerusalem|31.77|35.21,
            Beirut|33.89|35.50,
            Damascus|33.51|36.29,
            Baghdad|33.31|44.37,
            Tehran|35.69|51.39,
            Riyadh|24.71|46.68,
            Jeddah|21.49|39.19,
            Dubai|25.20|55.27,
            Abu Dhabi|24.45|54.38,
            Doha|25.29|51.53,
            Kuwait City|29.38|47.99,
            Muscat|23.59|58.41,
            Amman|31.95|35.93,
            Karachi|24.86|67.01,
            Lahore|31.55|74.34,
            Islamabad|33.69|73.05,
            Delhi|28.61|77.21,
            Mumbai|19.08|72.88,
            Kolkata|22.57|88.36,
            Chennai|13.08|80.27,
            Bengaluru|12.97|77.59,
            Hyderabad|17.39|78.49,
            Ahmedabad|23.02|72.57,
            Dhaka|23.81|90.41,
            Kathmandu|27.72|85.32,
            Colombo|6.93|79.86,
            Yangon|16.87|96.20,
            Bangkok|13.76|100.50,
            Hanoi|21.03|105.85,
            Ho Chi Minh City|10.82|106.63,
            Phnom Penh|11.56|104.93,
            Kuala Lumpur|3.14|101.69,
            Singapore|1.35|103.82,
            Jakarta|-6.21|106.85,
            Surabaya|-7.26|112.75,
            Manila|14.60|120.98,
            Taipei|25.03|121.57,
            Hong Kong|22.32|114.17,
            Guangzhou|23.13|113.26,
            Shenzhen|22.54|114.06,
            Shanghai|31.23|121.47,
            Beijing|39.90|116.41,
            Chengdu|30.57|104.07,
            Wuhan|30.59|114.31,
            Xian|34.34|108.94,
            Chongqing|29.56|106.55,
            Shenyang|41.80|123.43,
            Seoul|37.57|126.98,
            Busan|35.18|129.08,
            Pyongyang|39.04|125.76,
            Tokyo|35.68|139.69,
            Osaka|34.69|135.50,
            Nagoya|35.18|136.91,
            Sapporo|43.06|141.35,
            Fukuoka|33.59|130.40,
            Ulaanbaatar|47.89|106.91,
            Almaty|43.24|76.89,
            Tashkent|41.30|69.24,
            Baku|40.41|49.87,
            Tbilisi|41.72|44.78,
            Yerevan|40.18|44.51,
            Kabul|34.53|69.17,
            Sydney|-33.87|151.21,
            Melbourne|-37.81|144.96,
            Brisbane|-27.47|153.03,
            Perth|-31.95|115.86,
            Adelaide|-34.93|138.60,
            Auckland|-36.85|174.76,
            Wellington|-41.29|174.78,
    """

    private val cities: List<Triple<String, Double, Double>> by lazy {
        CITY_TABLE.lineSequence()
            .map { it.trim().trimEnd(',') }
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val p = row.split('|')
                val name = p.getOrNull(0)?.trim() ?: return@mapNotNull null
                val lat = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = p.getOrNull(2)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                Triple(name, lat, lon)
            }
            .toList()
    }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val out = ArrayList<GeoBlip>(cities.size)
        // Open-Meteo accepts many coordinates per call but not unlimited,
        // so the list goes in batches.
        cities.chunked(60).forEach { batch ->
            runCatching { fetchBatch(batch, out) }
        }
        out
    }

    private fun fetchBatch(batch: List<Triple<String, Double, Double>>, into: MutableList<GeoBlip>) {
        val lats = batch.joinToString(",") { it.second.toString() }
        val lons = batch.joinToString(",") { it.third.toString() }
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lats&longitude=$lons&current=european_aqi,pm2_5,pm10"
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return
            val root = json.parseToJsonElement(body)
            val items = (root as? JsonArray) ?: JsonArray(listOf(root))
            items.forEachIndexed { i, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
                val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
                val current = o["current"] as? JsonObject
                fun v(k: String) = (current?.get(k) as? JsonPrimitive)?.doubleOrNull
                val aqi = v("european_aqi")
                val name = batch.getOrNull(i)?.first
                val label = buildString {
                    if (name != null) append("$name · ")
                    append("AQI ${aqi?.toInt() ?: "?"}")
                    aqi?.let { append(" (${band(it)})") }
                    v("pm2_5")?.let { append(" · PM2.5 ${it.toInt()}") }
                }
                into.add(GeoBlip(lat, lon, label))
            }
        }
    }

    /** The European AQI bands, so a number means something at a glance. */
    private fun band(aqi: Double): String = when {
        aqi <= 20 -> "good"
        aqi <= 40 -> "fair"
        aqi <= 60 -> "moderate"
        aqi <= 80 -> "poor"
        aqi <= 100 -> "very poor"
        else -> "extremely poor"
    }
}
