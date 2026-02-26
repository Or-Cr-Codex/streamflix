package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import okhttp3.ResponseBody
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object CB01Provider : Provider {

    override val name = "CB01"
    override val baseUrl = "https://cb01official.uno"
    override val logo: String get() = "$baseUrl/apple-icon-180x180px.png"
    override val language = "it"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface CB01Service {
        @Headers("User-Agent: $USER_AGENT")
        @GET(".") suspend fun getHome(): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("page/{page}/") suspend fun getMovies(@Path("page") page: Int): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("serietv/") suspend fun getTvShows(): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("serietv/page/{page}/") suspend fun getTvShows(@Path("page") page: Int): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET(".") suspend fun searchMovies(@Query("s") query: String): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("page/{page}/") suspend fun searchMovies(@Path("page") page: Int, @Query("s") query: String): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("serietv/") suspend fun searchTvShows(@Query("s") query: String): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("serietv/page/{page}/") suspend fun searchTvShows(@Path("page") page: Int, @Query("s") query: String): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): CB01Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(JsoupConverterFactory.create())
                .client(NetworkClient.default)
                .build()
                .create(CB01Service::class.java)
        }
    }

    private val service = CB01Service.build(baseUrl)

    private interface StayService {
        @FormUrlEncoded
        @POST suspend fun postAjax(
            @Url url: String,
            @Field("id") id: String,
            @Field("ref") ref: String = "",
            @Header("User-Agent") userAgent: String,
            @Header("Referer") referer: String
        ): Response<ResponseBody>

        companion object {
            fun build(): StayService = Retrofit.Builder()
                .baseUrl("https://stayonline.pro/")
                .addConverterFactory(JsoupConverterFactory.create())
                .client(NetworkClient.default)
                .build()
                .create(StayService::class.java)
        }
    }

    private val stayService = StayService.build()

    private fun cleanTitle(raw: String): String {
        val withoutYearHd = raw.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").replace(Regex("\\s*\\[\\s*hd\\s*(?:/3d)?\\s*\\]", RegexOption.IGNORE_CASE), "")
        val cleanedEpisode = Regex(pattern = "\\s*[–-]\\s*\\d+[x×]\\d+(?:[./]\\d+)*\\s*[–-]\\s*([A-Za-z][A-Za-z -]{1,})\\s*$", option = RegexOption.IGNORE_CASE)
            .replace(withoutYearHd) { mr -> if (mr.groupValues.getOrNull(1)?.trim()?.startsWith("sub", ignoreCase = true) == true) " - ${mr.groupValues[1].trim()}" else "" }
        return Regex("\\s*[–-]\\s*Stagione\\s+\\d+\\s*[–-]\\s*COMPLETA\\s*$", RegexOption.IGNORE_CASE).replace(cleanedEpisode, "").trim()
    }

    private fun parseGenresText(raw: String, hasDuration: Boolean): List<Genre> {
        val base = if (hasDuration) raw.split(Regex("\\s*[–-]\\s*DURATA", RegexOption.IGNORE_CASE)).firstOrNull() ?: raw else raw
        return base.replace(Regex("\\s*\\(\\d{4}[^)]*\\)\\s*$"), "").trim().split(Regex("\\s*/\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            .map { Genre(id = it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }, name = it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }) }
    }

    private fun parseHomeMovie(el: Element): Movie? {
        val anchor = el.selectFirst("h3.card-title a[href]") ?: return null
        val title = cleanTitle(anchor.text().trim())
        if (anchor.attr("href").isBlank() || title.isBlank()) return null
        return Movie(id = anchor.attr("href").trim(), title = title, poster = el.selectFirst(".card-image img[src]")?.attr("src").orEmpty(), quality = if (anchor.text().contains("[HD]", true)) "HD" else null)
    }

    private fun parseHomeTvShow(el: Element): TvShow? {
        val anchor = el.selectFirst("h3.card-title a[href]") ?: return null
        val title = cleanTitle(anchor.text().trim())
        if (anchor.attr("href").isBlank() || title.isBlank()) return null
        return TvShow(id = anchor.attr("href").trim(), title = title, poster = el.selectFirst(".card-image img[src]")?.attr("src").orEmpty(), quality = if (anchor.text().contains("[HD]", true)) "HD" else null)
    }

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        doc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Film", list = it)) }
        doc.select("#rpwe_widget-2 ul.rpwe-ul li.rpwe-li").mapNotNull { el ->
            val anchor = el.selectFirst("h3.rpwe-title a[href]") ?: return@mapNotNull null
            Movie(id = anchor.attr("href").trim(), title = cleanTitle(anchor.text().trim()), poster = el.selectFirst("img.rpwe-thumb")?.attr("src").orEmpty().replace("-60x90", ""), quality = if (anchor.text().contains("[HD]", true)) "HD" else null)
        }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Ultimi Film Aggiunti", list = it)) }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            val home = service.getHome()
            return (home.select("#mega-menu-sequex-main-menu li:has(> a:matchesOwn(^Film HD Streaming$)) .mega-sub-menu a.mega-menu-link").mapNotNull { a ->
                val href = a.attr("href").trim(); if (href.isBlank() || !href.contains("/category/film-hd-streaming/", true)) return@mapNotNull null
                Genre(id = if (href.startsWith("http")) href else baseUrl + href, name = a.text().trim().lowercase().replaceFirstChar { it.titlecase() }.replace("hd", "Hd", true))
            } + home.select("#mega-menu-sequex-main-menu li:has(> a:matchesOwn(^Film per Genere$)) .mega-sub-menu a.mega-menu-link").mapNotNull { a ->
                val href = a.attr("href").trim(); if (href.isBlank() || !href.contains("/category/", true)) return@mapNotNull null
                Genre(id = if (href.startsWith("http")) href else baseUrl + href, name = a.text().trim().lowercase().replaceFirstChar { it.titlecase() })
            }).sortedBy { it.name }
        }
        return (try { (if (page <= 1) service.searchMovies(query) else service.searchMovies(page, query)).select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) } } catch (_: Exception) { emptyList() }) +
               (try { (if (page <= 1) service.searchTvShows(query) else service.searchTvShows(page, query)).select("div.card.mp-post.horizontal").mapNotNull { parseHomeTvShow(it) } } catch (_: Exception) { emptyList() })
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { (if (page <= 1) service.getHome() else service.getMovies(page)).select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) } } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { (if (page <= 1) service.getTvShows() else service.getTvShows(page)).select("div.card.mp-post.horizontal").mapNotNull { parseHomeTvShow(it) } } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id)
        val rawTitle = doc.selectFirst("span.breadcrumb_last")?.text()?.trim() ?: doc.selectFirst("h1, h2")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
        val tmdbDeferred = async { TmdbUtils.getMovie(title, language = language) }
        val tmdb = tmdbDeferred.await()
        
        return@coroutineScope Movie(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.sequex-featured-img.s-post img[src]")?.attr("src"),
            overview = tmdb?.overview ?: doc.select("div.ignore-css > p").firstOrNull { !it.text().contains("DURATA", true) }?.text()?.replace(Regex("\\s*\\+?Info\\s*»\\s*$", RegexOption.IGNORE_CASE), "")?.trim(),
            genres = tmdb?.genres ?: (doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { parseGenresText(it, true) } ?: emptyList()),
            trailer = tmdb?.trailer ?: doc.selectFirst("table.cbtable:has(font:matchesOwn(^Guarda il Trailer:$)) + p iframe[data-src*='youtube.com/embed/']")?.attr("data-src")?.replace("/embed/", "/watch?v="),
            quality = if (rawTitle.contains("[HD]", true)) "HD" else null, rating = tmdb?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" } ?: Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.getOrNull(1),
            runtime = tmdb?.runtime ?: doc.selectFirst("div.ignore-css > p > strong")?.text()?.let { Regex("DURATA\\s+(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() },
            banner = tmdb?.banner, imdbId = tmdb?.imdbId, cast = tmdb?.cast ?: emptyList()
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getPage(id)
        val rawTitle = doc.selectFirst("span.breadcrumb_last")?.text()?.trim() ?: doc.selectFirst("h1, h2")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language) }
        val tmdb = tmdbDeferred.await()

        val seasons = doc.select("div.sp-wrap").mapNotNull { wrap ->
            val head = wrap.selectFirst("div.sp-head")?.text()?.trim() ?: return@mapNotNull null
            if (head.contains(Regex("STAGIONE\\s+\\d+\\s+A\\s+\\d+", RegexOption.IGNORE_CASE)) || head.contains("TUTTE LE STAGIONI", true)) return@mapNotNull null
            if (wrap.selectFirst("div.sp-body")?.select("a[href]")?.none { it.text().contains("mixdrop", true) || it.attr("href").contains("stayonline.pro", true) } == true) return@mapNotNull null
            val num = Regex("STAGIONE\\s+(\\d+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            Season(id = "$id#s$num", number = num, poster = tmdb?.seasons?.find { it.number == num }?.poster)
        }

        return@coroutineScope TvShow(
            id = id, title = title, poster = tmdb?.poster ?: doc.selectFirst("div.sequex-featured-img.s-post img[src]")?.attr("src"),
            trailer = tmdb?.trailer ?: doc.selectFirst("table.cbtable:has(font:matchesOwn(^Guarda il Trailer:$)) + p iframe")?.attr("data-src")?.replace("/embed/", "/watch?v="),
            overview = tmdb?.overview ?: doc.select("div.ignore-css > p").firstOrNull { !it.text().contains("DURATA", true) }?.clone()?.apply { select("strong, b").remove() }?.text()?.replace(Regex("\\s*\\+?Info\\s*»\\s*$", RegexOption.IGNORE_CASE), "")?.trim(),
            genres = tmdb?.genres ?: (doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { parseGenresText(it, false) } ?: emptyList()),
            seasons = seasons, rating = tmdb?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" } ?: doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { Regex("\\((\\d{4})\\)").findAll(it).lastOrNull()?.groupValues?.getOrNull(1) },
            runtime = tmdb?.runtime, banner = tmdb?.banner, imdbId = tmdb?.imdbId, cast = tmdb?.cast ?: emptyList()
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val showId = seasonId.substringBefore("#s"); val num = seasonId.substringAfter("#s").toIntOrNull() ?: return@coroutineScope emptyList()
        val doc = service.getPage(showId)
        val tmdbDeferred = async { TmdbUtils.getTvShow(cleanTitle(doc.selectFirst("h1")?.text() ?: ""), language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, num, language = language) } ?: emptyList() }
        val wrap = doc.select("div.sp-wrap").firstOrNull { it.selectFirst("div.sp-head")?.text()?.contains(Regex("STAGIONE\\s+$num", RegexOption.IGNORE_CASE)) == true } ?: return@coroutineScope emptyList()
        val tmdbEps = tmdbDeferred.await()

        return@coroutineScope (wrap.selectFirst("div.sp-body")?.select("p")?.mapNotNull { p ->
            val eNum = Regex("(\\d+)[x×](\\d+)").find(p.text())?.groupValues?.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val tmdbEp = tmdbEps.find { it.number == eNum }
            Episode(id = "$showId#s${num}-e${eNum}", number = eNum, title = tmdbEp?.title, poster = tmdbEp?.poster, overview = tmdbEp?.overview)
        } ?: emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = "", filmography = emptyList())

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val movies = service.getPage(if (page <= 1) id else id.trimEnd('/') + "/page/$page/").select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }
        Genre(id = id, name = id.substringAfterLast('/').replace('-', ' ').uppercase(), shows = movies)
    } catch (_: Exception) { Genre(id = id, name = id.substringAfterLast('/'), shows = emptyList()) }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val servers = mutableListOf<Video.Server>()
        when (videoType) {
            is Video.Type.Movie -> {
                val doc = service.getPage(id)
                var section = ""; for (tbl in doc.select("table.tableinside, table.cbtable")) {
                    val head = tbl.selectFirst("u > strong")?.text()?.trim()?.lowercase(); if (head != null) { section = head; continue }
                    if (!section.startsWith("streaming") || !tbl.hasClass("tableinside")) continue
                    tbl.select("a[href]").filter { it.text().contains("mixdrop", true) }.forEach { a ->
                        val href = a.attr("href").trim(); if (href.contains("stayonline.pro", true)) {
                            Regex("/l/([A-Za-z0-9]+)/?").find(href)?.groupValues?.getOrNull(1)?.let { stayId ->
                                try {
                                    val resp = stayService.postAjax("https://stayonline.pro/ajax/linkEmbedView.php", stayId, "", USER_AGENT, "https://stayonline.pro/")
                                    JSONObject(resp.body()?.string() ?: "").takeIf { it.optString("status").equals("success", true) }?.optJSONObject("data")?.optString("value")?.let { mix ->
                                        val label = if (section.contains("3d")) "${a.text()} 3D" else if (section.contains("hd")) "${a.text()} HD" else a.text()
                                        servers.add(Video.Server(id = mix, name = label, src = mix))
                                    }
                                } catch (_: Exception) {}
                            }
                        } else servers.add(Video.Server(id = href, name = a.text(), src = href))
                    }
                }
            }
            is Video.Type.Episode -> {
                val sId = id.substringBefore("#s"); val p = id.substringAfter("#s"); val sN = p.substringBefore("-e").toIntOrNull(); val eN = p.substringAfter("-e").toIntOrNull()
                if (sN == null || eN == null) return@coroutineScope emptyList()
                val line = service.getPage(sId).select("div.sp-wrap").firstOrNull { it.selectFirst("div.sp-head")?.text()?.contains(Regex("STAGIONE\\s+$sN", RegexOption.IGNORE_CASE)) == true }
                    ?.selectFirst("div.sp-body")?.select("p")?.firstOrNull { Regex("(\\d+)[x×](\\d+)").find(it.text())?.groupValues?.getOrNull(2)?.toIntOrNull() == eN }
                line?.select("a[href]")?.filter { it.text().contains("mixdrop", true) }?.forEach { a ->
                    val href = a.attr("href").trim(); if (href.contains("stayonline.pro", true)) {
                        Regex("/l/([A-Za-z0-9]+)/?").find(href)?.groupValues?.getOrNull(1)?.let { stayId ->
                            try {
                                val resp = stayService.postAjax("https://stayonline.pro/ajax/linkEmbedView.php", stayId, "", USER_AGENT, "https://stayonline.pro/")
                                JSONObject(resp.body()?.string() ?: "").takeIf { it.optString("status").equals("success", true) }?.optJSONObject("data")?.optString("value")?.let { mix ->
                                    servers.add(Video.Server(id = mix, name = "Mixdrop", src = mix))
                                }
                            } catch (_: Exception) {}
                        }
                    } else servers.add(Video.Server(id = href, name = "Mixdrop", src = href))
                }
            }
        }
        servers
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
}