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
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Path
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object GuardaSerieProvider : Provider {

    override val name = "GuardaSerie"
    override val baseUrl = "https://guardoserie.blog"
    override val logo: String = "$baseUrl/wp-content/uploads/2021/02/Guardaserie-3.png"
    override val language = "it"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(GuardaSerieService::class.java)

    private interface GuardaSerieService {
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun getHome(): Document
        @Headers("User-Agent: $USER_AGENT") @GET suspend fun getPage(@Url url: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET("{path}page/{page}/") suspend fun getPage(@Path(value = "path", encoded = true) path: String, @Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serie/") suspend fun getSerie(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serie/page/{page}/") suspend fun getSerie(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET("turche/") suspend fun getTurche(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("guarda-film-streaming-ita/") suspend fun getMovies(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("guarda-film-streaming-ita/page/{page}/") suspend fun getMovies(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun search(@Query(value = "s", encoded = true) query: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET("page/{page}/") suspend fun search(@Path("page") page: Int, @Query(value = "s", encoded = true) query: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val serieDef = async { try { service.getSerie() } catch(_:Exception) { null } }
        val turcheDef = async { try { service.getTurche() } catch(_:Exception) { null } }
        val categories = mutableListOf<Category>()
        serieDef.await()?.select("div.movies-list.movies-list-full div.ml-item")?.mapNotNull { parseGridItem(it) }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Serie", list = it)) }
        turcheDef.await()?.select("div.movies-list.movies-list-full div.ml-item")?.mapNotNull { parseGridItem(it) }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Serie Turche", list = it)) }
        categories
    }

    private fun parseGridItem(el: Element): AppAdapter.Item? {
        val link = el.selectFirst("a.ml-mask[href]") ?: return null
        val href = link.attr("href").trim(); if (href.isBlank()) return null
        val title = el.selectFirst("span.mli-info h2")?.text()?.trim() ?: return null
        val poster = el.selectFirst("img.lazy.thumb.mli-thumb")?.attr("data-original")?.takeIf { it.isNotBlank() } ?: ""
        return if (href.contains("/serie/")) TvShow(id = href, title = title, poster = poster) else Movie(id = href, title = title, poster = poster)
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return service.getHome().select("li.menu-item:has(> a:matchesOwn(^Genere$)) ul.sub-menu li a[href]").mapNotNull { a ->
                val href = a.attr("href").trim(); if (href.isBlank() || a.text().trim().isBlank()) null else Genre(id = href, name = a.text().trim())
            }
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = if (page > 1) service.search(page, encoded) else service.search(encoded)
        return doc.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { parseGridItem(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = (if (page > 1) service.getMovies(page) else service.getMovies()).select("div.movies-list.movies-list-full div.ml-item").mapNotNull { parseGridItem(it) as? Movie }
    override suspend fun getTvShows(page: Int): List<TvShow> = (if (page > 1) service.getSerie(page) else service.getSerie()).select("div.movies-list.movies-list-full div.ml-item").mapNotNull { parseGridItem(it) as? TvShow }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: ""
        val tmdbDef = async { TmdbUtils.getMovie(title, language = language) }; val tmdb = tmdbDef.await()
        Movie(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.thumb.mvic-thumb img")?.attr("data-src") ?: "",
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("div.mvici-right a[rel='tag']")?.text()?.trim(),
            runtime = tmdb?.runtime ?: doc.selectFirst("div.mvici-right p:has(strong:matchesOwn((?i)^Duration:)) span")?.text()?.substringBefore(" min")?.trim()?.toIntOrNull(),
            rating = tmdb?.rating ?: doc.selectFirst("div.mvici-right div.imdb_r span.imdb-r")?.text()?.toDoubleOrNull(),
            genres = tmdb?.genres ?: doc.select("div.mvici-left a[rel='category tag']").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("div.mvici-left p:has(strong:matchesOwn((?i)^attori:)) span a[href]").map { el -> People(id = el.attr("href").trim(), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) },
            overview = tmdb?.overview ?: doc.selectFirst("p.f-desc")?.text()?.trim() ?: "", banner = tmdb?.banner, imdbId = tmdb?.imdbId,
            trailer = tmdb?.trailer ?: doc.select("script").firstOrNull { it.html().contains("iframe-trailer") && it.html().contains("youtube.com/embed/") }?.let { it.html().substringAfter("'src', '").substringBefore("');").replace("/embed/", "/watch?v=").let { s -> if (s.startsWith("//")) "https:$s" else s } }
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: ""
        val tmdbDef = async { TmdbUtils.getTvShow(title, language = language) }; val tmdb = tmdbDef.await()
        val seasons = doc.select("div#seasons div.tvseason").mapNotNull { sEl ->
            if (sEl.selectFirst("div.les-content a.ep-404") != null) return@mapNotNull null
            val sNum = Regex("Stagione\\s+(\\d+)", RegexOption.IGNORE_CASE).find(sEl.selectFirst("div.les-title strong")?.text() ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            Season(id = "$id#s$sNum", number = sNum, poster = tmdb?.seasons?.find { it.number == sNum }?.poster)
        }
        TvShow(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.thumb.mvic-thumb img")?.attr("data-src") ?: "",
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("div.mvici-right a[rel='tag']")?.text()?.trim(),
            runtime = tmdb?.runtime ?: doc.selectFirst("div.mvici-right p:has(strong:matchesOwn((?i)^Duration:)) span")?.text()?.substringBefore(" min")?.trim()?.toIntOrNull(),
            rating = tmdb?.rating ?: doc.selectFirst("div.mvici-right div.imdb_r span.imdb-r")?.text()?.toDoubleOrNull(),
            overview = tmdb?.overview ?: doc.selectFirst("p.f-desc")?.text()?.trim()?.takeUnless { it.lowercase().contains("guardaserie") },
            genres = tmdb?.genres ?: doc.select("div.mvici-left a[rel='category tag']").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("div.mvici-left p:has(strong:matchesOwn((?i)^attori:)) span a[href]").map { el -> People(id = el.attr("href").trim(), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) },
            seasons = seasons, banner = tmdb?.banner, imdbId = tmdb?.imdbId, trailer = tmdb?.trailer
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val sId = seasonId.substringBefore("#s"); val sNum = seasonId.substringAfter("#s").toIntOrNull() ?: return@coroutineScope emptyList()
        val doc = service.getPage(sId); val title = cleanTitle(doc.selectFirst("div.mvic-desc h3")?.text() ?: "")
        val tmdbDef = async { TmdbUtils.getTvShow(title, language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDef.await()
        doc.select("div#seasons div.tvseason").firstOrNull { Regex("Stagione\\s+$sNum", RegexOption.IGNORE_CASE).containsMatchIn(it.selectFirst("div.les-title strong")?.text() ?: "") }
            ?.select("div.les-content a[href]")?.mapNotNull { a ->
                val epNum = Regex("Episodio\\s+(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val tE = tmdbEps.find { it.number == epNum }
                Episode(id = a.attr("href").trim(), number = epNum, title = tE?.title ?: a.text().trim(), poster = tE?.poster, overview = tE?.overview)
            } ?: emptyList()
    }

    private fun cleanTitle(title: String): String = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val doc = if (page <= 1) service.getPage(id) else service.getPage(id, page)
        Genre(id = id, name = "", shows = doc.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { parseGridItem(it) as? Show })
    } catch (_: Exception) { Genre(id = id, name = "", shows = emptyList()) }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPage(id); if (page > 1) return People(id = id, name = "", filmography = emptyList())
        return People(id = id, name = "", filmography = doc.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { parseGridItem(it) as? Show })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = service.getPage(id).select("div#player2 div[id^=tab]").mapIndexedNotNull { i, div ->
        val iframe = div.selectFirst("div.movieplay iframe"); val src = iframe?.attr("data-src")?.takeIf { it.isNotBlank() } ?: iframe?.attr("src")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        try { Video.Server(id = src.trim(), name = "Server ${i + 1} - ${src.trim().toHttpUrl().host.replace("www.", "").substringBefore(".")}", src = src.trim()) } catch (_: Exception) { null }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
}