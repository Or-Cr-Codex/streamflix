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
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

object WiflixProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {

    override val name = "Wiflix"
    override val defaultPortalUrl = "https://ww1.wiflix-adresses.fun/"
    override val portalUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL).ifEmpty { defaultPortalUrl }

    override val defaultBaseUrl = "http://flemmix.one/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }

    override val logo: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO)
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()
    private var hasMore = true

    override suspend fun getHome(): List<Category> {
        initializeService()
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        val blockNames = listOf("TOP Séries", "TOP Films", "Films Anciens")
        
        doc.select("div.block-main").forEachIndexed { i, block ->
            if (i >= blockNames.size) return@forEachIndexed
            val list = block.select("div.mov").map { el ->
                val id = el.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
                val title = el.selectFirst("a.mov-t")?.text() ?: ""
                val poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it }
                if (i == 0) TvShow(id = id, title = listOfNotNull(title, el.selectFirst("span.block-sai")?.text()).joinToString(" - "), poster = poster)
                else Movie(id = id, title = title, poster = poster)
            }
            if (list.isNotEmpty()) categories.add(Category(name = blockNames[i], list = list))
        }
        return categories
    }

    private fun ignoreSource(s: String) = arrayOf("netu", "vudeo").any { it.equals(s, true) }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        initializeService()
        if (query.isEmpty()) return service.getHome().select("div.side-b").getOrNull(1)?.select("ul li")?.map {
            val a = it.selectFirst("a")
            Genre(id = a?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", name = a?.text() ?: "")
        } ?: emptyList()

        if (page > 1 && !hasMore) return emptyList()
        val doc = service.search(story = query, searchStart = page, resultFrom = 1 + 20 * (page - 1))
        doc.selectFirst("div.berrors")?.text()?.let { text ->
            val total = text.substringAfter("trouvé ").substringBefore(" réponses").toIntOrNull() ?: 0
            val current = text.substringAfter("Résultats de la requête ").substringBefore(")").split(" - ").getOrNull(1)?.toIntOrNull() ?: 0
            hasMore = current < total
        }

        return doc.select("div.mov").mapNotNull { el ->
            val id = el.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
            val poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it }
            val href = el.selectFirst("a.mov-t")?.attr("href") ?: ""
            if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) Movie(id = id, title = el.selectFirst("a.mov-t")?.text() ?: "", poster = poster)
            else if (href.contains("serie-en-streaming/") || href.contains("vf/")) TvShow(id = id, title = listOfNotNull(el.selectFirst("a.mov-t")?.text(), el.selectFirst("span.block-sai")?.text()).joinToString(" - "), poster = poster)
            else null
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()
        return service.getMovies(page).select("div.mov").map { el ->
            Movie(id = el.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("a.mov-t")?.text() ?: "", poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it })
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        return service.getTvShows(page).select("div.mov").map { el ->
            TvShow(id = el.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: "", title = listOfNotNull(el.selectFirst("a.mov-t")?.text(), el.selectFirst("span.block-sai")?.text()).joinToString(" - "), poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it })
        }
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val doc = service.getMovie(id)
        val movList = doc.select("ul.mov-list li")
        return Movie(
            id = id, title = doc.selectFirst("header.full-title h1")?.text() ?: "", poster = doc.selectFirst("img#posterimg")?.attr("src")?.let { baseUrl + it },
            overview = doc.selectFirst("div.screenshots-full")?.ownText()?.substringAfter("en Streaming Complet:")?.trim(),
            released = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Date de sortie") == true }?.selectFirst("div.mov-desc")?.text()?.trim(),
            runtime = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Durée") == true }?.selectFirst("div.mov-desc")?.text()?.let {
                (it.substringBefore("h").toIntOrNull() ?: 0) * 60 + (it.substringBeforeLast("min").substringAfterLast("h").trim().toIntOrNull() ?: 0)
            }?.takeIf { it != 0 },
            quality = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Qualité") == true }?.selectFirst("div.mov-desc")?.text(),
            genres = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("GENRE") == true }?.select("div.mov-desc a")?.mapNotNull { if (it.text() == "Film") null else Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) } ?: emptyList(),
            directors = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("ALISATEUR") == true }?.selectFirst("div.mov-desc span")?.text()?.split(", ")?.mapIndexed { i, n -> People(id = "director$i", name = n) } ?: emptyList(),
            cast = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("ACTEURS") == true }?.select("div.mov-desc a")?.map { People(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) } ?: emptyList(),
            recommendations = doc.select("div.related div.item").filter { !it.hasClass("cloned") }.mapNotNull { el ->
                val rId = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it }; val href = el.selectFirst("a")?.attr("href") ?: ""
                if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) Movie(id = rId, title = el.selectFirst("span.title1")?.text() ?: "", poster = poster)
                else if (href.contains("serie-en-streaming/") || href.contains("vf/")) TvShow(id = rId, title = el.selectFirst("span.title1")?.text() ?: "", poster = poster) else null
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        val doc = service.getTvShow(id)
        val title = doc.selectFirst("header.full-title h1")?.text() ?: ""
        val sNum = title.substringAfter("Saison ").trim().toIntOrNull() ?: 0
        val movList = doc.select("ul.mov-list li")
        return TvShow(
            id = id, title = title, poster = doc.selectFirst("img#posterimg")?.attr("src")?.let { baseUrl + it },
            overview = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Synopsis") == true }?.selectFirst("div.mov-desc")?.text()?.substringAfter("en Streaming Complet:")?.trim(),
            released = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Date de sortie") == true }?.selectFirst("div.mov-desc")?.text()?.trim(),
            runtime = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("Durée") == true }?.selectFirst("div.mov-desc")?.text()?.let {
                (it.substringBefore("h").toIntOrNull() ?: 0) * 60 + (it.substringBeforeLast(" mn").substringAfterLast(" ").toIntOrNull() ?: 0)
            }?.takeIf { it != 0 },
            seasons = listOfNotNull(
                Season(id = "$id/blocvostfr", title = "Épisodes - VOSTFR", number = sNum).takeIf { doc.select("div.blocvostfr ul.eplist li").size > 0 },
                Season(id = "$id/blocfr", title = "Épisodes - VF", number = sNum).takeIf { doc.select("div.blocfr ul.eplist li").size > 0 }
            ),
            directors = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("ALISATEUR") == true }?.selectFirst("div.mov-desc span")?.text()?.split(", ")?.mapIndexed { i, n -> People(id = "director$i", name = n) } ?: emptyList(),
            cast = movList.find { it.selectFirst("div.mov-label")?.text()?.contains("ACTEURS") == true }?.select("div.mov-desc a")?.map { People(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) } ?: emptyList(),
            recommendations = doc.select("div.related div.item").filter { !it.hasClass("cloned") }.mapNotNull { el ->
                val rId = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it }; val href = el.selectFirst("a")?.attr("href") ?: ""
                if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) Movie(id = rId, title = el.selectFirst("span.title1")?.text() ?: "", poster = poster)
                else if (href.contains("serie-en-streaming/") || href.contains("vf/")) TvShow(id = rId, title = el.selectFirst("span.title1")?.text() ?: "", poster = poster) else null
            }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        val (tvId, className) = seasonId.split("/")
        return service.getTvShow(tvId).select("div.$className ul.eplist li").map { Episode(id = "$tvId/${it.attr("rel")}", number = it.text().substringAfter("Episode ").toIntOrNull() ?: 0, title = it.text()) }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        return Genre(id = id, name = "", shows = service.getGenre(id, page).select("div.mov").map { Movie(id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: "", title = it.selectFirst("a.mov-t")?.text() ?: "", poster = it.selectFirst("img")?.attr("src")?.let { src -> baseUrl + src }) })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService()
        val doc = try { service.getPeople(id, page) } catch (e: HttpException) { if (e.code() == 404) return People(id, "") else throw e }
        return People(id = id, name = "", filmography = doc.select("div.mov").mapNotNull { el ->
            val rId = el.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""; val poster = el.selectFirst("img")?.attr("src")?.let { baseUrl + it }; val href = el.selectFirst("a.mov-t")?.attr("href") ?: ""
            if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) Movie(id = rId, title = el.selectFirst("a.mov-t")?.text() ?: "", poster = poster)
            else if (href.contains("serie-en-streaming/") || href.contains("vf/")) TvShow(id = rId, title = listOfNotNull(el.selectFirst("a.mov-t")?.text(), el.selectFirst("span.block-sai")?.text()).joinToString(" - "), poster = poster) else null
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        val doc = if (videoType is Video.Type.Episode) service.getTvShow(id.split("/")[0]) else service.getMovie(id)
        val selector = if (videoType is Video.Type.Episode) "div.${id.split("/")[1]} a" else "div.tabs-sel a"
        return doc.select(selector).filter { !ignoreSource(it.text().trim()) }.mapIndexed { i, el ->
            val name = el.selectFirst("span")?.text() ?: i.toString()
            Video.Server(id = name, name = name, src = el.attr("onclick").substringAfter("loadVideo('").substringBeforeLast("')"))
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val doc = Service.buildAddressFetcher().getHome()
                    val newUrl = doc.select("div.alert-success").firstOrNull { it.text().contains("Nom de domaine principal") || it.text().contains("Nouveau site") }?.selectFirst("a")?.attr("href")?.trim()?.replace("http://", "https://")
                    if (!newUrl.isNullOrEmpty()) {
                        val formatted = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formatted)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${formatted}templates/flemmixnew/images/favicon.png")
                    }
                } catch (_: Exception) {}
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock { if (!serviceInitialized) onChangeUrl() }
    }

    private interface Service {
        companion object {
            fun buildAddressFetcher(): Service = Retrofit.Builder().baseUrl(portalUrl).addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
            fun build(baseUrl: String): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }

        @GET(".") suspend fun getHome(): Document
        @POST("index.php?do=search") @FormUrlEncoded suspend fun search(@Field("story") story: String, @Field("do") doo: String = "search", @Field("subaction") subaction: String = "search", @Field("search_start") searchStart: Int = 0, @Field("full_search") fullSearch: Int = 0, @Field("result_from") resultFrom: Int = 1): Document
        @GET("film-en-streaming/page/{page}") suspend fun getMovies(@Path("page") page: Int): Document
        @GET("serie-en-streaming/page/{page}") suspend fun getTvShows(@Path("page") page: Int): Document
        @GET("film-en-streaming/{id}") suspend fun getMovie(@Path("id") id: String): Document
        @GET("serie-en-streaming/{id}") suspend fun getTvShow(@Path("id") id: String): Document
        @GET("film-en-streaming/{genre}/page/{page}") suspend fun getGenre(@Path("genre") genre: String, @Path("page") page: Int): Document
        @GET("xfsearch/acteurs/{id}/page/{page}") suspend fun getPeople(@Path("id") id: String, @Path("page") page: Int): Document
    }
}