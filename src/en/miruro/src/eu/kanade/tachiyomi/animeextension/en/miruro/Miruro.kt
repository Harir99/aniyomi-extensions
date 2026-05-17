package eu.kanade.tachiyomi.animeextension.en.miruro

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.json.JsonConfiguration

// ---------------------------------------------------------------------------
// Miruro  –  https://www.miruro.to
//
// Miruro is AniList-ID based. Public Consumet/AniSkip-style API observed:
//   Search  : /api/v2/hianime/search?query=<q>&page=<N>
//             (Miruro proxies HiAnime data via a Consumet-compatible API)
//   Info    : /api/v2/hianime/anime/<anilistId>
//   Episodes: /api/v2/hianime/anime/<anilistId>/episodes
//   Sources : /api/v2/hianime/episode/sources?animeEpisodeId=<epId>&server=<srv>&category=sub
//
// URL scheme on the website:
//   Info page : /info/<anilistId>/<slug>
//   Watch page: /watch/<anilistId>/<slug>
// ---------------------------------------------------------------------------

class Miruro : AnimeHttpSource() {

    override val name = "Miruro"
    override val baseUrl = "https://www.miruro.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiBase = "$baseUrl/api/v2/hianime"

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Popular / Latest
    // ══════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiBase/home", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<HomeDto>(response.body.string())
        val animes = (data.trendingAnimes ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiBase/home", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = json.decodeFromString<HomeDto>(response.body.string())
        val animes = (data.latestEpisodeAnimes ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Search
    // ══════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiBase/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
        (filters.find { it is GenreFilter } as? GenreFilter)?.selected?.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("genres", it) }
        return GET(url.build(), apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<SearchResultDto>(response.body.string())
        val animes = (data.animes ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, data.hasNextPage ?: false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Anime details  (url = /info/<id>/<slug>)
    // ══════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.removePrefix("/info/").substringBefore("/")
        return GET("$apiBase/anime/$id", apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val d = json.decodeFromString<AnimeInfoDto>(response.body.string())
        val a = d.anime?.info ?: return SAnime.create()
        return SAnime.create().apply {
            title = a.name
            thumbnail_url = a.poster
            description = a.description
            genre = a.genres?.joinToString(", ")
            status = when (a.stats?.status?.lowercase()) {
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
        val id = anime.url.removePrefix("/info/").substringBefore("/")
        return GET("$apiBase/anime/$id/episodes", apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.decodeFromString<EpisodeListDto>(response.body.string())
        return (data.episodes ?: emptyList()).map { ep ->
            SEpisode.create().apply {
                name = ep.title ?: "Episode ${ep.number}"
                episode_number = ep.number?.toFloat() ?: 0f
                url = "/ep/${ep.episodeId}"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Video sources
    // ══════════════════════════════════════════════════════════════════════

    private val servers = listOf("hd-1", "hd-2", "megacloud")

    override fun videoListRequest(episode: SEpisode): Request {
        val epId = episode.url.removePrefix("/ep/")
        return GET(
            "$apiBase/episode/sources?animeEpisodeId=$epId&server=hd-1&category=sub",
            apiHeaders
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val epId = response.request.url.queryParameter("animeEpisodeId") ?: return emptyList()
        val videos = mutableListOf<Video>()

        for (server in servers) {
            for (category in listOf("sub", "dub")) {
                runCatching {
                    val r = client.newCall(
                        GET("$apiBase/episode/sources?animeEpisodeId=$epId&server=$server&category=$category", apiHeaders)
                    ).execute()
                    val data = json.decodeFromString<SourceDto>(r.body.string())
                    data.sources.forEach { src ->
                        videos.add(Video(src.url, "$server - $category - ${src.quality ?: "auto"}", src.url))
                    }
                }
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
            "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural")
    ) {
        val selected get() = values[state]
    }

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun AnimeItemDto.toSAnime() = SAnime.create().apply {
        // Build a URL that encodes the id so we can retrieve it later
        url = "/info/$id/${name?.replace(" ", "-")?.lowercase() ?: id}"
        title = name ?: "Unknown"
        thumbnail_url = poster
    }

    // ══════════════════════════════════════════════════════════════════════
    // DTOs
    // ══════════════════════════════════════════════════════════════════════

    @Serializable data class HomeDto(
        val trendingAnimes: List<AnimeItemDto>?,
        val latestEpisodeAnimes: List<AnimeItemDto>?
    )
    @Serializable data class SearchResultDto(val animes: List<AnimeItemDto>?, val hasNextPage: Boolean?)
    @Serializable data class AnimeItemDto(val id: String, val name: String?, val poster: String?)
    @Serializable data class AnimeInfoDto(val anime: AnimeWrapperDto?)
    @Serializable data class AnimeWrapperDto(val info: AnimeInfoInnerDto?)
    @Serializable data class AnimeInfoInnerDto(
        val name: String, val poster: String?, val description: String?,
        val genres: List<String>?, val stats: AnimeStatsDto?
    )
    @Serializable data class AnimeStatsDto(val status: String?)
    @Serializable data class EpisodeListDto(val episodes: List<EpisodeDto>?)
    @Serializable data class EpisodeDto(val episodeId: String, val number: Int?, val title: String?)
    @Serializable data class SourceDto(val sources: List<VideoSourceDto>)
    @Serializable data class VideoSourceDto(val url: String, val quality: String?)
}
