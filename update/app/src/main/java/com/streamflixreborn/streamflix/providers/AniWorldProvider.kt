package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AniWorldDatabase
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.AniWorldUpdateTvShowWorker
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.Locale

object AniWorldProvider : Provider {

    private const val URL = "https://aniworld.to/"
    override val baseUrl = URL
    override val name = "AniWorld"
    override val logo = "$URL/public/img/facebook.jpg"
    override val language = "de"

    private val service = Service.build()
    private var tvShowDao: TvShowDao? = null
    private var isWorkerScheduled = false
    private lateinit var appContext: Context
    private val seriesCache = mutableListOf<TvShow>()
    private const val chunkSize = 25
    private var isSeriesCacheLoaded = false
    private val cacheLock = Any()

    fun initialize(context: Context) {
        if (tvShowDao == null) {
            tvShowDao = AniWorldDatabase.getInstance(context).tvShowDao()
            appContext = context.applicationContext
        }
        if (!isWorkerScheduled) {
            scheduleUpdateWorker(context)
            isWorkerScheduled = true
        }
    }

    private fun scheduleUpdateWorker(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workRequest = OneTimeWorkRequestBuilder<AniWorldUpdateTvShowWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("AniWorldUpdateTvShowWorker", ExistingWorkPolicy.KEEP, workRequest)
    }

    private fun getDao() = tvShowDao ?: throw IllegalStateException("AniWorldProvider not initialized")

    override suspend fun getHome(): List<Category> {
        preloadSeriesAlphabet()
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        val blocks = listOf(7, 11, 16)
        val names = listOf("Beliebt bei AniWorld", "Neue Animes", "Derzeit beliebte Animes")
        
        blocks.forEachIndexed { i, index ->
            categories.add(Category(name = names[i], list = doc.select("div.container > div:nth-child($index) > div.previews div.coverListItem").map {
                TvShow(id = it.selectFirst("a")?.attr("href")?.substringAfter("/anime/stream/") ?: "", title = it.selectFirst("a h3")?.text() ?: "", poster = it.selectFirst("img")?.attr("data-src")?.let { src -> URL + src })
            }))
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getGenres().select("#seriesContainer h3").map { Genre(id = it.text().lowercase(Locale.getDefault()), name = it.text()) }
        return getDao().searchTvShows(query.trim().lowercase(Locale.getDefault()), chunkSize, (page - 1) * chunkSize)
    }

    override suspend fun getMovies(page: Int): List<Movie> = throw Exception("Keine Filme verfügbar")

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (!isSeriesCacheLoaded) {
            val cachedShows = runCatching { getDao().getAll().first() }.getOrDefault(emptyList())
            if (cachedShows.isNotEmpty()) synchronized(cacheLock) { seriesCache.clear(); seriesCache.addAll(cachedShows); isSeriesCacheLoaded = true }
            else preloadSeriesAlphabet()
        }
        CoroutineScope(Dispatchers.IO).launch { preloadSeriesAlphabet() }
        synchronized(cacheLock) {
            val from = (page - 1) * chunkSize
            if (from >= seriesCache.size) return emptyList()
            return seriesCache.subList(from, minOf(page * chunkSize, seriesCache.size)).toList()
        }
    }

    override suspend fun getMovie(id: String): Movie = throw Exception("Keine Filme verfügbar")

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getAnime(id)
        val title = doc.selectFirst("h1 > span")?.text() ?: ""
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language) }
        val tmdb = tmdbDeferred.await()

        val tvShow = TvShow(
            id = id, title = title, overview = tmdb?.overview ?: doc.selectFirst("p.seri_des")?.attr("data-full-description"),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("div.series-title > small > span:nth-child(1)")?.text() ?: "",
            trailer = tmdb?.trailer ?: doc.selectFirst("div[itemprop='trailer'] a")?.attr("href"),
            poster = tmdb?.poster ?: doc.selectFirst("div.seriesCoverBox img")?.attr("data-src")?.let { URL + it },
            banner = tmdb?.banner ?: doc.selectFirst("#series > section > div.backdrop")?.attr("style")?.replace("background-image: url(/", "")?.replace(")", "")?.let { URL + it },
            rating = tmdb?.rating, imdbId = tmdb?.imdbId,
            seasons = doc.select("#stream > ul:nth-child(1) > li").filter { it.select("a").isNotEmpty() }.mapIndexed { i, it ->
                val sText = it.selectFirst("a")?.text() ?: ""; val sNum = if (sText.contains("Filme", true) || sText.contains("Specials", true)) 0 else Regex("""\d+""").find(sText)?.value?.toIntOrNull() ?: (i + 1)
                Season(id = it.selectFirst("a")?.attr("href")?.substringAfter("/anime/stream/") ?: "", number = sNum, title = it.selectFirst("a")?.attr("title") ?: sText, poster = tmdb?.seasons?.find { s -> s.number == sNum }?.poster)
            },
            genres = tmdb?.genres ?: doc.select(".genres li").map { Genre(id = it.selectFirst("a")?.text()?.lowercase(Locale.getDefault()) ?: "", name = it.selectFirst("a")?.text() ?: "") },
            cast = doc.select(".cast li[itemprop='actor']").map { p ->
                val name = p.selectFirst("span")?.text() ?: ""; val tmdbP = tmdb?.cast?.find { it.name.equals(name, true) }
                People(id = p.selectFirst("a")?.attr("href")?.substringAfter("/animes/") ?: "", name = name, image = tmdbP?.image)
            }
        )
        tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val (tvId, sId) = seasonId.split("/")
        val doc = service.getSeason(tvId, sId)
        val sNum = if (sId.contains("Filme", true) || sId.contains("Specials", true)) 0 else Regex("""\d+""").find(sId)?.value?.toIntOrNull() ?: 1
        val tmdbDeferred = async { TmdbUtils.getTvShow(doc.selectFirst("h1 > span")?.text() ?: "", language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDeferred.await()

        doc.select("tbody tr").map {
            val num = it.selectFirst("meta")?.attr("content")?.toIntOrNull() ?: 0; val tmdbE = tmdbEps.find { e -> e.number == num }
            Episode(id = it.selectFirst("a")?.attr("href")?.substringAfter("/anime/stream/") ?: "", number = num, title = tmdbE?.title ?: it.selectFirst("strong")?.text(), poster = tmdbE?.poster, overview = tmdbE?.overview)
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (page > 1) return Genre(id, "")
        val doc = service.getGenre(id, page)
        return Genre(id = id, name = doc.selectFirst("h1")?.text()?.substringBefore(" Animes") ?: "", shows = doc.select(".seriesListContainer > div").map {
            TvShow(id = it.selectFirst("a")?.attr("href")?.substringAfter("/anime/stream/") ?: "", title = it.selectFirst("h3")?.text() ?: "", poster = it.selectFirst("img")?.attr("data-src")?.let { src -> URL + src })
        })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")
        val doc = service.getPeople(id)
        return People(id = id, name = doc.selectFirst("h1 strong")?.text() ?: "", filmography = doc.select(".seriesListContainer > div").map {
            TvShow(id = it.selectFirst("a")?.attr("href")?.substringAfter("/anime/stream/") ?: "", title = it.selectFirst("h3")?.text() ?: "", poster = it.selectFirst("img")?.attr("data-src")?.let { src -> URL + src })
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (tvId, sId, epId) = id.split("/")
        return service.getEpisode(tvId, sId, epId).select("div.hosterSiteVideo > ul > li").mapNotNull {
            val redirect = it.selectFirst("a")?.attr("href")?.let { href -> URL + href } ?: return@mapNotNull null
            val lang = when (it.attr("data-lang-key")) { "1" -> " - DUB"; "2" -> " - SUB English"; "3" -> " - SUB"; else -> "" }
            val name = (it.selectFirst("h4")?.text() ?: "") + lang
            Video.Server(id = name, name = name, src = redirect)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val response = service.getRedirectLink(server.src).raw() as okhttp3.Response
        val videoUrl = response.request.url
        return Extractor.extract(if (server.name.startsWith("VOE")) "https://voe.sx${videoUrl.encodedPath}" else videoUrl.toString())
    }

    private suspend fun preloadSeriesAlphabet() {
        val loadedShows = service.getAnimesAlphabet().select(".genre > ul > li").map {
            TvShow(id = it.selectFirst("a[data-alternative-title]")?.attr("href")?.substringAfter("/anime/stream/") ?: "", title = it.selectFirst("a[data-alternative-title]")?.text() ?: "", overview = "")
        }
        val dao = getDao(); val existing = dao.getAllIds(); val news = loadedShows.filter { it.id !in existing }
        if (news.isNotEmpty()) dao.insertAll(news)
        val all = dao.getAll().first()
        synchronized(cacheLock) { seriesCache.clear(); seriesCache.addAll(all); isSeriesCacheLoaded = true }
        scheduleUpdateWorker(appContext)
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            seriesCache.clear()
            isSeriesCacheLoaded = false
        }
    }

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }

        @GET(".") suspend fun getHome(): Document
        @POST("https://aniworld.to/ajax/search") @FormUrlEncoded suspend fun search(@Field("keyword") query: String): List<SearchItem>
        @GET("animes-genres") suspend fun getGenres(): Document
        @GET("animes-alphabet") suspend fun getAnimesAlphabet(): Document
        @GET("anime/stream/{id}") suspend fun getAnime(@Path("id") id: String): Document
        @GET("anime/stream/{tvShowId}/{seasonId}") suspend fun getSeason(@Path("tvShowId") tvShowId: String, @Path("seasonId") seasonId: String): Document
        @GET("genre/{id}/{page}") suspend fun getGenre(@Path("id") id: String, @Path("page") page: Int): Document
        @GET("animes/{id}") suspend fun getPeople(@Path("id", encoded = true) id: String): Document
        @GET("anime/stream/{tvShowId}/{seasonId}/{episodeId}") suspend fun getEpisode(@Path("tvShowId") tvShowId: String, @Path("seasonId") seasonId: String, @Path("episodeId") episodeId: String): Document
        @GET @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)") suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>

        data class SearchItem(val title: String, val description: String, val link: String)
    }
}