package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.sololatino.Item
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*
import java.util.Locale
import java.net.URL

object SoloLatinoProvider : Provider {

    override val name = "SoloLatino"
    override val baseUrl = "https://sololatino.net"
    override val language = "es"
    override val logo = "$baseUrl/wp-content/uploads/2022/11/logo-final.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(SoloLatinoService::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    private interface SoloLatinoService {
        @GET suspend fun getPage(@Url url: String): Document
        @POST("wp-admin/admin-ajax.php") suspend fun getPlayerAjax(@Header("Referer") referer: String, @Body body: RequestBody): Response<ResponseBody>
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val deferreds = listOf("Tendencias" to "$baseUrl/tendencias/page/1", "Películas de Estreno" to "$baseUrl/pelicula/estrenos", "Series Mejor Valoradas" to "$baseUrl/series/mejor-valoradas", "Animes Mejor Valorados" to "$baseUrl/animes/mejor-valoradas", "Toons" to "$baseUrl/genre_series/toons", "KDramas" to "$baseUrl/genre_series/kdramas")
            .map { (name, url) -> name to async { try { service.getPage(url) } catch(_:Exception) { null } } }

        deferreds.forEach { (name, def) ->
            def.await()?.let { doc ->
                val shows = parseMixed(doc)
                if (shows.isNotEmpty()) {
                    if (name == "Tendencias") categories.add(Category(Category.FEATURED, shows.take(12).map { if (it is Movie) it.copy(banner = it.poster) else (it as TvShow).copy(banner = it.poster) }))
                    categories.add(Category(name, shows.take(12)))
                }
            }
        }
        categories
    }

    private fun parseMixed(doc: Document): List<Show> = doc.select("article.item").mapNotNull { el ->
        val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
        val url = if (href.startsWith("http")) href else "$baseUrl$href"
        val img = el.selectFirst("img")?.attr("data-srcset") ?: ""
        val t = el.selectFirst("img")?.attr("alt") ?: ""
        val p = if (img.startsWith("http")) img else "$baseUrl$img"
        if (el.hasClass("movies")) Movie(id = url, title = t, poster = p) else TvShow(id = url, title = t, poster = p)
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "action-adventure", "animacion", "aventura", "belica", "ciencia-ficcion", "comedia", "crimen", "disney", "documental", "drama", "familia", "fantasia", "hbo", "historia", "kids", "misterio", "musica", "romance", "sci-fi-fantasy", "soap", "suspense", "talk", "terror", "war-politics", "western").map { Genre(it, it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        return try { parseMixed(service.getPage("$baseUrl/page/$page?s=$query")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getPage("$baseUrl/pelicula/estrenos/page/$page").let { doc -> doc.select("article.item").mapNotNull { el -> val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null; val p = el.selectFirst("img")?.attr("data-srcset") ?: ""; Movie(id = if (h.startsWith("http")) h else "$baseUrl$h", title = el.selectFirst("img")?.attr("alt") ?: "", poster = if (p.startsWith("http")) p else "$baseUrl$p") } } } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { service.getPage("$baseUrl/series/page/$page").let { doc -> doc.select("article.item").mapNotNull { el -> val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null; val p = el.selectFirst("img")?.attr("data-srcset") ?: ""; TvShow(id = if (h.startsWith("http")) h else "$baseUrl$h", title = el.selectFirst("img")?.attr("alt") ?: "", poster = if (p.startsWith("http")) p else "$baseUrl$p") } } } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = service.getPage(id).let { doc ->
        val head = doc.selectFirst("div.sheader")!!; val extra = doc.selectFirst("div.sbox.extra")
        Movie(id = id, title = head.selectFirst("div.data > h1")?.text() ?: "", poster = head.selectFirst("div.poster > img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, banner = doc.selectFirst("div.wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(")"),
            genres = head.select("div.data > div.sgeneros > a").map { Genre(it.attr("href").removeSuffix("/").substringAfterLast("/"), it.text()) }, overview = doc.selectFirst("div.wp-content > p")?.text(),
            rating = doc.selectFirst("div.nota > span")?.text()?.substringBefore(" ")?.toDoubleOrNull(), runtime = extra?.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull(), released = head.selectFirst("span.date")?.text()?.split(", ")?.getOrNull(1), trailer = extra?.selectFirst("li > span > a[href*=youtube]")?.attr("href"),
            cast = doc.select("div.sbox.srepart div.person").map { People(it.selectFirst("a")?.attr("href") ?: "", it.selectFirst(".name a")?.text() ?: "", it.selectFirst(".img img")?.attr("src")) }, recommendations = parseMixed(doc))
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage(id).let { doc ->
        val head = doc.selectFirst("div.sheader")!!; val extra = doc.selectFirst("div.sbox.extra")
        TvShow(id = id, title = head.selectFirst("div.data > h1")?.text() ?: "", poster = head.selectFirst("div.poster > img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, banner = doc.selectFirst("div.wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(")"),
            genres = head.select("div.data > div.sgeneros > a").map { Genre(it.attr("href").removeSuffix("/").substringAfterLast("/"), it.text()) }, overview = doc.selectFirst("div.wp-content > p")?.text(),
            rating = doc.selectFirst("div.nota > span")?.text()?.substringBefore(" ")?.toDoubleOrNull(), runtime = extra?.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull(), released = head.selectFirst("span.date")?.text()?.split(", ")?.getOrNull(1), trailer = extra?.selectFirst("li > span > a[href*=youtube]")?.attr("href"),
            seasons = doc.select("div#seasons div.se-c").map { Season("$id@${it.attr("data-season")}", it.attr("data-season").toIntOrNull() ?: 0, "Temporada ${it.attr("data-season")}") }.filter { it.number != 0 }.reversed(),
            cast = doc.select("div.sbox.srepart div.person").map { People(it.selectFirst("a")?.attr("href") ?: "", it.selectFirst(".name a")?.text() ?: "", it.selectFirst(".img img")?.attr("src")) }, recommendations = parseMixed(doc))
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try {
        val (id, sNum) = seasonId.split("@"); service.getPage(id).select("div.se-c[data-season=$sNum] ul.episodios li").map { el ->
            val num = el.selectFirst("div.numerando")?.text()?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            Episode(id = el.selectFirst("a")!!.attr("href"), number = num, title = el.selectFirst("div.episodiotitle .epst")?.text() ?: "Episodio $num", poster = el.selectFirst("div.imagen > img")?.attr("src"))
        }
    } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val doc = service.getPage(id); val res = mutableListOf<Video.Server>()
        for (el in doc.select("li[data-type][data-post][data-nume]")) try {
            val body = FormBody.Builder().add("action", "doo_player_ajax").add("post", el.attr("data-post")).add("nume", el.attr("data-nume")).add("type", el.attr("data-type")).build()
            val ajaxBody = service.getPlayerAjax(id, body).body()?.string() ?: continue
            val iframeUrl = ajaxBody.substringAfter("src='").substringBefore("'") ?: continue
            val iframeHtml = service.getPage(iframeUrl).html(); val dlMatch = Regex("""dataLink = (\[.+?\]);""").find(iframeHtml)
            if (dlMatch != null) json.decodeFromString<List<Item>>(dlMatch.groupValues[1]).forEach { item -> val l = when(item.video_language) { "LAT"->"[LAT]"; "ESP"->"[CAST]"; "SUB"->"[SUB]"; else->"" }; item.sortedEmbeds.forEach { if (!it.servername.equals("download", true)) decodeBase64Link(it.link)?.let { link -> res.add(Video.Server(link, "${it.servername} $l".trim())) } } }
            else Jsoup.parse(iframeHtml).select(".ODDIV .OD_1 li[onclick]").forEach { dom: Element -> val m = Regex("""go_to_playerVast\(\s*'([^']+)'""").find(dom.attr("onclick")); m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { if (!it.contains("1fichier", true)) res.add(Video.Server(it, dom.selectFirst("span")?.text()?.trim() ?: "")) } }
        } catch (_: Exception) {}
        res.distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override suspend fun getGenre(id: String, page: Int): Genre = try { Genre(id, id.replaceFirstChar { it.uppercase() }, parseMixed(service.getPage("$baseUrl/page/$page?s=$id"))) } catch (_: Exception) { Genre(id, id.replaceFirstChar { it.uppercase() }, emptyList()) }
    override suspend fun getPeople(id: String, page: Int): People = service.getPage(id).let { doc -> People(id, doc.selectFirst(".data h1")?.text() ?: "", image = doc.selectFirst(".poster img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, filmography = parseMixed(doc)) }

    private fun decodeBase64Link(e: String): String? = try { val p = e.split("."); if (p.size != 3) null else { var b64 = p[1]; val pad = b64.length % 4; if (pad != 0) b64 += "=".repeat(4 - pad); String(Base64.decode(b64, Base64.DEFAULT)).substringAfter("\"link\":\"").substringBefore("\"") } } catch (_: Exception) { null }
}