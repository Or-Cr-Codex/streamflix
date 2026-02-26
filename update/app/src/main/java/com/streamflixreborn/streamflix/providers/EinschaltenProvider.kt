package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.DoodLaExtractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.http.*

object EinschaltenProvider : Provider {

    override val name = "Einschalten"
    override val baseUrl = "https://einschalten.in"
    override val logo = "https://images2.imgbox.com/74/12/NBWU0dNi_o.png"
    override val language = "de"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(EinschaltenService::class.java)

    private interface EinschaltenService {
        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json") @GET("api/movies/{id}") suspend fun getMovie(@Path("id") id: String): ResponseBody
        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json") @GET("api/movies/{id}/watch") suspend fun getWatch(@Path("id") id: String): ResponseBody
        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json") @GET("api/genres") suspend fun getGenres(): ResponseBody
        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json") @POST("api/search") suspend fun search(@Body body: RequestBody): ResponseBody
        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json") @GET("api/movies") suspend fun getMovies(@Query("genreId") gId: Int?, @Query("pageNumber") p: Int, @Query("order") o: String = "new"): ResponseBody
    }

    private suspend fun getPoster(id: String, path: String): String = if (path.isNotBlank()) "$baseUrl/api/image/poster$path" else TmdbUtils.getMovieById(id.toIntOrNull() ?: 0, language = language)?.poster ?: ""

    private suspend fun parseMovies(arr: JSONArray): List<Movie> = (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null; val id = obj.optInt("id", 0).toString(); val t = obj.optString("title", "")
        if (id != "0" && t.isNotBlank()) Movie(id = id, title = t, poster = getPoster(id, obj.optString("posterPath", ""))) else null
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val nDef = async { try { parseMovies(JSONObject(service.getMovies(12, 1, "new").string()).optJSONArray("data") ?: JSONArray()) } catch(_:Exception) { emptyList<Movie>() } }
        val aDef = async { try { parseMovies(JSONObject(service.getMovies(null, 1, "added").string()).optJSONArray("data") ?: JSONArray()) } catch(_:Exception) { emptyList<Movie>() } }
        val categories = mutableListOf<Category>()
        nDef.await().takeIf { it.isNotEmpty() }?.let { categories.add(Category("Neue Filme", it)) }
        aDef.await().takeIf { it.isNotEmpty() }?.let { categories.add(Category("Zuletzt hinzugefügte Filme", it)) }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return try { val arr = JSONArray(service.getGenres().string()); (0 until arr.length()).mapNotNull { val obj = arr.optJSONObject(it); if (obj != null && obj.optInt("id") > 0) Genre(obj.optInt("id").toString(), obj.optString("name")) else null } } catch(_:Exception) { emptyList() }
        return try { parseMovies(JSONObject(service.search(JSONObject(mapOf("query" to query, "pageSize" to 32, "pageNumber" to page)).toString().toRequestBody("application/json".toMediaType())).string()).optJSONArray("data") ?: JSONArray()) } catch(_:Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { parseMovies(JSONObject(service.getMovies(null, page, "new").string()).optJSONArray("data") ?: JSONArray()) } catch(_:Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val json = JSONObject(service.getMovie(id).string()); val tmdbDef = async { TmdbUtils.getMovieById(id.toIntOrNull() ?: 0, language = language) }; val tmdb = tmdbDef.await()
        val path = json.optString("posterPath", ""); val gArr = json.optJSONArray("genres")
        Movie(id = id, title = json.optString("title", ""), overview = json.optString("overview").ifBlank { tmdb?.overview ?: "" }, released = json.optString("releaseDate").takeIf { it.isNotBlank() } ?: tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" }, runtime = json.optInt("runtime").takeIf { it > 0 } ?: tmdb?.runtime, rating = json.optDouble("voteAverage").takeIf { it > 0 } ?: tmdb?.rating, poster = if (path.isNotBlank()) "$baseUrl/api/image/poster$path" else tmdb?.poster ?: "", banner = tmdb?.banner, cast = tmdb?.cast ?: emptyList(), trailer = tmdb?.trailer, recommendations = tmdb?.recommendations ?: emptyList(),
            genres = if (gArr != null && gArr.length() > 0) (0 until gArr.length()).mapNotNull { i -> val obj = gArr.optJSONObject(i); if (obj != null) Genre(obj.optInt("id").toString(), obj.optString("name")) else null } else tmdb?.genres ?: emptyList())
    }

    override suspend fun getTvShow(id: String): TvShow = throw Exception("Not supported")
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = throw Exception("Not supported")

    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        val gId = id.toIntOrNull() ?: return@coroutineScope Genre(id, "")
        val nameDef = async { try { val arr = JSONArray(service.getGenres().string()); (0 until arr.length()).mapNotNull { val obj = arr.optJSONObject(it); if (obj?.optInt("id") == gId) obj.optString("name") else null }.firstOrNull() ?: "" } catch(_:Exception) { "" } }
        val moviesDef = async { try { parseMovies(JSONObject(service.getMovies(gId, page, "new").string()).optJSONArray("data") ?: JSONArray()) } catch(_:Exception) { emptyList<Movie>() } }
        Genre(id = id, name = nameDef.await(), shows = moviesDef.await())
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id, "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (videoType !is Video.Type.Movie) return emptyList()
        val url = JSONObject(service.getWatch(id).string()).optString("streamUrl", "").trim()
        return if (url.isBlank()) emptyList() else listOf(Video.Server(url, "DoodStream", url))
    }

    override suspend fun getVideo(server: Video.Server): Video = DoodLaExtractor().extract(server.src)
}