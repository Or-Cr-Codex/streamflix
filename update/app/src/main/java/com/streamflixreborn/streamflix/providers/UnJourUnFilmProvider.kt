package com.streamflixreborn.streamflix.providers

import android.text.Html
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.ApiVoirFilmExtractor
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

object UnJourUnFilmProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "1Jour1Film"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    override val defaultPortalUrl = "https://1jour1film-officiel.site/"
    override val portalUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL).ifEmpty { defaultPortalUrl }
    override val defaultBaseUrl = "https://1jour1film0126b.site/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }
    override val logo: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO).ifEmpty { portalUrl + "wp-content/uploads/2025/07/1J1F-150x150.jpg" }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        initializeService(); val doc = service.getHome(); val categories = mutableListOf<Category>()
        doc.select("div#slider-movies-tvshows").getOrNull(0)?.select("article.item")?.map { el ->
            val h = el.selectFirst("a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: ""
            val t = el.selectFirst("h3.title")?.text() ?: ""; val b = el.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: ""
            if ((el.selectFirst("span.item_type")?.text() ?: "").contains("TV")) TvShow(id = h, title = t, banner = b) else Movie(id = h, title = t, banner = b)
        }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }

        val rEp = Regex("^(.*?)-s\\d+-episode-\\d+"); val rSai = Regex("^(.*?)-saison-\\d+")
        doc.select("header").filter { it.children().firstOrNull()?.tagName() == "h2" }.forEach { part ->
            var sib = part.nextElementSibling(); while (sib != null && !(sib.tagName() == "header" && sib.selectFirst("h2") != null)) {
                sib.select("article.item").mapNotNull { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNull null; val img = el.selectFirst("img")
                    if (el.hasClass("movies")) Movie(id = a.attr("href").substringBeforeLast("/").substringAfterLast("/"), title = img?.attr("alt") ?: "", poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: "")
                    else { var id = a.attr("href").substringBeforeLast("/").substringAfterLast("/"); id = rEp.find(id)?.groupValues?.get(1) ?: rSai.find(id)?.groupValues?.get(1) ?: id; TvShow(id = id, title = img?.attr("alt") ?: "", poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: "") }
                }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(part.selectFirst("h2")?.text() ?: "", it)) }
                sib = sib.nextElementSibling()
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) return service.getHome().selectFirst("ul.mega-sub-menu:has(li.mega-menu-item-object-genres)")?.select("li.mega-menu-item-object-genres")?.mapNotNull { it.selectFirst(">a")?.let { a -> Genre(id = a.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = a.text()) } } ?: emptyList()
        return service.search(query).select("div.result-item > article").mapNotNull { el ->
            val a = el.selectFirst("div.title")?.selectFirst("a"); val h = a?.attr("href") ?: ""; val id = h.substringBeforeLast("/").substringAfterLast("/")
            if (h.contains("/films/")) Movie(id = id, title = a?.text() ?: "", poster = el.selectFirst("img.lazyload")?.attr("data-src"))
            else if (h.contains("/tvshows/")) TvShow(id = id, title = a?.text() ?: "", poster = el.selectFirst("img.lazyload")?.attr("data-src")) else null
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService(); val doc = service.getMovies(page); var res = emptyList<Movie>()
        if (page == 1) {
            res = doc.select("div#slider-movies").getOrNull(0)?.select("article.item")?.map { Movie(id = it.selectFirst("a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", title = it.selectFirst("h3.title")?.text() ?: "", poster = it.selectFirst("img")?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } ?: emptyList()
            doc.selectFirst("div.items.featured")?.select("article.item")?.mapNotNull { el -> el.selectFirst("a")?.let { a -> val img = el.selectFirst("img"); Movie(id = a.attr("href"), title = img?.attr("alt") ?: "", poster = img?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } }?.let { res = res + it }
        }
        doc.selectFirst("div.items.full")?.select("article.item")?.mapNotNull { el -> el.selectFirst("a")?.let { a -> val img = el.selectFirst("img"); Movie(id = a.attr("href"), title = img?.attr("alt") ?: "", poster = img?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } }?.let { res = res + it }
        return res
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService(); val doc = service.getTvShows(page); var res = emptyList<TvShow>()
        if (page == 1) {
            res = doc.select("div#slider-tvshows").getOrNull(0)?.select("article.item")?.map { TvShow(id = it.selectFirst("a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", title = it.selectFirst("h3.title")?.text() ?: "", poster = it.selectFirst("img")?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } ?: emptyList()
            doc.selectFirst("div.items.featured")?.select("article.item")?.mapNotNull { el -> el.selectFirst("a")?.let { a -> val img = el.selectFirst("img"); TvShow(id = a.attr("href"), title = img?.attr("alt") ?: "", poster = img?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } }?.let { res = res + it }
        }
        doc.selectFirst("div.items.full")?.select("article.item")?.mapNotNull { el -> el.selectFirst("a")?.let { a -> val img = el.selectFirst("img"); TvShow(id = a.attr("href"), title = img?.attr("alt") ?: "", poster = img?.let { i -> i.attr("src").ifBlank { i.attr("data-src") } } ?: "") } }?.let { res = res + it }
        return res
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService(); val doc = service.getMovie(id); val script = doc.head().selectFirst("script[type=application/ld+json]:not([class])")?.data() ?: ""
        val t = script.substringAfter("name\":\"").substringBefore("\","); val o = script.substringAfter("description\":\"").substringBefore("\",").substringAfter(": ").substringBeforeLast(" Voir ")
        return Movie(id = id, title = decode(t), overview = decode(o), released = id.substringAfterLast("-"), runtime = doc.select("span.runtime").text().substringAfter(" ").let { val h = it.substringBefore("h").toIntOrNull() ?: 0; val m = it.substringAfter("h").trim().toIntOrNull() ?: 0; h * 60 + m }.takeIf { it != 0 }, quality = doc.selectFirst("div.fakeplayer span.quality")?.text(), poster = doc.selectFirst("div.poster > img.lazyload")?.attr("data-src") ?: "", trailer = script.substringAfter("\"embedUrl\":").substringBefore(",").replace("\"", "").takeIf { it != "null" && it.isNotEmpty() }?.substringBefore("?")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            genres = doc.select("div.sgeneros a").map { Genre(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) }, directors = doc.select("div.persons > div.person[itemprop=director] a[itemprop=url]").map { People(it.attr("href") ?: "", it.text() ?: "") }, cast = doc.select("div.persons > div.person[itemprop=actor]").map { val a = it.selectFirst("a[itemprop=url]"); People(id = a?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", name = a?.text() ?: "", image = it.selectFirst("div.img > a > img")?.attr("data-src") ?: "") }, rating = doc.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")?.text()?.toDoubleOrNull(),
            recommendations = doc.select("div#single_relacionados > article").map { val a = it.selectFirst("a"); val img = it.selectFirst("img.lazyload"); val h = a?.attr("href") ?: ""; if (h.contains("/films/")) Movie(h.substringBeforeLast("/").substringAfterLast("/"), img?.attr("alt") ?: "", poster = img?.attr("data-src") ?: "") else TvShow(h.substringBeforeLast("/").substringAfterLast("/"), img?.attr("alt") ?: "", poster = img?.attr("data-src") ?: "") })
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService(); val doc = service.getTvShow(id); val script = doc.head().selectFirst("script[type=application/ld+json]:not([class])")?.data() ?: ""
        val t = script.substringAfter("name\":\"").substringBefore("\","); var o = decode(doc.selectFirst("div.wp-content")?.text() ?: ""); if (o.startsWith("Regarder ")) o = o.substringAfter(": ")
        var rf = ""; var rl = ""; val seasons = doc.selectFirst("div#seasons")?.select("div.se-c")?.mapIndexed { i, s -> val rel = (s.selectFirst("span.title i")?.text() ?: "").substringAfterLast(", "); if (rf.isEmpty()) rf = rel; rl = rel; val title = (s.selectFirst("span.title")?.text() ?: "Saison $i").replaceAfterLast(")", ""); Season("$id/$i", title.substringAfter("Saison ").substringBefore(" ").toInt(), title, s.selectFirst("img.lazyload")?.attr("data-src")) } ?: emptyList()
        return TvShow(id = id, title = decode(t), overview = o, released = if (rf != rl) "$rl-$rf" else rf, poster = doc.selectFirst("div.poster > img.lazyload")?.attr("data-src") ?: "", seasons = seasons, trailer = doc.selectFirst("div#trailer div.embed iframe")?.attr("src")?.substringBefore("?")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            genres = doc.select("div.sgeneros a").map { Genre(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) }, directors = doc.select("div.persons > div.person[itemprop=director] a[itemprop=url]").map { People(it.attr("href") ?: "", it.text() ?: "") }, cast = doc.select("div.persons > div.person[itemprop=actor]").map { val a = it.selectFirst("a[itemprop=url]"); People(id = a?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", name = a?.text() ?: "", image = it.selectFirst("div.img > a > img")?.attr("data-src") ?: "") }, rating = doc.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")?.text()?.toDoubleOrNull(),
            recommendations = doc.select("div#single_relacionados > article").map { val a = it.selectFirst("a"); val img = it.selectFirst("img.lazyload"); val h = a?.attr("href") ?: ""; if (h.contains("/films/")) Movie(h.substringBeforeLast("/").substringAfterLast("/"), img?.attr("alt") ?: "", poster = img?.attr("data-src") ?: "") else TvShow(h.substringBeforeLast("/").substringAfterLast("/"), img?.attr("alt") ?: "", poster = img?.attr("data-src") ?: "") })
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        initializeService(); val (tvId, sNum) = seasonId.split("/"); val doc = service.getTvShow(tvId); val season = doc.selectFirst("div#seasons")?.select("div.se-c")?.getOrNull(sNum.toInt())
        val defPoster = doc.selectFirst("div.poster > img.lazyload")?.attr("data-src") ?: ""
        season?.select("ul.episodios > li")?.mapIndexed { i, ep -> val a = ep.selectFirst("div.episodiotitle > a"); val id = a?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: ""; async { Episode(id, i + 1, a?.text() ?: "", ep.selectFirst("img.lazyload")?.attr("data-src") ?: defPoster, decode(service.getEpisode(id).selectFirst("div.wp-content > p")?.text() ?: "")) } }?.awaitAll() ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService(); val doc = try { service.getGenre(id, page) } catch (e: HttpException) { if (e.code() == 404) return Genre(id, "") else throw e }
        return Genre(id, "", doc.select("div.items.full > article.item").mapNotNull { val a = it.selectFirst("div.data a"); val h = a?.attr("href") ?: ""; val sId = h.substringBeforeLast("/").substringAfterLast("/")
            if (h.contains("films/")) Movie(sId, a?.text() ?: "", poster = it.selectFirst("img")?.attr("src")) else if (h.contains("tvshows/")) TvShow(sId, a?.text() ?: "", poster = it.selectFirst("img")?.attr("src")) else null })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService(); val doc = try { service.getPeople(id, page) } catch (e: HttpException) { if (e.code() == 404) return People(id, "") else throw e }
        return People(id, "", filmography = doc.select("div.items.full > article.item").mapNotNull { val a = it.selectFirst("div.data a"); val h = a?.attr("href") ?: ""; val sId = h.substringBeforeLast("/").substringAfterLast("/")
            if (h.contains("/films/")) Movie(sId, a?.text() ?: "", poster = it.selectFirst("img")?.attr("src") ?: "") else if (h.contains("/tvshows/")) TvShow(sId, a?.text() ?: "", poster = it.selectFirst("img")?.attr("src") ?: "") else null })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService(); val apivf = ApiVoirFilmExtractor(); var apiU = ""; val doc = if (videoType is Video.Type.Episode) service.getEpisode(id) else service.getMovie(id)
        val res = doc.selectFirst("ul#playeroptionsul")?.select("li.dooplay_player_option")?.mapIndexedNotNull { i, el ->
            val link = service.getServers(p = el.attr("data-post"), n = el.attr("data-nume"), t = if (videoType is Video.Type.Episode) "tv" else "movie"); if (link.embed_url.isNullOrEmpty() || (arrayOf("youtube.").any { link.embed_url!!.contains(it, true) })) return@mapIndexedNotNull null
            if (link.embed_url!!.startsWith(apivf.mainUrl)) { apiU = link.embed_url!!; return@mapIndexedNotNull null }
            Video.Server("srv$i", el.selectFirst("span.title")?.text() ?: "Server $i", link.embed_url!!)
        }?.toMutableList() ?: mutableListOf()
        if (apiU.isNotEmpty()) res.addAll(apivf.expand(apiU, baseUrl, "FR "))
        return res
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    private fun decode(s: String): String = try { val t = Html.fromHtml(s).toString(); JSONObject("{\"v\":\"$t\"}").getString("v") } catch(_:Exception) { s }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try { val doc = Service.buildAddressFetcher().getHome(); val url = doc.html().substringAfter("window.location.href = \"").substringBefore("\"").trim()
                    if (url.isNotEmpty()) { val formatted = if (url.endsWith("/")) url else "$url/"; UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formatted); UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, doc.selectFirst("link[rel=apple-touch-icon]")?.attr("href") ?: "$defaultPortalUrl/wp-content/uploads/2025/07/1J1F-150x150.jpg") }
                } catch (_: Exception) {}
            }
            service = Service.build(baseUrl); serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() { initializationMutex.withLock { if (!serviceInitialized) onChangeUrl() } }

    private interface Service {
        companion object {
            fun buildAddressFetcher(): Service = Retrofit.Builder().baseUrl(portalUrl).addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
            fun build(baseUrl: String): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }
        @GET(".") suspend fun getHome(@Header("User-agent") ua: String = USER_AGENT): Document
        @GET("/") suspend fun search(@Query("s") q: String, @Header("User-agent") ua: String = USER_AGENT): Document
        @GET("films/page/{p}/") suspend fun getMovies(@Path("p") p: Int, @Header("User-agent") ua: String = USER_AGENT): Document
        @POST("wp-admin/admin-ajax.php") @FormUrlEncoded suspend fun getServers(@Field("action") a: String = "doo_player_ajax", @Field("post") p: String, @Field("nume") n: String, @Field("type") t: String = "movie", @Header("User-agent") ua: String = USER_AGENT): itemLink
        @GET("tvshows/page/{p}/") suspend fun getTvShows(@Path("p") p: Int, @Header("User-agent") ua: String = USER_AGENT): Document
        @GET("episodes/{id}/") suspend fun getEpisode(@Path("id") id: String, @Header("User-agent") ua: String = USER_AGENT): Document
        @GET("films/{id}/") suspend fun getMovie(@Path("id") id: String, @Header("User-agent") ua: String = USER_AGENT): Document
        @GET("tvshows/{id}/") suspend fun getTvShow(@Path("id") id: String, @Header("User-agent") ua: String = USER_AGENT): Document
        @GET("genre/{g}/page/{p}/") suspend fun getGenre(@Path("g") g: String, @Path("p") p: Int, @Header("User-agent") cookie: String = USER_AGENT): Document
        @GET("cast/{id}/page/{p}") suspend fun getPeople(@Path("id") id: String, @Path("p") p: Int, @Header("User-agent") cookie: String = USER_AGENT): Document
    }
    data class itemLink(val embed_url: String?, val type: String?)
}