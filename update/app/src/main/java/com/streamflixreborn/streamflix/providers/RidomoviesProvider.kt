package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

object RidomoviesProvider : Provider {

    const val URL = "https://ridomovies.tv/"
    override val baseUrl = URL
    override val name = "Ridomovies"
    override val logo = "$URL/images/home-logo.png"
    override val language = "en"

    private var currentSlug: String? = null

    private val service = Retrofit.Builder()
        .baseUrl(URL)
        .addConverterFactory(JsoupConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .client(NetworkClient.default.newBuilder().addInterceptor { chain ->
            val res = chain.proceed(chain.request())
            if (chain.request().url.toString() != res.request.url.toString()) {
                currentSlug = res.request.url.toString().substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast("/")
            }
            res
        }.addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("Accept-Language", "en-US,en;q=0.5").addHeader("Platform", "android").build())
        }.build())
        .build()
        .create(Service::class.java)

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome(); val categories = mutableListOf<Category>()
        doc.select("div.carousel-container ul li").mapNotNull { el ->
            val h = el.selectFirst("a.btn-watch-now")?.attr("href") ?: ""; val id = h.substringAfterLast("/")
            val t = el.selectFirst("h3")?.text() ?: ""; val o = el.selectFirst("div.slider-item-plot")?.text(); val b = el.selectFirst("img")?.attr("src")
            if (h.contains("movies/")) Movie(id = id, title = t, overview = o, banner = b)
            else if (h.contains("tv/")) TvShow(id = id, title = t, overview = o, banner = b) else null
        }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }

        listOf("Latest Movies" to "Latest Movies", "Latest TV Series" to "Latest TV Series").forEach { (name, match) ->
            doc.select("section").find { it.selectFirst("div.section-title")?.text() == match }?.select("div.poster")?.map { el ->
                val h = el.selectFirst("a")?.attr("href") ?: ""; val t = el.selectFirst("h3")?.text() ?: ""; val details = el.select("div.poster-details span")
                if (name.contains("Movies")) Movie(id = h.substringAfterLast("/"), title = t, released = details.getOrNull(0)?.text(), runtime = details.getOrNull(1)?.text()?.substringBefore(" min")?.toIntOrNull(), quality = el.selectFirst("span.poster-type")?.text(), poster = el.selectFirst("img")?.attr("src"))
                else TvShow(id = h.substringAfterLast("/"), title = t, released = el.select("div.poster-details").getOrNull(0)?.text(), runtime = el.select("div.poster-details").getOrNull(1)?.text()?.substringBefore(" min")?.toIntOrNull(), quality = el.selectFirst("span.poster-type")?.text(), poster = el.selectFirst("img")?.attr("src"))
            }?.let { categories.add(Category(name, it)) }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getGenres().data.map { Genre(id = "0", name = it.name) }
        return service.search(query, page).data.items.mapNotNull {
            if (it.type == "movie") Movie(id = it.slug, title = it.title, overview = it.contentable.overview, released = it.contentable.releaseDate, runtime = it.contentable.duration.toInt(), poster = it.contentable.apiPosterPath, banner = it.contentable.apiBackdropPath)
            else if (it.type == "tv-series") TvShow(id = it.slug, title = it.title, overview = it.contentable.overview, released = it.contentable.releaseDate, runtime = it.contentable.duration.toInt(), poster = it.contentable.apiPosterPath, banner = it.contentable.apiBackdropPath) else null
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = service.getLatestMovies(page).data.items.map { Movie(id = it.content.slug, title = it.content.title, overview = it.overview, runtime = it.duration.toInt(), poster = "${URL.trimEnd('/')}/${it.posterPath.trimStart('/')}") }
    override suspend fun getTvShows(page: Int): List<TvShow> = service.getLatestSeries(page).data.items.map { TvShow(id = it.content.slug, title = it.content.title, overview = it.overview, runtime = it.duration.toInt(), poster = "${URL.trimEnd('/')}/${it.posterPath.trimStart('/')}") }

    override suspend fun getMovie(id: String): Movie = service.getMovie(id).let { doc ->
        val fId = currentSlug ?: id; val cell = doc.select("div.info-cell")
        Movie(id = fId, title = doc.selectFirst("h1")?.text() ?: "", overview = doc.selectFirst("div.text-overview-plot p")?.text(), released = doc.selectFirst("span.post-year")?.text()?.substringBefore(")")?.substringAfter("("), rating = doc.selectFirst("div.btn-imdb")?.text()?.toDoubleOrNull(), poster = doc.selectFirst("div.single-poster img")?.attr("src"),
            genres = cell.find { it.selectFirst("strong")?.text() == "Genre: " }?.select("ul li")?.map { Genre(id = "0", name = it.text()) } ?: emptyList(),
            cast = doc.select("div.cast-item").map { People("", it.selectFirst("div.cast-name")?.text() ?: "", it.selectFirst("div.cast-image img")?.attr("src")) })
    }

    override suspend fun getTvShow(id: String): TvShow = service.getTv(id).let { doc ->
        val fId = currentSlug ?: id; val cell = doc.select("div.info-cell")
        TvShow(id = fId, title = doc.selectFirst("h1")?.text() ?: "", overview = doc.selectFirst("div.text-overview-plot p")?.text(), released = doc.selectFirst("span.post-year")?.text()?.substringBefore(")")?.substringAfter("("), rating = doc.selectFirst("div.btn-imdb")?.text()?.toDoubleOrNull(), poster = doc.selectFirst("div.single-poster img")?.attr("src"),
            seasons = service.getSeasons(fId).data.items.map { Season("$fId/${it.id}", it.seasonNumber.toInt(), "Season ${it.seasonNumber}") },
            genres = cell.find { it.selectFirst("strong")?.text() == "Genre: " }?.select("ul li")?.map { Genre(id = "0", name = it.text()) } ?: emptyList(),
            cast = doc.select("div.cast-item").map { People("", it.selectFirst("div.cast-name")?.text() ?: "", it.selectFirst("div.cast-image img")?.attr("src")) })
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvId, sId) = seasonId.split("/"); return service.getEpisodes(tvId, sId).data.items.map { Episode(it.id, it.episodeNumber, it.title, released = it.releaseDate) }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, "", service.getGenre(id, page).data.items.mapNotNull {
        val c = it.content; if (c.type == "movie") Movie(id = c.slug, title = c.title, overview = it.overview, released = it.releaseYear, runtime = it.duration.toInt(), poster = "$URL/${it.posterPath}")
        else if (c.type == "tv-series") TvShow(id = c.slug, title = c.title, overview = it.overview, released = it.releaseYear, runtime = it.duration.toInt(), poster = "$URL/${it.posterPath}") else null
    })

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = (if (videoType is Video.Type.Episode) service.getEpisodeVideos(id) else service.getMovieVideos(id)).data.mapNotNull { 
        Jsoup.parse(it.url).selectFirst("iframe")?.attr("data-src")?.let { src -> Video.Server(it.id, it.quality, src) }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    private interface Service {
        @GET("home") suspend fun getHome(): Document
        @GET("core/api/movies/latest") suspend fun getLatestMovies(@Query("page[number]") p: Int = 1): Response<DataItems<ShowItem>>
        @GET("core/api/series/latest") suspend fun getLatestSeries(@Query("page[number]") p: Int = 1): Response<DataItems<ShowItem>>
        @GET("core/api/search") suspend fun search(@Query("q") q: String, @Query("page[number]") p: Int = 1): Response<DataItems<SearchItem>>
        @GET("movies/{s}") suspend fun getMovie(@Path("s") s: String): Document
        @GET("tv/{s}") suspend fun getTv(@Path("s") s: String): Document
        @GET("core/api/series/{s}/seasons") suspend fun getSeasons(@Path("s") s: String): Response<DataItems<Seasons>>
        @GET("core/api/series/{s}/seasons/{si}/episodes") suspend fun getEpisodes(@Path("s") s: String, @Path("si") si: String): Response<DataItems<EpisodeItem>>
        @GET("core/api/genres") suspend fun getGenres(): Response<List<GenreItem>>
        @GET("core/api/genres/{g}/contents") suspend fun getGenre(@Path("g") g: String, @Query("page[number]") p: Int = 1): Response<DataItems<ShowItem>>
        @GET("api/movies/{s}") suspend fun getMovieVideos(@Path("s") s: String): Response<List<VideoItem>>
        @GET("api/episodes/{id}") suspend fun getEpisodeVideos(@Path("id") id: String): Response<List<VideoItem>>

        data class Response<T>(val code: Int, val message: String, val data: T)
        data class DataItems<T>(val items: List<T>, val pagination: Pagination) { data class Pagination(val hasNext: Boolean, val hasPrev: Boolean, val pageNumber: Int, val pageSize: Int, val totalPages: Int, val totalRecords: Int) }
        data class ShowItem(val id: String, val overview: String, val releaseYear: String, val duration: Long, val posterPath: String, val content: Content) { data class Content(val slug: String, val title: String, val type: String) }
        data class SearchItem(val slug: String, val title: String, val type: String, val contentable: Contentable) { data class Contentable(val overview: String, val releaseDate: String, val duration: Long, val apiPosterPath: String, val apiBackdropPath: String) }
        data class Seasons(val id: Long, val seasonNumber: Long)
        data class EpisodeItem(val id: String, val episodeNumber: Int, val title: String, val releaseDate: String)
        data class GenreItem(val id: Long, val name: String, val slug: String)
        data class VideoItem(val id: String, val quality: String, val url: String)
    }
}