package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.animeflv.ServerModel
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

object AnimeFlvProvider : Provider {

    override val name = "AnimeFLV"
    override val baseUrl = "https://www3.animeflv.net"
    override val language = "es"
    override val logo = "https://www3.animeflv.net/assets/animeflv/img/logo.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(AnimeFlvService::class.java)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private interface AnimeFlvService {
        @GET suspend fun getPage(@Url url: String): Document
        @GET("browse") suspend fun search(@Query("q") q: String, @Query("page") p: Int): Document
        @GET("browse") suspend fun getTvShows(@Query("order") o: String = "rating", @Query("page") p: Int): Document
        @GET("browse") suspend fun getMovies(@Query("type[]") t: String = "movie", @Query("page") p: Int): Document
        @GET("browse") suspend fun getGenre(@Query("genre[]") g: String, @Query("page") p: Int): Document
        @GET("anime/{id}") suspend fun getShowDetails(@Path("id") id: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val homeDef = async { try { service.getPage(baseUrl) } catch(_:Exception) { null } }
        val addedDef = async { try { service.getPage("$baseUrl/browse?order=added&page=1") } catch(_:Exception) { null } }
        val airingDef = async { try { service.getPage("$baseUrl/browse?status[]=1&page=1") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        
        val featured = addedDef.await()?.select("ul.ListAnimes li article")?.mapNotNull { el ->
            val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
            val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            TvShow(id = url.substringAfterLast("/"), title = el.selectFirst("a h3")?.text() ?: "", banner = p)
        }
        if (!featured.isNullOrEmpty()) categories.add(Category(name = Category.FEATURED, list = featured))

        val latestEps = homeDef.await()?.select("ul.ListEpisodios li")?.mapNotNull { el ->
            val url = el.selectFirst("a")?.attr("href")?.replace("/ver/", "/anime/")?.substringBeforeLast("-") ?: return@mapNotNull null
            val p = el.selectFirst("span.Image img")?.attr("src")?.let { "$baseUrl$it" }?.replace("thumbs", "covers")
            TvShow(id = url.substringAfterLast("/"), title = el.selectFirst("strong.Title")?.text() ?: "", poster = p)
        }?.distinctBy { it.id }
        if (!latestEps.isNullOrEmpty()) categories.add(Category(name = "Últimos Episodios", list = latestEps))

        val airing = airingDef.await()?.select("ul.ListAnimes li article")?.mapNotNull { el ->
            val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
            val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            TvShow(id = url.substringAfterLast("/"), title = el.selectFirst("a h3")?.text() ?: "", poster = p)
        }
        if (!airing.isNullOrEmpty()) categories.add(Category(name = "Animes en Emisión", list = airing))
        
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "artes-marciales", "aventura", "carreras", "ciencia-ficcion", "comedia", "demencia", "demonios", "deportes", "drama", "ecchi", "escolares", "espacial", "fantasia", "harem", "historico", "infantil", "josei", "juegos", "magia", "mecha", "militar", "misterio", "musica", "parodia", "policia", "psicologico", "recuentos-de-la-vida", "romance", "samurai", "seinen", "shoujo", "shounen", "sobrenatural", "superpoderes", "suspenso", "terror", "vampiros", "yaoi", "yuri").map { Genre(id = it, name = it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        if (page > 1) return emptyList()
        return try { service.search(query, page).select("ul.ListAnimes li article").mapNotNull { el ->
            val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null; val id = url.substringAfterLast("/")
            val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            if (el.selectFirst("span.Type")?.text() == "Película") Movie(id = id, title = el.selectFirst("a h3")?.text() ?: "", poster = p)
            else TvShow(id = id, title = el.selectFirst("a h3")?.text() ?: "", poster = p)
        } } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = try { service.getTvShows(p = page).select("ul.ListAnimes li article").mapNotNull { el ->
        val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
        val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
        TvShow(id = url.substringAfterLast("/"), title = el.selectFirst("a h3")?.text() ?: "", poster = p)
    } } catch (_: Exception) { emptyList() }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getMovies(p = page).select("ul.ListAnimes li article").mapNotNull { el ->
        val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
        val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
        Movie(id = url.substringAfterLast("/"), title = el.selectFirst("a h3")?.text() ?: "", poster = p)
    } } catch (_: Exception) { emptyList() }

    override suspend fun getTvShow(id: String): TvShow = try {
        val doc = service.getShowDetails(id); val script = doc.select("script").find { it.data().contains("var episodes =") }?.data() ?: ""
        val info = Regex("""var\s+anime_info\s*=\s*(\[[^\]]+])""").find(script)?.groupValues?.get(1)?.let { json.decodeFromString<List<String>>(it) }
        val aId = info?.getOrNull(0) ?: ""; val aUri = info?.getOrNull(2) ?: ""
        val eps = script.substringAfter("var episodes = [").substringBefore("];").split("],[").mapNotNull {
            val num = it.replace("[", "").replace("]", "").split(",")[0]; if (num.isBlank() || aId.isBlank()) null
            else Episode(id = "ver/$aUri-$num", number = num.toIntOrNull() ?: 0, title = "Episodio $num", poster = "https://cdn.animeflv.net/screenshots/$aId/$num/th_3.jpg")
        }.reversed()
        TvShow(id = id, title = doc.selectFirst("div.Ficha.fchlt div.Container .Title")?.text() ?: "", overview = doc.selectFirst("div.Description")?.text()?.removeSurrounding("\""), poster = doc.selectFirst("div.AnimeCover div.Image figure img")?.attr("src")?.let { "$baseUrl$it" }, rating = doc.selectFirst("span.vtprmd#votes_prmd")?.text()?.toDoubleOrNull(), genres = doc.select("nav.Nvgnrs a").map { Genre(id = it.attr("href").substringAfterLast("/"), name = it.text()) }, seasons = listOf(Season(id = id, number = 1, title = "Episodios", episodes = eps)))
    } catch (_: Exception) { TvShow(id = id, title = "Error") }

    override suspend fun getMovie(id: String): Movie = getTvShow(id).let { Movie(id = it.id, title = it.title, overview = it.overview, poster = it.poster, rating = it.rating, genres = it.genres) }
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = getTvShow(seasonId).seasons.firstOrNull()?.episodes ?: emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val shows = service.getGenre(id, page).select("ul.ListAnimes li article").mapNotNull { el ->
            val url = el.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null; val sId = url.substringAfterLast("/")
            val p = el.selectFirst("a div.Image figure img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            if (el.selectFirst("span.Type")?.text() == "Película") Movie(id = sId, title = el.selectFirst("a h3")?.text() ?: "", poster = p)
            else TvShow(id = sId, title = el.selectFirst("a h3")?.text() ?: "", poster = p)
        }
        Genre(id = id, name = id.replace("-", " ").replaceFirstChar { it.uppercase() }, shows = shows)
    } catch (_: Exception) { Genre(id = id, name = id, shows = emptyList()) }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val url = if (videoType is Video.Type.Movie) {
            val script = service.getShowDetails(id).selectFirst("script:containsData(var episodes =)")?.data() ?: ""; val info = Regex("""var\s+anime_info\s*=\s*(\[[^\]]+])""").find(script)?.groupValues?.get(1)
            "$baseUrl/ver/${info?.let { json.decodeFromString<List<String>>(it).getOrNull(2) } ?: ""}-1"
        } else "$baseUrl/$id"
        val script = service.getPage(url).selectFirst("script:containsData(var videos = {)")?.data() ?: ""; val jsonString = script.substringAfter("var videos =").substringBefore(";").trim()
        json.decodeFromString<ServerModel>(jsonString).sub.mapNotNull { if (it.code.isNotBlank()) Video.Server(id = it.code, name = it.title ?: "Server") else null }
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
}