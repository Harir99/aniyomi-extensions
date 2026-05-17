package eu.kanade.tachiyomi.animeextension.en.allmanga

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.json.JsonConfiguration

// ---------------------------------------------------------------------------
// AllManga (Anime)  –  https://allmanga.to/anime
//
// AllManga runs a GraphQL API at /api. All queries go through POST.
// Observed operations:
//   searchAnimeQuery   – search + browse with variables { query, genres, page, size }
//   animeInfoQuery     – full details by _id
//   episodesByAnimeId  – episodes list
//   sourcesByCDN       – video URLs per episode + CDN
// ---------------------------------------------------------------------------

class AllManga : AnimeHttpSource() {

    override val name = "AllManga"
    override val baseUrl = "https://allmanga.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val apiUrl = "$baseUrl/api"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/anime")
            .build()
    }

    private fun gqlBody(query: String, variables: String): okhttp3.RequestBody =
        """{"query":$query,"variables":$variables}""".toRequestBody(jsonMediaType)

    // ══════════════════════════════════════════════════════════════════════
    // Popular / Latest
    // ══════════════════════════════════════════════════════════════════════

    private val popularQuery = """"query(${'$'}page:Int,${'$'}size:Int){queryPopularAnime(page:${'$'}page,size:${'$'}size){edges{_id,name,thumbnail,availableEpisodes{sub,dub}},pageInfo{hasNextPage}}}""""

    override fun popularAnimeRequest(page: Int): Request = POST(
        apiUrl,
        apiHeaders,
        gqlBody(popularQuery, """{"page":$page,"size":20}""")
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<GqlWrapper<PopularData>>(response.body.string())
        val edges = data.data?.queryPopularAnime?.edges ?: emptyList()
        return AnimesPage(edges.map { it.toSAnime() }, data.data?.queryPopularAnime?.pageInfo?.hasNextPage ?: false)
    }

    private val latestQuery = """"query(${'$'}page:Int,${'$'}size:Int){queryRecentlyUpdated(page:${'$'}page,size:${'$'}size){edges{_id,name,thumbnail,availableEpisodes{sub,dub}},pageInfo{hasNextPage}}}""""

    override fun latestUpdatesRequest(page: Int): Request = POST(
        apiUrl,
        apiHeaders,
        gqlBody(latestQuery, """{"page":$page,"size":20}""")
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ══════════════════════════════════════════════════════════════════════
    // Search
    // ══════════════════════════════════════════════════════════════════════

    private val searchQuery = """"query(${'$'}search:SearchInput,${'$'}limit:Int,${'$'}page:Int){queryListAnime(search:${'$'}search,limit:${'$'}limit,page:${'$'}page){edges{_id,name,thumbnail,availableEpisodes{sub,dub}},pageInfo{hasNextPage}}}""""

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.selected ?: ""
        val searchObj = buildString {
            append("{")
            if (query.isNotBlank()) append("\"query\":\"${query.trim()}\",")
            if (genre.isNotBlank()) append("\"genres\":[\"$genre\"],")
            append("\"allowAdult\":false,\"allowUnknown\":false}")
        }
        return POST(apiUrl, apiHeaders, gqlBody(searchQuery, """{"search":$searchObj,"limit":20,"page":$page}"""))
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ══════════════════════════════════════════════════════════════════════
    // Anime details  (url = /anime/<_id>)
    // ══════════════════════════════════════════════════════════════════════

    private val infoQuery = """"query(${'$'}id:String!){queryAnime(_id:${'$'}id){_id,name,thumbnail,description,genres,status,availableEpisodes{sub,dub}}}""""

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.removePrefix("/anime/")
        return POST(apiUrl, apiHeaders, gqlBody(infoQuery, """{"id":"$id"}"""))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = json.decodeFromString<GqlWrapper<AnimeInfoData>>(response.body.string())
        val a = data.data?.queryAnime ?: return SAnime.create()
        return SAnime.create().apply {
            url = "/anime/${a._id}"
            title = a.name
            thumbnail_url = a.thumbnail
            description = a.description
            genre = a.genres?.joinToString(", ")
            status = when (a.status?.lowercase()) {
                "releasing" -> SAnime.ONGOING
                "finished" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Episodes
    // ══════════════════════════════════════════════════════════════════════

    private val episodesQuery = """"query(${'$'}animeId:String!,${'$'}type:VaildAnimeTranslationType!){queryEpisodesByAnimeId(animeId:${'$'}animeId,translationType:${'$'}type){episodeIdNum,episodeString,notes}}""""

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.removePrefix("/anime/")
        // Fetch both sub and dub in one call by defaulting to sub; dub handled in video list
        return POST(apiUrl, apiHeaders, gqlBody(episodesQuery, """{"animeId":"$id","type":"sub"}"""))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.decodeFromString<GqlWrapper<EpisodesData>>(response.body.string())
        val animeId = extractAnimeId(response)
        return (data.data?.queryEpisodesByAnimeId ?: emptyList()).map { ep ->
            SEpisode.create().apply {
                name = "Episode ${ep.episodeString ?: ep.episodeIdNum}"
                episode_number = ep.episodeIdNum.toFloat()
                url = "/ep/$animeId/${ep.episodeIdNum}"
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun extractAnimeId(response: Response): String =
        // The animeId is in the request body; we re-parse it simply
        response.request.body?.let {
            val buf = okio.Buffer(); it.writeTo(buf)
            buf.readUtf8().substringAfter("\"animeId\":\"").substringBefore("\"")
        } ?: ""

    // ══════════════════════════════════════════════════════════════════════
    // Video sources
    // ══════════════════════════════════════════════════════════════════════

    private val sourcesQuery = """"query(${'$'}animeId:String!,${'$'}episodeId:String!,${'$'}type:VaildAnimeTranslationType!){sourcesByCDNPriority(animeId:${'$'}animeId,episodeId:${'$'}episodeId,translationType:${'$'}type,serverName:\"Sak\"){sourceUrl,sourceName,priority}}""""

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.removePrefix("/ep/").split("/")
        val animeId = parts[0]; val epId = parts[1]
        return POST(apiUrl, apiHeaders, gqlBody(sourcesQuery, """{"animeId":"$animeId","episodeId":"$epId","type":"sub"}"""))
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = json.decodeFromString<GqlWrapper<SourcesData>>(response.body.string())
        val parts = response.request.body?.let {
            val buf = okio.Buffer(); it.writeTo(buf); buf.readUtf8()
        } ?: ""
        val animeId = parts.substringAfter("\"animeId\":\"").substringBefore("\"")
        val epId = parts.substringAfter("\"episodeId\":\"").substringBefore("\"")

        val videos = mutableListOf<Video>()
        (data.data?.sourcesByCDNPriority ?: emptyList()).forEach { src ->
            val url = src.sourceUrl ?: return@forEach
            // AllMmanga wraps links in a ?link= redirect; unwrap it
            val realUrl = if (url.contains("?link=")) url.substringAfter("?link=") else url
            videos.add(Video(realUrl, "${src.sourceName ?: "Server"} - sub", realUrl))
        }

        // Also try dub
        runCatching {
            val dubResp = client.newCall(
                POST(apiUrl, apiHeaders, gqlBody(sourcesQuery.replace("\"sub\"", "\"dub\""), """{"animeId":"$animeId","episodeId":"$epId","type":"dub"}"""))
            ).execute()
            val dubData = json.decodeFromString<GqlWrapper<SourcesData>>(dubResp.body.string())
            (dubData.data?.sourcesByCDNPriority ?: emptyList()).forEach { src ->
                val url = src.sourceUrl ?: return@forEach
                val realUrl = if (url.contains("?link=")) url.substringAfter("?link=") else url
                videos.add(Video(realUrl, "${src.sourceName ?: "Server"} - dub", realUrl))
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
            "Isekai", "Mecha", "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports",
            "Supernatural", "Thriller")
    ) { val selected get() = values[state] }

    override fun getFilterList() = AnimeFilterList(GenreFilter())

    // ══════════════════════════════════════════════════════════════════════
    // Helpers / DTOs
    // ══════════════════════════════════════════════════════════════════════

    private fun AnimeEdgeDto.toSAnime() = SAnime.create().apply {
        url = "/anime/$_id"
        title = name
        thumbnail_url = thumbnail
    }

    @Serializable data class GqlWrapper<T>(val data: T?)
    @Serializable data class PopularData(
        val queryPopularAnime: AnimeListResult?,
        val queryRecentlyUpdated: AnimeListResult?,
        val queryListAnime: AnimeListResult?
    )
    @Serializable data class AnimeListResult(val edges: List<AnimeEdgeDto>, val pageInfo: PageInfoDto?)
    @Serializable data class AnimeEdgeDto(val _id: String, val name: String, val thumbnail: String?, val availableEpisodes: AvailableEpsDto?)
    @Serializable data class AvailableEpsDto(val sub: Int?, val dub: Int?)
    @Serializable data class PageInfoDto(val hasNextPage: Boolean?)
    @Serializable data class AnimeInfoData(val queryAnime: AnimeInfoDto?)
    @Serializable data class AnimeInfoDto(val _id: String, val name: String, val thumbnail: String?, val description: String?, val genres: List<String>?, val status: String?)
    @Serializable data class EpisodesData(val queryEpisodesByAnimeId: List<EpisodeDto>?)
    @Serializable data class EpisodeDto(val episodeIdNum: Float, val episodeString: String?, val notes: String?)
    @Serializable data class SourcesData(val sourcesByCDNPriority: List<SourceItemDto>?)
    @Serializable data class SourceItemDto(val sourceUrl: String?, val sourceName: String?, val priority: Int?)
}
