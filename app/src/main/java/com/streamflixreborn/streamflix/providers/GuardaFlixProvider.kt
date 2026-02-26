package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Path
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object GuardaFlixProvider : Provider {

    override val name: String = "GuardaFlix"
    override val baseUrl: String = "https://guardaplay.fit"
    override val logo: String = "$baseUrl/wp-content/uploads/2021/05/cropped-Guarda-Flix-2.png"
    override val language = "it"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(GuardaFlixService::class.java)

    private interface GuardaFlixService {
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun getHome(): Document
        @Headers("User-Agent: $USER_AGENT") @GET suspend fun getPage(@Url url: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun search(@Query(value = "s", encoded = true) q: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET("page/{page}/") suspend fun search(@Path("page") p: Int, @Query(value = "s", encoded = true) q: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET("page/{page}/") suspend fun movies(@Path("page") p: Int): Document
    }

    private fun normalizeUrl(url: String): String = when { url.startsWith("http") -> url; url.startsWith("//") -> "https:$url"; url.startsWith("/") -> baseUrl.trimEnd('/') + url; else -> baseUrl.trimEnd('/') + "/" + url.trimStart('/') }

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome(); val categories = mutableListOf<Category>()
        doc.select("section.section.movies").forEach { sec ->
            val title = sec.selectFirst("header .section-title")?.text()?.trim() ?: return@forEach
            val items = sec.select(".post-lst li").mapNotNull { parseGridItem(it) }
            if (items.isNotEmpty()) categories.add(Category(name = title, list = items))
        }
        return categories
    }

    private fun parseGridItem(el: Element): AppAdapter.Item? {
        val t = el.selectFirst(".entry-title")?.text()?.trim() ?: return null
        val h = el.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        return Movie(id = h, title = t, poster = el.selectFirst("img")?.attr("src")?.let { normalizeUrl(it) } ?: "", rating = el.selectFirst(".vote")?.text()?.trim()?.toDoubleOrNull())
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return service.getHome().select("li.menu-item:has(> a[href*='/movies']) ul.sub-menu li a[href]").mapNotNull { a -> val h = a.attr("href").trim(); if (h.isBlank() || a.text().isBlank()) null else Genre(id = h, name = a.text().trim()) }
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        if (page > 1 && service.search(encoded).selectFirst(".navigation.pagination .nav-links a.page-link") == null) return emptyList()
        val doc = if (page > 1) service.search(page, encoded) else service.search(encoded)
        return doc.select(".post-lst li").mapNotNull { parseGridItem(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = (if (page > 1) service.movies(page) else service.getHome()).select("section.section.movies .post-lst li").mapNotNull { parseGridItem(it) as? Movie }
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id); val t = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val tmdbDef = async { TmdbUtils.getMovie(t, language = language) }; val tmdb = tmdbDef.await()
        val rt = doc.selectFirst("span.duration.fa-clock.far")?.text()?.trim()?.let { s -> val h = Regex("(\\d+)h").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0; val m = Regex("(\\d+)m").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0; if (h > 0 || m > 0) h * 60 + m else null }
        val trailer = tmdb?.trailer ?: runCatching { val b64 = doc.selectFirst("script#funciones_public_js-js-extra[src^=data:text/javascript;base64,]")?.attr("src")?.substringAfter("base64,") ?: ""; if (b64.isBlank()) null else Regex("""\"trailer\"\s*:\s*\".*?src=\\\"(https?:\\/\\/www\.youtube\.com\\/embed\\/[^\\\"]+)\\\"""").find(String(Base64.getDecoder().decode(b64)))?.groupValues?.get(1)?.replace("\\/", "/")?.let { if (it.contains("youtube.com/embed/")) it.replace("/embed/", "/watch?v=").substringBefore("?") else it } }.getOrNull()
        Movie(id = id, title = t, poster = tmdb?.poster ?: doc.selectFirst(".post-thumbnail img")?.attr("src")?.let { normalizeUrl(it) } ?: "", overview = tmdb?.overview ?: doc.selectFirst(".description p")?.text()?.trim() ?: "", rating = tmdb?.rating ?: doc.selectFirst("span.vote.fa-star .num")?.text()?.trim()?.replace(',', '.')?.toDoubleOrNull(), released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" }, genres = tmdb?.genres ?: doc.select("span.genres a[href]").map { Genre(it.attr("href"), it.text().trim()) }, cast = doc.select("ul.cast-lst p a[href]").map { a -> People(id = a.attr("href").trim(), name = a.text().trim(), image = tmdb?.cast?.find { it.name.equals(a.text().trim(), true) }?.image) }, trailer = trailer, banner = tmdb?.banner, runtime = tmdb?.runtime ?: rt, imdbId = tmdb?.imdbId)
    }

    override suspend fun getTvShow(id: String): TvShow = throw Exception("TV shows not supported")
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = throw Exception("TV shows not supported")

    override suspend fun getGenre(id: String, page: Int): Genre {
        val base = if (id.startsWith("http")) id.removeSuffix("/") else "$baseUrl/${id.removePrefix("/").removeSuffix("/")}"
        val doc = if (page > 1) { val f = service.getPage("$base/"); if (f.selectFirst(".navigation.pagination .nav-links a.page-link") == null) return Genre(id, f.selectFirst(".section-header .section-title, h1.section-title, h1")?.text()?.trim() ?: "", emptyList()); service.getPage("$base/page/$page/") } else service.getPage("$base/")
        return Genre(id = id, name = doc.selectFirst(".section-header .section-title, h1.section-title, h1")?.text()?.trim() ?: "", shows = doc.select("ul.post-lst li").mapNotNull { parseGridItem(it) as? Show })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPage(id); val name = doc.selectFirst(".section-header .section-title, h1.section-title, h1")?.text()?.trim() ?: ""
        return People(id = id, name = name, filmography = if (page > 1) emptyList() else doc.select("ul.post-lst li").mapNotNull { parseGridItem(it) as? Show })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = service.getPage(id).select("#aa-options div[id^=options-]").mapIndexedNotNull { i, div ->
        val raw = div.selectFirst("iframe[data-src]")?.attr("data-src") ?: div.selectFirst("iframe")?.attr("src") ?: return@mapIndexedNotNull null
        try { val embedDoc = service.getPage(raw.trim()); val final = embedDoc.selectFirst(".Video iframe[src]")?.attr("src")?.trim() ?: return@mapIndexedNotNull null; Video.Server(id = final, name = "Opzione ${i + 1} - ${final.toHttpUrl().host.replace("www.", "").substringBefore(".")}", src = final) } catch (_: Exception) { null }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
}