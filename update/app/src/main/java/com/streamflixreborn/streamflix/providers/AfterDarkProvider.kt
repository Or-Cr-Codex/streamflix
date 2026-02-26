package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.AfterDarkExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.Response
import kotlinx.coroutines.Deferred

object AfterDarkProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "AfterDark"
    override val defaultPortalUrl: String = "https://topsitestreaming.club/site/afterdark/"
    override val portalUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL).ifEmpty { defaultPortalUrl }
    override val defaultBaseUrl = "https://afterdark.mom/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }
    override val logo: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO).ifEmpty { baseUrl + "logo.png" }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()
    private var homeUrl: String? = null; private var movieUrl: String? = null; private var tvUrl: String? = null; private var searchUrl: String? = null

    data class AfterDarkItem(val tmdbId: Int, val title: String, val posterPath: String?, val backdropPath: String?, val kind: String, val season: Int?, val episode: Int?)
    data class AfterDarkResponse(val items: List<AfterDarkItem>)

    private fun AfterDarkItem.toShow(): Show = if (kind == "show") TvShow(id = tmdbId.toString(), title = title + if (season != null && episode != null) " - S${season}E${episode}" else "", banner = backdropPath?.original, poster = posterPath?.w500)
    else Movie(id = tmdbId.toString(), title = title, banner = backdropPath?.original, poster = posterPath?.w500)

    private fun TMDb3.MultiItem.toAppItem(): AppAdapter.Item? = when (this) {
        is TMDb3.Movie -> Movie(id = id.toString(), title = title, overview = overview, released = releaseDate, rating = voteAverage.toDouble(), poster = posterPath?.w500, banner = backdropPath?.original)
        is TMDb3.Tv -> TvShow(id = id.toString(), title = name, overview = overview, released = firstAirDate, rating = voteAverage.toDouble(), poster = posterPath?.w500, banner = backdropPath?.original)
        else -> null
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        initializeService()
        val categories = mutableListOf<Category>()
        val carouselDef = async { try { service.getCarousel("home").items.map { it.toShow() } } catch (_: Exception) { emptyList<Show>() } }
        
        val html = try { homeUrl?.let { service.loadPage(it).body() } } catch (_: Exception) { null }
        if (!html.isNullOrEmpty()) {
            val queryMatches = Regex("""queryFn\s*:\s*\(\)\s*=>\s*e\("([^"]+)"(?:,\{([^}]*)\})?\)""").findAll(html).toList()
            val titles = Regex("""title:"([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            val deferreds = mutableListOf<Deferred<Category?>>()
            queryMatches.forEachIndexed { i, m ->
                val endpoint = m.groupValues[1]
                val paramsRaw = m.groupValues.getOrNull(2)
                deferreds.add(async {
                    val p = Regex("""["]?([\w.]+)["]?:["]([^"]*)["]""").findAll(paramsRaw ?: "").associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap().apply { putIfAbsent("language", "fr-FR") }
                    val list = when {
                        endpoint.contains("discover/movie") -> TMDb3.Discover.movie(p)
                        endpoint.contains("discover/tv") -> TMDb3.Discover.tv(p)
                        endpoint.contains("tv/top_rated") -> TMDb3.TvSeriesLists.topRated(p)
                        endpoint.contains("movie/top_rated") -> TMDb3.MovieLists.topRated(p)
                        else -> return@async null
                    }
                    Category(name = titles.getOrElse(i + 2) { "Autres $i" }, list = list.results.mapNotNull { it.toAppItem() })
                })
            }
            categories.addAll(deferreds.awaitAll().filterNotNull())
        }
        
        val advDef = async { try { listOf(service.getFeat("home"), service.getFeat("movies"), service.getFeat("shows")).map { it.toShow() } } catch (_: Exception) { emptyList<Show>() } }
        
        val carousel = carouselDef.await()
        if (carousel.isNotEmpty()) categories.add(0, Category(Category.FEATURED, carousel))
        
        val advisors = advDef.await()
        if (advisors.isNotEmpty()) categories.add(minOf(2, categories.size), Category("Les recommandations du mois", advisors))
        
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page == 1 && query.isEmpty()) {
            initializeService(); val html = searchUrl?.let { try { service.loadPage(it).body() } catch(_:Exception) { null } } ?: ""
            return Regex("""\{\s*name\s*:\s*"([^"]+)"\s*,\s*slug\s*:\s*"([^"]*)"\s*,\s*movieIds\s*:\s*\[([^\]]*)]\s*,\s*tvIds\s*:\s*\[([^\]]*)]\s*\}""").findAll(html).map { m -> Genre(id = "${m.groupValues[3]}/${m.groupValues[4]}/${m.groupValues[1]}", name = m.groupValues[1]) }.toList()
        }
        return TMDb3.Search.multi(query, page = page, language = "fr-FR").results.mapNotNull { it.toAppItem() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        if (page > 1) return@coroutineScope emptyList<Movie>()
        initializeService(); val res = mutableListOf<Movie>()
        try { res.addAll(service.getCarousel("movies").items.map { it.toShow() as Movie }) } catch (_: Exception) {}
        movieUrl?.let { try { service.loadPage(it).body() } catch(_:Exception) { null } }?.let { html ->
            val deferreds = mutableListOf<Deferred<List<Movie>>>()
            Regex("""queryFn\s*:\s*\(\)\s*=>\s*e\("([^"]+)"(?:,\{([^}]*)\})?\)""").findAll(html).forEach { m ->
                deferreds.add(async {
                    val p = Regex("""["]?([\w.]+)["]?:["]([^"]*)["]""").findAll(m.groupValues[2] ?: "").associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap().apply { putIfAbsent("language", "fr-FR") }
                    TMDb3.Discover.movie(p).results.mapNotNull { it.toAppItem() as? Movie }
                })
            }
            res.addAll(deferreds.awaitAll().flatten())
        }
        res
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        if (page > 1) return@coroutineScope emptyList<TvShow>()
        initializeService(); val res = mutableListOf<TvShow>()
        try { res.addAll(service.getCarousel("shows").items.map { it.toShow() as TvShow }) } catch (_: Exception) {}
        tvUrl?.let { try { service.loadPage(it).body() } catch(_:Exception) { null } }?.let { html ->
            val deferreds = mutableListOf<Deferred<List<TvShow>>>()
            Regex("""queryFn\s*:\s*\(\)\s*=>\s*e\("([^"]+)"(?:,\{([^}]*)\})?\)""").findAll(html).forEach { m ->
                deferreds.add(async {
                    val p = Regex("""["]?([\w.]+)["]?:["]([^"]*)["]""").findAll(m.groupValues[2] ?: "").associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap().apply { putIfAbsent("language", "fr-FR") }
                    TMDb3.Discover.tv(p).results.mapNotNull { it.toAppItem() as? TvShow }
                })
            }
            res.addAll(deferreds.awaitAll().flatten())
        }
        res
    }

    override suspend fun getMovie(id: String): Movie = TmdbProvider("fr").getMovie(id)
    override suspend fun getTvShow(id: String): TvShow = TmdbProvider("fr").getTvShow(id)
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = TmdbProvider("fr").getEpisodesBySeason(seasonId)
    override suspend fun getGenre(id: String, page: Int): Genre {
        val p = id.split("/"); val list = mutableListOf<Show>()
        if (p[0].isNotBlank()) list.addAll(TMDb3.Discover.movie(withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(p[0].toIntOrNull() ?: 0), language = "fr-FR", page = page).results.mapNotNull { it.toAppItem() as? Show })
        if (p[1].isNotBlank()) list.addAll(TMDb3.Discover.tv(withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(p[1].toIntOrNull() ?: 0), language = "fr-FR", page = page).results.mapNotNull { it.toAppItem() as? Show })
        return Genre(id = "${p[0]}/${p[1]}", name = p[2], shows = list)
    }

    override suspend fun getPeople(id: String, page: Int): People = TmdbProvider("fr").getPeople(id, page)
    override suspend fun getVideo(server: Video.Server): Video = server.video!!
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = AfterDarkExtractor(baseUrl).servers(videoType)

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val res = Service.buildAddressFetcher().getPortalHome()
                    val html = res.body() ?: ""
                    Regex("""slug:"afterdark".*?domain:"([^"]+)"""").find(html)?.groupValues?.get(1)?.let { url ->
                        val formatted = if (url.endsWith("/")) url else "$url/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formatted)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${formatted}logo.png")
                    }
                } catch (_: Exception) {}
            }
            service = Service.build(baseUrl)
            try {
                homeUrl = getRealUrlFor(""); movieUrl = getRealUrlFor("movies"); tvUrl = getRealUrlFor("series")
                serviceInitialized = true
            } catch (_: Exception) {}
        }
        return baseUrl
    }

    private suspend fun getRealUrlFor(section: String): String {
        val res = try { service.loadPage(section) } catch(_:Exception) { null }
        val html = res?.body() ?: ""
        val url = Regex("""<script\s+type="module".*?src="(/assets/index-[^"]+\.js)"""").find(html)?.groupValues?.get(1) ?: return ""
        val jsRes = try { service.loadPage(url) } catch(_:Exception) { null }
        val js = jsRes?.body() ?: ""
        val sUrl = Regex(""""(assets/search-.*?\.js)"""").find(js)?.groupValues?.get(1)
        if (sUrl != null) searchUrl = sUrl
        return Regex(""""(assets/index-.*?\.js)"""").find(js)?.groupValues?.get(1) ?: ""
    }

    private suspend fun initializeService() { initializationMutex.withLock { if (!serviceInitialized) onChangeUrl() } }

    private interface Service {
        companion object {
            fun buildAddressFetcher(): Service = Retrofit.Builder().baseUrl(portalUrl).addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
            fun build(baseUrl: String): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(ScalarsConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }
        @GET("{url}") suspend fun loadPage(@Path("url") url: String, @Header("user-agent") ua: String = "Mozilla"): Response<String>
        @GET(".") suspend fun getPortalHome(@Header("user-agent") ua: String = "Mozilla"): Response<String>
        @GET("api/carousel/{s}") suspend fun getCarousel(@Path("s") s: String, @Header("user-agent") ua: String = "Mozilla"): AfterDarkResponse
        @GET("api/feat/{s}") suspend fun getFeat(@Path("s") s: String, @Header("user-agent") ua: String = "Mozilla"): AfterDarkItem
    }
}