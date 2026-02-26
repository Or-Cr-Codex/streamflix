package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url
import kotlin.math.round

object FrenchStreamProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "FrenchStream"
    override val defaultPortalUrl = "http://fstream.info/"
    override val portalUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL).ifEmpty { defaultPortalUrl }
    override val defaultBaseUrl = "https://fs9.lol/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }
    override val logo: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO).ifEmpty { portalUrl + "favicon-96x96.png" }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        initializeService()
        val cookie = if (UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_NEW_INTERFACE) != "false") "dle_skin=VFV25" else "dle_skin=VFV1"
        val doc = service.getHome(cookie); val categories = mutableListOf<Category>()
        
        if (cookie.contains("VFV25")) {
            doc.select("section.vod-section").forEach { sec ->
                val title = sec.selectFirst("> div.vod-header h2.vod-title-section")?.let { listOfNotNull(it.ownText().trim(), it.select("span").firstOrNull()?.text()?.trim()).joinToString(" ") } ?: ""
                val list = sec.select("> div.vod-wrap > div.vod-slider > article.vod-card").mapNotNull { item ->
                    val a = item.selectFirst("a") ?: return@mapNotNull null; val link = a.attr("href"); val id = link.substringAfterLast("/")
                    val t = a.selectFirst("div.vod-name")?.text() ?: ""; val p = a.selectFirst("div.vod-poster > img")?.attr("src") ?: ""
                    if (link.startsWith("/s-tv/") || link.contains("-saison-")) TvShow(id = id, title = t, poster = p) else Movie(id = id, title = t, poster = p)
                }
                if (list.isNotEmpty()) categories.add(Category(name = title, list = list))
            }
        } else {
            val names = listOf("Nouveautés Films", "Nouveautés Séries", "Ajouts de la Commu", "BOX OFFICE")
            doc.select("div.pages.clearfix").forEachIndexed { i, div ->
                if (i < names.size) categories.add(Category(name = names[i], list = div.select("div.short").map { el ->
                    val id = el.selectFirst("a.short-poster")?.attr("href")?.substringAfterLast("/") ?: ""
                    val t = el.selectFirst("div.short-title")?.text() ?: ""; val p = el.selectFirst("img")?.attr("src") ?: ""
                    if (i == 1) TvShow(id = id, title = listOfNotNull(t, el.selectFirst("span.film-version")?.text()).joinToString(" - "), poster = p) else Movie(id = id, title = t, poster = p)
                }))
            }
        }
        return categories
    }

    private fun ignoreSource(s: String, h: String): Boolean = s.trim().equals("Dood.Stream", true) && h.contains("/bigwar5/")

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) return service.getHome().selectFirst("div.menu-section")?.select(">a")?.map { Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) } ?: emptyList()
        
        return service.search(query).select("div.search-item").mapNotNull { el ->
            val id = el.attr("onclick").substringAfter("/").substringBefore("'"); if (id.isEmpty()) return@mapNotNull null
            val t = el.selectFirst("div.search-title")?.text()?.replace("\\'", "'") ?: ""; val p = el.selectFirst("img")?.attr("src") ?: ""
            if (id.contains("-saison-")) TvShow(id = id, title = t, poster = p) else Movie(id = id, title = t, poster = p)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()
        return service.getMovies(page).select("div#dle-content>div.short").map { el ->
            Movie(id = el.selectFirst("a.short-poster")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("div.short-title")?.text() ?: "", poster = el.selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        return service.getTvShows(page).select("div#dle-content>div.short").map { el ->
            TvShow(id = el.selectFirst("a.short-poster")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("div.short-title")?.text() ?: "", poster = el.selectFirst("img")?.attr("src"))
        }
    }

    private fun getRating(v: Element): Double {
        val plus = v.selectFirst("span.ratingtypeplusminus")?.text()?.toIntOrNull() ?: 0
        val num = v.select("span[id]").lastOrNull()?.text()?.toIntOrNull() ?: 0
        return if (num >= plus && num > 0) round((num - (num - plus) / 2.0) / num * 100) / 10 else 0.0
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val doc = service.getItem(id); val actors = extractActors(doc); val fData = doc.selectFirst("div#film-data")
        val movList = doc.select("ul#s-list li")
        return Movie(
            id = id, title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "",
            overview = doc.selectFirst("div#s-desc")?.apply { selectFirst("p.desc-text")?.remove() }?.text()?.trim() ?: "",
            released = doc.selectFirst("span.release_date")?.text()?.substringAfter("-")?.trim(),
            runtime = doc.select("span.runtime").text().substringAfter(" ").let { val h = it.substringBefore("h").toIntOrNull() ?: 0; val m = it.substringAfter("h").trim().toIntOrNull() ?: 0; h * 60 + m }.takeIf { it != 0 },
            quality = doc.selectFirst("span[id=film_quality]")?.text(),
            poster = fData?.attr("data-affiche"), banner = fData?.attr("data-affiche2"),
            trailer = fData?.attr("data-trailer")?.let { "https://www.youtube.com/watch?v=$it" },
            genres = doc.select("span.genres a").mapNotNull { Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) },
            directors = movList.find { it.selectFirst("span")?.text()?.contains("alisateur") == true }?.select("a")?.mapIndexed { i, el -> People(id = "director$i", name = el.text()) } ?: emptyList(),
            cast = actors.map { People(id = it[0].replace(" ", "+"), name = it[0], image = it[1]) },
            rating = doc.selectFirst("div.fr-votes")?.let { getRating(it) }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        val doc = service.getItem(id, "dle_skin=VFV25"); val actors = extractActors(doc); val title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val movList = doc.select("ul#s-list li")
        return TvShow(
            id = id, title = title, overview = doc.selectFirst("div.fdesc > p")?.text()?.trim() ?: "",
            released = doc.selectFirst("span.release")?.text()?.substringBefore("-")?.trim(),
            runtime = doc.select("span.runtime").text().substringAfter(" ").let { val h = it.substringBefore("h").toIntOrNull() ?: 0; val m = it.substringAfter("h").trim().toIntOrNull() ?: 0; h * 60 + m }.takeIf { it != 0 },
            quality = doc.selectFirst("span[id=film_quality]")?.text(),
            poster = doc.selectFirst("img.dvd-thumbnail")?.attr("src") ?: "",
            seasons = extractTvShowVersions(doc).map { v -> Season(id = "$id/$v/-$v", number = title.substringAfter("Saison ").trim().toIntOrNull() ?: 0, title = "Épisodes - ${v.uppercase()}") },
            genres = doc.select("span.genres").text().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { Genre(id = it, name = it) },
            directors = movList.find { it.selectFirst("span")?.text()?.contains("alisateur") == true }?.select("a")?.mapIndexed { i, el -> People(id = "director$i", name = el.text()) } ?: emptyList(),
            cast = actors.map { People(id = it[0].replace(" ", "+"), name = it[0], image = it[1]) },
            rating = doc.selectFirst("div.fr-votes")?.let { getRating(it) }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        val (tvId, lang) = seasonId.split("/"); val doc = service.getTvShow(tvId, "dle_skin=VFV25")
        val infoData = doc.selectFirst("div#episodes-info-data") ?: return emptyList()
        val infoEp = doc.selectFirst("div#episodes-$lang-data") ?: return emptyList()
        val defPoster = doc.selectFirst("img.dvd-thumbnail")?.attr("src") ?: ""

        return infoEp.select("> div").filter { el -> el.attributes().any { it.key.startsWith("data-") && it.value.startsWith("http") } }.map { el ->
            val num = el.attr("data-ep").toIntOrNull() ?: 0; val ptr = infoData.selectFirst("div[data-ep=$num]")
            Episode(id = "$tvId/$lang/$num", number = num, poster = ptr?.attr("data-poster")?.takeIf { it.isNotBlank() } ?: defPoster, title = ptr?.attr("data-title")?.trim() ?: "Episode $num", overview = ptr?.attr("data-synopsis") ?: "")
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        return Genre(id = id, name = "", shows = service.getGenre(id, page).select("div#dle-content>div.short").map { el ->
            Movie(id = el.selectFirst("a.short-poster")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("div.short-title")?.text() ?: "", poster = el.selectFirst("img")?.attr("src"))
        })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService()
        val doc = try { service.getPeople(id, page) } catch (e: HttpException) { if (e.code() == 404) return People(id, "") else throw e }
        return People(id = id, name = "", filmography = doc.select("div#dle-content > div.short").mapNotNull { el ->
            val href = el.selectFirst("a.short-poster")?.attr("href") ?: ""; val sId = href.substringAfterLast("/"); val t = el.selectFirst("div.short-title")?.text() ?: ""; val p = el.selectFirst("img")?.attr("src") ?: ""
            if (href.contains("-saison-") || href.contains("s-tv/")) TvShow(id = sId, title = t, poster = p) else if (href.isNotBlank()) Movie(id = sId, title = t, poster = p) else null
        })
    }

    private fun extractTvShowVersions(doc: Document): List<String> = listOf("vostfr", "vf").filter { v -> doc.select("div#episodes-$v-data > div").any { div -> div.attributes().any { it.value.startsWith("http") } } }

    private fun extractActors(doc: Document): List<List<String>> {
        val script = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""actorData\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)?.let { content ->
            Regex(""""(.+?)\s*\(.*?\)\s*-\s*([^"]+)"""").findAll(content).map { m -> listOf(m.groupValues[1].trim(), m.groupValues[2].trim()) }.toList()
        } ?: emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        return when (videoType) {
            is Video.Type.Episode -> {
                val (tvId, lang, num) = id.split("/")
                service.getItem(tvId, "dle_skin=VFV25").selectFirst("div#episodes-$lang-data div[data-ep=$num]")?.attributes()?.filter { it.key.startsWith("data-") && it.value.startsWith("http") }?.mapIndexedNotNull { i, attr ->
                    val name = attr.key.removePrefix("data-").replaceFirstChar { it.uppercase() }
                    if (ignoreSource(name, attr.value)) null else Video.Server(id = "vid$i", name = name, src = attr.value)
                } ?: emptyList()
            }
            is Video.Type.Movie -> {
                val labels = mapOf("vff" to "TrueFrench", "vfq" to "French", "vostfr" to "VOSTFR", "vo" to "VO")
                val seen = mutableSetOf<String>()
                service.getItem(id, "dle_skin=VFV25").selectFirst("div#film-data")?.attributes()?.filter { it.key.startsWith("data-") && !it.key.startsWith("data-affiche") && it.value.startsWith("http") }?.mapIndexed { i, attr ->
                    val name = attr.key.removePrefix("data-"); val provider = name.removeSuffix("vo").removeSuffix("vostfr").removeSuffix("vfq").removeSuffix("vff")
                    val lang = name.removePrefix(provider); object { val id = i; val provider = provider; val lang = lang; val url = attr.value }
                }?.sortedWith(compareBy({ it.provider }, { when (it.lang) { "vff" -> 1; "vfq" -> 2; "vo" -> 3; "vostfr" -> 4; else -> 10 } }))?.mapNotNull {
                    if (!seen.add(it.url)) null else Video.Server(id = "vid${it.id}", name = it.provider.replaceFirstChar { c -> c.uppercase() } + if (it.lang.isNotBlank()) " (${labels[it.lang] ?: it.lang})" else "", src = it.url)
                } ?: emptyList()
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = if (server.src.contains("newplayer", true)) (service.getRedirectLink(server.src).raw() as okhttp3.Response).request.url.toString() else server.src
        return Extractor.extract(url)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val doc = Service.buildAddressFetcher().getHome()
                    doc.select("div.container > div.url-card").selectFirst("a")?.attr("href")?.trim()?.let { url ->
                        val formatted = if (url.endsWith("/")) url else "$url/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formatted)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${formatted}favicon-96x96.png")
                    }
                } catch (_: Exception) {}
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() { initializationMutex.withLock { if (!serviceInitialized) onChangeUrl() } }

    private interface Service {
        companion object {
            fun buildAddressFetcher(): Service = Retrofit.Builder().baseUrl(portalUrl).addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
            fun build(baseUrl: String): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }
        @GET(".") suspend fun getHome(@Header("Cookie") cookie: String = "dle_skin=VFV1"): Document
        @FormUrlEncoded @POST("engine/ajax/search.php") suspend fun search(@Field("query") q: String, @Field("page") p: Int = 1): Document
        @GET("films/page/{page}/") suspend fun getMovies(@Path("page") page: Int): Document
        @GET("s-tv/page/{page}") suspend fun getTvShows(@Path("page") page: Int): Document
        @GET("/{id}") suspend fun getItem(@Path("id") id: String, @Header("Cookie") cookie: String = "dle_skin=VFV1"): Document
        @GET("films/{id}") suspend fun getMovie(@Path("id") id: String, @Header("Cookie") cookie: String = "dle_skin=VFV1"): Document
        @GET("s-tv/{id}") suspend fun getTvShow(@Path("id") id: String, @Header("Cookie") cookie: String = "dle_skin=VFV1"): Document
        @GET("film-en-streaming/{genre}/page/{page}") suspend fun getGenre(@Path("genre") g: String, @Path("page") p: Int): Document
        @GET("xfsearch/actors/{id}/page/{page}") suspend fun getPeople(@Path("id") id: String, @Path("page") p: Int): Document
        @GET suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }
}