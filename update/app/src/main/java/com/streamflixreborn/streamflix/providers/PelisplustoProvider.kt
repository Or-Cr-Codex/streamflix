package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import kotlinx.coroutines.delay

object PelisplustoProvider : Provider {

    override val name = "Pelisplusto"
    override val baseUrl = "https://pelisplus.to"
    override val language = "es"
    override val logo = "https://pelisplus.to/images/logo2.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(PelisplustoService::class.java)

    private interface PelisplustoService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val mainDef = async { try { service.getPage(baseUrl) } catch(_:Exception) { null } }
        val moviesDef = async { try { service.getPage("$baseUrl/peliculas") } catch(_:Exception) { null } }
        val seriesDef = async { try { service.getPage("$baseUrl/series") } catch(_:Exception) { null } }
        val animesDef = async { try { service.getPage("$baseUrl/animes") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        mainDef.await()?.select("div.home__slider_index div.swiper-slide article")?.mapNotNull { el ->
            val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val b = el.selectFirst("div.bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\"")
            val t = el.selectFirst("h2")?.text()?.substringBefore(" (") ?: ""
            val id = h.substringAfterLast('/').removeSuffix("/")
            if (h.contains("/pelicula/")) Movie(id = id, title = t, banner = getAbsUrl(b))
            else if (h.contains("/serie/")) TvShow(id = id, title = t, banner = getAbsUrl(b))
            else if (h.contains("/anime/")) TvShow(id = "anime/$id", title = t, banner = getAbsUrl(b))
            else null
        }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }

        listOf("Películas" to moviesDef, "Series" to seriesDef, "Animes" to animesDef).forEach { (name, def) ->
            def.await()?.let { parseShows(it).takeIf { l -> l.isNotEmpty() }?.let { l -> categories.add(Category(name, l.filterIsInstance<Show>())) } }
        }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "animacion", "anime", "aventura", "belica", "ciencia-ficcion", "comedia", "crimen", "documental", "drama", "familia", "fantasia", "guerra", "historia", "misterio", "musica", "romance", "suspense", "terror").map { Genre("genero/$it", it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        if (page > 1) return emptyList()
        return try { parseShows(service.getPage("$baseUrl/search/${URLEncoder.encode(query, "UTF-8")}")) } catch (_: Exception) { emptyList() }
    }

    private fun parseShows(doc: Document): List<AppAdapter.Item> = doc.select("article.item.liste.relative a.itemA").mapNotNull { el ->
        val url = el.attr("href"); val t = el.selectFirst("h2")?.text()?.substringBefore(" (") ?: return@mapNotNull null
        val p = el.selectFirst("img")?.attr("data-src") ?: ""
        if (url.contains("/pelicula/")) Movie(id = url.substringAfter("/pelicula/").removeSuffix("/"), title = t, poster = p)
        else if (url.contains("/serie/")) TvShow(id = url.substringAfter("/serie/").removeSuffix("/"), title = t, poster = p)
        else if (url.contains("/anime/")) TvShow(id = "anime/${url.substringAfter("/anime/").removeSuffix("/")}", title = t, poster = p)
        else null
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { parseShows(service.getPage(if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/$page")).filterIsInstance<Movie>() } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(service.getPage(if (page == 1) "$baseUrl/series" else "$baseUrl/series/$page")).filterIsInstance<TvShow>() } catch (_: Exception) { emptyList() }
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, id.substringAfter("genero/").replaceFirstChar { it.uppercase() }, parseShows(service.getPage(if (page == 1) "$baseUrl/$id" else "$baseUrl/$id/page/$page")).filterIsInstance<Show>())

    private fun getAbsUrl(u: String?): String? = if (u.isNullOrEmpty()) null else if (u.startsWith("http")) u else "$baseUrl${u.removePrefix("background-image: url(\"").removeSuffix("\");")}"

    override suspend fun getMovie(id: String): Movie = service.getPage("$baseUrl/pelicula/$id").let { doc ->
        val info = doc.selectFirst("div.genres.rating"); val b = doc.selectFirst("div.bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\"")
        Movie(id = id, title = doc.selectFirst("h1.slugh1")?.text()?.substringBefore(" (") ?: "", overview = doc.selectFirst("div.description p")?.text(), poster = getAbsUrl(doc.selectFirst("meta[property=og:image]")?.attr("content")), banner = getAbsUrl(b), rating = info?.select("span")?.find { it.text().contains("Rating:") }?.text()?.substringAfter(":")?.trim()?.toDoubleOrNull(), released = info?.selectFirst("a")?.text(),
            genres = doc.select("div.genres").find { it.selectFirst("span b")?.text() == "Generos" }?.select("a")?.map { Genre(it.attr("href"), it.text()) } ?: emptyList())
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage(if (id.startsWith("anime/")) "$baseUrl/$id" else "$baseUrl/serie/$id").let { doc ->
        val info = doc.selectFirst("div.genres.rating"); val b = doc.selectFirst("div.bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\"")
        val json = doc.select("script").find { it.data().contains("seasonsJson") }?.data()?.substringAfter("const seasonsJson = ")?.substringBefore(";")?.let { JSONObject(it) } ?: JSONObject()
        TvShow(id = id, title = doc.selectFirst("h1.slugh1")?.text()?.substringBefore(" (") ?: "", overview = doc.selectFirst("div.description p")?.text(), poster = getAbsUrl(doc.selectFirst("meta[property=og:image]")?.attr("content")), banner = getAbsUrl(b), rating = info?.select("span")?.find { it.text().contains("Rating:") }?.text()?.substringAfter(":")?.trim()?.toDoubleOrNull(), released = info?.selectFirst("a")?.text(),
            genres = doc.select("div.genres").find { it.selectFirst("span b")?.text() == "Generos" }?.select("a")?.map { Genre(it.attr("href"), it.text()) } ?: emptyList(),
            seasons = json.keys().asSequence().mapNotNull { it.toIntOrNull() }.sortedDescending().map { Season("$id/$it", it, "Temporada $it") }.toList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try {
        val sId = seasonId.substringBeforeLast("/"); val sNum = seasonId.substringAfterLast("/")
        val doc = service.getPage(if (sId.startsWith("anime/")) "$baseUrl/$sId" else "$baseUrl/serie/$sId")
        val json = doc.select("script").find { it.data().contains("seasonsJson") }?.data()?.substringAfter("const seasonsJson = ")?.substringBefore(";")?.let { JSONObject(it) }?.getJSONArray(sNum) ?: throw Exception()
        List(json.length()) { i -> val obj = json.getJSONObject(i); val num = obj.getInt("episode"); Episode("$seasonId/$num", num, obj.getString("title"), "https://image.tmdb.org/t/p/w300${obj.getString("image")}") }.sortedBy { it.number }
    } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val url = if (videoType is Video.Type.Movie) "$baseUrl/pelicula/$id" else { val p = id.split("/"); "${if (p[0].startsWith("anime/")) "$baseUrl/${p[0]}" else "$baseUrl/serie/${p[0]}"}/season/${p[1]}/episode/${p[2]}" }
        val res = mutableListOf<Video.Server>()
        service.getPage(url).select(".bg-tabs ul li").forEach { li ->
            try {
                val sName = li.text().replace(" Reproducir", ""); val data = li.attr("data-server"); if (data.isEmpty()) return@forEach
                val decoded = String(Base64.decode(data, Base64.DEFAULT))
                val final = if (!decoded.contains("https://")) service.getPage("$baseUrl/player/${String(Base64.encode(data.toByteArray(), Base64.DEFAULT)).trim()}").selectFirst("script:containsData(window.onload)")?.data()?.let { Regex("""(https?://[^\s'"]+)""").find(it)?.groupValues?.get(1) } else decoded
                if (!final.isNullOrEmpty()) res.add(Video.Server(final, sName, final))
            } catch (_: Exception) {}
            delay(1000L) // Reduced delay for better UX
        }
        res
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}