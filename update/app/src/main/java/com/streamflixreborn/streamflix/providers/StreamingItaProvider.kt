package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import retrofit2.http.Header
import retrofit2.http.Body
import java.net.URLDecoder
import java.net.URLEncoder

object StreamingItaProvider : Provider {

    override val name = "StreamingIta"
    override val baseUrl = "https://streamingita.homes"
    override val language = "it"
    override val logo: String get() = "$baseUrl/wp-content/uploads/2019/204/logos.png"

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(Service::class.java)

    private interface Service {
        @GET suspend fun getPage(@Url url: String): Document
        @POST("wp-admin/admin-ajax.php") suspend fun getPlayerAjax(@Header("Referer") referer: String, @Body body: FormBody): Response<ResponseBody>
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        try {
            val document = service.getPage(baseUrl)
            val sliderItems = document.select("#slider-movies-tvshows article.item").map { el ->
                async {
                    val href = el.selectFirst(".image a")?.attr("href").orEmpty()
                    val img = el.selectFirst(".image img")?.attr("src")
                    val title = el.selectFirst(".data h3.title, .data h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: ""
                    val tmdbRating = if (href.contains("/film/")) TmdbUtils.getMovie(title, language = language)?.rating else if (href.contains("/tv/")) TmdbUtils.getTvShow(title, language = language)?.rating else null
                    if (href.contains("/film/")) Movie(id = href, title = title, poster = img, banner = img, rating = tmdbRating)
                    else if (href.contains("/tv/")) TvShow(id = href, title = title, poster = img, banner = img, rating = tmdbRating) else null
                }
            }.awaitAll().filterNotNull()

            val categories = mutableListOf<Category>()
            if (sliderItems.isNotEmpty()) categories.add(Category(name = Category.FEATURED, list = sliderItems))

            document.select("div.content.full_width_layout.normal h2").forEach { headerEl ->
                val categoryName = headerEl.text().trim()
                var container = headerEl.parent()?.nextElementSibling()
                while (container != null && !(container.hasClass("items") || container.id() == "featured-titles" || container.id() == "dt-movies" || container.id() == "dt-tvshows")) container = container.nextElementSibling()
                container?.select("article.item")?.mapNotNull { el ->
                    val href = el.selectFirst("a")?.attr("href").orEmpty(); if (href.isBlank()) return@mapNotNull null
                    val img = el.selectFirst(".poster img, .image img, img")?.attr("src")
                    val title = el.selectFirst(".data h3 a, .data h3, h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: ""
                    if (href.contains("/tv/")) TvShow(id = href, title = title, poster = img) else if (href.contains("/film/")) Movie(id = href, title = title, poster = img) else null
                }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = categoryName, list = it)) }
            }
            categories
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return try {
                service.getPage(baseUrl).select("ul#main_header > li a[href='/film/']").firstOrNull()?.closest("li")?.select("ul.sub-menu li a[href]")?.mapNotNull { a ->
                    val href = a.attr("href").trim(); if (href.isBlank() || !href.contains("/genere/")) return@mapNotNull null
                    Genre(id = if (href.startsWith("http")) href else baseUrl + href.removePrefix("/"), name = a.text().trim())
                }?.sortedBy { it.name } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        return try {
            val url = if (page > 1) "$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}" else "$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
            service.getPage(url).select(".search-page .result-item article").mapNotNull { el ->
                val anchor = el.selectFirst(".details .title > a") ?: return@mapNotNull null
                val href = anchor.attr("href").orEmpty(); if (href.isBlank()) return@mapNotNull null
                val img = el.selectFirst(".thumbnail img")?.attr("src")?.replace("-150x150", "")
                if (href.contains("/film/")) Movie(id = href, title = anchor.text().trim(), poster = img) else if (href.contains("/tv/")) TvShow(id = href, title = anchor.text().trim(), poster = img) else null
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try {
        val doc = service.getPage(if (page > 1) "$baseUrl/film/page/$page/" else "$baseUrl/film/")
        doc.select(if (page > 1) "#archive-content article.item" else "article.item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href").orEmpty(); if (href.isBlank() || !href.contains("/film/")) return@mapNotNull null
            Movie(id = href, title = el.selectFirst(".data h3 a, .data h3, h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: "", poster = el.selectFirst(".poster img, .image img, img")?.attr("src"))
        }
    } catch (_: Exception) { emptyList() }

    override suspend fun getTvShows(page: Int): List<TvShow> = try {
        val doc = service.getPage(if (page > 1) "$baseUrl/tv/page/$page/" else "$baseUrl/tv/")
        doc.select(if (page > 1) "#archive-content article.item" else "article.item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href").orEmpty(); if (href.isBlank() || !href.contains("/tv/")) return@mapNotNull null
            TvShow(id = href, title = el.selectFirst(".data h3 a, .data h3, h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: "", poster = el.selectFirst(".poster img, .image img, img")?.attr("src"))
        }
    } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("div.data > h1")?.text() ?: ""
        val tmdbDeferred = async { TmdbUtils.getMovie(title, language = language) }
        val tmdb = tmdbDeferred.await()
        return@coroutineScope Movie(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.poster img[itemprop=image]")?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
            overview = tmdb?.overview ?: doc.selectFirst("#info .wp-content p")?.text(), rating = tmdb?.rating ?: doc.selectFirst(".starstruck-rating .dt_rating_vgs")?.text()?.replace(',', '.')?.toDoubleOrNull(),
            trailer = tmdb?.trailer ?: doc.selectFirst("#trailer iframe, #trailer .embed iframe")?.attr("src")?.let { normalizeUrl(it) }?.let { mapTrailerToWatchUrl(it) },
            genres = tmdb?.genres ?: doc.select("div.sgeneros a[rel=tag]").map { Genre(it.text(), it.text()) },
            cast = doc.select("#cast h2:matches(^Cast$) + .persons .person").map { el ->
                val anchor = el.selectFirst(".data .name a"); val name = anchor?.text() ?: el.selectFirst("[itemprop=name]")?.attr("content") ?: ""
                val img = el.selectFirst(".img img")?.attr("src"); val tmdbP = tmdb?.cast?.find { it.name.equals(name, true) }
                People(id = anchor?.attr("href")?.let { h -> img?.let { i -> "$h?poster=${URLEncoder.encode(i, "UTF-8")}" } ?: h } ?: name, name = name, image = tmdbP?.image ?: img)
            }, released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = tmdb?.runtime, banner = tmdb?.banner, imdbId = tmdb?.imdbId
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("div.data > h1")?.text() ?: ""
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language) }
        val tmdb = tmdbDeferred.await()
        return@coroutineScope TvShow(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.poster img[itemprop=image]")?.attr("src"),
            overview = tmdb?.overview ?: doc.selectFirst("#info .wp-content p")?.text(), rating = tmdb?.rating ?: doc.selectFirst(".starstruck-rating .dt_rating_vgs")?.text()?.replace(',', '.')?.toDoubleOrNull(),
            trailer = tmdb?.trailer ?: doc.selectFirst("#trailer iframe, #trailer .embed iframe")?.attr("src")?.let { normalizeUrl(it) }?.let { mapTrailerToWatchUrl(it) },
            seasons = doc.select("#serie_contenido #seasons .se-c").map { el ->
                val num = el.selectFirst(".se-q .se-t")?.text()?.trim()?.toIntOrNull() ?: 1
                Season(id = "$id?season=$num", number = num, title = "Stagione $num", poster = tmdb?.seasons?.find { it.number == num }?.poster)
            }.sortedBy { it.number }, genres = tmdb?.genres ?: doc.select("div.sgeneros a[rel=tag]").map { Genre(it.text(), it.text()) },
            cast = tmdb?.cast ?: doc.select("#cast h2:matches(^Cast$) + .persons .person").map { el ->
                val anchor = el.selectFirst(".data .name a"); val name = anchor?.text() ?: el.selectFirst("[itemprop=name]")?.attr("content") ?: ""
                People(id = anchor?.attr("href") ?: name, name = name, image = el.selectFirst(".img img")?.attr("src"))
            }, released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = tmdb?.runtime, banner = tmdb?.banner, imdbId = tmdb?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val num = seasonId.substringAfter("?season=").toIntOrNull() ?: 1; val doc = service.getPage(seasonId.substringBefore("?season="))
        val tmdbDeferred = async { TmdbUtils.getTvShow(doc.selectFirst("div.data > h1")?.text() ?: "", language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, num, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDeferred.await()
        doc.select("#serie_contenido #seasons .se-c").filter { it.selectFirst(".se-q .se-t")?.text()?.trim()?.toIntOrNull() == num }.flatMap { it.select(".se-a ul.episodios > li") }.mapNotNull { el ->
            val nText = el.selectFirst(".numerando")?.text()?.trim() ?: ""; val epNum = nText.substringAfter("-").trim().toIntOrNull() ?: 0; val tmdbE = tmdbEps.find { it.number == epNum }
            Episode(id = el.selectFirst(".episodiotitle a")?.attr("href").orEmpty(), number = epNum, title = tmdbE?.title ?: el.selectFirst(".episodiotitle a")?.text(), poster = tmdbE?.poster ?: el.selectFirst(".imagen img")?.attr("src"), overview = tmdbE?.overview)
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val base = if (id.startsWith("http")) id.removeSuffix("/") else "$baseUrl/${id.removePrefix("/").removeSuffix("/")}"
        val doc = service.getPage(if (page > 1) "$base/page/$page/" else "$base/")
        Genre(id = id, name = doc.selectFirst("h1, .archive-title, .data h1")?.text()?.trim() ?: id.substringAfterLast('/').replace('-', ' '), shows = doc.select("article.item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href").orEmpty(); if (href.isBlank() || !href.contains("/film/")) return@mapNotNull null
            Movie(id = href, title = el.selectFirst(".data h3 a, .data h3, h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: "", poster = el.selectFirst(".poster img, .image img, img")?.attr("src"))
        })
    } catch (_: Exception) { Genre(id = id, name = "", shows = emptyList()) }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = id.substringAfterLast('/').substringBefore('?').replace('-', ' '), image = null, filmography = emptyList())
        val poster = id.substringAfter("poster=", "").substringBefore("&").takeIf { it.isNotBlank() }?.let { URLDecoder.decode(it, "UTF-8") }
        val doc = service.getPage(id.substringBefore("?"))
        return People(id = id, name = doc.selectFirst(".data h1, h1")?.text() ?: "", image = poster ?: doc.selectFirst(".poster img")?.attr("src"), filmography = parseMixedItems(doc))
    }

    private fun parseMixedItems(doc: Document): List<Show> = doc.select("article.item").mapNotNull { el ->
        val href = el.selectFirst("a")?.attr("href").orEmpty(); if (href.isBlank()) return@mapNotNull null
        val title = el.selectFirst(".data h3 a, .data h3, h3 a, h3.title")?.text() ?: el.selectFirst("img")?.attr("alt") ?: ""
        val img = el.selectFirst(".poster img, .image img, img")?.attr("src")
        if (href.contains("/tv/")) TvShow(id = href, title = title, poster = img) else if (href.contains("/film/")) Movie(id = href, title = title, poster = img) else null
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val doc = service.getPage(id); val pId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: doc.select("#report-video").attr("data-post").ifBlank { throw Exception() }
        val type = doc.select("[data-type]").firstOrNull()?.attr("data-type") ?: doc.select("#report-video").attr("data-type").ifBlank { "movie" }
        val res = mutableListOf(Video.Server(id = (followRedirect(requestEmbedUrl(pId, "1", type))), name = "Server 1"))
        if (doc.select("[data-nume]").any { it.attr("data-nume") == "2" }) {
            val mirrorsUrl = followRedirect(requestEmbedUrl(pId, "2", type))
            Jsoup.parse(NetworkClient.default.newCall(Request.Builder().url(mirrorsUrl).header("Referer", id).build()).execute().use { it.body?.string() ?: "" }, mirrorsUrl)
                .select("ul._player-mirrors li[data-link]").filterNot { it.text().contains("4K", true) }.mapNotNull { li ->
                    val dl = li.attr("data-link").trim(); if (dl.isBlank()) return@mapNotNull null
                    val norm = if (dl.startsWith("//")) "https:$dl" else if (dl.startsWith("http")) dl else "https://$dl"
                    Video.Server(id = norm, name = (li.ownText().ifBlank { li.text() }.trim()).ifBlank { norm.toHttpUrlOrNull()?.host?.substringBefore('.') ?: "Server" })
                }.groupBy { it.id }.map { it.value.maxByOrNull { s -> if ("hd" in s.name.lowercase()) 2 else 1 }!! }.let { res.addAll(it) }
        }
        res
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    private suspend fun requestEmbedUrl(pId: String, n: String, t: String): String {
        val body = FormBody.Builder().add("action", "doo_player_ajax").add("post", pId).add("nume", n).add("type", t).build()
        return org.json.JSONObject(service.getPlayerAjax(baseUrl, body).body()?.string() ?: "").optString("embed_url").replace("\\/", "/")
    }

    private suspend fun followRedirect(url: String): String = NetworkClient.default.newCall(Request.Builder().url(url).header("Referer", baseUrl).header("User-Agent", DEFAULT_USER_AGENT).build()).execute().use { it.request.url.toString() }
    private fun normalizeUrl(r: String): String = r.trim().let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("http")) it else "https://$it" }
    private fun mapTrailerToWatchUrl(u: String): String = if (u.contains("youtube.com/embed/")) u.replace("/embed/", "/watch?v=").substringBefore("?") else u
}