package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.SerienStreamDatabase
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
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.Locale

object SerienStreamProvider : Provider {

    private val URL = Base64.decode("aHR0cHM6Ly9z", Base64.NO_WRAP).toString(Charsets.UTF_8) + 
                      Base64.decode("LnRvLw==", Base64.NO_WRAP).toString(Charsets.UTF_8)
    override val baseUrl = URL
    @SuppressLint("StaticFieldLeak")
    override val name = Base64.decode("U2VyaWVuU3RyZWFt", Base64.NO_WRAP).toString(Charsets.UTF_8)
    override val logo = "$URL/public/img/logo-sto-serienstream-sx-to-serien-online-streaming-vod.png"
    override val language = "de"
    
    private val service = SerienStreamService.build()
    private var tvShowDao: TvShowDao? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (tvShowDao == null) {
            tvShowDao = SerienStreamDatabase.getInstance(context).tvShowDao()
            appContext = context.applicationContext
        }
    }

    private fun getDao() = tvShowDao ?: throw IllegalStateException("SerienStreamProvider not initialized")

    private fun getTvShowIdFromLink(link: String) = link.removePrefix(URL).removePrefix("/").removePrefix("serie/").split("/")[0]
    private fun getSeasonIdFromLink(link: String): String {
        val parts = link.removePrefix(URL).removePrefix("/").removePrefix("serie/").split("/")
        return "${parts[0]}/${parts[1]}"
    }
    private fun getEpisodeIdFromLink(link: String): String {
        val parts = link.removePrefix(URL).removePrefix("/").removePrefix("serie/").split("/")
        return "${parts[0]}/${parts[1]}/${parts[2]}"
    }

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        categories.add(Category(name = Category.FEATURED, list = doc.select(".home-hero-slide").map {
            TvShow(id = getTvShowIdFromLink(it.selectFirst("a.home-hero-cta")?.attr("href") ?: ""), title = it.selectFirst("h2.home-hero-title")?.text() ?: "", 
                banner = normalizeImageUrl(it.select("picture.home-hero-bg img").flatMap { img -> img.attr("srcset").split(",") }.find { url -> url.contains("hero-2x-desktop") }?.trim()?.split(" ")?.firstOrNull()))
        }))
        categories.add(Category(name = "Angesagt", list = doc.select(".trending-widget .swiper-slide").map { TvShow(id = getTvShowIdFromLink(it.selectFirst("h3.trend-title a")?.attr("href") ?: ""), title = it.selectFirst("h3.trend-title a")?.text()?.trim() ?: "", poster = normalizeImageUrl(it.extractPoster())) }))
        categories.add(Category(name = "Neu auf S.to", list = doc.select("div:has(h4:contains(Neu auf S.to)) + div.row > div").map { TvShow(id = getTvShowIdFromLink(it.selectFirst("a")?.attr("href") ?: ""), title = it.selectFirst("h6 a")?.text() ?: "", poster = normalizeImageUrl(it.extractPoster())) }))
        doc.select("#discover-blocks .col").forEach { column ->
            val catName = column.selectFirst("h4")?.text()?.trim() ?: ""
            if (catName.isNotEmpty()) categories.add(Category(name = catName, list = column.select("li").map { TvShow(id = getTvShowIdFromLink(it.selectFirst("a")?.attr("href") ?: ""), title = it.selectFirst("span.h6")?.text()?.trim() ?: "", poster = normalizeImageUrl(it.extractPoster())) }))
        }
        categories.add(Category(name = "Derzeit beliebte Serien", list = doc.select("div.carousel:contains(Derzeit beliebt) div.coverListItem").map { TvShow(id = getTvShowIdFromLink(it.selectFirst("a")?.attr("href") ?: ""), title = it.selectFirst("a h3")?.text() ?: "", poster = normalizeImageUrl(it.extractPoster())) }))
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getSeriesListWithCategories().select("div[data-group='genres'] .list-inline-item a").map { Genre(id = it.attr("href").substringAfterLast("/"), name = it.text().trim()) }
        return service.search(query, page).select("div.search-results-list div.card.cover-card").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/serie/]")?.attr("href") ?: return@mapNotNull null
            TvShow(id = getTvShowIdFromLink(link), title = card.selectFirst("h6.show-title")?.text().orEmpty(), poster = normalizeImageUrl(card.extractPoster()))
        }.distinctBy { it.id }
    }

    override suspend fun getMovies(page: Int): List<Movie> = throw Exception("Keine Filme verfügbar")

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getAllTvShows(page).select("div.search-results-list div.card.cover-card").mapNotNull { card ->
        val link = card.selectFirst("a[href^=/serie/]")?.attr("href") ?: return@mapNotNull null
        TvShow(id = getTvShowIdFromLink(link), title = card.selectFirst("h6.show-title")?.text().orEmpty(), poster = normalizeImageUrl(card.extractPoster()))
    }.distinctBy { it.id }

    override suspend fun getMovie(id: String): Movie = throw Exception("Keine Filme verfügbar")

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getTvShow(id)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language) }
        val tmdb = tmdbDeferred.await()

        val localRating = if (tmdb?.rating == null) {
            val imdbUrl = doc.selectFirst("a[href*='imdb.com']")?.attr("href") ?: ""
            val imdbDoc = if (imdbUrl.isNotEmpty()) try { service.getCustomUrl(imdbUrl) } catch (_: Exception) { null } else null
            imdbDoc?.selectFirst("div[data-testid='hero-rating-bar__aggregate-rating__score'] span")?.text()?.toDoubleOrNull() ?: doc.selectFirst(".text-white-50:contains(Bewertungen)")?.text()?.split(" ")?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        } else 0.0

        TvShow(id = id, title = title, 
            overview = tmdb?.overview ?: doc.selectFirst("span.description-text")?.text() ?: doc.selectFirst("div.series-description p")?.text(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("a.small.text-muted")?.text() ?: "",
            rating = tmdb?.rating ?: localRating, runtime = tmdb?.runtime,
            directors = doc.select(".series-group:contains(Regisseur) a").map { People(id = it.attr("href").removePrefix(URL).removePrefix("/"), name = it.text()) },
            cast = doc.select(".series-group:contains(Besetzung) a").map { p -> 
                val name = p.text(); val tmdbP = tmdb?.cast?.find { it.name.equals(name, true) }
                People(id = p.attr("href").removePrefix(URL).removePrefix("/"), name = name, image = tmdbP?.image)
            },
            genres = tmdb?.genres ?: doc.select(".series-group:contains(Genre) a").map { Genre(id = it.text().lowercase(Locale.getDefault()), name = it.text()) },
            trailer = tmdb?.trailer ?: doc.selectFirst("div[itemprop='trailer'] a")?.attr("href") ?: "",
            poster = tmdb?.poster ?: doc.selectFirst("div.show-header-wrapper img")?.let { it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src") },
            banner = tmdb?.banner ?: doc.selectFirst("div.backdrop-picture img")?.let { it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src") },
            seasons = doc.select("#season-nav ul li a").map {
                val sText = it.text().trim(); val sNum = sText.toIntOrNull() ?: 0
                Season(id = getSeasonIdFromLink(it.attr("href")), number = sNum, title = if (sText == "Filme") "Filme" else "Staffel $sNum", poster = tmdb?.seasons?.find { s -> s.number == sNum }?.poster)
            },
            imdbId = tmdb?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val parts = seasonId.split("/"); val showName = parts[0]; val sNumStr = parts[1]; val sNum = Regex("""\d+""").find(sNumStr)!!.value.toInt()
        val doc = service.getTvShowEpisodes(showName, sNumStr)
        val title = (doc.selectFirst("h1")?.text()?.trim() ?: "").split(" Staffel").firstOrNull()?.trim() ?: ""
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDeferred.await()

        doc.select("tr.episode-row").map {
            val num = it.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull() ?: 0; val tmdbE = tmdbEps.find { e -> e.number == num }
            val link = it.attr("onclick").substringAfter("window.location='").substringBefore("'")
            Episode(id = getEpisodeIdFromLink(link), number = num, title = tmdbE?.title ?: it.selectFirst(".episode-title-ger")?.text() ?: it.selectFirst(".episode-title-eng")?.text() ?: "Episode $num", poster = tmdbE?.poster, overview = tmdbE?.overview)
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val shows = mutableListOf<TvShow>()
        try {
            service.getGenre(id, page).select("div.row.g-3 > div").forEach { shows.add(TvShow(id = it.selectFirst("a")?.attr("href")?.let { h -> getTvShowIdFromLink(h) } ?: "", title = it.selectFirst("h6")?.text()?.trim() ?: "", poster = normalizeImageUrl(it.extractPoster()))) }
        } catch (_: Exception) {}
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")
        val doc = service.getPeople(id)
        return People(id = id, name = doc.selectFirst("h1 strong")?.text() ?: "", filmography = doc.select("div.row.g-3 > div").map {
            TvShow(id = it.selectFirst("a")?.attr("href")?.let { h -> getTvShowIdFromLink(h) } ?: "", title = it.selectFirst("h6 a")?.text() ?: "", poster = it.selectFirst("img")?.let { i -> i.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: i.attr("src") })
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val parts = id.split("/"); val doc = service.getTvShowEpisodeServers(parts[0], parts[1], parts[2])
        val servers = mutableListOf<Video.Server>()
        for (el in doc.select("button.link-box")) {
            val sName = el.attr("data-provider-name"); val lang = el.attr("data-language-label"); val href = el.attr("data-play-url")
            if (href.isEmpty()) continue
            try {
                val redirect = URL + href.removePrefix("/")
                val resp = try { service.getRedirectLink(redirect) } catch (_: Exception) { SerienStreamService.buildUnsafe().getRedirectLink(redirect) }
                servers.add(Video.Server(id = (resp.raw() as okhttp3.Response).request.url.toString(), name = "$sName ($lang)"))
            } catch (_: Exception) {}
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id)

    interface SerienStreamService {
        companion object {
            fun build(): SerienStreamService = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(SerienStreamService::class.java)
            fun buildUnsafe(): SerienStreamService = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.trustAll).build().create(SerienStreamService::class.java)
        }

        @GET(".") suspend fun getHome(): Document
        @GET("suche?tab=genres") suspend fun getSeriesListWithCategories(): Document
        @GET("serien-alphabet") suspend fun getSeriesListAlphabet(): Document
        @GET("suche") suspend fun search(@Query("term") k: String, @Query("page") p: Int, @Query("tab") t: String = "shows"): Document
        @GET("suche") suspend fun getAllTvShows(@Query("page") p: Int, @Query("tab") t: String = "shows"): Document
        @GET("genre/{g}") suspend fun getGenre(@Path("g") g: String, @Query("page") p: Int): Document
        @GET("{id}") suspend fun getPeople(@Path("id", encoded = true) id: String): Document
        @GET("serie/{n}") suspend fun getTvShow(@Path("n") n: String): Document
        @GET("serie/{n}/{s}") suspend fun getTvShowEpisodes(@Path("n") n: String, @Path("s") s: String): Document
        @GET("serie/{n}/{s}/{e}") suspend fun getTvShowEpisodeServers(@Path("n") n: String, @Path("s") s: String, @Path("e") e: String): Document
        @GET @Headers("User-Agent: Mozilla/5.0") suspend fun getCustomUrl(@Url u: String): Document
        @GET @Headers("User-Agent: Mozilla/5.0", "Accept: text/html", "Connection: keep-alive") suspend fun getRedirectLink(@Url u: String): Response<ResponseBody>
    }

    private fun Element.extractPoster(): String {
        selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }?.let { return it }
        select("source[data-srcset]").firstOrNull { it.attr("type") != "image/webp" && it.attr("type") != "image/avif" }?.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.let { return it }
        return selectFirst("img[src]")?.attr("src") ?: ""
    }

    private fun normalizeImageUrl(u: String?): String? = if (u.isNullOrBlank()) null else if (u.startsWith("http")) u else "https://s.to$u"
}