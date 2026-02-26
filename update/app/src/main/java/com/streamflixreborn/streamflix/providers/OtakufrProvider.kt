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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object OtakufrProvider : Provider {

    private const val URL = "https://otakufr.cc/"
    override val baseUrl = URL
    override val name = "Otakufr"
    override val logo = "https://i.ibb.co/GndKBbF/otakufr-logo.webp"
    override val language = "fr"

    private val service = Service.build()

    override suspend fun getHome(): List<Category> = coroutineScope {
        val enCoursDef = async { try { service.getEnCours().select("article.card").map { parseShow(it) } } catch(_:Exception) { emptyList<TvShow>() } }
        val terminesDef = async { try { service.getTermines().select("article.card").map { parseShow(it) } } catch(_:Exception) { emptyList<TvShow>() } }
        
        listOf(Category("En cours", enCoursDef.await()), Category("Terminé", terminesDef.await()))
    }

    private fun parseShow(el: Element): TvShow = TvShow(
        id = el.selectFirst("a")?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/") ?: "",
        title = el.selectFirst("a.episode-name.h4")?.text() ?: "",
        overview = el.selectFirst("div.except p")?.ownText(),
        poster = el.selectFirst("img")?.attr("src")
    )

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getHome().select("div.dropdown-menu a").map { Genre(id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"), name = it.text()) }
        if (page > 1) return emptyList()
        return service.search(query).select("article.card").map { parseShow(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = throw Exception("Not supported")

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getAnimes(page).select("article.card").map { parseShow(it) }

    override suspend fun getMovie(id: String): Movie = throw Exception("Not supported")

    override suspend fun getTvShow(id: String): TvShow = service.getAnime(id).let { doc ->
        TvShow(
            id = id, title = doc.selectFirst("div.title.h1")?.text() ?: "",
            overview = doc.select("div.synop p").joinToString("\n") { it.ownText() },
            released = doc.select("div.synop ul li").find { it.selectFirst("strong")?.text()?.contains("Sortie initiale") == true }?.ownText(),
            runtime = doc.select("div.synop ul li").find { it.selectFirst("strong")?.text()?.contains("Durée") == true }?.ownText()?.substringBefore(" min")?.toIntOrNull(),
            poster = doc.selectFirst("div.card-body img")?.attr("src"),
            seasons = listOf(Season(id = id, title = "Épisodes")),
            genres = doc.select("div.synop ul li").find { it.selectFirst("strong")?.text()?.contains("Genre") == true }?.select("a")?.map { Genre(it.attr("href").substringBeforeLast("/").substringAfterLast("/"), it.text()) } ?: emptyList()
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = service.getAnime(seasonId).select("div.list-episodes a").reversed().mapIndexed { i, el ->
        Episode(id = el.attr("href").substringBeforeLast("/").substringAfterLast("/"), number = i + 1, title = el.ownText(), released = el.selectFirst("span")?.text())
    }

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val doc = service.getGenre(id, page)
        Genre(id = id, name = doc.selectFirst("div.title.h1")?.text() ?: "", shows = doc.select("article.card").map { parseShow(it) })
    } catch (e: HttpException) { if (e.code() == 404) Genre(id, "") else throw e }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = service.getEpisode(id).let { doc ->
        doc.select("div#nav-tab a").map { Video.Server(id = it.id(), name = it.text(), src = doc.selectFirst("div#${it.attr("aria-controls")} iframe")?.attr("src") ?: "") }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = if (server.src.contains("parisanime.com")) service.getParisanime(server.src).selectFirst("div[data-url]")?.attr("data-url")?.let { if (it.startsWith("//")) "https:$it" else it } ?: throw Exception() else server.src
        return Extractor.extract(url)
    }

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default.newBuilder().addInterceptor { chain ->
                val res = chain.proceed(chain.request())
                if (!res.isSuccessful && !res.body?.string().isNullOrEmpty()) res.newBuilder().code(200).body(res.body!!.string().toResponseBody(res.body?.contentType())).build() else res
            }.build()).build().create(Service::class.java)
        }
        @GET(".") suspend fun getHome(): Document
        @GET("en-cours") suspend fun getEnCours(): Document
        @GET("termine") suspend fun getTermines(): Document
        @GET("toute-la-liste-affiches") suspend fun search(@Query("q") q: String): Document
        @GET("en-cours/page/{page}") suspend fun getAnimes(@Path("page") p: Int): Document
        @GET("anime/{id}") suspend fun getAnime(@Path("id") id: String): Document
        @GET("genre/{slug}/page/{page}") suspend fun getGenre(@Path("slug") s: String, @Path("page") p: Int): Document
        @GET("episode/{id}") suspend fun getEpisode(@Path("id") id: String): Document
        @GET @Headers("X-Requested-With: XMLHttpRequest") suspend fun getParisanime(@Url url: String): Document
    }
}