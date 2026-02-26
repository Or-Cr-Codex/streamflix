package com.streamflixreborn.streamflix.providers

import android.util.Base64
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
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URL

object MStreamProvider : Provider {
    private val URL = Base64.decode("aHR0cHM6Ly9tb2ZsaXg=", Base64.NO_WRAP).toString(Charsets.UTF_8) + 
                      Base64.decode("LXN0cmVhbS54eXo=", Base64.NO_WRAP).toString(Charsets.UTF_8)
    override val baseUrl = URL
    override val name = Base64.decode("TW9mbGl4", Base64.NO_WRAP).toString(Charsets.UTF_8) + 
                        Base64.decode("LXN0cmVhbQ==", Base64.NO_WRAP).toString(Charsets.UTF_8)
    override val logo = "$URL/storage/branding_media/b0d168ea-8d1b-4b40-9292-65e9a600d3c6.png"
    override val language = "de"

    private val service = MStreamService.build()

    private fun <R> JSONArray.map(transform: (JSONObject) -> R): List<R> {
        val result = mutableListOf<R>()
        for (i in 0 until length()) result.add(transform(getJSONObject(i)))
        return result
    }

    private fun getMovieObj(json: JSONObject, reducePoster: Boolean): Movie {
        val title = json.optJSONObject("title") ?: json
        return Movie(
            id = title.optString("id") + "#" + title.optJSONObject("primary_video")?.optString("id"),
            title = title.optString("name") ?: "unknown",
            poster = (if (title.optString("poster").startsWith("http")) "" else "$URL/") + title.optString("poster").let { if (reducePoster) it else it.replace("original", "w300") },
            banner = (if (title.optString("backdrop").startsWith("http")) "" else "$URL/") + title.optString("backdrop"),
            overview = title.optString("description"), released = title.optString("year"), rating = title.optDouble("rating"), runtime = title.optInt("runtime"),
            genres = title.optJSONArray("genres")?.map { Genre(id = it.getString("id"), name = it.getString("display_name")) } ?: emptyList(),
            cast = json.optJSONObject("credits")?.optJSONArray("actors")?.map { People(id = it.getString("id"), name = it.getString("name"), image = it.getString("poster")) } ?: emptyList()
        )
    }

    private fun getTvShowObj(json: JSONObject): TvShow {
        val title = json.optJSONObject("title") ?: json
        return TvShow(
            id = title.optString("id", "0"), title = title.optString("name") ?: "unknown",
            poster = (if (title.optString("poster").startsWith("http")) "" else "$URL/") + title.optString("poster"),
            banner = (if (title.optString("backdrop").startsWith("http")) "" else "$URL/") + title.optString("backdrop"),
            overview = title.optString("description"), released = title.optString("year"), rating = title.optDouble("rating"),
            genres = title.optJSONArray("genres")?.map { Genre(id = it.getString("id"), name = it.getString("display_name")) } ?: emptyList(),
            seasons = json.optJSONObject("seasons")?.optJSONArray("data")?.map { Season(id = it.getString("title_id") + "_" + it.getInt("number"), number = it.getInt("number"), poster = it.getString("poster")) } ?: emptyList()
        )
    }

    override suspend fun getHome(): List<Category> = JSONObject(service.getChannel("350").string()).getJSONObject("channel").getJSONObject("content").getJSONArray("data").map {
        Category(name = if (it.getString("id") == "354") Category.FEATURED else it.getString("name"), list = it.optJSONObject("content")?.optJSONArray("data")?.map { item -> if (item.optBoolean("is_series")) getTvShowObj(item) else getMovieObj(item, true) } ?: emptyList())
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty() && page == 1) return listOf("Drama", "Action", "Animation", "Abenteuer", "Familie", "Fantasy", "Komödie", "Thriller", "Krimi", "Mystery", "Horror", "Liebesfilm", "Historie", "Kriegsfilm", "Western", "Musik", "Dokumentarfilm", "Anime", "Science Fiction").map { Genre(id = it.replace(" & ", "-").replace(" ", "-"), name = it) }
        if (page > 1) return emptyList()
        return JSONObject(service.getSearch(query).string()).getJSONArray("results").map { if (it.optBoolean("is_series")) getTvShowObj(it) else if (it.optString("model_type") == "movie") getMovieObj(it, true) else null }.filterNotNull()
    }

    override suspend fun getMovies(page: Int): List<Movie> = JSONObject(service.getChannelWithPage("345", page.toString()).string()).getJSONObject("pagination").getJSONArray("data").map { getMovieObj(it, false) }
    override suspend fun getTvShows(page: Int): List<TvShow> = JSONObject(service.getChannelWithPage("346", page.toString()).string()).getJSONObject("pagination").getJSONArray("data").map { getTvShowObj(it) }
    override suspend fun getMovie(id: String): Movie = getMovieObj(JSONObject(service.getTitle(id.split("#")[0]).string()), false)
    override suspend fun getTvShow(id: String): TvShow = getTvShowObj(JSONObject(service.getTitle(id).string()))

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("_"); return JSONObject(service.getEpisodes(parts[0], parts[1]).string()).getJSONObject("pagination").getJSONArray("data").map {
            Episode(id = it.getString("title_id") + "_" + it.getInt("season_number") + "_" + it.getInt("episode_number"), title = it.getString("name"), number = it.getInt("episode_number"), poster = it.getString("poster"))
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = JSONObject(service.getGenre(id).string()).let { json ->
        Genre(id, name = json.getJSONObject("channel").getJSONObject("restriction").getString("display_name"), shows = if (page == 1) json.getJSONObject("channel").getJSONObject("content").getJSONArray("data").map { if (it.optBoolean("is_series")) getTvShowObj(it) else getMovieObj(it, true) } else emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People = JSONObject(service.getPerson(id).string()).let { json ->
        val p = json.getJSONObject("person")
        People(id = p.optString("id"), name = p.optString("name"), image = p.optString("poster"), placeOfBirth = p.optString("birth_place"), birthday = p.optString("birth_date"), deathday = p.optString("death_date"), filmography = if (page == 1) json.getJSONArray("knownFor").map { if (it.optBoolean("is_series")) getTvShowObj(it) else getMovieObj(it, true) } else emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val json = if (id.contains("_")) { val p = id.split("_"); JSONObject(service.getEpisodeStreams(p[0], p[1], p[2]).string()).getJSONObject("episode") } 
                   else JSONObject(service.getStreams(id.split("#")[1]).string())
        val key = if (id.contains("_")) "videos" else "alternative_videos"
        return json.getJSONArray(key).map { Video.Server(id = it.getString("id"), name = URL(it.getString("src")).host + " (" + it.getString("name") + ")", src = it.getString("src")) }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    interface MStreamService {
        companion object {
            fun build(): MStreamService = Retrofit.Builder().baseUrl(URL).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default.newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Referer", URL).build()) }.build()).build().create(MStreamService::class.java)
        }
        @GET("/api/v1/channel/{id}") suspend fun getChannel(@Path("id") id: String): ResponseBody
        @GET("/api/v1/channel/{id}?returnContentOnly=true") suspend fun getChannelWithPage(@Path("id") id: String, @Query("page") p: String): ResponseBody
        @GET("/api/v1/titles/{id}") suspend fun getTitle(@Path("id") id: String): ResponseBody
        @GET("/api/v1/titles/{id}/seasons/{s}/episodes?perPage=999&orderBy=episode_number&orderDir=asc") suspend fun getEpisodes(@Path("id") id: String, @Path("s") s: String): ResponseBody
        @GET("/api/v1/titles/{id}/seasons/{s}/episodes/{e}?loader=episodePage") suspend fun getEpisodeStreams(@Path("id") id: String, @Path("s") s: String, @Path("e") e: String): ResponseBody
        @GET("/api/v1/search/{q}?loader=searchPage") suspend fun getSearch(@Path("q") q: String): ResponseBody
        @GET("/api/v1/people/{id}?loader=personPage") suspend fun getPerson(@Path("id") id: String): ResponseBody
        @GET("/api/v1/channel/genre?channelType=channel&loader=channelPage") suspend fun getGenre(@Query("restriction") n: String): ResponseBody
        @GET("/api/v1/watch/{id}") suspend fun getStreams(@Path("id") id: String): ResponseBody
    }
}