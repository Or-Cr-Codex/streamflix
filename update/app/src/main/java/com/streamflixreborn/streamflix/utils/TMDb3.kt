package com.streamflixreborn.streamflix.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap
import java.lang.reflect.Type
import java.util.Calendar

object TMDb3 {

    private const val URL = "https://api.themoviedb.org/3/"
    private var service = ApiService.build()

    fun rebuildService() {
        service = ApiService.build()
    }

    object Discover {

        suspend fun movie(
            language: String? = null,
            page: Int? = null,
            region: String? = null,
            sortBy: Params.SortBy.Movie? = null,
            watchRegion: String? = null,
            withCast: Params.WithBuilder<*>? = null,
            withCompanies: Params.WithBuilder<*>? = null,
            withCrew: Params.WithBuilder<*>? = null,
            withGenres: Params.WithBuilder<*>? = null,
            withKeywords: Params.WithBuilder<*>? = null,
            withOriginCountry: Params.WithBuilder<*>? = null,
            withOriginalLanguage: Params.WithBuilder<*>? = null,
            withPeople: Params.WithBuilder<*>? = null,
            withReleaseType: Params.WithBuilder<*>? = null,
            withWatchMonetizationTypes: Params.WithBuilder<*>? = null,
            withWatchProviders: Params.WithBuilder<*>? = null,
            withoutCompanies: Params.WithBuilder<*>? = null,
            withoutGenres: Params.WithBuilder<*>? = null,
            withoutKeywords: Params.WithBuilder<*>? = null,
            withoutWatchProviders: Params.WithBuilder<*>? = null,
            year: Int? = null,
        ): PageResult<Movie> {
            val params = mapOf(
                Params.Key.LANGUAGE to language,
                Params.Key.PAGE to page?.toString(),
                Params.Key.REGION to region,
                Params.Key.SORT_BY to sortBy?.value,
                Params.Key.WATCH_REGION to watchRegion,
                Params.Key.WITH_CAST to withCast?.toString(),
                Params.Key.WITH_COMPANIES to withCompanies?.toString(),
                Params.Key.WITH_CREW to withCrew?.toString(),
                Params.Key.WITH_GENRES to withGenres?.toString(),
                Params.Key.WITH_KEYWORDS to withKeywords?.toString(),
                Params.Key.WITH_ORIGIN_COUNTRY to withOriginCountry?.toString(),
                Params.Key.WITH_ORIGINAL_LANGUAGE to withOriginalLanguage?.toString(),
                Params.Key.WITH_PEOPLE to withPeople?.toString(),
                Params.Key.WITH_RELEASE_TYPE to withReleaseType?.toString(),
                Params.Key.WITH_WATCH_MONETIZATION_TYPES to withWatchMonetizationTypes?.toString(),
                Params.Key.WITH_WATCH_PROVIDERS to withWatchProviders?.toString(),
                Params.Key.WITHOUT_COMPANIES to withoutCompanies?.toString(),
                Params.Key.WITHOUT_GENRES to withoutGenres?.toString(),
                Params.Key.WITHOUT_KEYWORDS to withoutKeywords?.toString(),
                Params.Key.WITHOUT_WATCH_PROVIDERS to withoutWatchProviders?.toString(),
                Params.Key.YEAR to year?.toString(),
            )
            return service.getDiscoverMovies(params = params.filterNotNullValues())
        }

        suspend fun movie(params: Map<String, String>): PageResult<Movie> {
            return service.getDiscoverMovies(params = params.filterNotNullValues())
        }

        suspend fun tv(
            language: String? = null,
            page: Int? = null,
            sortBy: Params.SortBy.Tv? = null,
            watchRegion: String? = null,
            withCompanies: Params.WithBuilder<*>? = null,
            withGenres: Params.WithBuilder<*>? = null,
            withKeywords: Params.WithBuilder<*>? = null,
            withNetworks: Params.WithBuilder<*>? = null,
            withOriginCountry: Params.WithBuilder<*>? = null,
            withOriginalLanguage: Params.WithBuilder<*>? = null,
            withStatus: Params.WithBuilder<*>? = null,
            withWatchMonetizationTypes: Params.WithBuilder<*>? = null,
            withWatchProviders: Params.WithBuilder<*>? = null,
            withoutCompanies: Params.WithBuilder<*>? = null,
            withoutGenres: Params.WithBuilder<*>? = null,
            withoutKeywords: Params.WithBuilder<*>? = null,
            withoutWatchProviders: Params.WithBuilder<*>? = null,
            withType: Params.WithBuilder<*>? = null,
        ): PageResult<Tv> {
            val params = mapOf(
                Params.Key.LANGUAGE to language,
                Params.Key.PAGE to page?.toString(),
                Params.Key.SORT_BY to sortBy?.value,
                Params.Key.WATCH_REGION to watchRegion,
                Params.Key.WITH_COMPANIES to withCompanies?.toString(),
                Params.Key.WITH_GENRES to withGenres?.toString(),
                Params.Key.WITH_KEYWORDS to withKeywords?.toString(),
                Params.Key.WITH_NETWORKS to withNetworks?.toString(),
                Params.Key.WITH_ORIGIN_COUNTRY to withOriginCountry?.toString(),
                Params.Key.WITH_ORIGINAL_LANGUAGE to withOriginalLanguage?.toString(),
                Params.Key.WITH_STATUS to withStatus?.toString(),
                Params.Key.WITH_WATCH_MONETIZATION_TYPES to withWatchMonetizationTypes?.toString(),
                Params.Key.WITH_WATCH_PROVIDERS to withWatchProviders?.toString(),
                Params.Key.WITHOUT_COMPANIES to withoutCompanies?.toString(),
                Params.Key.WITHOUT_GENRES to withoutGenres?.toString(),
                Params.Key.WITHOUT_KEYWORDS to withoutKeywords?.toString(),
                Params.Key.WITHOUT_WATCH_PROVIDERS to withoutWatchProviders?.toString(),
                Params.Key.WITH_TYPE to withType?.toString(),
            )
            return service.getDiscoverTv(params = params.filterNotNullValues())
        }

        suspend fun tv(params: Map<String, String>): PageResult<Tv> {
            return service.getDiscoverTv(params = params.filterNotNullValues())
        }
    }

    object Genres {
        suspend fun movieList(language: String? = null): GenresResponse {
            return service.getGenreMoviesList(params = mapOf(Params.Key.LANGUAGE to language).filterNotNullValues())
        }
        suspend fun tvList(language: String? = null): GenresResponse {
            return service.getGenreTvList(params = mapOf(Params.Key.LANGUAGE to language).filterNotNullValues())
        }
    }

    object MovieLists {
        suspend fun popular(page: Int? = null, language: String? = null): PageResult<Movie> {
            val params = mapOf(Params.Key.PAGE to page?.toString(), Params.Key.LANGUAGE to language)
            return service.getPopularMovies(params = params.filterNotNullValues())
        }
        suspend fun topRated(params: Map<String, String>): PageResult<Movie> {
            return service.getTopRatedMovies(params = params.filterNotNullValues())
        }
    }

    object Movies {
        suspend fun details(movieId: Int, appendToResponse: List<Params.AppendToResponse.Movie>? = null, language: String? = null): Movie.Detail {
            val params = mapOf(Params.Key.APPEND_TO_RESPONSE to appendToResponse?.joinToString(",") { it.value }, Params.Key.LANGUAGE to language)
            return service.getMovieDetails(movieId = movieId, params = params.filterNotNullValues())
        }
    }

    object People {
        suspend fun details(personId: Int, appendToResponse: List<Params.AppendToResponse.Person>? = null, language: String? = null): Person.Detail {
            val params = mapOf(Params.Key.APPEND_TO_RESPONSE to appendToResponse?.joinToString(",") { it.value }, Params.Key.LANGUAGE to language)
            return service.getPersonDetails(personId = personId, params = params.filterNotNullValues())
        }
    }

    object Search {
        suspend fun multi(query: String, includeAdult: Boolean? = null, language: String? = null, page: Int? = null): PageResult<MultiItem> {
            val params = mapOf(Params.Key.INCLUDE_ADULT to includeAdult?.toString(), Params.Key.LANGUAGE to language, Params.Key.PAGE to page?.toString())
            return service.searchMulti(query = query, params = params.filterNotNullValues())
        }
    }

    object Trending {
        suspend fun all(timeWindow: Params.TimeWindow, language: String? = null, page: Int? = null): PageResult<MultiItem> {
            val params = mapOf(Params.Key.LANGUAGE to language, Params.Key.PAGE to page?.toString())
            return service.getTrendingAll(timeWindow = timeWindow.value, params = params.filterNotNullValues())
        }
    }

    object TvSeriesLists {
        suspend fun popular(page: Int? = null, language: String? = null): PageResult<Tv> {
            val params = mapOf(Params.Key.PAGE to page?.toString(), Params.Key.LANGUAGE to language)
            return service.getPopularTv(params = params.filterNotNullValues())
        }
        suspend fun topRated(params: Map<String, String>): PageResult<Tv> {
            return service.getTopRatedTv(params = params.filterNotNullValues())
        }
    }

    object TvSeries {
        suspend fun details(seriesId: Int, appendToResponse: List<Params.AppendToResponse.Tv>? = null, language: String? = null): Tv.Detail {
            val params = mapOf(Params.Key.APPEND_TO_RESPONSE to appendToResponse?.joinToString(",") { it.value }, Params.Key.LANGUAGE to language)
            return service.getTvDetails(seriesId = seriesId, params = params.filterNotNullValues())
        }
    }

    object TvSeasons {
        suspend fun details(seriesId: Int, seasonNumber: Int, appendToResponse: List<Params.AppendToResponse.TvSeason>? = null, language: String? = null): Season.Detail {
            val params = mapOf(Params.Key.APPEND_TO_RESPONSE to appendToResponse?.joinToString(",") { it.value }, Params.Key.LANGUAGE to language)
            return service.getTvSeasonDetails(seriesId = seriesId, seasonNumber = seasonNumber, params = params.filterNotNullValues())
        }
    }

    object Keyword {
        enum class KeywordId(val id: Int) {
            ANIME(210024),
            BASED_ON_ANIME(222243);
            override fun toString() = id.toString()
        }
    }

    object Provider {
        enum class WatchProviderId(val id: Int) {
            NETFLIX(8),
            AMAZON_VIDEO(10),
            DISNEY_PLUS(337),
            HULU(15),
            APPLE_TV_PLUS(350);
            override fun toString() = id.toString()
        }
        enum class WatchMonetizationType(val value: String) {
            @SerializedName("flatrate") FLATRATE("flatrate")
        }
    }

    object Network {
        enum class NetworkId(val id: Int) {
            NETFLIX(213),
            AMAZON(1024),
            DISNEY_PLUS(2739),
            HULU(453),
            APPLE_TV(2552),
            HBO(49);
            override fun toString() = id.toString()
        }
    }

    object Company {
        enum class CompanyId(val id: Int) {
            UNIVERSAL(33);
            override fun toString() = id.toString()
        }
    }

    object Params {
        data class Range<T>(val gte: T? = null, val lte: T? = null)
        object SortBy {
            enum class Movie(val value: String) { POPULARITY_DESC("popularity.desc"), RELEASE_DATE_DESC("release_date.desc"), VOTE_AVERAGE_DESC("vote_average.desc") }
            enum class Tv(val value: String) { POPULARITY_DESC("popularity.desc"), FIRST_AIR_DATE_DESC("first_air_date.desc"), VOTE_AVERAGE_DESC("vote_average.desc") }
        }
        object AppendToResponse {
            enum class Movie(val value: String) { CREDITS("credits"), RECOMMENDATIONS("recommendations"), VIDEOS("videos"), EXTERNAL_IDS("external_ids") }
            enum class Person(val value: String) { COMBINED_CREDITS("combined_credits"), EXTERNAL_IDS("external_ids") }
            enum class Tv(val value: String) { CREDITS("credits"), RECOMMENDATIONS("recommendations"), VIDEOS("videos"), EXTERNAL_IDS("external_ids") }
            enum class TvSeason(val value: String) { VIDEOS("videos"), EXTERNAL_IDS("external_ids") }
        }
        enum class TimeWindow(val value: String) { DAY("day"), WEEK("week") }
        object Key {
            const val LANGUAGE = "language"; const val PAGE = "page"; const val REGION = "region"; const val SORT_BY = "sort_by"; const val WATCH_REGION = "watch_region"
            const val WITH_CAST = "with_cast"; const val WITH_COMPANIES = "with_companies"; const val WITH_CREW = "with_crew"; const val WITH_GENRES = "with_genres"
            const val WITH_KEYWORDS = "with_keywords"; const val WITH_NETWORKS = "with_networks"; const val WITH_ORIGINAL_LANGUAGE = "with_original_language"
            const val WITH_ORIGIN_COUNTRY = "with_origin_country"; const val WITH_PEOPLE = "with_people"; const val WITH_RELEASE_TYPE = "with_release_type"
            const val WITH_STATUS = "with_status"; const val WITH_TYPE = "with_type"; const val WITH_WATCH_MONETIZATION_TYPES = "with_watch_monetization_types"
            const val WITH_WATCH_PROVIDERS = "with_watch_providers"; const val WITHOUT_COMPANIES = "without_companies"; const val WITHOUT_GENRES = "without_genres"
            const val WITHOUT_KEYWORDS = "without_keywords"; const val WITHOUT_WATCH_PROVIDERS = "without_watch_providers"; const val YEAR = "year"
            const val APPEND_TO_RESPONSE = "append_to_response"; const val INCLUDE_ADULT = "include_adult"
        }
        class WithBuilder<T : Any> {
            private var value: String = ""
            constructor(id: Int) { value += id }
            constructor(id: String) { value += id }
            constructor(any: T) { value += any.toString() }
            fun and(id: Int): WithBuilder<T> { value += ",$id"; return this }
            fun or(id: Int): WithBuilder<T> { value += "|$id"; return this }
            fun or(any: T): WithBuilder<T> { value += "|$any"; return this }
            override fun toString() = value
        }
    }

    val String.w500: String get() = if (startsWith("/")) "https://image.tmdb.org/t/p/w500$this" else this
    val String.original: String get() = if (startsWith("/")) "https://image.tmdb.org/t/p/original$this" else this

    private interface ApiService {
        companion object {
            fun build(): ApiService = Retrofit.Builder().baseUrl(URL).client(NetworkClient.default.newBuilder().addInterceptor { chain ->
                val req = chain.request(); val url = req.url.newBuilder().addQueryParameter("api_key", UserPreferences.tmdbApiKey.ifEmpty { BuildConfig.TMDB_API_KEY }).build()
                chain.proceed(req.newBuilder().url(url).build())
            }.build()).addConverterFactory(GsonConverterFactory.create(GsonBuilder().registerTypeAdapter(MultiItem::class.java, MultiItem.Deserializer()).create())).build().create(ApiService::class.java)
        }
        @GET("discover/movie") suspend fun getDiscoverMovies(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Movie>
        @GET("discover/tv") suspend fun getDiscoverTv(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Tv>
        @GET("genre/movie/list") suspend fun getGenreMoviesList(@QueryMap params: Map<String, String> = emptyMap()): GenresResponse
        @GET("genre/tv/list") suspend fun getGenreTvList(@QueryMap params: Map<String, String> = emptyMap()): GenresResponse
        @GET("movie/popular") suspend fun getPopularMovies(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Movie>
        @GET("movie/top_rated") suspend fun getTopRatedMovies(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Movie>
        @GET("movie/{id}") suspend fun getMovieDetails(@Path("id") movieId: Int, @QueryMap params: Map<String, String> = emptyMap()): Movie.Detail
        @GET("person/{id}") suspend fun getPersonDetails(@Path("id") personId: Int, @QueryMap params: Map<String, String> = emptyMap()): Person.Detail
        @GET("search/multi") suspend fun searchMulti(@retrofit2.http.Query("query") query: String, @QueryMap params: Map<String, String> = emptyMap()): PageResult<MultiItem>
        @GET("trending/all/{tw}") suspend fun getTrendingAll(@Path("tw") timeWindow: String, @QueryMap params: Map<String, String> = emptyMap()): PageResult<MultiItem>
        @GET("tv/popular") suspend fun getPopularTv(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Tv>
        @GET("tv/top_rated") suspend fun getTopRatedTv(@QueryMap params: Map<String, String> = emptyMap()): PageResult<Tv>
        @GET("tv/{id}") suspend fun getTvDetails(@Path("id") seriesId: Int, @QueryMap params: Map<String, String> = emptyMap()): Tv.Detail
        @GET("tv/{id}/season/{n}") suspend fun getTvSeasonDetails(@Path("id") seriesId: Int, @Path("n") seasonNumber: Int, @QueryMap params: Map<String, String> = emptyMap()): Season.Detail
    }

    data class PageResult<T>(@SerializedName("page") val page: Int, @SerializedName("results") val results: List<T> = emptyList())
    data class GenresResponse(val genres: List<Genre>)
    sealed class MultiItem {
        class Deserializer : JsonDeserializer<MultiItem> {
            override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): MultiItem? {
                val obj = json?.asJsonObject ?: JsonObject()
                return when (obj.get("media_type")?.asString ?: "") {
                    "movie" -> Gson().fromJson(json, Movie::class.java)
                    "person" -> Gson().fromJson(json, Person::class.java)
                    "tv" -> Gson().fromJson(json, Tv::class.java)
                    else -> null
                }
            }
        }
    }
    data class Genre(val id: Int, val name: String) {
        enum class Movie(val id: Int) { ACTION(28), ANIMATION(16) }
        enum class Tv(val id: Int) { ANIMATION(16) }
    }
    data class Movie(@SerializedName("poster_path") val posterPath: String?, val overview: String, @SerializedName("release_date") val releaseDate: String? = null, val id: Int, val title: String, @SerializedName("backdrop_path") val backdropPath: String?, val popularity: Float, @SerializedName("vote_average") val voteAverage: Float) : MultiItem() {
        data class Detail(@SerializedName("backdrop_path") val backdropPath: String?, val genres: List<Genre>, val id: Int, @SerializedName("imdb_id") val imdbId: String? = null, val title: String, val runtime: Int? = null, val overview: String, @SerializedName("poster_path") val posterPath: String?, @SerializedName("vote_average") val voteAverage: Float, @SerializedName("external_ids") val externalIds: ExternalIds? = null, @SerializedName("release_date") val releaseDate: String?, val videos: Result<Video>? = null, val recommendations: PageResult<MultiItem>? = null, val credits: Credits? = null)
    }
    data class Tv(@SerializedName("poster_path") val posterPath: String?, val id: Int, @SerializedName("backdrop_path") val backdropPath: String?, @SerializedName("vote_average") val voteAverage: Float, val overview: String, @SerializedName("first_air_date") val firstAirDate: String? = null, val name: String, val popularity: Float) : MultiItem() {
        data class Detail(val id: Int, val name: String, @SerializedName("poster_path") val posterPath: String?, @SerializedName("backdrop_path") val backdropPath: String?, val popularity: Float, @SerializedName("first_air_date") val firstAirDate: String? = null, val genres: List<Genre>, val seasons: List<Season>, val overview: String, @SerializedName("vote_average") val voteAverage: Float, @SerializedName("external_ids") val externalIds: ExternalIds? = null, val videos: Result<Video>? = null, val recommendations: PageResult<MultiItem>? = null, val credits: Credits? = null)
    }
    data class Season(@SerializedName("id") val id: Int, val name: String, @SerializedName("poster_path") val posterPath: String?, @SerializedName("season_number") val seasonNumber: Int, val episodes: List<Episode>? = null) {
        data class Detail(val id: Int, val episodes: List<Episode>? = null)
    }
    data class Episode(@SerializedName("id") val id: Int, @SerializedName("episode_number") val episodeNumber: Int, @SerializedName("air_date") val airDate: String? = null, val name: String? = null, @SerializedName("still_path") val stillPath: String? = null, val overview: String? = null)
    data class Person(@SerializedName("id") val id: Int, val name: String, @SerializedName("profile_path") val profilePath: String?, val popularity: Float) : MultiItem() {
        data class Detail(@SerializedName("biography") val biography: String? = null, val birthday: String? = null, val deathday: String? = null, val id: Int, @SerializedName("imdb_id") val imdbId: String? = null, val name: String, @SerializedName("place_of_birth") val placeOfBirth: String? = null, @SerializedName("profile_path") val profilePath: String? = null, @SerializedName("external_ids") val externalIds: ExternalIds? = null, @SerializedName("combined_credits") val combinedCredits: Credits<MultiItem>? = null)
        data class Credits<T>(val cast: List<T>)
    }
    data class Result<T>(val results: List<T>)
    data class Credits(val cast: List<Cast>)
    data class Cast(val id: Int, val name: String, @SerializedName("profile_path") val profilePath: String?)
    data class ExternalIds(@SerializedName("imdb_id") val imdbId: String? = null)
    data class Video(val key: String? = null, val site: VideoSite? = null, @SerializedName("published_at") val publishedAt: String? = null) { enum class VideoSite { YOUTUBE } }
}
