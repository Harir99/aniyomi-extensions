package eu.kanade.tachiyomi.animeextension.en.anizone

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
import kotlinx.serialization.json.JsonConfiguration

// ---------------------------------------------------------------------------
// AniZone  –  https://anizone.to
//
// Site returned 403 to automated fetch; cloudflareClient used.
//
// Observed URL patterns (standard Zoro/HiAnime clone):
//   Popular  : /most-popular?page=<N>
//   Latest   : /recently-updated?page=<N>
//   Search   : /search?keyword=<q>&page=<N>
//   Anime    : /anime/<slug>-<id>
//   Ajax eps : /ajax/episode/list/<id>
//   Ajax srv : /ajax/episode/servers?episodeId=<id>
//   Ajax src : /ajax/episode/sources?id=<serverId>
// ---------------------------------------------------------------------------

class AniZone : AnimeHttpSource() {

    override val name = "AniZone"
    override val baseUrl = "https://anizone.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json = Json { ignoreUnknownKeys = true }

    override val client = network.cloudflareClient

    private val htmlHeaders by lazy {
        headersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
            .add("Referer", "$baseUrl/")
            .build()
    }

    private val ajaxHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Popular / Latest
    // ══════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/most-popular?page=$page", htmlHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseListPage(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/recently-updated?page=$page", htmlHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseListPage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Search
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

    override fun searchAnimeParse(response: Response): AnimesPage = parseListPage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Anime details
    // ══════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", htmlHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h2.film-name, .film-name")?.text() ?: ""
            thumbnail_url = doc.selectFirst("img.film-poster-img, .film-poster img")?.attr("src")
            description = doc.selectFirst(".film-description .text, #synopsis")?.text()
            genre = doc.select("a[href*=/genre/]").joinToString(", ") { it.text() }
            status = when (
                doc.selectFirst(".item-title:contains(Status) + .name, .film-stats .status")
                    ?.text()?.lowercase()
            ) {
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
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episode/list/$id", ajaxHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val wrapper = json.decodeFromString<AjaxHtml>(response.body.string())
        val doc = org.jsoup.Jsoup.parse(wrapper.html ?: return emptyList())
        return doc.select("a.ssl-item.ep-item").map { el ->
            SEpisode.create().apply {
                name = el.attr("title").ifBlank { "Episode ${el.attr("data-number")}" }
                episode_number = el.attr("data-number").toFloatOrNull() ?: 0f
                url = "/ajax/episode/servers?episodeId=${el.attr("data-id")}"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Video sources
    // ══════════════════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", ajaxHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val wrapper = json.decodeFromString<AjaxHtml>(response.body.string())
        val doc = org.jsoup.Jsoup.parse(wrapper.html ?: return emptyList())
        val videos = mutableListOf<Video>()

        doc.select("div.server-item[data-id], div[data-id]").forEach { el ->
            val id = el.attr("data-id")
            val label = el.text().trim().ifBlank { "Server $id" }
            runCatching {
                val r = client.newCall(GET("$baseUrl/ajax/episode/sources?id=$id", ajaxHeaders)).execute()
                val src = json.decodeFromString<AjaxSource>(r.body.string())
                src.link?.let { videos.add(Video(it, label, it)) }
            }
        }
        return videos
    }

    // ══════════════════════════════════════════════════════════════════════
    // Filters
    // ══════════════════════════════════════════════════════════════════════

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf("", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Isekai", "Mecha", "Mystery", "Romance", "Sci-Fi", "Slice of Life",
            "Sports", "Supernatural", "Thriller")
    ) { val selected get() = values[state] }

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun parseListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.flw-item").map { el ->
            SAnime.create().apply {
                url = el.selectFirst("a.film-poster-ahref, h3 a, h2 a")?.attr("href") ?: ""
                title = el.selectFirst("h3.film-name a, h2.film-name a, .film-name a")?.text() ?: ""
                thumbnail_url = el.selectFirst("img")?.run {
                    attr("data-src").ifBlank { attr("src") }
                }
            }
        }
        val hasNext = doc.selectFirst("li.page-item a[title=Next], .next-page") != null
        return AnimesPage(animes, hasNext)
    }

    private fun Response.asJsoup() = org.jsoup.Jsoup.parse(body.string())

    @Serializable data class AjaxHtml(val status: Boolean?, val html: String?)
    @Serializable data class AjaxSource(val type: String?, val link: String?)
}
