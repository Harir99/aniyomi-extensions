package eu.kanade.tachiyomi.animeextension.en.reanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.json.JsonConfiguration

// ---------------------------------------------------------------------------
// ReAnime  –  https://reanime.to
//
// URL patterns observed:
//   Anime detail : /anime/<slug>-<id>
//   Watch page   : /watch/<slug>-<id>?ep=<N>
//   Search API   : /api/anime/search?q=<query>&page=<N>
//   Episodes API : /api/anime/<id>/episodes
//   Sources API  : /api/episode/<id>/sources   (returns server list)
//   Stream API   : /api/episode/<id>/stream?server=<name>
// ---------------------------------------------------------------------------

class ReAnime : AnimeHttpSource() {

    override val name = "ReAnime"
    override val baseUrl = "https://reanime.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json = Json { ignoreUnknownKeys = true }

    // ── Headers ─────────────────────────────────────────────────────────────

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Popular / Latest
    // ══════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/anime/popular?page=$page", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/anime/recent?page=$page", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Search
    // ══════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/api/anime/search?q=${query.trim()}&page=$page"
        } else {
            val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.selected ?: ""
            "$baseUrl/api/anime/search?genre=$genre&page=$page"
        }
        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ══════════════════════════════════════════════════════════════════════
    // Anime details
    // ══════════════════════════════════════════════════════════════════════

    // animeUrl stored as /anime/<slug>-<id>
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/api${anime.url}", apiHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val data = json.decodeFromString<AnimeDetailDto>(response.body.string())
        return SAnime.create().apply {
            title = data.title
            thumbnail_url = data.cover
            description = data.description
            genre = data.genres?.joinToString(", ")
            status = when (data.status?.lowercase()) {
                "releasing" -> SAnime.ONGOING
                "finished" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Episodes
    // ══════════════════════════════════════════════════════════════════════

    // We extract the numeric ID from the slug (last hyphen-separated token)
    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/api/anime/$id/episodes", apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val list = json.decodeFromString<EpisodeListDto>(response.body.string())
        return list.episodes.map { ep ->
            SEpisode.create().apply {
                name = ep.title ?: "Episode ${ep.number}"
                episode_number = ep.number.toFloat()
                url = "/episode/${ep.id}"
                date_upload = ep.airedAt ?: 0L
            }
        }.sortedByDescending { it.episode_number }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Video sources
    // ══════════════════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/api${episode.url}/sources", apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val sources = json.decodeFromString<SourceListDto>(response.body.string())
        val videos = mutableListOf<Video>()
        sources.servers.forEach { server ->
            runCatching {
                val streamResp = client.newCall(
                    GET("$baseUrl/api${response.request.url.encodedPath.removeSuffix("/sources")}/stream?server=${server.name}", apiHeaders)
                ).execute()
                val stream = json.decodeFromString<StreamDto>(streamResp.body.string())
                stream.sources.forEach { src ->
                    videos.add(Video(src.url, "${server.name} - ${src.quality ?: "default"}", src.url))
                }
            }
        }
        return videos.ifEmpty {
            // fallback: return a placeholder so the user at least sees a server list
            sources.servers.map { Video("$baseUrl/watch", it.name, "$baseUrl/watch") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Filters
    // ══════════════════════════════════════════════════════════════════════

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf("", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")
    ) {
        val selected get() = values[state]
    }

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun parseAnimePage(response: Response): AnimesPage {
        val page = json.decodeFromString<AnimePageDto>(response.body.string())
        val animes = page.results.map { it.toSAnime() }
        return AnimesPage(animes, page.hasNextPage ?: false)
    }

    private fun AnimeItemDto.toSAnime() = SAnime.create().apply {
        url = "/anime/$slug"
        title = this@toSAnime.title
        thumbnail_url = cover
    }

    // ══════════════════════════════════════════════════════════════════════
    // DTOs
    // ══════════════════════════════════════════════════════════════════════

    @Serializable data class AnimePageDto(val results: List<AnimeItemDto>, val hasNextPage: Boolean?)
    @Serializable data class AnimeItemDto(val slug: String, val title: String, val cover: String?)
    @Serializable data class AnimeDetailDto(
        val title: String, val cover: String?, val description: String?,
        val genres: List<String>?, val status: String?
    )
    @Serializable data class EpisodeListDto(val episodes: List<EpisodeDto>)
    @Serializable data class EpisodeDto(val id: String, val number: Int, val title: String?, val airedAt: Long?)
    @Serializable data class SourceListDto(val servers: List<ServerDto>)
    @Serializable data class ServerDto(val name: String)
    @Serializable data class StreamDto(val sources: List<StreamSourceDto>)
    @Serializable data class StreamSourceDto(val url: String, val quality: String?)
}
