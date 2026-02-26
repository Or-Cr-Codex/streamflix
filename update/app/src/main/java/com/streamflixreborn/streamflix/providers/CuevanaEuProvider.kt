package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.cuevanaeu.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

object CuevanaEuProvider : Provider {

    override val name = "Cuevana 3"
    override val baseUrl = "https://www.cuevana3.eu"
    override val language = "es"
    override val logo: String get() = "$baseUrl/favicon.ico"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(CuevanaEuService::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    private interface CuevanaEuService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val mDef = async { try { service.getPage("$baseUrl/peliculas/estrenos/page/1") } catch(_:Exception) { null } }
        val mwDef = async { try { service.getPage("$baseUrl/peliculas/tendencias/semana") } catch(_:Exception) { null } }
        val swDef = async { try { service.getPage("$baseUrl/series/tendencias/semana") } catch(_:Exception) { null } }
        val sdDef = async { try { service.getPage("$baseUrl/series/tendencias/dia") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        
        mDef.await()?.let { doc ->
            val res = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }
            val featured = res?.props?.pageProps?.movies?.map { TvShow(id = "ver-pelicula/${it.slug?.name}", title = it.titles?.name ?: "Sin Título", banner = it.images?.backdrop?.let { b -> if (b.startsWith("http")) b else "$baseUrl$b" }) }?.filter { it.id != "ver-pelicula/null" }
            if (!featured.isNullOrEmpty()) categories.add(Category(Category.FEATURED, featured))
            
            val estrenos = res?.props?.pageProps?.movies?.map { Movie(id = "ver-pelicula/${it.slug?.name}", title = it.titles?.name ?: "", poster = it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) }?.filter { it.id != "ver-pelicula/null" }
            if (!estrenos.isNullOrEmpty()) categories.add(Category("Estrenos", estrenos))
        }
        
        suspend fun addCategory(def: kotlinx.coroutines.Deferred<Document?>, name: String, isTv: Boolean) {
            def.await()?.selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }?.props?.pageProps?.movies?.map {
                val slugName = it.slug?.name
                val id = if (isTv) "ver-serie/$slugName" else "ver-pelicula/$slugName"
                val p = it.images?.poster?.let { img -> if (img.startsWith("http")) img else "$baseUrl$img" }
                if (isTv) TvShow(id = id, title = it.titles?.name ?: "", poster = p) else Movie(id = id, title = it.titles?.name ?: "", poster = p)
            }?.filter { it.id.substringAfter("/") != "null" }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name, it)) }
        }
        
        addCategory(mwDef, "Películas - Tendencias de la Semana", false)
        addCategory(swDef, "Series - Tendencias de la Semana", true)
        addCategory(sdDef, "Series - Tendencias del Día", true)
        
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "aventura", "animacion", "ciencia-ficcion", "comedia", "crimen", "documental", "drama", "familia", "fantasia", "misterio", "romance", "suspense", "terror").map { Genre(id = it, name = it.replaceFirstChar { c -> c.uppercase() }) }
        if (page > 1) return emptyList()
        return try {
            val res = service.getPage("$baseUrl/search?q=$query").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }
            res?.props?.pageProps?.movies?.mapNotNull { it.slug?.name?.let { s -> if (it.url?.slug?.contains("pelicula") == true) Movie(id = "ver-pelicula/$s", title = it.titles?.name ?: "", poster = it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) else TvShow(id = "ver-serie/$s", title = it.titles?.name ?: "", poster = it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) } } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getPage("$baseUrl/peliculas/estrenos/page/$page").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }?.props?.pageProps?.movies?.map { Movie(id = "ver-pelicula/${it.slug?.name}", title = it.titles?.name ?: "", poster = it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) }?.filter { it.id != "ver-pelicula/null" } ?: emptyList() } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { service.getPage("$baseUrl/series/estrenos/page/$page").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }?.props?.pageProps?.movies?.map { TvShow(id = "ver-serie/${it.slug?.name}", title = it.titles?.name ?: "", poster = it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) }?.filter { it.id != "ver-serie/null" } ?: emptyList() } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = service.getPage("$baseUrl/$id").let { doc ->
        val data = json.decodeFromString<ApiResponse>(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").props?.pageProps?.thisMovie ?: throw Exception()
        Movie(id = id, title = data.titles?.name ?: "", overview = data.overview ?: "", released = data.releaseDate?.substringBefore("-"), poster = data.images?.poster?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, banner = data.images?.backdrop?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, genres = data.genres?.map { Genre(it.slug ?: "", it.name ?: "") } ?: emptyList(), cast = data.cast?.acting?.mapNotNull { it.name?.let { n -> People("", n) } } ?: emptyList())
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage("$baseUrl/$id").let { doc ->
        val data = json.decodeFromString<ApiResponse>(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").props?.pageProps?.thisSerie ?: throw Exception()
        TvShow(id = id, title = data.titles?.name ?: "", overview = data.overview ?: "", released = data.releaseDate?.substringBefore("-"), poster = data.images?.poster?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, banner = data.images?.backdrop?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, genres = data.genres?.map { Genre(it.slug ?: "", it.name ?: "") } ?: emptyList(), cast = data.cast?.acting?.mapNotNull { it.name?.let { n -> People("", n) } } ?: emptyList(), seasons = data.seasons?.map { Season("${data.slug?.name}/${it.number}", it.number ?: 0, "Temporada ${it.number}") } ?: emptyList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try {
        val res = service.getPage("$baseUrl/ver-serie/${seasonId.substringBeforeLast("/")}").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }
        res?.props?.pageProps?.thisSerie?.seasons?.find { it.number == seasonId.substringAfterLast("/").toInt() }?.episodes?.map { Episode("episodio/${it.slug?.name}-temporada-${it.slug?.season}-episodio-${it.slug?.episode}", it.number ?: 0, "Episodio ${it.number}: ${it.title}", it.image?.let { img -> if (img.startsWith("http")) img else "$baseUrl$img" }) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val res = service.getPage("$baseUrl/$id").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }
        val vids = if (videoType is Video.Type.Movie) res?.props?.pageProps?.thisMovie?.videos else res?.props?.pageProps?.episode?.videos
        val servers = mutableListOf<Video.Server>()
        suspend fun fetch(v: VideoInfo, l: String) { try { service.getPage(v.result!!).select("script").find { it.data().contains("var url =") }?.data()?.substringAfter("var url = '")?.substringBefore("'")?.let { if (it.isNotBlank()) servers.add(Video.Server(it, "${v.cyberlocker} [$l]")) } } catch(_:Exception){} }
        vids?.latino?.forEach { fetch(it, "LAT") }; vids?.spanish?.forEach { fetch(it, "CAST") }
        servers
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    
    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val res = service.getPage("$baseUrl/genero/$id/page/$page").selectFirst("script#__NEXT_DATA__")?.data()?.let { json.decodeFromString<ApiResponse>(it) }
        Genre(id, id.replaceFirstChar { it.uppercase() }, res?.props?.pageProps?.movies?.mapNotNull { it.slug?.name?.let { s -> if (it.url?.slug?.contains("pelicula") == true) Movie("ver-pelicula/$s", it.titles?.name ?: "", it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) else TvShow("ver-serie/$s", it.titles?.name ?: "", it.images?.poster?.let { p -> if (p.startsWith("http")) p else "$baseUrl$p" }) } } ?: emptyList())
    } catch (_: Exception) { Genre(id, id.replaceFirstChar { it.uppercase() }) }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}