package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

// ---------------------------------------------------------------------------
// AniKoto  –  https://anikototv.to
//
// Site returned 403 to automated requests. The extension uses a spoofed
// browser-like request to try to get around basic bot detection.
//
// Observed URL patterns (from public source inspection):
//   Home     : /home
//   Anime    : /anime/<slug>
//   Watch    : /watch/<slug>/<ep-slug>
//   API      : /ajax/episode/servers?episodeId=<id>
//              /ajax/episode/sources?id=<serverId>
//   Search   : /search?keyword=<q>  (HTML scrape via Jsoup)
// ---------------------------------------------------------------------------

class AniKoto : AnimeHttpSource() {

    override val name = "AniKoto"
    override val baseUrl = "https://anikototv.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    // Spoof a real browser to work around Cloudflare-lite bot checks
    override val client = network.cloudflareClient

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$baseUrl/")
            .build()
    }

    private val htmlHeaders by lazy {
        headersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Popular / Latest  (HTML scrape)
    // ══════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/most-popular?page=$page", htmlHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimeListPage(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/recently-updated?page=$page", htmlHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimeListPage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Search  (HTML scrape)
    // ══════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.selected ?: ""
        val url = when {
            query.isNotBlank() -> "$baseUrl/search?keyword=${query.trim()}&page=$page"
            genre.isNotBlank() -> "$baseUrl/genre/${genre.lowercase().replace(" ", "-")}?page=$page"
            else -> "$baseUrl/most-popular?page=$page"
        }
        return GET(url, htmlHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimeListPage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Anime details
    // ══════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", htmlHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h2.film-name")?.text() ?: ""
            thumbnail_url = doc.selectFirst("img.film-poster-img")?.attr("src")
            description = doc.selectFirst("div.film-description .text")?.text()
            genre = doc.select("div.item-list a[href*=/genre/]").joinToString(", ") { it.text() }
            status = when (doc.selectFirst("div.item-title:contains(Status) + .name")?.text()?.lowercase()) {
                "currently airing" -> SAnime.ONGOING
                "finished airing" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Episodes
    // ══════════════════════════════════════════════════════════════════════

    override fun episodeListRequest(anime: SAnime): Request {
        // Extract the numeric id from the anime page slug (trailing digits after last hyphen)
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episode/list/$id", apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.decodeFromString<AjaxResponse>(response.body.string())
        val doc = org.jsoup.Jsoup.parse(data.html ?: return emptyList())
        return doc.select("a.ssl-item.ep-item").map { el ->
            SEpisode.create().apply {
                name = "Episode ${el.attr("data-number")}"
                episode_number = el.attr("data-number").toFloatOrNull() ?: 0f
                url = "/ajax/episode/servers?episodeId=${el.attr("data-id")}"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Video sources
    // ══════════════════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val data = json.decodeFromString<AjaxResponse>(response.body.string())
        val doc = org.jsoup.Jsoup.parse(data.html ?: return emptyList())
        val videos = mutableListOf<Video>()

        doc.select("div.server-item").forEach { serverEl ->
            val serverId = serverEl.attr("data-id")
            val serverName = serverEl.text()
            runCatching {
                val srcResp = client.newCall(
                    GET("$baseUrl/ajax/episode/sources?id=$serverId", apiHeaders)
                ).execute()
                val srcData = json.decodeFromString<SourceResponse>(srcResp.body.string())
                val link = srcData.link ?: return@runCatching
                videos.add(Video(link, serverName, link))
            }
        }
        return videos
    }

    // ══════════════════════════════════════════════════════════════════════
    // Filters
    // ══════════════════════════════════════════════════════════════════════

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf("", "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy",
            "Horror", "Isekai", "Mecha", "Mystery", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller")
    ) { val selected get() = values[state] }

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.flw-item").map { el ->
            SAnime.create().apply {
                url = el.selectFirst("a.film-poster-ahref")?.attr("href") ?: ""
                title = el.selectFirst("h3.film-name a")?.text() ?: ""
                thumbnail_url = el.selectFirst("img.film-poster-img")?.attr("data-src")
                    ?: el.selectFirst("img.film-poster-img")?.attr("src")
            }
        }
        val hasNext = doc.selectFirst("li.page-item a[title=Next]") != null
        return AnimesPage(animes, hasNext)
    }

    private fun Response.asJsoup() = org.jsoup.Jsoup.parse(body.string())

    @Serializable data class AjaxResponse(val status: Boolean?, val html: String?)
    @Serializable data class SourceResponse(val type: String?, val link: String?)
}
