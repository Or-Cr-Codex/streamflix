package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.TokenModel
import com.streamflixreborn.streamflix.models.doramasflix.VideoToken
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URL
import java.util.Locale

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    private const val apiUrl = "https://sv1.fluxcedene.net/api/"
    override val language = "es"
    override val logo = "https://doramasflix.in/img/logo.png"

    private val service = Retrofit.Builder().baseUrl(apiUrl).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(DoramasflixService::class.java)
    private val serviceHtml = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default).build().create(DoramasflixService::class.java)

    private const val accessPlatform = "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ=="
    private val LANGUAGES = mapOf("36" to "[ENG]", "37" to "[CAST]", "38" to "[LAT]", "192" to "[SUB]", "1327" to "[POR]", "13109" to "[COR]", "13110" to "[JAP]", "13111" to "[MAN]", "13112" to "[TAI]", "13113" to "[FIL]", "13114" to "[IND]", "343422" to "[VIET]")

    private interface DoramasflixService {
        @POST("gql") @Headers("accept: application/json", "platform: doramasflix", "x-access-platform: $accessPlatform") suspend fun getApiResponse(@Body body: okhttp3.RequestBody): ApiResponse
        @GET suspend fun getPage(@Url url: String): Document
        @POST @Headers("Content-Type: application/json") suspend fun postApi(@Url url: String, @Body body: okhttp3.RequestBody): VideoToken
    }

    private fun getPoster(path: String?): String = if (path?.startsWith("http") == true) path else "https://image.tmdb.org/t/p/w500$path"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val hDef = async { try { serviceHtml.getPage(baseUrl) } catch(_:Exception) { null } }
        val dDef = async { getTvShows(1) }; val mDef = async { getMovies(1) }
        val banner = hDef.await()?.select("article.styles__Article-nxyw6x-3")?.mapNotNull { el ->
            val href = el.selectFirst("div.styles__Buttons-sc-78uayx-17 a")?.attr("href") ?: return@mapNotNull null
            val t = el.selectFirst("h2.styles__Title-sc-78uayx-1")?.text() ?: ""; val p = getPoster(el.selectFirst("noscript img")?.attr("src"))
            if (href.contains("/peliculas-online/")) Movie(id = href.removePrefix("/"), title = t, banner = p)
            else TvShow(id = href.removePrefix("/"), title = t, banner = p)
        } ?: emptyList()
        listOf(Category(name = Category.FEATURED, list = banner), Category("Doramas Populares", dDef.await()), Category("Películas Populares", mDef.await()))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf(Genre("doramas", "Doramas"), Genre("peliculas", "Películas"), Genre("variedades", "Variedades"))
        val q = "{\"operationName\":\"searchAll\",\"variables\":{\"input\":\"$query\"},\"query\":\"query searchAll(\$input: String!) {\\n  searchDorama(input: \$input, limit: 32) {\\n    slug\\n    name\\n    name_es\\n    poster_path\\n    poster\\n  }\\n  searchMovie(input: \$input, limit: 32) {\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    poster\\n  }\\n}\"}"
        return try { service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.let { d ->
            (d.searchDorama?.map { TvShow("doramas-online/${it.slug}", "${it.name} (${it.nameEs ?: ""})".trim(), poster = getPoster(it.posterPath ?: it.poster)) } ?: emptyList()) +
            (d.searchMovie?.map { Movie("peliculas-online/${it.slug}", "${it.name} (${it.nameEs ?: ""})".trim(), poster = getPoster(it.posterPath ?: it.poster)) } ?: emptyList())
        } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val q = "{\"operationName\":\"listMovies\",\"variables\":{\"perPage\":20,\"sort\":\"POPULARITY_DESC\",\"filter\":{},\"page\":$page},\"query\":\"query listMovies(\$page: Int, \$perPage: Int, \$sort: SortFindManyMovieInput, \$filter: FilterFindManyMovieInput) {\\n  paginationMovie(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    items {\\n      name\\n      name_es\\n      slug\\n      poster_path\\n      poster\\n    }\\n  }\\n}\"}"
        return try { service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.paginationMovie?.items?.map { Movie("peliculas-online/${it.slug}", "${it.name} (${it.nameEs ?: ""})".trim(), poster = getPoster(it.posterPath ?: it.poster)) } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val q = "{\"operationName\":\"listDoramas\",\"variables\":{\"page\":$page,\"sort\":\"POPULARITY_DESC\",\"perPage\":20,\"filter\":{\"isTVShow\":false}},\"query\":\"query listDoramas(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    items {\\n      name\\n      name_es\\n      slug\\n      poster_path\\n      poster\\n    }\\n  }\\n}\"}"
        return try { service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.paginationDorama?.items?.map { TvShow("doramas-online/${it.slug}", "${it.name} (${it.nameEs ?: ""})".trim(), poster = getPoster(it.posterPath ?: it.poster)) } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie = serviceHtml.getPage(if (id.startsWith("http")) id else "$baseUrl/$id").let { doc ->
        val data = JsonParser.parseString(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("apolloState").entrySet().first { it.key.startsWith("Movie:") }.value.asJsonObject
        Movie(id = data.get("_id").asString, title = "${data.get("name").asString} (${data.get("name_es")?.asString ?: ""})".trim(), overview = data.get("overview")?.asString, poster = getPoster(data.get("poster_path")?.asString ?: data.get("poster")?.asString))
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = serviceHtml.getPage(if (id.startsWith("http")) id else "$baseUrl/$id")
        val data = JsonParser.parseString(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("apolloState").entrySet().first { it.key.startsWith("Dorama:") || it.key.startsWith("Movie:") }.value.asJsonObject
        val dId = data.get("_id").asString; val q = "{\"operationName\":\"listSeasons\",\"variables\":{\"serie_id\":\"$dId\"},\"query\":\"query listSeasons(\$serie_id: MongoID!) {\\n  listSeasons(sort: NUMBER_ASC, filter: {serie_id: \$serie_id}) {\\n    season_number\\n    poster_path\\n  }\\n}\"}"
        val seasons = try { service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.listSeasons?.map { Season("$dId/${it.seasonNumber}", it.seasonNumber ?: 0, "Temporada ${it.seasonNumber}", getPoster(it.posterPath)) } } catch(_:Exception) { null }
        TvShow(id = dId, title = "${data.get("name").asString} (${data.get("name_es")?.asString ?: ""})".trim(), overview = data.get("overview")?.asString, poster = getPoster(data.get("poster_path")?.asString ?: data.get("poster")?.asString), seasons = seasons ?: emptyList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val q = "{\"operationName\":\"listEpisodes\",\"variables\":{\"serie_id\":\"${seasonId.substringBefore("/")}\",\"season_number\":${seasonId.substringAfter("/").toInt()}},\"query\":\"query listEpisodes(\$season_number: Float!, \$serie_id: MongoID!) {\\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \\\"dorama\\\", serie_id: \$serie_id, season_number: \$season_number}) {\\n    name\\n    slug\\n    episode_number\\n    season_number\\n    still_path\\n  }\\n}\"}"
        return try { service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.listEpisodes?.map { Episode(it.slug ?: "", it.episodeNumber ?: 0, "Episodio ${it.episodeNumber ?: 0}: ${it.name ?: ""}".trim(), getPoster(it.stillPath)) } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val doc = serviceHtml.getPage(if (videoType is Video.Type.Movie) "$baseUrl/$id" else "$baseUrl/episodios/$id")
        val state = JsonParser.parseString(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("apolloState")
        val media = state.entrySet().first { it.key.startsWith("Episode:") || it.key.startsWith("Movie:") }.value.asJsonObject
        val links = media.getAsJsonObject("links_online")?.getAsJsonArray("json")?.mapNotNull { el -> val obj = el.asJsonObject; val u = obj.get("link")?.asString ?: return@mapNotNull null; val l = LANGUAGES[obj.get("lang")?.asString] ?: ""; Video.Server(getRealLink(u), "${URL(u).host.replace("www.", "").substringBefore(".")} $l".trim()) }
        if (!links.isNullOrEmpty()) links else state.entrySet().filter { it.key.startsWith("ROOT_QUERY.listProblems") }.map { it.value.asJsonObject.getAsJsonObject("server").getAsJsonObject("json") }.mapNotNull { el -> val u = el.get("link")?.asString ?: return@mapNotNull null; val l = LANGUAGES[el.get("lang")?.asString] ?: ""; Video.Server(getRealLink(u), "${URL(u).host.replace("www.", "").substringBefore(".")} $l".trim()) }.distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    private suspend fun getRealLink(link: String): String = if (!link.contains("fkplayer.xyz")) link else try {
        val token = Gson().fromJson(serviceHtml.getPage(link).selectFirst("script#__NEXT_DATA__")?.data() ?: "", TokenModel::class.java).props?.pageProps?.token ?: return link
        String(Base64.decode(service.postApi("https://fkplayer.xyz/api/decoding", "{\"token\":\"$token\"}".toRequestBody("application/json".toMediaType())).link, Base64.DEFAULT))
    } catch (_: Exception) { link }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = if (id == "peliculas") getMovies(page) else if (id == "variedades") try { val q = "{\"operationName\":\"listDoramas\",\"variables\":{\"page\":$page,\"sort\":\"CREATEDAT_DESC\",\"perPage\":32,\"filter\":{\"isTVShow\":true}},\"query\":\"query listDoramas(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    items {\\n      name\\n      name_es\\n      slug\\n      poster_path\\n      poster\\n    }\\n  }\\n}\"}"; service.getApiResponse(q.toRequestBody("application/json".toMediaType())).data?.paginationDorama?.items?.map { TvShow("doramas-online/${it.slug}", "${it.name} (${it.nameEs ?: ""})".trim(), poster = getPoster(it.posterPath ?: it.poster)) } ?: emptyList() } catch(_:Exception) { emptyList() } else getTvShows(page))
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}