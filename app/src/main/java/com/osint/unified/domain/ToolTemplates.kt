package com.osint.unified.domain

import android.content.Context
import com.osint.unified.data.OsintFramework
import java.net.URLEncoder

/**
 * Turns the tool *directory* into something you can actually run.
 *
 * Most entries in the OSINT catalogues are websites, not programs — but
 * almost all of them take their query in the URL. So for any entry whose URL
 * carries a recognisable query parameter, substituting the target makes that
 * tool executable with one tap: it opens already pointed at your observable
 * instead of at a blank search box.
 *
 * Three sources feed the registry:
 *
 *  - [CURATED] — hand-written templates for the highest-value sites, marked
 *    [Template.fetchable] when the endpoint returns plain text or JSON that
 *    the app can execute itself and show inline, with no browser at all.
 *  - the bundled username rulesets (WhatsMyName / Sherlock / Maigret), which
 *    are thousands of exact profile-URL templates, executed natively by
 *    [com.osint.unified.domain.executor.UsernameChecker].
 *  - everything in the bundled catalogues whose URL has a query parameter,
 *    derived automatically.
 */
object ToolTemplates {

    /**
     * One runnable tool. [url] contains `{}` where the target belongs.
     * [fetchable] means the app can execute it directly and show the result;
     * otherwise it opens in the in-app browser with the target filled in.
     */
    data class Template(
        val name: String,
        val url: String,
        val type: String,
        val fetchable: Boolean = false,
        val source: String = ""
    ) {
        fun resolve(target: String): String =
            url.replace("{}", URLEncoder.encode(target.trim(), "UTF-8").replace("+", "%20"))
    }

    // Query parameters that mean "this is the search term".
    private val QUERY_KEYS = setOf(
        "q", "query", "search", "s", "term", "keyword", "keywords", "text",
        "name", "user", "username", "account", "handle", "domain", "host",
        "ip", "addr", "address", "email", "mail", "phone", "number", "lookup",
        "target", "input", "value", "url", "site"
    )

    /**
     * Hand-written templates for sites worth running first. Those marked
     * fetchable return text or JSON, so the app executes them itself.
     */
    private val CURATED: List<Template> = listOf(
        // ---- domain ----
        Template("crt.sh certificates", "https://crt.sh/?q={}", "domain", true),
        Template("HackerTarget host search", "https://api.hackertarget.com/hostsearch/?q={}", "domain", true),
        Template("HackerTarget DNS lookup", "https://api.hackertarget.com/dnslookup/?q={}", "domain", true),
        Template("HackerTarget HTTP headers", "https://api.hackertarget.com/httpheaders/?q={}", "domain", true),
        Template("HackerTarget page links", "https://api.hackertarget.com/pagelinks/?q={}", "domain", true),
        Template("Cert Spotter issuances", "https://api.certspotter.com/v1/issuances?domain={}&include_subdomains=true&expand=dns_names", "domain", true),
        Template("RDAP registration", "https://rdap.org/domain/{}", "domain", true),
        Template("Shodan InternetDB", "https://internetdb.shodan.io/{}", "domain", true),
        Template("AlienVault OTX", "https://otx.alienvault.com/api/v1/indicators/domain/{}/general", "domain", true),
        Template("Wayback availability", "https://archive.org/wayback/available?url={}", "domain", true),
        Template("Common Crawl index", "https://index.commoncrawl.org/CC-MAIN-2026-43-index?url=*.{}&output=json&limit=50", "domain", true),
        Template("urlscan.io search", "https://urlscan.io/api/v1/search/?q=domain:{}", "domain", true),
        Template("BuiltWith", "https://builtwith.com/{}", "domain"),
        Template("DNSdumpster", "https://dnsdumpster.com/?q={}", "domain"),
        Template("SecurityTrails", "https://securitytrails.com/domain/{}/dns", "domain"),
        Template("ViewDNS reverse IP", "https://viewdns.info/reverseip/?host={}&t=1", "domain"),
        Template("SSL Labs report", "https://www.ssllabs.com/ssltest/analyze.html?d={}", "domain"),
        Template("Google Transparency", "https://transparencyreport.google.com/safe-browsing/search?url={}", "domain"),
        Template("URLVoid", "https://www.urlvoid.com/scan/{}/", "domain"),
        Template("Netcraft site report", "https://sitereport.netcraft.com/?url={}", "domain"),
        Template("Robtex", "https://www.robtex.com/dns-lookup/{}", "domain"),
        Template("Wayback Machine", "https://web.archive.org/web/*/{}*", "domain"),

        // ---- ip ----
        Template("ipwho.is", "https://ipwho.is/{}", "ip", true),
        Template("Shodan InternetDB", "https://internetdb.shodan.io/{}", "ip", true),
        Template("RIPEstat abuse contact", "https://stat.ripe.net/data/abuse-contact-finder/data.json?resource={}", "ip", true),
        Template("RIPEstat network info", "https://stat.ripe.net/data/network-info/data.json?resource={}", "ip", true),
        Template("BGPView", "https://api.bgpview.io/ip/{}", "ip", true),
        Template("RDAP IP", "https://rdap.org/ip/{}", "ip", true),
        Template("AlienVault OTX", "https://otx.alienvault.com/api/v1/indicators/IPv4/{}/general", "ip", true),
        Template("Onionoo Tor lookup", "https://onionoo.torproject.org/details?search={}", "ip", true),
        Template("GreyNoise viz", "https://viz.greynoise.io/ip/{}", "ip"),
        Template("AbuseIPDB", "https://www.abuseipdb.com/check/{}", "ip"),
        Template("Censys", "https://search.censys.io/hosts/{}", "ip"),
        Template("Shodan host", "https://www.shodan.io/host/{}", "ip"),
        Template("VirusTotal IP", "https://www.virustotal.com/gui/ip-address/{}", "ip"),
        Template("Talos reputation", "https://talosintelligence.com/reputation_center/lookup?search={}", "ip"),
        Template("IPinfo", "https://ipinfo.io/{}", "ip"),

        // ---- email ----
        Template("Gravatar profile", "https://www.gravatar.com/{}.json", "email", true),
        Template("XposedOrNot breaches", "https://api.xposedornot.com/v1/check-email/{}", "email", true),
        Template("Hudson Rock infostealer", "https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-email?email={}", "email", true),
        Template("openpgp.org key", "https://keys.openpgp.org/vks/v1/by-email/{}", "email", true),
        Template("GitHub by email", "https://api.github.com/search/users?q={}+in:email", "email", true),
        Template("Have I Been Pwned", "https://haveibeenpwned.com/account/{}", "email"),
        Template("EmailRep", "https://emailrep.io/{}", "email"),
        Template("Epieos", "https://epieos.com/?q={}", "email"),
        Template("That'sThem email", "https://thatsthem.com/email/{}", "email"),
        Template("IntelligenceX", "https://intelx.io/?s={}", "email"),

        // ---- name / entity ----
        Template("GLEIF legal entity", "https://api.gleif.org/api/v1/lei-records?filter%5Bentity.legalName%5D={}&page%5Bsize%5D=5", "name", true),
        Template("FBI wanted", "https://api.fbi.gov/wanted/v1/list?title={}", "name", true),
        Template("Wikidata search", "https://www.wikidata.org/w/api.php?action=wbsearchentities&search={}&language=en&format=json", "name", true),
        Template("Wikipedia search", "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={}&format=json", "name", true),
        Template("OpenCorporates", "https://opencorporates.com/companies?q={}", "name"),
        Template("SEC EDGAR", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&company={}&output=atom", "name", true),
        Template("Companies House", "https://find-and-update.company-information.service.gov.uk/search?q={}", "name"),
        Template("TruePeopleSearch", "https://www.truepeoplesearch.com/results?name={}", "name"),
        Template("FastPeopleSearch", "https://www.fastpeoplesearch.com/name/{}", "name"),
        Template("That'sThem", "https://thatsthem.com/name/{}", "name"),
        Template("Google", "https://www.google.com/search?q={}", "name"),
        Template("Bing", "https://www.bing.com/search?q={}", "name"),
        Template("DuckDuckGo", "https://duckduckgo.com/?q={}", "name"),
        Template("Yandex", "https://yandex.com/search/?text={}", "name"),
        Template("GDELT news", "https://api.gdeltproject.org/api/v2/doc/doc?query={}&mode=artlist&format=json&maxrecords=20", "name", true),

        // ---- coordinate ----
        Template("Nominatim reverse", "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat={LAT}&lon={LON}", "coordinate", true),
        Template("Commons photos nearby", "https://commons.wikimedia.org/w/api.php?action=query&generator=geosearch&ggscoord={LAT}%7C{LON}&ggsradius=2000&ggslimit=30&prop=imageinfo&iiprop=url&format=json", "coordinate", true),
        Template("KartaView street imagery", "https://api.openstreetcam.org/2.0/photo/?lat={LAT}&lng={LON}&radius=300", "coordinate", true),
        Template("Open-Meteo elevation", "https://api.open-meteo.com/v1/elevation?latitude={LAT}&longitude={LON}", "coordinate", true),
        Template("Google Maps", "https://www.google.com/maps/@{LAT},{LON},17z", "coordinate"),
        Template("Google Earth", "https://earth.google.com/web/@{LAT},{LON},0a,1000d", "coordinate"),
        Template("Bing Maps", "https://www.bing.com/maps?cp={LAT}~{LON}&lvl=17&style=h", "coordinate"),
        Template("Yandex Maps", "https://yandex.com/maps/?ll={LON},{LAT}&z=17&l=sat", "coordinate"),
        Template("OpenStreetMap", "https://www.openstreetmap.org/?mlat={LAT}&mlon={LON}#map=17/{LAT}/{LON}", "coordinate"),
        Template("Sentinel Hub browser", "https://apps.sentinel-hub.com/eo-browser/?lat={LAT}&lng={LON}&zoom=14", "coordinate"),
        Template("SunCalc", "https://www.suncalc.org/#/{LAT},{LON},15/", "coordinate"),
        Template("Peakvisor", "https://peakvisor.com/panorama.html?lat={LAT}&lng={LON}", "coordinate"),
        Template("Wikimapia", "https://wikimapia.org/#lat={LAT}&lon={LON}&z=16", "coordinate"),
        Template("Overpass turbo", "https://overpass-turbo.eu/?Q=&C={LAT};{LON};16", "coordinate"),

        // ---- phone ----
        Template("Truecaller", "https://www.truecaller.com/search/us/{}", "phone"),
        Template("Sync.me", "https://sync.me/search/?number={}", "phone"),
        Template("NumLookup", "https://www.numlookup.com/?phone={}", "phone"),
        Template("That'sThem phone", "https://thatsthem.com/phone/{}", "phone"),
        Template("Google phone", "https://www.google.com/search?q=%22{}%22", "phone"),

        // ---- wallet ----
        Template("Blockchain.info", "https://blockchain.info/rawaddr/{}", "wallet", true),
        Template("Blockstream", "https://blockstream.info/api/address/{}", "wallet", true),
        Template("Ethplorer", "https://api.ethplorer.io/getAddressInfo/{}?apiKey=freekey", "wallet", true),
        Template("Etherscan", "https://etherscan.io/address/{}", "wallet"),
        Template("Blockchair", "https://blockchair.com/search?q={}", "wallet"),
        Template("OXT", "https://oxt.me/address/{}", "wallet"),

        // ---- image ----
        Template("Google Lens", "https://lens.google.com/uploadbyurl?url={}", "image"),
        Template("Yandex images", "https://yandex.com/images/search?rpt=imageview&url={}", "image"),
        Template("Bing visual", "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:{}", "image"),
        Template("TinEye", "https://tineye.com/search?url={}", "image"),
        Template("Karma Decay", "https://karmadecay.com/search?q={}", "image"),

        // ---- aircraft / vessel ----
        Template("adsb.lol registration", "https://api.adsb.lol/v2/registration/{}", "aircraft", true),
        Template("hexdb aircraft", "https://hexdb.io/api/v1/aircraft/{}", "aircraft", true),
        Template("JetPhotos", "https://www.jetphotos.com/registration/{}", "aircraft"),
        Template("FlightAware", "https://flightaware.com/live/flight/{}", "aircraft"),
        Template("ADS-B Exchange", "https://globe.adsbexchange.com/?reg={}", "aircraft")
    )

    /** Curated templates for an observable type. */
    fun curated(type: String): List<Template> = CURATED.filter { it.type == type }

    /**
     * Every catalogue entry whose URL carries a search parameter, turned into
     * a runnable template. These open in the in-app browser already pointed
     * at the target.
     */
    fun derived(context: Context): List<Template> {
        cachedDerived?.let { return it }
        val root = OsintFramework.load(context) ?: return emptyList()
        val out = LinkedHashMap<String, Template>()
        OsintFramework.leaves(root).forEach { (name, url, path) ->
            val template = templatise(url) ?: return@forEach
            if (!out.containsKey(template)) {
                out[template] = Template(name, template, "any", false, path.substringBefore(" ›"))
            }
        }
        val list = out.values.toList()
        cachedDerived = list
        return list
    }

    @Volatile private var cachedDerived: List<Template>? = null

    /**
     * Replaces the value of a recognised search parameter with `{}`. Returns
     * null when the URL carries no query the app could fill in — a plain
     * homepage stays a plain link rather than pretending to be runnable.
     */
    private fun templatise(url: String): String? {
        val qIndex = url.indexOf('?')
        if (qIndex < 0 || qIndex == url.length - 1) return null
        val base = url.substring(0, qIndex)
        val query = url.substring(qIndex + 1)
        var replaced = false
        val rebuilt = query.split("&").joinToString("&") { part ->
            val key = part.substringBefore('=')
            if (!replaced && key.lowercase() in QUERY_KEYS) {
                replaced = true
                "$key={}"
            } else part
        }
        return if (replaced) "$base?$rebuilt" else null
    }

    /**
     * A runnable template for one directory URL, or null when that URL has no
     * query the app could fill in.
     */
    fun templateFor(url: String): Template? =
        templatise(url)?.let { Template(url.substringAfter("://").substringBefore("/"), it, "any") }

    /** Resolves a template against a target, handling coordinate pairs. */
    fun resolve(template: Template, target: String): String {
        if (template.url.contains("{LAT}")) {
            val parts = target.split(",").map { it.trim() }
            val lat = parts.getOrNull(0) ?: return template.url
            val lon = parts.getOrNull(1) ?: return template.url
            return template.url.replace("{LAT}", lat).replace("{LON}", lon)
        }
        return template.resolve(target)
    }

    /** Maps an observable to the type key used by the templates. */
    fun typeOf(observable: Observable): String = when (observable) {
        is Observable.Email -> "email"
        is Observable.Username -> "username"
        is Observable.Domain -> "domain"
        is Observable.IpAddress -> "ip"
        is Observable.PhoneNumber -> "phone"
        is Observable.Coordinate -> "coordinate"
        is Observable.Url -> "domain"
        is Observable.ImageUrl -> "image"
        is Observable.Wallet -> "wallet"
        is Observable.Aircraft -> "aircraft"
        is Observable.Name -> "name"
        is Observable.Unknown -> "any"
    }
}
