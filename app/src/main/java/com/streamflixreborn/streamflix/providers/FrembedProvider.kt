package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.FrembedExtractor
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
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

object FrembedProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "Frembed"
    override val defaultPortalUrl = "https://audin213.com/"
    override val portalUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL).ifEmpty { defaultPortalUrl }
    override val defaultBaseUrl = "https://frembed.beer/"
    override val baseUrl: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifEmpty { defaultBaseUrl }
    override val logo: String get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO).ifEmpty { baseUrl + "favicon-32x32.png" }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    private val GENRES = mapOf("28" to "Action", "12" to "Adventure", "16" to "Animation", "35" to "Comedy", "80" to "Crime", "99" to "Documentary", "19" to "Drama", "10751" to "Family", "14" to "Fantasy", "36" to "History", "27" to "Horror", "10402" to "Music", "9648" to "Mystery", "10749" to "Romance", "878" to "Sci-Fi", "10770" to "TV Movie", "53" to "Thriller", "10752" to "War", "37" to "Western")

    data class FrembedCastItem(val id: Int, val name: String, val profile_path: String?)
    data class FrembedSimilarItem(val tmdb: Int, val title: String, val poster_path: String?)
    data class FrembedShowItem(val director: String?, val genres: String?, val imdb: String?, val tmdb: Int?, val overview: String?, val rating: Double?, val title: String?, val trailer: String?, val year: String?, val poster: String?, val backdrops: List<String>?, val cast: List<FrembedCastItem>?)
    data class FrembedShortCutItem(val tmdb: Int?, val id: Int?, val imdb: String?, val title: String?, val name: String?, val director: String?, val cast: List<FrembedCastItem>?, val poster: String?, val poster_path: String?, val version: String?, val year: Int?, val release_date: String?, val first_air_date: String?, val rating: Double?, var sa: Int?, var overview: String?, var trailer: String?, var media_type: String?)
    data class FrembedListEpItem(val epi: Int, val id: Int, val title: String?)
    data class FrembedSeasonResponse(val episodes: List<FrembedListEpItem>, val sa: Int)
    data class FrembedMoviesResponse(val movies: List<FrembedShortCutItem>)
    data class FrembedTvShowsResponse(val series: List<FrembedShortCutItem>)
    data class FrembedSearchResponse(val movies: List<FrembedShortCutItem>, val tvShows: List<FrembedShortCutItem>)
    data class FrembedActorItem(val name: String, val profile_path: String?, val birthday: String?, val deathday: String?, val biography: String?, val place_of_birth: String?, val known_for_department: String?)
    data class FrembedSearchActorsResponse(val actor: FrembedActorItem, val movies: List<FrembedShortCutItem>)

    private fun FrembedShortCutItem.toShow(movie: Boolean = false, tvshow: Boolean = false): Show = if ((sa != null && media_type != "movie" && !movie) || tvshow) TvShow(id = (tmdb ?: id).toString(), title = (title ?: name ?: "TvShow") + if (sa != null) " - S$sa" else "", poster = (poster?.w500) ?: poster_path, banner = poster?.original, rating = rating)
    else Movie(id = (tmdb ?: id).toString(), title = title ?: name ?: "Movie", poster = (poster?.w500) ?: poster_path, banner = poster?.original, rating = rating)

    override suspend fun getHome(): List<Category> = coroutineScope {
        initializeService()
        val sections = listOf("ranking" to Category.FEATURED, "latest" to "Nouveaux films", "updated" to "Films mis à jour", "most-viewed" to "Meilleurs films", "latest-added-seasons" to "Nouvelles séries", "most-viewed-seasons" to "Meilleures séries")
        sections.map { (key, name) ->
            async { 
                try {
                    val list = if (key.contains("seasons") || key == "most-viewed") service.getApiView(key) else service.getApiPublic(key)
                    Category(name = name, list = list.map { it.toShow() })
                } catch (_: Exception) { null }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page == 1 && query.isEmpty()) return GENRES.map { (id, name) -> Genre(id = id, name = name) }
        initializeService()
        return try { service.getApiSearch(page, query).let { res -> res.movies.map { it.toShow(movie = true) } + res.tvShows.map { it.toShow(tvshow = true) } } } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> { if (page == 1) initializeService(); return try { service.getMovies(page).movies.map { it.toShow() as Movie } } catch (_: Exception) { emptyList() } }
    override suspend fun getTvShows(page: Int): List<TvShow> { if (page == 1) initializeService(); return try { service.getTvShows(page).series.map { it.toShow() as TvShow } } catch (_: Exception) { emptyList() } }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        initializeService()
        val movieDef = async { service.getMovie(id) }
        val similarDef = async { try { service.getApiSimilar("movies", id).map { Movie(id = it.tmdb.toString(), title = it.title, poster = it.poster_path?.w500) } } catch (_: Exception) { emptyList() } }
        val movie = movieDef.await()
        Movie(
            id = movie.tmdb.toString(), title = movie.title ?: "Movie", overview = movie.overview, released = movie.year,
            trailer = movie.trailer?.let { "https://www.youtube.com/watch?v=$it" }, rating = movie.rating, poster = movie.poster?.w500,
            banner = movie.backdrops?.randomOrNull()?.original, imdbId = movie.imdb,
            genres = movie.genres?.split(", ")?.map { Genre(it, it) } ?: emptyList(),
            directors = movie.director?.split(", ")?.map { People(id = it, name = it) } ?: emptyList(),
            cast = movie.cast?.map { People(id = it.id.toString(), name = it.name, image = it.profile_path?.original) } ?: emptyList(),
            recommendations = similarDef.await()
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        initializeService()
        val showDef = async { service.getTvShow(id) }
        val similarDef = async { try { service.getApiSimilar("tv-show", id).map { TvShow(id = it.tmdb.toString(), title = it.title, poster = it.poster_path?.w500) } } catch (_: Exception) { emptyList() } }
        val episodesDef = async { try { service.getApiListEp(id) } catch (_: Exception) { emptyList() } }
        
        val show = showDef.await(); val posters = show.backdrops ?: emptyList()
        TvShow(
            id = show.tmdb.toString(), title = show.title ?: "TvShow", overview = show.overview, released = show.year,
            trailer = show.trailer?.let { "https://www.youtube.com/watch?v=$it" }, rating = show.rating, poster = show.poster?.w500,
            banner = posters.randomOrNull()?.original, imdbId = show.imdb,
            genres = show.genres?.split(", ")?.map { Genre(it, it) } ?: emptyList(),
            directors = show.director?.split(", ")?.map { People(id = it, name = it) } ?: emptyList(),
            cast = show.cast?.map { People(id = it.id.toString(), name = it.name, image = it.profile_path?.original) } ?: emptyList(),
            recommendations = similarDef.await(),
            seasons = episodesDef.await().mapIndexed { i, s -> Season(id = "$id/${s.sa}", number = s.sa, title = "Saison ${s.sa}", poster = posters.getOrNull(i % posters.size.coerceAtLeast(1))?.w500) }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvId, sNum) = seasonId.split("/")
        return try { service.getApiListEp(tvId).find { it.sa.toString() == sNum }?.episodes?.map { Episode(id = it.id.toString(), number = it.epi, title = it.title ?: "Episode ${it.epi}") } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun getGenre(id: String, page: Int): Genre { initializeService(); return Genre(id = id, name = GENRES[id] ?: id, shows = service.getMovies(page, g = id).movies.map { it.toShow(true) as Movie }) }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = "")
        initializeService(); val res = try { service.getApiSearchActor(id) } catch (_: Exception) { return People(id = id, name = "") }
        val actor = res.actor
        return People(id = id, name = actor.name, birthday = actor.birthday, deathday = actor.deathday, image = actor.profile_path, biography = actor.biography, filmography = res.movies.map { it.toShow() })
    }

    override suspend fun getVideo(server: Video.Server): Video = if (server.video != null) server.video!! else Extractor.extract(server.src)
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = FrembedExtractor(baseUrl).servers(videoType)

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    Service.buildAddressFetcher().getPortalHome().selectFirst("a")?.attr("href")?.trim()?.let { url ->
                        val formatted = if (url.endsWith("/")) url else "$url/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formatted)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${formatted}favicon-32x32.png")
                    }
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
            fun build(baseUrl: String): Service = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(ScalarsConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(Service::class.java)
        }
        @GET("{url}") suspend fun loadPage(@Path("url") url: String, @Header("user-agent") ua: String = "Mozilla"): Response<String>
        @GET(".") suspend fun getPortalHome(@Header("user-agent") ua: String = "Mozilla"): Document
        @GET("api/public/movies/{section}") suspend fun getApiPublic(@Path("section") s: String, @Header("user-agent") ua: String = "Mozilla"): List<FrembedShortCutItem>
        @GET("api/views/{section}") suspend fun getApiView(@Path("section") s: String, @Header("user-agent") ua: String = "Mozilla"): List<FrembedShortCutItem>
        @GET("api/public/movies") suspend fun getMovies(@Query("page") p: Int = 1, @Query("pageSize") ps: Int = 16, @Query("genre") g: String = "", @Header("user-agent") ua: String = "Mozilla"): FrembedMoviesResponse
        @GET("api/public/tv-show") suspend fun getTvShows(@Query("page") p: Int = 1, @Query("pageSize") ps: Int = 16, @Header("user-agent") ua: String = "Mozilla"): FrembedTvShowsResponse
        @GET("api/public/search") suspend fun getApiSearch(@Query("page") p: Int = 1, @Query("query") q: String, @Header("user-agent") ua: String = "Mozilla"): FrembedSearchResponse
        @GET("api/public/actor/{id}") suspend fun getApiSearchActor(@Path("id") id: String, @Header("user-agent") ua: String = "Mozilla"): FrembedSearchActorsResponse
        @GET("api/public/movies/{id}") suspend fun getMovie(@Path("id") id: String): FrembedShowItem
        @GET("api/public/tv-show/{id}") suspend fun getTvShow(@Path("id") id: String): FrembedShowItem
        @GET("api/public/{type}/similar/{id}") suspend fun getApiSimilar(@Path("type") t: String, @Path("id") id: String, @Header("user-agent") ua: String = "Mozilla"): List<FrembedSimilarItem>
        @GET("api/public/tv-show/{id}/listep") suspend fun getApiListEp(@Path("id") id: String, @Header("user-agent") ua: String = "Mozilla"): List<FrembedSeasonResponse>
    }
}