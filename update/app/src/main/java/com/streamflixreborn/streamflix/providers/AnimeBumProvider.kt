package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

object AnimeBumProvider : Provider {

    override val name = "AnimeBum"
    override val baseUrl = "https://www.animebum.net"
    override val language = "es"
    override val logo = "$baseUrl/images/logo.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(AnimeBumService::class.java)

    private interface AnimeBumService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    private fun parseShows(doc: Document): List<TvShow> = doc.select("article.serie").mapNotNull { el ->
        val a = el.selectFirst("div.title h3 a") ?: return@mapNotNull null
        TvShow(id = a.attr("href"), title = a.attr("title"), poster = el.selectFirst("figure.image img")?.attr("src"))
    }

    private fun parseSearch(elements: List<Element>): List<AppAdapter.Item> = elements.mapNotNull { el ->
        val a = el.selectFirst("div.search-results__left a") ?: return@mapNotNull null
        val t = el.selectFirst("div.search-results__left a h2")?.text() ?: ""
        val p = el.selectFirst("div.search-results__img a img")?.attr("src")
        val o = el.selectFirst("div.search-results__left div.description")?.text()
        if (el.selectFirst("div.search-results__left .result-type")?.text() == "Película") Movie(id = a.attr("href"), title = t, poster = p, overview = o)
        else TvShow(id = a.attr("href"), title = t, poster = p, overview = o)
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val emisionDef = async { try { service.getPage("$baseUrl/emision") } catch(_:Exception) { null } }
        val latinoDef = async { try { service.getPage("$baseUrl/genero/audio-latino") } catch(_:Exception) { null } }
        val recentDef = async { try { service.getPage("$baseUrl/series") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        emisionDef.await()?.let { doc -> parseShows(doc).takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it.take(10).map { s -> s.copy(banner = s.poster) })); categories.add(Category("En Emisión", it)) } }
        latinoDef.await()?.let { doc -> parseShows(doc).takeIf { it.isNotEmpty() }?.let { categories.add(Category("Audio Latino", it)) } }
        recentDef.await()?.let { doc -> parseShows(doc).takeIf { it.isNotEmpty() }?.let { categories.add(Category("Series Recientes", it)) } }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "aventura", "ciencia-ficcion", "comedia", "deportes", "demonios", "drama", "ecchi", "escolares", "fantasia", "harem", "historico", "juegos", "latino", "lucha", "magia", "mecha", "militar", "misterio", "musica", "parodia", "policia", "psicologico", "recuentos-de-la-vida", "romance", "samurai", "seinen", "shoujo", "shounen", "sobrenatural", "super-poderes", "suspense", "terror", "vampiros", "yaoi").map { Genre(id = "genero/$it", name = it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        return try { parseSearch(service.getPage("$baseUrl/search?s=$query&page=$page").select("div.search-results__item")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getPage("$baseUrl/peliculas?page=$page").select("article.serie").mapNotNull { el -> val a = el.selectFirst("div.title h3 a") ?: return@mapNotNull null; Movie(id = a.attr("href"), title = a.attr("title"), poster = el.selectFirst("figure.image img")?.attr("src")) } } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(service.getPage("$baseUrl/series?page=$page")) } catch (_: Exception) { emptyList() }
    override suspend fun getGenre(id: String, page: Int): Genre = try { val doc = service.getPage("$baseUrl/$id?page=$page"); Genre(id = id, name = doc.selectFirst("h1.main-title")?.text() ?: id.substringAfterLast("/"), shows = parseShows(doc)) } catch (_: Exception) { Genre(id = id, name = "Error", shows = emptyList()) }

    override suspend fun getMovie(id: String): Movie = try {
        val doc = service.getPage(id); val p = doc.selectFirst("p.datos-serie strong:contains(Año)")?.parent()?.text()?.substringAfter("Año:")?.trim()
        Movie(id = id, title = doc.selectFirst("h1.title-h1-serie")?.text()?.replace(" Online", "") ?: "", poster = doc.selectFirst("div.poster-serie img.poster-serie__img")?.attr("src"), overview = doc.selectFirst("div.description p")?.text(), genres = doc.select("div.boom-categories a").map { Genre(id = it.attr("href"), name = it.text()) }, released = p, rating = doc.selectFirst("div.Prct #A-circle")?.attr("data-percent")?.toDoubleOrNull()?.let { it / 20.0 })
    } catch (_: Exception) { Movie(id = id, title = "Error") }

    override suspend fun getTvShow(id: String): TvShow = try {
        val doc = service.getPage(id); val p = doc.selectFirst("p.datos-serie strong:contains(Año)")?.parent()?.text()?.substringAfter("Año:")?.trim()
        val eps = doc.select("ul.list-episodies li").mapNotNull { el -> val a = el.selectFirst("a") ?: return@mapNotNull null; val t = a.ownText().trim(); Episode(id = a.attr("href"), number = Regex("""Episodio (\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0, title = t) }
        TvShow(id = id, title = doc.selectFirst("h1.title-h1-serie")?.text()?.replace(" Online", "") ?: "", poster = doc.selectFirst("div.poster-serie img.poster-serie__img")?.attr("src"), overview = doc.selectFirst("div.description p")?.text(), genres = doc.select("div.boom-categories a").map { Genre(id = it.attr("href"), name = it.text()) }, released = p, rating = doc.selectFirst("div.Prct #A-circle")?.attr("data-percent")?.toDoubleOrNull()?.let { it / 20.0 }, seasons = listOf(Season(id = id, number = 1, title = "Episodios", episodes = eps)))
    } catch (_: Exception) { TvShow(id = id, title = "Error") }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = getTvShow(seasonId).seasons.firstOrNull()?.episodes ?: emptyList()

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val url = if (videoType is Video.Type.Movie) service.getPage(id).selectFirst("ul.list-episodies li a")?.attr("href") ?: id else id
        Regex("""video\[\d+\]\s*=\s*['"]<iframe[^>]+src=["']([^"']+)["']""").findAll(service.getPage(url).selectFirst("script:containsData(var video = [])")?.data() ?: "").map { m ->
            var u = m.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
            Video.Server(id = u, name = if (u.contains("pcloud")) "Pcloud" else if (u.contains("amazon")) "Amazon Drive" else try { u.toHttpUrl().host.replace("www.", "").substringBefore(".").replaceFirstChar { it.titlecase() } } catch(_:Exception) { "Server" })
        }.toList()
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video {
        var u = server.id; if (u.contains("pcloud")) { val doc = service.getPage(u); val s = doc.select("script").find { it.data().contains("var shareId") }?.data() ?: ""; u = "https://u.pcloud.link/publink/show?code=${Regex("""var shareId\s*=\s*["']([^"']+)["']""").find(s)?.groupValues?.get(1) ?: ""}" }
        else if (u.contains("amazon")) { val doc = service.getPage(u); val s = doc.select("script").find { it.data().contains("var shareId") }?.data() ?: ""; u = "https://www.amazon.com/drive/v1/shares/${Regex("""var shareId\s*=\s*["']([^"']+)["']""").find(s)?.groupValues?.get(1) ?: ""}" }
        return Extractor.extract(u, server)
    }
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}