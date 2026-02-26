package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Url

object FilmPalastProvider : Provider {

    private const val BASE_URL = "https://filmpalast.to/"
    override val baseUrl = BASE_URL
    override val name = "Filmpalast"
    override val logo = "$BASE_URL/themes/downloadarchive/images/logo.png"
    override val language = "de"

    private val service = FilmpalastService.build()

    override suspend fun getHome(): List<Category> = coroutineScope {
        val docDef = async { try { service.getHome() } catch(_:Exception) { null } }
        val tvDef = async { try { service.getTvShowsHome() } catch(_:Exception) { null } }
        val doc = docDef.await(); val tvDoc = tvDef.await() ?: doc
        val categories = mutableListOf<Category>()

        doc?.let { d ->
            d.select("div.headerslider ul#sliderDla li").map { li ->
                async {
                    val t = li.select("span.title.rb").text(); val h = li.select("a.moviSliderPlay").attr("href")
                    val p = li.select("a img").attr("src").let { if (it.startsWith("/")) "https://filmpalast.to$it" else it }
                    val r = li.select("span.views b").lastOrNull()?.text()?.split("/")?.get(1)?.trim()?.toDoubleOrNull() ?: 0.0
                    val tmdb = TmdbUtils.getMovie(t, language = language)
                    Movie(id = h.substringAfterLast("/"), title = t, overview = li.select("div.moviedescription").text(), released = li.select("span.releasedate b").text(), rating = r, poster = p, banner = tmdb?.banner)
                }
            }.awaitAll().takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = Category.FEATURED, list = it)) }
            
            d.select("div#content article").map { el ->
                val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
                val info = el.select("*").toInfo(); Movie(id = h.substringAfterLast("/"), title = a?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p)
            }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Filme", list = it)) }
        }

        tvDoc?.select("div#content article")?.map { el ->
            val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
            val info = el.select("*").toInfo(); TvShow(id = h.substringAfterLast("/"), title = a?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p)
        }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Serien", list = it)) }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getHome().select("aside#sidebar section#genre ul li a").map { Genre(it.text(), it.text()) }
        val doc = if (page <= 1) service.searchNoPage(query) else service.search(query, page)
        return doc.select("div#content article").map { el ->
            val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val t = a?.text() ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
            val info = el.select("*").toInfo(); val poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p
            if (t.matches(Regex(".*S\\d+E\\d+.*"))) TvShow(id = h.substringAfterLast("/"), title = t, released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = poster)
            else Movie(id = h.substringAfterLast("/"), title = t, released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = poster)
        }.distinctBy { if (it is Movie) it.id else (it as TvShow).id }
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getMoviePage("${BASE_URL}stream/$id"); val t = doc.selectFirst("h2")?.text() ?: ""
        val tmdbDef = async { TmdbUtils.getMovie(t, language = language) }; val tmdb = tmdbDef.await()
        Movie(id = id, title = t, poster = doc.selectFirst("img.cover2")?.attr("src")?.let { if (it.startsWith("http")) it else "${BASE_URL.removeSuffix("/")}$it" },
            banner = tmdb?.banner, rating = tmdb?.rating ?: doc.selectFirst("div#star-rate")?.attr("data-rating")?.toDoubleOrNull(), overview = tmdb?.overview ?: doc.selectFirst("span[itemprop=description]")?.text(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("ul#detail-content-list > li:has(p:matchesOwn(Release)) a")?.text()?.trim(),
            genres = tmdb?.genres ?: doc.select("ul#detail-content-list > li:has(p:matchesOwn(Kategorien, Genre)) a").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("ul#detail-content-list > li:has(p:matchesOwn(Schauspieler)) a").map { p -> val n = p.text().trim(); val tmdbP = tmdb?.cast?.find { it.name.equals(n, true) }; People(id = n, name = n, image = tmdbP?.image) },
            runtime = tmdb?.runtime, imdbId = tmdb?.imdbId)
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getTvShow("${BASE_URL}stream/$id"); val tFull = doc.selectFirst("h2")?.text() ?: ""
        val tClean = tFull.replace(Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE), "").trim()
        val tmdbDef = async { TmdbUtils.getTvShow(tClean, language = language) }; val tmdb = tmdbDef.await()
        val seasons = doc.select("div#staffelWrapper div.staffelWrapperLoop").mapIndexed { i, block ->
            val num = i + 1; val eps = block.select("ul.staffelEpisodenList li a.getStaffelStream").mapIndexed { ei, a -> Episode(id = a.attr("href").let { if (it.startsWith("//")) "https:$it" else it }.substringAfterLast("/"), number = ei + 1, title = a.ownText().trim()) }
            Season(id = "${id}_$num", number = num, episodes = eps, poster = tmdb?.seasons?.find { it.number == num }?.poster)
        }
        TvShow(id = id, title = tFull, poster = doc.selectFirst("img.cover2")?.attr("src")?.let { if (it.startsWith("http")) it else "${BASE_URL.removeSuffix("/")}$it" },
            banner = tmdb?.banner, rating = tmdb?.rating ?: doc.selectFirst("div#star-rate")?.attr("data-rating")?.toDoubleOrNull(), overview = tmdb?.overview ?: doc.selectFirst("span[itemprop=description]")?.text(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("ul#detail-content-list > li:has(p:matchesOwn(Release)) a")?.text()?.trim(),
            genres = tmdb?.genres ?: doc.select("ul#detail-content-list > li:has(p:matchesOwn(Kategorien, Genre)) a").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("ul#detail-content-list > li:has(p:matchesOwn(Schauspieler)) a").map { p -> val n = p.text().trim(); val tmdbP = tmdb?.cast?.find { it.name.equals(n, true) }; People(id = n, name = n, image = tmdbP?.image) },
            seasons = seasons, imdbId = tmdb?.imdbId)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val p = seasonId.split("_"); if (p.size != 2) return@coroutineScope emptyList<Episode>()
        val doc = service.getTvShow("${BASE_URL}stream/${p[0]}"); val sNum = p[1].toIntOrNull() ?: 1
        val t = (doc.selectFirst("h2")?.text() ?: "").replace(Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE), "").trim()
        val tmdbDef = async { TmdbUtils.getTvShow(t, language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDef.await(); val eps = mutableListOf<Episode>()
        doc.select("div#staffelWrapper div.staffelWrapperLoop").getOrNull(sNum - 1)?.select("ul.staffelEpisodenList li a.getStaffelStream")?.forEachIndexed { i, a ->
            val num = i + 1; val tE = tmdbEps.find { it.number == num }
            eps.add(Episode(id = a.attr("href").let { if (it.startsWith("//")) "https:$it" else it }.substringAfterLast("/"), number = num, title = tE?.title ?: a.ownText().trim(), poster = tE?.poster, overview = tE?.overview))
        }
        eps
    }

    override suspend fun getMovies(page: Int): List<Movie> = service.getMovies(page).select("div#content article").map { el ->
        val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
        val info = el.select("*").toInfo(); Movie(id = h.substringAfterLast("/"), title = a?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p)
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getTvShows(page).select("div#content article").map { el ->
        val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
        val info = el.select("*").toInfo(); TvShow(id = h.substringAfterLast("/"), title = a?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p)
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = service.getGenre(id, page).select("div#content article").map { el ->
        val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val p = el.selectFirst("a img")?.attr("src") ?: ""
        val info = el.select("*").toInfo(); Movie(id = h.substringAfterLast("/"), title = a?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating ?: 0.0, poster = if (p.startsWith("/")) "https://filmpalast.to$p" else p)
    })

    override suspend fun getPeople(id: String, page: Int): People = service.getPeoplePage("$BASE_URL/search/title/$id").let { doc ->
        val p = doc.selectFirst("img.cover2")?.attr("src")?.let { if (it.startsWith("http")) it else "${BASE_URL.removeSuffix("/")}$it" }
        People(id = id, name = doc.selectFirst("h1")?.text() ?: "", image = p, filmography = doc.select("div#content article").map { el ->
            val a = el.selectFirst("h2 a"); val h = a?.attr("href") ?: ""; val t = a?.text() ?: ""; val pS = el.selectFirst("a img")?.attr("src") ?: ""; val i = el.select("*").toInfo(); val poster = if (pS.startsWith("/")) "https://filmpalast.to$pS" else pS
            if (t.matches(Regex(".*S\\d+E\\d+.*"))) TvShow(id = h.substringAfterLast("/"), title = t, released = i.released, quality = i.quality, rating = i.rating ?: 0.0, poster = poster)
            else Movie(id = h.substringAfterLast("/"), title = t, released = i.released, quality = i.quality, rating = i.rating ?: 0.0, poster = poster)
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = service.getMoviePage("${BASE_URL}stream/$id"); val res = mutableListOf<Video.Server>()
        doc.select("ul.currentStreamLinks").forEach { b ->
            val n = b.selectFirst("li.hostBg p.hostName")?.text()?.trim() ?: "Unbekannt"
            val u = (b.selectFirst("a[href]")?.attr("href") ?: b.selectFirst("a[data-player-url]")?.attr("data-player-url"))?.trim()
            if (!u.isNullOrEmpty()) res.add(Video.Server(id = n.split(" ")[0], name = if (listOf("bigwarp", "vinovo").none { n.lowercase().contains(it) }) n else "$n (VLC Only)", src = u))
        }
        return res
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = (service.getRedirectLink(server.src).raw() as okhttp3.Response).request.url.let { if (server.name.startsWith("VOE")) "https://voe.sx/e/${it.encodedPath.trimStart('/')}?" else it.toString() }
        return Extractor.extract(url, server)
    }

    private fun Elements.toInfo() = this.mapNotNull { it.text().trim().takeIf { s -> s.isNotEmpty() } }.let { l -> val s = select("img[src*=star_on]").size; object { val rating = (s / 10.0).takeIf { s > 0 }; val quality = l.find { it in listOf("HD", "SD", "CAM", "TS", "HDRip") }; val released = l.find { it.matches(Regex("\\d{4}")) } } }

    private interface FilmpalastService {
        companion object {
            fun build(): FilmpalastService = Retrofit.Builder().baseUrl(BASE_URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(FilmpalastService::class.java)
        }
        @GET("movies/new/page/1") suspend fun getHome(): Document
        @GET("serien/view/page/1") suspend fun getTvShowsHome(): Document
        @GET suspend fun getMoviePage(@Url u: String): Document
        @GET suspend fun getTvShow(@Url u: String): Document
        @GET("movies/new/page/{p}") suspend fun getMovies(@Path("p") p: Int): Document
        @GET("serien/view/page/{p}") suspend fun getTvShows(@Path("p") p: Int): Document
        @GET("search/title/{q}/{p}") suspend fun search(@Path("q") q: String, @Path("p") p: Int): Document
        @GET("search/title/{q}") suspend fun searchNoPage(@Path("q") q: String): Document
        @GET @Headers("User-Agent: Mozilla/5.0") suspend fun getRedirectLink(@Url u: String): Response<ResponseBody>
        @GET("search/genre/{g}/{p}") suspend fun getGenre(@Path("g") g: String, @Path("p") p: Int): Document
        @GET suspend fun getPeoplePage(@Url u: String): Document
    }
}