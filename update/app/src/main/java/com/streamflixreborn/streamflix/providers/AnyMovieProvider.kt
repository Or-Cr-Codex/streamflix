package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

object AnyMovieProvider : Provider {

    private const val URL = "https://anymovie.cc/"
    override val baseUrl = URL
    override val name = "AnyMovie"
    override val logo = "$URL/wp-content/uploads/2023/08/AM-LOGO-1.png"
    override val language = "en"

    private var _wpsearch = ""
    private val service = AllMoviesForYouService.build()

    override suspend fun getHome(): List<Category> = coroutineScope {
        val document = service.getHome()
        Regex("\"nonce\":\"(.*?)\"").find(document.toString())?.groupValues?.get(1)?.let { _wpsearch = it }

        val categories = mutableListOf<Category>()
        val featuredList = document.select("div#home-slider div.swiper-slide").mapNotNull { el ->
            val h = el.selectFirst("ul.rw a")?.attr("href") ?: ""; val id = h.substringBeforeLast("/").substringAfterLast("/")
            val g = el.select("span.categories").map { Genre(id = it.selectFirst("a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", name = it.text()) }
            val item = if (h.contains("/series/")) TvShow(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", overview = el.selectFirst("div.entry-content")?.text(), released = el.selectFirst("span.year")?.text(), runtime = el.selectFirst("span.duration")?.text()?.toMinutes(), rating = el.selectFirst("span.rating.fa-star")?.text()?.toDoubleOrNull(), banner = el.selectFirst("div.bg")?.attr("style")?.substringAfter("url(")?.substringBefore(");")?.toSafeUrl(), genres = g)
            else if (h.contains("/movies/")) Movie(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", overview = el.selectFirst("div.entry-content")?.text(), released = el.selectFirst("span.year")?.text(), runtime = el.selectFirst("span.duration")?.text()?.toMinutes(), rating = el.selectFirst("span.rating.fa-star")?.text()?.toDoubleOrNull(), banner = el.selectFirst("div.bg")?.attr("style")?.substringAfter("url(")?.substringBefore(");")?.toSafeUrl(), genres = g)
            else null
            item
        }
        if (featuredList.isNotEmpty()) categories.add(Category(Category.FEATURED, featuredList))

        document.select("section.section").forEach { sec ->
            val list = sec.select("div.swiper-slide").mapNotNull { el ->
                val h = el.selectFirst("ul.rw a")?.attr("href") ?: ""; val id = h.substringBeforeLast("/").substringAfterLast("/")
                val p = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl()
                val g = el.select("li.rw.sm").find { it.selectFirst("span")?.text() == "Genres" }?.select("a")?.map { Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) } ?: emptyList()
                if (h.contains("/series/")) TvShow(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", overview = el.selectFirst("div.entry-content")?.text(), released = el.selectFirst("span.year")?.text(), runtime = el.selectFirst("span.duration")?.text()?.toMinutes(), quality = el.selectFirst("span.quality")?.text(), rating = el.selectFirst("span.rating.fa-star")?.text()?.toDoubleOrNull(), poster = p, genres = g)
                else if (h.contains("/movies/")) Movie(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", overview = el.selectFirst("div.entry-content")?.text(), released = el.selectFirst("span.year")?.text(), runtime = el.selectFirst("span.duration")?.text()?.toMinutes(), quality = el.selectFirst("span.quality")?.text(), rating = el.selectFirst("span.rating.fa-star")?.text()?.toDoubleOrNull(), poster = p, genres = g)
                else null
            }
            if (list.isNotEmpty()) categories.add(Category(sec.selectFirst("h2.section-title")?.text()?.trim() ?: "", list))
        }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.search("").selectFirst("ul.fg1 li")?.select("li")?.map { Genre(id = it.attr("data-genre"), name = it.text()) }?.distinctBy { it.id }?.sortedBy { it.name } ?: emptyList()
        val res = service.api(JSONObject(mapOf("_wpsearch" to _wpsearch, "taxonomy" to "none", "search" to query, "term" to "none", "type" to "mixed", "genres" to emptyList<String>(), "years" to emptyList<String>(), "sort" to "1", "page" to page)).toString())
        return Jsoup.parse(res.html).select("article.movies").mapNotNull { el ->
            val h = el.selectFirst("ul.rw a")?.attr("href") ?: ""; val id = h.substringBeforeLast("/").substringAfterLast("/")
            val p = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl()
            if (h.contains("/movies/")) Movie(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = p)
            else if (h.contains("/series/")) TvShow(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = p)
            else null
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = Jsoup.parse(service.api(JSONObject(mapOf("_wpsearch" to _wpsearch, "taxonomy" to "none", "search" to "", "term" to "none", "type" to "movies", "genres" to emptyList<String>(), "years" to emptyList<String>(), "sort" to "1", "page" to page)).toString()).html).select("article.movies").map { el ->
        Movie(id = el.selectFirst("ul.rw a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = Jsoup.parse(service.api(JSONObject(mapOf("_wpsearch" to _wpsearch, "taxonomy" to "none", "search" to "", "term" to "none", "type" to "series", "genres" to emptyList<String>(), "years" to emptyList<String>(), "sort" to "1", "page" to page)).toString()).html).select("article.movies").map { el ->
        TvShow(id = el.selectFirst("ul.rw a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl())
    }

    override suspend fun getMovie(id: String): Movie = service.getMovie(id).let { doc ->
        val lst = doc.select("article.single ul.details-lst li.rw.sm")
        Movie(id = id, title = doc.selectFirst("h1.entry-title")?.text() ?: "", overview = doc.selectFirst("div.entry-content")?.text(), released = doc.selectFirst("span.year")?.text(), runtime = doc.selectFirst("span.duration")?.text()?.toMinutes(), poster = doc.selectFirst("div.post-thumbnail img")?.attr("src"), rating = doc.selectFirst("span.rating")?.text()?.toDoubleOrNull(),
            genres = lst.find { it.selectFirst("span")?.text() == "Genres" }?.select("a")?.map { Genre(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) } ?: emptyList(),
            cast = lst.find { it.selectFirst("span")?.text() == "Cast" }?.select("a")?.map { People(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) } ?: emptyList())
    }

    override suspend fun getTvShow(id: String): TvShow = service.getTvShow(id).let { doc ->
        val lst = doc.select("article.single ul.details-lst li.rw.sm")
        TvShow(id = id, title = doc.selectFirst("h1.entry-title")?.text() ?: "", overview = doc.selectFirst("div.entry-content")?.text(), released = doc.selectFirst("span.year")?.text(), runtime = doc.selectFirst("span.duration")?.text()?.toMinutes(), poster = doc.selectFirst("div.post-thumbnail img")?.attr("src"), rating = doc.selectFirst("span.rating")?.text()?.toDoubleOrNull(),
            seasons = doc.select("div.seasons div.seasons-bx").mapIndexed { i, el -> Season(id = "$id/$i", number = el.selectFirst("div p span")?.text()?.toIntOrNull() ?: 0, title = el.selectFirst("div p")?.text(), poster = el.selectFirst("img")?.attr("src")?.replace("w92", "w500")) },
            genres = lst.find { it.selectFirst("span")?.text() == "Genres" }?.select("a")?.map { Genre(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) } ?: emptyList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvId, i) = seasonId.split("/"); return service.getTvShow(tvId).select("div.seasons div.seasons-bx").getOrNull(i.toInt())?.select("ul.seasons-lst li")?.map { el ->
            Episode(id = el.selectFirst("ul.rw a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "", number = el.selectFirst("h3.title > span")?.text()?.substringAfter("-E")?.toIntOrNull() ?: 0, title = el.selectFirst("h3.title")?.ownText(), poster = el.selectFirst("img")?.attr("src")?.toSafeUrl()?.replace("w185", "w500"))
        } ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = "", shows = Jsoup.parse(service.api(JSONObject(mapOf("_wpsearch" to _wpsearch, "taxonomy" to "none", "search" to "", "term" to "none", "type" to "mixed", "genres" to listOf(id), "years" to emptyList<String>(), "sort" to "1", "page" to page)).toString()).html).select("article.movies").mapNotNull { el ->
        val h = el.selectFirst("ul.rw a")?.attr("href") ?: ""; val id = h.substringBeforeLast("/").substringAfterLast("/")
        if (h.contains("/movies/")) Movie(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl())
        else if (h.contains("/series/")) TvShow(id = id, title = el.selectFirst("h2.entry-title")?.text() ?: "", poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl()) else null
    })

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")
        val mDoc = try { service.getCast(id) } catch (_: Exception) { null }; val tDoc = try { service.getCastTv(id) } catch (_: Exception) { null }
        return People(id = id, name = mDoc?.selectFirst("h1.section-title > span")?.text() ?: "", filmography = (listOfNotNull(mDoc?.select("article.movies"), tDoc?.select("article.movies")).flatten().mapNotNull { el ->
            val h = el.selectFirst("ul.rw a")?.attr("href") ?: ""; val sId = h.substringBeforeLast("/").substringAfterLast("/")
            if (h.contains("/movies/")) Movie(id = sId, title = el.selectFirst("h2.entry-title")?.text() ?: "", released = el.selectFirst("span.year")?.text(), poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl())
            else if (h.contains("/series/")) TvShow(id = sId, title = el.selectFirst("h2.entry-title")?.text() ?: "", released = el.selectFirst("span.year")?.text(), poster = el.selectFirst("div.post-thumbnail img")?.attr("src")?.toSafeUrl()) else null
        }).sortedByDescending { if (it is Movie) it.released else (it as TvShow).released })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = if (videoType is Video.Type.Movie) service.getMovie(id) else service.getEpisode(id)
        return doc.select("aside.options li").mapIndexed { i, el -> Video.Server(id = el.attr("data-id"), name = el.selectFirst("span.option")?.text() ?: "", src = doc.selectFirst("div.player div.fg${i + 1} iframe")?.attr("src") ?: "") }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(if (server.src.contains("trembed")) service.getLink(server.src).selectFirst("body iframe")?.attr("src") ?: "" else server.src)

    private fun String.toMinutes(): Int = Regex("(\\d+)h (\\d+)m|(\\d+) min").find(this)?.groupValues.let { (it?.getOrNull(1)?.toIntOrNull() ?: 0) * 60 + (it?.getOrNull(2)?.toIntOrNull() ?: it?.getOrNull(3)?.toIntOrNull() ?: 0) }
    private fun String.toSafeUrl(): String = (if (this.startsWith("https:")) this else "https:$this").substringBefore("?")

    private interface AllMoviesForYouService {
        companion object {
            fun build(): AllMoviesForYouService = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(AllMoviesForYouService::class.java)
        }
        @GET(".") suspend fun getHome(): Document
        @GET(".") suspend fun search(@Query("s") s: String): Document
        @POST("https://anymovie.cc/wp-admin/admin-ajax.php") @FormUrlEncoded suspend fun api(@Field("vars") v: String, @Field("action") a: String = "action_search"): SearchResponse
        @GET("movies/{s}") suspend fun getMovie(@Path("s") s: String): Document
        @GET("series/{s}") suspend fun getTvShow(@Path("s") s: String): Document
        @GET("episode/{id}") suspend fun getEpisode(@Path("id") id: String): Document
        @GET("cast/{s}") suspend fun getCast(@Path("s") s: String): Document
        @GET("cast_tv/{s}") suspend fun getCastTv(@Path("s") s: String): Document
        @GET suspend fun getLink(@Url u: String): Document
        data class SearchResponse(val next: Boolean, val html: String)
    }
}