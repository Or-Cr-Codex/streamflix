package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.ResponseBody

object FrenchAnimeProvider : Provider, ProviderConfigUrl {
    override val defaultBaseUrl = "https://french-anime.com/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }
    override val name = "FrenchAnime"
    override val logo: String get() = "$baseUrl/templates/franime/images/favicon3.png"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private var service = Service.build()
    private var hasMore = true
    private const val PAGE_SIZE = 10

    override suspend fun getHome(): List<Category> = coroutineScope {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        
        val featuredDef = async {
            doc.select(".owl-carousel .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null; val h = a.attr("href")
                TvShow(id = h.substringAfterLast("/").substringBefore(".html"), title = a.selectFirst(".title1")?.text() ?: "", overview = a.selectFirst(".title0")?.text(), banner = a.selectFirst("img")?.attr("src")?.toUrl())
            }
        }

        val blocks = doc.select(".block-main").map { block ->
            async {
                val name = block.selectFirst(".block-title .left-ma")?.text() ?: ""
                val list = block.select(".mov").mapNotNull { mov ->
                    val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val h = a.attr("href"); val id = h.substringAfterLast("/").substringBefore(".html")
                    val isFr = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")?.text()).isFr()
                    val t = if (name.contains("FILMS", true)) a.text().toTitle(isFr) else a.text(); val p = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""
                    if (mov.selectFirst(".block-ep") != null) {
                        val sNum = mov.selectFirst(".block-sai")?.text()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                        val eNum = mov.selectFirst(".block-ep")?.text()?.split(" ")?.lastOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 0
                        TvShow(id = id, title = t, poster = p, seasons = if (sNum > 0 && eNum > 0) listOf(Season("", sNum, episodes = listOf(Episode("", eNum)))) else emptyList())
                    } else Movie(id = id, title = t, poster = p)
                }
                if (list.isNotEmpty()) Category(name, list) else null
            }
        }

        val featured = featuredDef.await()
        if (featured.isNotEmpty()) categories.add(Category(Category.FEATURED, featured))
        categories.addAll(blocks.awaitAll().filterNotNull())
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getHome().select("div.side-b nav.side-c ul.flex-row li a").map { Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) }
        if (page > 1 && !hasMore) return emptyList()
        val doc = service.search(q = query, ss = page, rf = if (page > 1) (page - 1) * PAGE_SIZE + 1 else -1)
        doc.selectFirst("div.berrors")?.let { t -> val res = t.text(); val tot = res.substringAfter("Trouvé ").substringBefore(" réponses").toIntOrNull() ?: 0; val cur = res.substringAfter("Résultats de la requête ").substringBefore(")").split(" - ").getOrNull(1)?.toIntOrNull() ?: 0; hasMore = cur < tot }
        return doc.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val h = a.attr("href"); val id = h.substringAfterLast("/").substringBefore(".html")
            val isFr = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")?.text()).isFr(); val t = a.text().toTitle(isFr); val p = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
            if (mov.selectFirst(".block-ep") != null) TvShow(id = id, title = t, poster = p) else Movie(id = id, title = t, poster = p)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getMovies(page).select("div.mov.clearfix").mapNotNull { mov ->
        val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val h = a.attr("href")
        Movie(id = h.substringAfterLast("/").substringBefore(".html"), title = a.text().toTitle(mov.selectFirst(".nbloc1")?.text().isFr()), poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl())
    } } catch (e: HttpException) { if (e.code() == 404) emptyList() else throw e }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        val vf = async { fetchTv("animes-vf/page/$page/") }; val vost = async { fetchTv("animes-vostfr/page/$page/") }
        (vf.await() + vost.await()).distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie = service.getMovie(id).let { doc ->
        val isFr = doc.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")?.text().isFr()
        val mov = doc.select("ul.mov-list li")
        Movie(id = id, title = doc.selectFirst("header.full-title h1")?.text()?.toTitle(isFr) ?: "", overview = doc.selectFirst("span[itemprop='description']")?.text() ?: "", released = mov.find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = mov.find { it.selectFirst("div.mov-label")?.text() == "Durée:" }?.selectFirst("div.mov-desc")?.text()?.extractRt(), poster = doc.selectFirst("div.mov-img img[itemprop='thumbnailUrl']")?.attr("src")?.toUrl(),
            genres = mov.find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }?.select("span[itemprop='genre'] a")?.map { Genre(it.attr("href").substringAfter("/genre/").substringBefore("/"), it.text()) } ?: emptyList(),
            cast = mov.find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }?.selectFirst("div.mov-desc span[itemprop='name']")?.text().toPeople())
    }

    override suspend fun getTvShow(id: String): TvShow = service.getTvShow(id).let { doc ->
        val isFr = doc.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")?.text().isFr()
        val mov = doc.select("ul.mov-list li")
        TvShow(id = id, title = doc.selectFirst("h1[itemprop=name]")?.text()?.toTitle(isFr) ?: "", overview = doc.selectFirst("span[itemprop=description]")?.text(), released = mov.find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = mov.find { it.selectFirst("div.mov-label")?.text() == "Durée:" }?.selectFirst("div.mov-desc")?.text()?.extractRt(), poster = doc.selectFirst("div.mov-img img[itemprop='thumbnailUrl']")?.attr("src")?.toUrl(), seasons = listOf(Season(id, 0, "Episodes")),
            genres = mov.find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }?.select("span[itemprop='genre'] a")?.map { Genre(it.attr("href").substringAfter("/genre/").substringBefore("/"), it.text()) } ?: emptyList(),
            cast = mov.find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }?.selectFirst("div.mov-desc span[itemprop='name']")?.text().toPeople())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = service.getTvShow(seasonId).selectFirst("div.eps")?.text()?.split(" ")?.mapNotNull { val p = it.split("!"); if (p.size == 2) Episode("${seasonId}_${p[0]}", p[0].toIntOrNull() ?: 0) else null } ?: emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val doc = service.getGenre(id, page); val shows = doc.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val itemId = a.attr("href").substringAfterLast("/").substringBefore(".html")
            val isFr = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")?.text()).isFr(); val t = a.text().toTitle(isFr); val p = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
            if (mov.selectFirst(".block-ep") != null) TvShow(itemId, t, poster = p) else Movie(itemId, t, poster = p)
        }
        Genre(id, doc.title().substringBefore(" - French Anime").substringBefore(" »"), shows)
    } catch (e: HttpException) { if (e.code() == 404) Genre(id, "") else throw e }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1 && !hasMore) return People(id, id)
        val doc = service.getPeople(q = id, ss = page, rf = if (page > 1) (page - 1) * PAGE_SIZE + 1 else -1)
        doc.selectFirst("div.berrors")?.let { t -> val res = t.text(); val tot = res.substringAfter("Trouvé ").substringBefore(" réponses").toIntOrNull() ?: 0; val cur = res.substringAfter("Résultats de la requête ").substringBefore(")").split(" - ").getOrNull(1)?.toIntOrNull() ?: 0; hasMore = cur < tot }
        return People(id = id, name = id, filmography = doc.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val itemId = a.attr("href").substringAfterLast("/").substringBefore(".html")
            val isFr = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")?.text()).isFr(); val t = a.text().toTitle(isFr); val p = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
            if (mov.selectFirst(".block-ep") != null) TvShow(itemId, t, poster = p) else Movie(itemId, t, poster = p)
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val eps = service.getTvShow(if (videoType is Video.Type.Movie) id else id.substringBeforeLast("_")).selectFirst("div.eps")?.text()?.split(" ") ?: return emptyList()
        val sfx = id.substringAfterLast("_")
        return eps.firstNotNullOfOrNull { l -> val p = l.split("!"); if (p.size == 2 && (p[0] == sfx || videoType is Video.Type.Movie)) p[1].split(",").filter { s -> s.startsWith("http") }.mapIndexed { i, s -> Video.Server(i.toString(), s.extractDom(), s) } else null } ?: emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    private suspend fun fetchTv(path: String): List<TvShow> = try { service.getTvSeries(path).select("div.mov.clearfix").mapNotNull { mov ->
        val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null; val id = a.attr("href").substringAfterLast("/").substringBefore(".html")
        val isFr = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")?.text()).isFr(); val t = a.text().toTitle(isFr); val p = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""
        val eT = mov.selectFirst(".block-ep")?.text() ?: return@mapNotNull null; val eN = eT.split(" ").lastOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 0
        val sN = mov.selectFirst(".block-sai")?.text()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        TvShow(id, t, poster = p, seasons = if (sN > 0 && eN > 0) listOf(Season("", sN, episodes = listOf(Episode("", eN)))) else emptyList())
    } } catch (_: Exception) { emptyList() }

    private fun String.toUrl() = if (startsWith("/")) baseUrl.dropLast(1) + this else this
    private fun String?.isFr() = this?.contains("FRENCH", true) ?: false
    private fun String.toTitle(isFr: Boolean) = if (isFr) "(FR) $this".replace(" FRENCH", "") else this.replace(" VOSTFR", "")
    private fun String?.toPeople(): List<People> = this?.split(", ")?.map { it.replace(Regex("[^\\p{L}\\d ]"), " ").trim() }?.filter { it.isNotEmpty() }?.map { People(it, it, "") } ?: emptyList()
    private fun String.extractDom() = Regex("""(?:https?:)?//(?:www\.)?([^.]+)\.""").find(this)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: this
    private fun String.extractRt() = when { contains("h") -> (substringBefore("h").toIntOrNull() ?: 0) * 60 + (substringAfter("h ").substringBefore("min").toIntOrNull() ?: 0); contains("min") -> substringBefore(" min").toIntOrNull(); contains("mn") -> substringBefore(" mn").toIntOrNull(); else -> null }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String { changeUrlMutex.withLock { service = Service.build() }; return baseUrl }

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }
        @GET(".") suspend fun getHome(@Header("Cookie") cookie: String = "dle_skin=VFV1"): Document
        @POST(".") @FormUrlEncoded suspend fun search(@Field("do") d: String = "search", @Field("subaction") s: String = "search", @Field("story") q: String, @Field("search_start") ss: Int = -1, @Field("result_from") rf: Int = -1, @Field("full_search") f: Int = 0): Document
        @GET("films-vf-vostfr/page/{p}") suspend fun getMovies(@Path("p") p: Int): Document
        @GET("{path}") suspend fun getTvSeries(@Path("path") path: String): Document
        @GET("films-vf-vostfr/{id}.html") suspend fun getMovie(@Path("id") id: String): Document
        @GET("{id}.html") suspend fun getTvShow(@Path("id") id: String): Document
        @GET("genre/{g}/page/{p}") suspend fun getGenre(@Path("g") g: String, @Path("p") p: Int): Document
        @POST(".") @FormUrlEncoded suspend fun getPeople(@Field("do") d: String = "search", @Field("subaction") s: String = "search", @Field("story") q: String, @Field("search_start") ss: Int = -1, @Field("result_from") rf: Int = -1, @Field("full_search") f: Int = 0): Document
        @GET suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }
}