package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.flixlatam.DataLinkItem
import com.streamflixreborn.streamflix.models.flixlatam.PlayerResponse
import android.util.Base64
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.Locale

object FlixLatamProvider : Provider {

    override val name = "FlixLatam"
    override val baseUrl = "https://flixlatam.com"
    override val language = "es"
    override val logo = "$baseUrl/wp-content/uploads/2022/04/cropped-Series-Latinoamerica.jpg"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(FlixLatamService::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getHome(): List<Category> = coroutineScope {
        try {
            val doc = service.getPage(baseUrl, baseUrl)
            val categories = mutableListOf<Category>()
            doc.select("#slider-movies-tvshows .item").mapNotNull { el ->
                val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val banner = el.selectFirst("img")?.attr("src")
                val title = el.selectFirst(".data h3")?.text() ?: ""
                val type = el.selectFirst("span.item_type")?.text()
                if (type == "TV" || href.contains("/serie/")) TvShow(id = href.getId(), title = title, banner = banner)
                else Movie(id = href.getId(), title = title, banner = banner)
            }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }

            doc.select("div.module").forEach { sec ->
                val title = sec.selectFirst("header > h2")?.text() ?: return@forEach
                parseShows(sec.select(".items article")).takeIf { it.isNotEmpty() }?.let { categories.add(Category(title, it)) }
            }
            categories
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "animacion", "aventura", "ciencia-ficcion", "comedia", "crimen", "documental", "drama", "familia", "fantasia", "historia", "kids", "misterio", "musica", "romance", "terror", "western").map { Genre(id = it, name = it.replaceFirstChar { it.uppercase() }) }
        return try { parseShows(service.getPage("$baseUrl/page/$page/?s=$query", baseUrl).select("div.search-page article, div.items article")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { parseShows(service.getPage(if (page == 1) "$baseUrl/pelicula/" else "$baseUrl/pelicula/page/$page/", baseUrl).select("div.items article")).filterIsInstance<Movie>() } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(service.getPage(if (page == 1) "$baseUrl/series/" else "$baseUrl/series/page/$page/", baseUrl).select("#archive-content article.item")).filterIsInstance<TvShow>() } catch (_: Exception) { emptyList() }

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val doc = service.getPage(if (page == 1) "$baseUrl/genero/$id/" else "$baseUrl/genero/$id/page/$page/", baseUrl)
        Genre(id = id, name = doc.selectFirst("h1.Title")?.text() ?: id.replaceFirstChar { it.uppercase() }, shows = parseShows(doc.select("div.items article")))
    } catch (_: Exception) { Genre(id = id, name = id.replaceFirstChar { it.uppercase() }) }

    override suspend fun getMovie(id: String): Movie = try {
        val doc = service.getPage("$baseUrl/pelicula/$id/", baseUrl); val d = parseShowDetails(doc)
        Movie(id = id, title = doc.selectFirst(".sheader .data h1")?.text() ?: "", poster = doc.selectFirst(".sheader .poster img")?.attr("src"), banner = doc.selectFirst("style:containsData(background-image)")?.data()?.getBackgroundImage(), overview = d.overview, rating = d.rating, released = d.released, genres = d.genres, cast = d.cast, recommendations = parseShows(doc.select("#single_relacionados article")))
    } catch (_: Exception) { Movie(id = id, title = "Error") }

    override suspend fun getTvShow(id: String): TvShow = try {
        val doc = service.getPage("$baseUrl/serie/$id/", baseUrl); val d = parseShowDetails(doc)
        val seasons = doc.select("#seasons .se-c").mapNotNull { el -> el.selectFirst(".se-q span.se-t")?.text()?.toIntOrNull()?.let { Season(id = "$id|$it", number = it, title = "Temporada $it") } }.reversed()
        TvShow(id = id, title = doc.selectFirst(".sheader .data h1")?.text() ?: "", poster = doc.selectFirst(".sheader .poster img")?.attr("src"), banner = doc.selectFirst("style:containsData(background-image)")?.data()?.getBackgroundImage(), overview = d.overview, rating = d.rating, released = d.released, genres = d.genres, cast = d.cast, recommendations = parseShows(doc.select("#single_relacionados article")), seasons = seasons)
    } catch (_: Exception) { TvShow(id = id, title = "Error") }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try {
        val (slug, sNum) = seasonId.split('|'); service.getPage("$baseUrl/series/$slug/", baseUrl).select("#seasons .se-c").find { it.selectFirst(".se-q span.se-t")?.text() == sNum }?.select(".se-a ul.episodios li")?.mapNotNull { el ->
            val a = el.selectFirst(".episodiotitle a") ?: return@mapNotNull null
            Episode(id = a.attr("href").getId(), title = a.text(), number = el.selectFirst(".numerando")?.text()?.trim()?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, poster = el.selectFirst(".imagen img")?.attr("src"))
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val (pId, num, type) = if (videoType is Video.Type.Movie) { val doc = service.getPage("$baseUrl/pelicula/$id/", baseUrl); Triple(doc.body().className().substringAfter("postid-").substringBefore(" ").trim(), "1", "movie") }
        else { val doc = service.getPage("$baseUrl/episodio/$id/", baseUrl); val opt = doc.selectFirst("#playeroptionsul li[data-post][data-nume]"); Triple(opt?.attr("data-post") ?: "", opt?.attr("data-nume") ?: "", opt?.attr("data-type") ?: "tv") }
        val resJson = service.getPlayerAjax(FormBody.Builder().add("action", "doo_player_ajax").add("post", pId).add("nume", num).add("type", type).build()).string()
        val embedUrl = json.decodeFromString<PlayerResponse>(resJson).embed_url.replace("\\", "")
        val doc = service.getEmbedPage(embedUrl, mapOf("Referer" to baseUrl))
        val dlJson = Regex("""dataLink = (\[.+?\]);""").find(doc.selectFirst("script:containsData(dataLink)")?.data() ?: "")?.groupValues?.get(1)
        if (dlJson != null) json.decodeFromString<List<DataLinkItem>>(dlJson).flatMap { item -> item.sortedEmbeds.mapNotNull { if (it.servername.equals("download", true)) null else decodeBase64Link(it.link)?.let { link -> Video.Server(id = link, name = "${it.servername.replaceFirstChar { c -> c.titlecase() }} [${item.video_language}]") } } }.distinctBy { it.id }
        else doc.select(".ODDIV .OD_1 li[onclick]").mapNotNull { el -> Regex("""go_to_playerVast\(\s*'([^']+)'""").find(el.attr("onclick"))?.groupValues?.get(1)?.trim()?.let { Video.Server(id = it, name = el.selectFirst("span")?.text()?.trim() ?: "") } }.distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id)
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    private fun String.getId(): String = this.removeSuffix("/").substringAfterLast("/")
    private fun String.getBackgroundImage(): String? = this.substringAfter("url(").substringBefore(")")

    private data class ShowDetails(val overview: String?, val rating: Double?, val released: String?, val genres: List<Genre>, val cast: List<People>)
    private fun parseShowDetails(doc: Document): ShowDetails = ShowDetails(doc.selectFirst("#info .wp-content p")?.text(), doc.selectFirst(".dt_rating_data .dt_rating_vgs")?.text()?.toDoubleOrNull(), doc.selectFirst(".sheader .extra span.date")?.text(), doc.select(".sgeneros a").map { Genre(it.attr("href").getId(), it.text()) }, doc.select("#cast .persons .person").map { People(it.selectFirst("a")?.attr("href")?.getId() ?: "", it.selectFirst(".name a")?.text() ?: "", it.selectFirst(".img img")?.attr("src")) })
    private fun parseShows(elements: List<Element>): List<Show> = elements.mapNotNull { el ->
        val a = el.selectFirst("a") ?: return@mapNotNull null; val href = a.attr("href"); val t = el.selectFirst("h3")?.text() ?: el.selectFirst(".title")?.text() ?: return@mapNotNull null
        val p = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        if (href.contains("/pelicula/")) Movie(id = href.getId(), title = t, poster = p) else if (href.contains("/serie/") || href.contains("/series/")) TvShow(id = href.getId(), title = t, poster = p) else null
    }

    private interface FlixLatamService {
        companion object {
            fun build(baseUrl: String): FlixLatamService = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(FlixLatamService::class.java)
        }
        @GET suspend fun getPage(@Url url: String, @Header("Referer") referer: String): Document
        @POST("/wp-admin/admin-ajax.php") @retrofit2.http.Headers("x-requested-with: XMLHttpRequest") suspend fun getPlayerAjax(@Body body: FormBody): ResponseBody
        @GET suspend fun getEmbedPage(@Url url: String, @HeaderMap headers: Map<String, String>): Document
    }

    private fun decodeBase64Link(encrypted: String): String? = try {
        val parts = encrypted.split("."); if (parts.size != 3) null else {
            var b64 = parts[1]; val pad = b64.length % 4; if (pad != 0) b64 += "=".repeat(4 - pad)
            val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            json.substringAfter("\"link\":\"").substringBefore("\"")
        }
    } catch (_: Exception) { null }
}