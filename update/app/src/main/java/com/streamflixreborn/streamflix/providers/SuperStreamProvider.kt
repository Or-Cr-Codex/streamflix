package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SuperStreamProvider : Provider {

    override val name = "SuperStream"
    override val logo = ""
    override val language = "en"
    private val URL_BASE = Base64.decode("aHR0cHM6Ly9zaG93Ym94LnNoZWd1Lm5ldA==", Base64.NO_WRAP).toString(Charsets.UTF_8) + 
                           Base64.decode("L2FwaS9hcGlfY2xpZW50L2luZGV4Lw==", Base64.NO_WRAP).toString(Charsets.UTF_8)
    override val baseUrl = URL_BASE

    private val service = SuperStreamApiService.build()

    private val IV = Base64.decode("d0VpcGhUbiE=", Base64.NO_WRAP).toString(Charsets.UTF_8)
    private val KEY = Base64.decode("MTIzZDZjZWRmNjI2ZHk1NDIzM2FhMXc2", Base64.NO_WRAP).toString(Charsets.UTF_8)
    private val APP_KEY = Base64.decode("bW92aWVib3g=", Base64.NO_WRAP).toString(Charsets.UTF_8)
    private val APP_ID = Base64.decode("Y29tLnRkby5zaG93Ym94", Base64.NO_WRAP).toString(Charsets.UTF_8)
    private val APP_ID_2 = Base64.decode("Y29tLm1vdmllYm94cHJvLmFuZHJvaWQ=", Base64.NO_WRAP).toString(Charsets.UTF_8)
    private const val APP_VER = "14.7"
    private const val APP_VER_CODE = "160"

    override suspend fun getHome(): List<Category> = service.getHome(queryApi(mapOf("childmode" to "1", "app_version" to APP_VER, "appid" to APP_ID_2, "module" to "Home_list_type_v2", "channel" to "Website", "page" to "0", "lang" to "en", "type" to "all", "pagelimit" to "10", "expired_date" to "${getExpiry()}", "platform" to "android"))).data.map { h ->
        Category(name = h.name?.takeIf { it.isNotEmpty() } ?: Category.FEATURED, list = h.list.mapNotNull { d ->
            if (d.boxType == 1) Movie(id = d.id.toString(), title = d.title ?: "", quality = d.qualityTag, rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster, banner = d.bannerMini)
            else if (d.boxType == 2) TvShow(id = d.id.toString(), title = d.title ?: "", rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster, banner = d.bannerMini, seasons = d.seasonEpisode?.let { s -> Regex("S(\\d+)\\s* E(\\d+)").find(s)?.groupValues?.let { g -> listOf(Season(id = "", number = g[1].toIntOrNull() ?: 0, episodes = listOf(Episode(id = "", number = g[2].toIntOrNull() ?: 0)))) } } ?: emptyList())
            else null
        })
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return emptyList()
        return service.search(baseUrl, queryApi(mapOf("childmode" to "1", "app_version" to "11.5", "appid" to APP_ID, "module" to "Search3", "channel" to "Website", "page" to page.toString(), "lang" to "en", "type" to "all", "keyword" to query, "pagelimit" to "20", "expired_date" to "${getExpiry()}", "platform" to "android"))).data.mapNotNull { d ->
            if (d.boxType == 1) Movie(id = d.id.toString(), title = d.title ?: "", overview = d.description, released = d.year?.toString(), runtime = d.runtime, quality = d.qualityTag, rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster, banner = d.bannerMini)
            else if (d.boxType == 2) TvShow(id = d.id.toString(), title = d.title ?: "", overview = d.description, released = d.year?.toString(), runtime = d.runtime, quality = d.qualityTag, rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster, banner = d.bannerMini)
            else null
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = throw Exception("Not implemented")
    override suspend fun getTvShows(page: Int): List<TvShow> = throw Exception("Not implemented")

    override suspend fun getMovie(id: String): Movie = service.getMovieById(queryApi(mapOf("childmode" to "1", "uid" to "", "app_version" to "11.5", "appid" to APP_ID, "module" to "Movie_detail", "channel" to "Website", "mid" to id, "lang" to "en", "expired_date" to "${getExpiry()}", "platform" to "android", "oss" to "", "group" to ""))).data.let { d ->
        Movie(id = d.id.toString(), title = d.title ?: "", overview = d.description, released = d.released, runtime = d.runtime, trailer = d.trailer, quality = d.qualityTag, rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster,
            genres = d.cats?.split(",")?.map { Genre(id = "0", name = it.trim()) } ?: emptyList(),
            directors = d.director?.split(",")?.map { People(id = it, name = it.trim()) } ?: emptyList(),
            cast = d.actors?.split(",")?.map { People(id = it, name = it.trim()) } ?: emptyList(),
            recommendations = d.recommend.map { Movie(id = it.mid?.toString() ?: "", title = it.title ?: "", released = it.year?.toString(), runtime = it.runtime, quality = it.qualityTag, rating = it.imdbRating?.toDoubleOrNull(), poster = it.poster, genres = it.cats?.split(",")?.map { g -> Genre(id = "0", name = g.trim()) } ?: emptyList()) })
    }

    override suspend fun getTvShow(id: String): TvShow = service.getTvShowById(queryApi(mapOf("childmode" to "1", "uid" to "", "app_version" to "11.5", "appid" to APP_ID, "module" to "TV_detail_1", "display_all" to "1", "channel" to "Website", "lang" to "en", "expired_date" to "${getExpiry()}", "platform" to "android", "tid" to id))).data.let { d ->
        TvShow(id = d.id.toString(), title = d.title ?: "", overview = d.description, released = d.released, trailer = d.trailerUrl, rating = d.imdbRating?.toDoubleOrNull(), poster = d.poster,
            seasons = d.season.map { n -> Season(id = "$id-$n", number = n) }, genres = d.cats?.split(",")?.map { Genre(id = "0", name = it.trim()) } ?: emptyList(),
            directors = d.director?.split(",")?.map { People(id = it, name = it.trim()) } ?: emptyList(), cast = d.actors?.split(",")?.map { People(id = it, name = it.trim()) } ?: emptyList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")
        return service.getEpisodes(queryApi(mapOf("childmode" to "1", "app_version" to APP_VER, "year" to "0", "appid" to APP_ID_2, "module" to "TV_episode", "display_all" to "1", "channel" to "Website", "season" to seasonNumber, "lang" to "en", "expired_date" to "${getExpiry()}", "platform" to "android", "tid" to tvShowId))).data.map { Episode(id = it.id.toString(), number = it.episode, title = it.title, released = it.released, poster = it.thumbs) }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not implemented")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val q = when (videoType) {
            is Video.Type.Movie -> mapOf("childmode" to "1", "uid" to "", "app_version" to "11.5", "appid" to APP_ID, "module" to "Movie_downloadurl_v3", "channel" to "Website", "mid" to id, "lang" to "", "expired_date" to "${getExpiry()}", "platform" to "android", "oss" to "1", "group" to "")
            is Video.Type.Episode -> mapOf("childmode" to "1", "app_version" to "11.5", "module" to "TV_downloadurl_v3", "channel" to "Website", "episode" to "${videoType.number}", "expired_date" to "${getExpiry()}", "platform" to "android", "tid" to id, "oss" to "1", "uid" to "", "appid" to APP_ID, "season" to "${videoType.season.number}", "lang" to "en", "group" to "")
        }
        val sources = if (videoType is Video.Type.Movie) service.getMovieSourceById(queryApi(q)).data else service.getEpisodeSources(queryApi(q)).data
        val fid = sources.list.firstOrNull { it.fid != null }?.fid ?: 0
        val sq = when (videoType) {
            is Video.Type.Movie -> mapOf("childmode" to "1", "fid" to fid.toString(), "uid" to "", "app_version" to "11.5", "appid" to APP_ID, "module" to "Movie_srt_list_v2", "channel" to "Website", "mid" to id, "lang" to "en", "expired_date" to "${getExpiry()}", "platform" to "android")
            is Video.Type.Episode -> mapOf("childmode" to "1", "fid" to "$fid", "app_version" to "11.5", "module" to "TV_srt_list_v2", "channel" to "Website", "episode" to "${videoType.number}", "expired_date" to "${getExpiry()}", "platform" to "android", "tid" to id, "uid" to "", "appid" to APP_ID, "season" to "${videoType.season.number}", "lang" to "en")
        }
        val subs = if (videoType is Video.Type.Movie) service.getMovieSubtitlesById(queryApi(sq)).data else service.getEpisodeSubtitles(queryApi(sq)).data
        return sources.list.filter { it.path?.isNotEmpty() == true }.mapIndexed { i, l -> Video.Server(i.toString(), "${l.quality} • ${l.size}").apply { video = Video(l.path ?: "", subs.list.flatMap { it.subtitles.filter { s -> s.filePath?.isNotEmpty() == true }.map { s -> Video.Subtitle(s.language ?: "", s.filePath ?: "") } }) } }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return server.video ?: throw Exception("No source found")
    }


    private fun getExpiry() = System.currentTimeMillis() + 43200000
    private fun md5(s: String): String? = try { MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } } catch(_:Exception) { null }
    private fun encrypt(s: String, k: String, i: String): String? = try {
        val c = Cipher.getInstance("DESede/CBC/PKCS5Padding"); val b = ByteArray(24); val kb = k.toByteArray()
        System.arraycopy(kb, 0, b, 0, minOf(kb.size, 24)); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(b, "DESede"), IvParameterSpec(i.toByteArray()))
        Base64.encode(c.doFinal(s.toByteArray()), 2).toString(Charsets.UTF_8)
    } catch(_:Exception) { null }

    private fun queryApi(q: Map<String, String>): Map<String, String> {
        val enc = encrypt(JSONObject(q).toString(), KEY, IV)!!; val appH = md5(APP_KEY)!!
        val body = JSONObject(mapOf("app_key" to appH, "verify" to md5(md5(APP_KEY) + KEY + enc), "encrypt_data" to enc)).toString()
        return mapOf("data" to Base64.encode(body.toByteArray(), Base64.NO_WRAP).toString(Charsets.UTF_8), "appid" to "27", "platform" to "android", "version" to APP_VER_CODE, "medium" to "Website&token${(0..31).joinToString("") { (('0'..'9')+('a'..'f')).random().toString() }}")
    }

    data class SResponse<T>(val data: T)
    data class HomeResponse(val name: String?, val list: List<SShow>)
    data class SShow(val id: Int, @SerializedName("box_type") val boxType: Int, val title: String?, val description: String?, val poster: String?, @SerializedName("banner_mini") val bannerMini: String?, @SerializedName("quality_tag") val qualityTag: String?, @SerializedName("imdb_rating") val imdbRating: String?, val year: Int?, val runtime: Int?, @SerializedName("season_episode") val seasonEpisode: String?)
    data class MovieDetails(val id: Int, val title: String?, val director: String?, val actors: String?, val runtime: Int?, val poster: String?, val description: String?, val cats: String?, val released: String?, @SerializedName("imdb_rating") val imdbRating: String?, val trailer: String?, val recommend: List<Recommend>, @SerializedName("quality_tag") val qualityTag: String?)
    data class Recommend(val mid: Int?, val title: String?, val poster: String?, @SerializedName("quality_tag") val qualityTag: String?, @SerializedName("imdb_rating") val imdbRating: String?, val runtime: Int?, val cats: String?, val year: Int?)
    data class STvShow(val id: Int, val title: String?, val director: String?, val actors: String?, val poster: String?, val description: String?, val cats: String?, val released: String?, @SerializedName("imdb_rating") val imdbRating: String?, @SerializedName("trailer_url") val trailerUrl: String?, val season: List<Int>, @SerializedName("quality_tag") val qualityTag: String?)
    data class SEpisode(val id: Int, val episode: Int, val title: String?, val released: String?, val thumbs: String?)
    data class LinkResponse(val list: List<Link>) { data class Link(val path: String?, val quality: String?, val size: String?, val fid: Int?) }
    data class PrivateSubtitleData(val list: List<SubtitleList>) { data class SubtitleList(val subtitles: List<SSubtitles>) { data class SSubtitles(@SerializedName("file_path") val filePath: String?, val language: String?) } }

    private interface SuperStreamApiService {
        companion object {
            fun build(): SuperStreamApiService = Retrofit.Builder().baseUrl(URL_BASE).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(SuperStreamApiService::class.java)
        }
        @POST(".") @FormUrlEncoded suspend fun getHome(@FieldMap d: Map<String, String>): SResponse<List<HomeResponse>>
        @POST @FormUrlEncoded suspend fun search(@Url u: String, @FieldMap d: Map<String, String>): SResponse<List<SShow>>
        @POST(".") @FormUrlEncoded suspend fun getMovieById(@FieldMap d: Map<String, String>): SResponse<MovieDetails>
        @POST(".") @FormUrlEncoded suspend fun getMovieSourceById(@FieldMap d: Map<String, String>): SResponse<LinkResponse>
        @POST(".") @FormUrlEncoded suspend fun getMovieSubtitlesById(@FieldMap d: Map<String, String>): SResponse<PrivateSubtitleData>
        @POST(".") @FormUrlEncoded suspend fun getTvShowById(@FieldMap d: Map<String, String>): SResponse<STvShow>
        @POST(".") @FormUrlEncoded suspend fun getEpisodes(@FieldMap d: Map<String, String>): SResponse<List<SEpisode>>
        @POST(".") @FormUrlEncoded suspend fun getEpisodeSources(@FieldMap d: Map<String, String>): SResponse<LinkResponse>
        @POST(".") @FormUrlEncoded suspend fun getEpisodeSubtitles(@FieldMap d: Map<String, String>): SResponse<PrivateSubtitleData>
    }
}