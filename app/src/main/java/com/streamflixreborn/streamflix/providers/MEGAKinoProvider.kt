package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.MyCookieJar
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object MEGAKinoProvider : Provider {

    override val name = "MEGAKino"
    override val baseUrl = "https://megakino1.com"
    override val logo = "https://images2.imgbox.com/a2/83/OubSojBq_o.png"
    override val language = "de"

    private const val DEFAULT_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default.newBuilder().cookieJar(MyCookieJar()).build())
        .build()
        .create(MEGAKinoService::class.java)

    private interface MEGAKinoService {
        @Headers("User-Agent: $DEFAULT_AGENT") @GET(".") suspend fun getHome(): Document
        @Headers("User-Agent: $DEFAULT_AGENT") @GET("index.php?yg=token") suspend fun getToken(): ResponseBody
        @Headers("User-Agent: $DEFAULT_AGENT") @GET suspend fun getDocument(@Url url: String): Document
        @Headers("User-Agent: $DEFAULT_AGENT") @GET("{path}page/{page}/") suspend fun getPage(@Path(value = "path", encoded = true) path: String, @Path("page") page: Int): Document
        @Headers("User-Agent: $DEFAULT_AGENT") @GET("/films/") suspend fun getFilms(): Document
        @Headers("User-Agent: $DEFAULT_AGENT") @GET("/serials/") suspend fun getSerials(): Document
        @Headers("User-Agent: $DEFAULT_AGENT") @FormUrlEncoded @POST("index.php?do=search") suspend fun search(@Field("do") d: String = "search", @Field("subaction") s: String = "search", @Field("search_start") ss: Int, @Field("full_search") f: Int = 0, @Field("result_from") rf: Int, @Field("story") q: String): Document
    }

    private var lastTokenTime = 0L
    private suspend fun ensureToken() { if (System.currentTimeMillis() - lastTokenTime > 600000) try { service.getToken(); lastTokenTime = System.currentTimeMillis() } catch (_: Exception) {} }

    private fun parseContent(element: Element): List<AppAdapter.Item> = element.select("div#dle-content a.poster.grid-item").mapNotNull { el ->
        val href = el.attr("href"); val t = el.select("h3.poster__title").text().trim(); val p = "$baseUrl${el.select("div.poster__img img").attr("data-src")}"
        if (href.contains("/serials/")) TvShow(id = href, title = t, poster = p) else Movie(id = href, title = t, poster = p)
    }

    override suspend fun getHome(): List<Category> {
        ensureToken(); val doc = service.getHome(); val categories = mutableListOf<Category>()
        doc.select("section.sect").find { it.select("h2.sect__title").text().contains("Topaktuelle Neuheiten", true) }?.let { parseContent(it).takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Topaktuelle Neuheiten", list = it)) } }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        ensureToken()
        if (query.isEmpty()) return service.getHome().let { doc -> (doc.selectFirst("div.side-block:has(div.side-block__title:contains(Genres))") ?: doc.select("div.side-block").find { it.select("div.side-block__title").text() == "Genres" })?.select("ul.side-block__content li a")?.map { Genre(it.attr("href"), it.text()) } ?: emptyList() }
        return try { parseContent(service.search(ss = page, rf = (page - 1) * 20 + 1, q = query)) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> { ensureToken(); return parseContent(if (page > 1) service.getPage("films/", page) else service.getFilms()).filterIsInstance<Movie>() }
    override suspend fun getTvShows(page: Int): List<TvShow> { ensureToken(); return parseContent(if (page > 1) service.getPage("serials/", page) else service.getSerials()).filterIsInstance<TvShow>() }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        ensureToken(); val doc = service.getDocument("$baseUrl$id"); val title = doc.select("h1[itemprop='name']").text().trim()
        val tmdbDef = async { TmdbUtils.getMovie(title, language = language) }; val tmdb = tmdbDef.await()
        Movie(id = id, title = title, poster = tmdb?.poster ?: "$baseUrl${doc.select("div.pmovie__poster img[itemprop='image']").attr("data-src")}", banner = tmdb?.banner, overview = tmdb?.overview ?: doc.select("div.page__text[itemprop='description']").text().trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.select("div.pmovie__year span[itemprop='dateCreated']").text().trim(),
            quality = doc.select("div.pmovie__poster div.poster__label").text().trim(), trailer = tmdb?.trailer ?: doc.select("link[itemprop='embedUrl']").attr("href").replace("/embed/", "/watch?v="),
            genres = tmdb?.genres ?: doc.select("div.pmovie__genres[itemprop='genre']").text().split("/").map { it.trim() }.filter { it.isNotEmpty() }.map { Genre(it, it) },
            cast = doc.select("span[itemprop='actors'] a").map { el -> People(id = el.attr("href"), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) })
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        ensureToken(); val doc = service.getDocument("$baseUrl$id"); val titleRaw = doc.select("h1[itemprop='name']").text().trim()
        val sNum = Regex("""- (\d+) Staffel""").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val tmdbDef = async { TmdbUtils.getTvShow(titleRaw.replace(Regex("""\s*-\s*\d+\s*Staffel\s*$"""), "").trim(), language = language) }; val tmdb = tmdbDef.await()
        TvShow(id = id, title = titleRaw, poster = tmdb?.poster ?: "$baseUrl${doc.select("div.pmovie__poster img[itemprop='image']").attr("data-src")}", banner = tmdb?.banner, overview = tmdb?.overview ?: doc.select("div.page__text[itemprop='description']").text().trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.select("div.pmovie__year span[itemprop='dateCreated']").text().trim(),
            seasons = listOf(Season(id = id, number = sNum, title = "Episode", poster = tmdb?.seasons?.find { it.number == sNum }?.poster)),
            trailer = tmdb?.trailer ?: doc.select("link[itemprop='embedUrl']").attr("href").replace("/embed/", "/watch?v="),
            genres = tmdb?.genres ?: doc.select("div.pmovie__genres[itemprop='genre']").text().split("/").map { it.trim() }.filter { it.isNotEmpty() }.map { Genre(it, it) },
            cast = doc.select("span[itemprop='actors'] a").map { el -> People(id = el.attr("href"), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) })
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        ensureToken(); val doc = service.getDocument("$baseUrl$seasonId"); val titleRaw = doc.select("h1[itemprop='name']").text().trim()
        val sNum = Regex("""- (\d+) Staffel""").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val tmdbDef = async { TmdbUtils.getTvShow(titleRaw.replace(Regex("""\s*-\s*\d+\s*Staffel\s*$"""), "").trim(), language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDef.await(); val eps = mutableListOf<Episode>()
        doc.select("select.se-select option").forEach { el ->
            val name = el.text(); val num = Regex("Episode\\s+(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: (eps.size + 1); val tE = tmdbEps.find { it.number == num }
            eps.add(Episode(id = "$seasonId|${el.attr("value")}", number = num, title = tE?.title ?: name, poster = tE?.poster, overview = tE?.overview))
        }
        eps
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        ensureToken(); val res = mutableListOf<Video.Server>()
        if (videoType is Video.Type.Movie) {
            val doc = service.getDocument("$baseUrl$id"); val names = doc.select("div.tabs-block__select span").map { it.text() }
            doc.select("div.tabs-block__content").forEachIndexed { i, c -> (c.selectFirst("iframe")?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: c.selectFirst("iframe")?.attr("src"))?.let { res.add(Video.Server(id = it, name = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Server ${i + 1}")) } }
        } else if (id.contains("|")) {
            val p = id.split("|"); service.getDocument("$baseUrl${p[0]}").select("select#${p[1]} option").forEach { if (it.attr("value").isNotEmpty()) res.add(Video.Server(id = it.attr("value"), name = it.text())) }
        }
        return res
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id)
    override suspend fun getGenre(id: String, page: Int): Genre = ensureToken().run { val doc = if (page > 1) service.getPage(id.removePrefix("/").removeSuffix("/") + "/", page) else service.getDocument("$baseUrl$id"); Genre(id = id, name = doc.select("h2.sect__title").text().trim().ifEmpty { id }, shows = parseContent(doc).filterIsInstance<com.streamflixreborn.streamflix.models.Show>()) }
    override suspend fun getPeople(id: String, page: Int): People = coroutineScope { ensureToken(); val doc = service.getDocument("$baseUrl$id"); People(id = id, name = doc.select("h1").text().trim(), filmography = if (page > 1) emptyList() else parseContent(doc).filterIsInstance<com.streamflixreborn.streamflix.models.Show>()) }
}