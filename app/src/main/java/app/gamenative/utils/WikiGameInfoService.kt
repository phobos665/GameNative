package app.gamenative.utils

import app.gamenative.data.GameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.roundToInt


// Service for fetching and parsing game scores from PCGamingWiki & HLTB 
object WikiGameInfoService {

    internal var pcgwApi = "https://www.pcgamingwiki.com/w/api.php"
    internal var hltbBase = "https://howlongtobeat.com"

    private val metacriticRegex = Regex("""game/row/reception\|Metacritic\|(\S+)\|([0-9]+)""")
    private val opencriticRegex = Regex("""game/row/reception\|OpenCritic\|(\S+)\|([0-9]+)""")
    private val hltbIdRegex = Regex("""hltb\s+=\s+([0-9]+)""")

    // User Agent for HLTB
    private const val HLTB_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


    data class ScoreInfo(val score: Short, val urlSlug: String)
    data class HltbTimes(val mainStory: Float, val mainExtra: Float, val completionist: Float)
    data class WikiInfo(
        val metacritic: ScoreInfo?,
        val opencritic: ScoreInfo?,
        val hltb: HltbTimes?,
    )

    // Get for pcgamingwiki the page and then parse the wiki text.
    suspend fun fetchAndStore(
        gameSource: GameSource,
        title: String,
        storeId: String?,
        onResult: suspend (WikiInfo) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val pageId = getPageId(gameSource, title, storeId ?: "")
            if (pageId == null) {
                Timber.d("WikiGameInfoService: no PCGamingWiki page found for '$title'")
                return@withContext
            }

            val wikitext = getWikiText(pageId)
            if (wikitext == null) {
                Timber.d("WikiGameInfoService: no wikitext for pageId $pageId")
                return@withContext
            }

            val metacritic = extractScore(wikitext, metacriticRegex)
            val opencritic = extractScore(wikitext, opencriticRegex)
            val hltbId = extractHltbId(wikitext)
            val hltb = hltbId?.let { fetchHltbTimes(it) }

            if (metacritic == null && opencritic == null && hltb == null) {
                Timber.d("WikiGameInfoService: no usable data found for '$title'")
                return@withContext
            }

            onResult(WikiInfo(metacritic, opencritic, hltb))
            Timber.d(
                "WikiGameInfoService: stored data for '$title' — " +
                    "MC=${metacritic?.score} OC=${opencritic?.score} " +
                    "HLTB=${hltb?.mainStory}/${hltb?.mainExtra}/${hltb?.completionist}",
            )
        } catch (e: Exception) {
            Timber.e(e, "WikiGameInfoService: error fetching wiki info for '$title'")
        }
    }

    private suspend fun getPageId(gameSource: GameSource, title: String, storeId: String): String? {
        // Steam and GOG: try cargo query by their respective ID fields first
        if (gameSource == GameSource.STEAM) {
            cargoQuery("Steam_AppID", storeId)?.let { return it }
        }
        if (gameSource == GameSource.GOG) {
            cargoQuery("GOGcom_ID", storeId)?.let { return it }
        }

        // PCGamingWiki mostly uses ":" instead of " -" for subtitles
        return titleSearch(title.replace(" -", ":"))
    }

    private suspend fun cargoQuery(field: String, id: String): String? {
        val url = "$pcgwApi?action=cargoquery&tables=Infobox_game" +
            "&fields=Infobox_game._pageID%3DpageID" +
            "&where=Infobox_game.${field}%20HOLDS%20${id}" +
            "&format=json"
        val json = getJson(url) ?: return null
        return json.optJSONArray("cargoquery")
            ?.optJSONObject(0)
            ?.optJSONObject("title")
            ?.optString("pageID")
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun titleSearch(title: String): String? {
        val encoded = title.trim().replace(" ", "%20")
        val url = "$pcgwApi?action=query&list=search&srsearch=$encoded&format=json"
        val json = getJson(url) ?: return null
        val pageId = json.optJSONObject("query")
            ?.optJSONArray("search")
            ?.optJSONObject(0)
            ?.optInt("pageid", -1)
            ?: return null
        return if (pageId > 0) pageId.toString() else null
    }

    private suspend fun getWikiText(pageId: String): String? {
        val url = "$pcgwApi?action=parse&format=json&pageid=$pageId&redirects=true&prop=wikitext"
        val json = getJson(url) ?: return null
        return json.optJSONObject("parse")
            ?.optJSONObject("wikitext")
            ?.optString("*")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractScore(wikitext: String, regex: Regex): ScoreInfo? {
        val match = regex.find(wikitext) ?: return null
        val score = match.groupValues[2].toShortOrNull() ?: return null
        val slug = match.groupValues[1].takeIf { it.isNotEmpty() } ?: return null
        return ScoreInfo(score, slug)
    }

    private fun extractHltbId(wikitext: String): String? =
        hltbIdRegex.find(wikitext)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }


    private suspend fun fetchHltbTimes(hltbId: String): HltbTimes? {
        val request = Request.Builder()
            .url("$hltbBase/game/$hltbId")
            .header("User-Agent", HLTB_UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", hltbBase)
            .build()

        val html = try {
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }
        } catch (e: Exception) {
            Timber.e(e, "WikiGameInfoService: failed to fetch HLTB page for id=$hltbId")
            return null
        }

        
        val scriptJson = Regex(
            """<script id="__NEXT_DATA__" type="application/json">([\s\S]*?)</script>""",
        ).find(html)?.groupValues?.get(1) ?: return null

        return try {
            val gameData = JSONObject(scriptJson)
                .getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONObject("game")
                .getJSONObject("data")
                .getJSONArray("game")
                .optJSONObject(0) ?: return null

            // Values are in seconds; convert to hours with 1 decimal
            HltbTimes(
                mainStory = secondsToHours(gameData.optLong("comp_main")),
                mainExtra = secondsToHours(gameData.optLong("comp_plus")),
                completionist = secondsToHours(gameData.optLong("comp_100")),
            )
        } catch (e: Exception) {
            Timber.e(e, "WikiGameInfoService: failed to parse HLTB data for id=$hltbId")
            null
        }
    }

    // Converts to hours with 1 decimal place.
    private fun secondsToHours(seconds: Long): Float {
        if (seconds <= 0L) return 0f
        return ((seconds / 3600f) * 10).roundToInt() / 10f
    }

    private suspend fun getJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).build()
        return try {
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            Timber.e(e, "WikiGameInfoService: HTTP request failed for $url")
            null
        }
    }
}
