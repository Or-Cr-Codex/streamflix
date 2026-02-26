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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object HiAnimeProvider : Provider {

    private const val URL = "https://hianime.to/"
    override val baseUrl = URL
    override val name = "HiAnime"
    override val logo = "$URL/images/logo.png"
    override val language = "en"

    private val service = HiAnimeService.build()

    override suspend fun getHome(): List<Category> = coroutineScope {
        val document = service.getHome()
        val categories = mutableListOf<Category>()

        val featuredDef = async {
            document.select("div#slider div.swiper-slide").map {
                val id = it.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""
                val title = it.selectFirst("div.desi-head-title")?.text() ?: ""
                val overview = it.selectFirst("div.desi-description")?.text()
                val runtime = it.select("div.scd-item").firstOrNull { el -> el.selectFirst("i.fa-clock") != null }?.text()?.removeSuffix("m")?.toIntOrNull()
                val quality = it.selectFirst("div.quality")?.text()
                val banner = it.selectFirst("img.film-poster-img")?.attr("data-src")
                val isMovie = it.select("div.scd-item").any { el -> el.selectFirst("i.fa-play-circle") != null && el.text() == "Movie" }

                if (isMovie) Movie(id = id, title = title, overview = overview, runtime = runtime, quality = quality, banner = banner)
                else TvShow(id = id, title = title, overview = overview, runtime = runtime, quality = quality, banner = banner, seasons = it.selectFirst("div.tick-sub")?.text()?.toIntOrNull()?.let { le -> listOf(Season(id = "", number = 0, episodes = listOf(Episode(id = "", number = le)))) } ?: listOf())
            }
        }

        val blocksDef = async {
            document.select("div.anif-block").map { block ->
                Category(name = block.selectFirst("div.anif-block-header")?.text() ?: "", list = block.select("li").map { el ->
                    val id = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""
                    val title = el.selectFirst("h3.film-name")?.text() ?: ""
                    val poster = el.selectFirst("img.film-poster-img")?.attr("data-src")
                    val isMovie = el.select("div.fd-infor span.fdi-item").lastOrNull()?.text() == "Movie"
                    if (isMovie) Movie(id = id, title = title, poster = poster)
                    else TvShow(id = id, title = title, poster = poster, seasons = el.selectFirst("div.tick-sub")?.text()?.toIntOrNull()?.let { le -> listOf(Season(id = "", number = 0, episodes = listOf(Episode(id = "", number = le)))) } ?: listOf())
                })
            }
        }

        val areaBlocksDef = async {
            document.select("section.block_area.block_area_home").mapNotNull { block ->
                val name = block.selectFirst("h2.cat-heading")?.text() ?: ""
                if (name != "Top Upcoming") {
                    Category(name = name, list = block.select("div.flw-item").map { el ->
                        val id = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""
                        val title = el.selectFirst("h3.film-name")?.text() ?: ""
                        val runtime = el.selectFirst("div.fd-infor span.fdi-duration")?.text()?.removeSuffix("m")?.toIntOrNull()
                        val poster = el.selectFirst("img.film-poster-img")?.attr("data-src")
                        val isMovie = el.selectFirst("div.fd-infor span.fdi-item")?.text() == "Movie"
                        if (isMovie) Movie(id = id, title = title, runtime = runtime, poster = poster)
                        else TvShow(id = id, title = title, runtime = runtime, poster = poster, seasons = el.selectFirst("div.tick-sub")?.text()?.toIntOrNull()?.let { le -> listOf(Season(id = "", number = 0, episodes = listOf(Episode(id = "", number = le)))) } ?: listOf())
                    })
                } else null
            }
        }

        categories.add(Category(name = Category.FEATURED, list = featuredDef.await()))
        categories.addAll(blocksDef.await())
        categories.addAll(areaBlocksDef.await())
        
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getHome().select("div#sidebar_subs_genre a.nav-link").map { Genre(id = it.attr("href").substringAfterLast("/"), name = it.text()) }.sortedBy { it.name }
        return service.search(query.replace(" ", "+"), page).select("div.flw-item").map { el ->
            val id = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val title = el.selectFirst("h3.film-name")?.text() ?: ""
            val runtime = el.selectFirst("span.fdi-duration")?.text()?.removeSuffix("m")?.toIntOrNull(); val poster = el.selectFirst("img.film-poster-img")?.attr("data-src")
            if (el.selectFirst("div.fd-infor > span.fdi-item")?.text() == "Movie") Movie(id = id, title = title, runtime = runtime, poster = poster)
            else TvShow(id = id, title = title, runtime = runtime, poster = poster)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = service.getMovies(page).select("div.flw-item").map { el ->
        Movie(id = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("h3.film-name")?.text() ?: "", overview = el.selectFirst("div.description")?.text(), runtime = el.selectFirst("span.fdi-duration")?.text()?.removeSuffix("m")?.toIntOrNull(), poster = el.selectFirst("img.film-poster-img")?.attr("data-src"))
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getTvSeries(page).select("div.flw-item").map { el ->
        TvShow(id = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: "", title = el.selectFirst("h3.film-name")?.text() ?: "", overview = el.selectFirst("div.description")?.text(), runtime = el.selectFirst("div.fd-infor span.fdi-duration")?.text()?.removeSuffix("m")?.toIntOrNull(), poster = el.selectFirst("img.film-poster-img")?.attr("data-src"), seasons = el.selectFirst("div.tick-sub")?.text()?.toIntOrNull()?.let { le -> listOf(Season(id = "", number = 0, episodes = listOf(Episode(id = "", number = le)))) } ?: listOf())
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getMovie(id)
        val infoItems = doc.select("div.anisc-info div.item")
        Movie(
            id = id, title = doc.selectFirst("div.anisc-detail h2.film-name")?.text() ?: "", overview = doc.selectFirst("div.anisc-detail div.film-description > .text")?.text(),
            released = infoItems.find { it.selectFirst("span.item-head")?.text() == "Aired:" }?.selectFirst("span.name")?.text()?.substringBefore(" to"),
            runtime = infoItems.find { it.selectFirst("span.item-head")?.text() == "Duration:" }?.selectFirst("span.name")?.text()?.let { (it.substringBefore("h").toIntOrNull() ?: 0) * 60 + (it.substringAfter("h ").substringBefore("m").toIntOrNull() ?: 0) },
            trailer = doc.select("section.block_area-promotions div.item").firstOrNull { it.attr("data-src").contains("youtube") }?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            rating = infoItems.find { it.selectFirst("span.item-head")?.text() == "MAL Score:" }?.selectFirst("span.name")?.text()?.toDoubleOrNull(),
            poster = doc.selectFirst("div.anisc-poster img")?.attr("src"),
            genres = infoItems.find { it.selectFirst("span.item-head")?.text() == "Genres:" }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = doc.select("div.block-actors-content div.bac-item").mapNotNull { el ->
                val name = el.selectFirst("div.rtl h4.pi-name")?.text() ?: ""; if (name.isEmpty()) null else People(id = el.selectFirst("div.rtl a")?.attr("href")?.substringAfterLast("/") ?: "", name = name, image = el.selectFirst("div.rtl img")?.attr("data-src"))
            },
            recommendations = doc.select("section.block_area_category").find { it.selectFirst("h2.cat-heading")?.text() == "Recommended for you" }?.select("div.flw-item")?.map { el ->
                val rId = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val rTitle = el.selectFirst("h3.film-name")?.text() ?: ""; val rRuntime = el.selectFirst("div.fd-infor span.fdi-duration")?.text()?.substringBefore("m")?.toIntOrNull(); val rPoster = el.selectFirst("img")?.attr("data-src")
                if (el.selectFirst("div.fd-infor > span.fdi-item")?.text() == "Movie") Movie(id = rId, title = rTitle, runtime = rRuntime, poster = rPoster)
                else TvShow(id = rId, title = rTitle, runtime = rRuntime, poster = rPoster)
            } ?: listOf()
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getTvShow(id)
        val infoItems = doc.select("div.anisc-info div.item")
        TvShow(
            id = id, title = doc.selectFirst("div.anisc-detail h2.film-name")?.text() ?: "", overview = doc.selectFirst("div.anisc-detail div.film-description > .text")?.text(),
            released = infoItems.find { it.selectFirst("span.item-head")?.text() == "Aired:" }?.selectFirst("span.name")?.text()?.substringBefore(" to"),
            runtime = infoItems.find { it.selectFirst("span.item-head")?.text() == "Duration:" }?.selectFirst("span.name")?.text()?.let { (it.substringBefore("h").toIntOrNull() ?: 0) * 60 + (it.substringAfter("h ").substringBefore("m").toIntOrNull() ?: 0) },
            trailer = doc.select("section.block_area-promotions div.item").firstOrNull { it.attr("data-src").contains("youtube") }?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            rating = infoItems.find { it.selectFirst("span.item-head")?.text() == "MAL Score:" }?.selectFirst("span.name")?.text()?.toDoubleOrNull(),
            poster = doc.selectFirst("div.anisc-poster img")?.attr("src"), seasons = listOf(Season(id = id.substringAfterLast("-"), number = 0, title = "Episodes")),
            genres = infoItems.find { it.selectFirst("span.item-head")?.text() == "Genres:" }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = doc.select("div.block-actors-content div.bac-item").mapNotNull { el ->
                val name = el.selectFirst("div.rtl h4.pi-name")?.text() ?: ""; if (name.isEmpty()) null else People(id = el.selectFirst("div.rtl a")?.attr("href")?.substringAfterLast("/") ?: "", name = name, image = el.selectFirst("div.rtl img")?.attr("data-src"))
            },
            recommendations = doc.select("section.block_area_category").find { it.selectFirst("h2.cat-heading")?.text() == "Recommended for you" }?.select("div.flw-item")?.map { el ->
                val rId = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val rTitle = el.selectFirst("h3.film-name")?.text() ?: ""; val rRuntime = el.selectFirst("div.fd-infor span.fdi-duration")?.text()?.substringBefore("m")?.toIntOrNull(); val rPoster = el.selectFirst("img")?.attr("data-src")
                if (el.selectFirst("div.fd-infor > span.fdi-item")?.text() == "Movie") Movie(id = rId, title = rTitle, runtime = rRuntime, poster = rPoster)
                else TvShow(id = rId, title = rTitle, runtime = rRuntime, poster = rPoster)
            } ?: listOf()
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = Jsoup.parse(service.getTvShowEpisodes(seasonId).html).select("div.ss-list > a[href].ssl-item.ep-item").map {
        Episode(id = it.attr("href").substringAfterLast("="), number = it.selectFirst("div.ssli-order")?.text()?.toIntOrNull() ?: 0, title = it.selectFirst("div.ep-name")?.text())
    }

    override suspend fun getGenre(id: String, page: Int): Genre = service.getGenre(id, page).let { doc ->
        Genre(id = id, name = doc.selectFirst("h2.cat-heading")?.text() ?: "", shows = doc.select("div.flw-item").map { el ->
            val sId = el.selectFirst("a")?.attr("href")?.substringAfterLast("/") ?: ""; val sTitle = el.selectFirst("h3.film-name")?.text() ?: ""; val sOverview = el.selectFirst("div.description")?.text()
            val sRuntime = el.selectFirst("div.fd-infor span.fdi-duration")?.text()?.substringBefore("m")?.toIntOrNull(); val sPoster = el.selectFirst("img")?.attr("data-src")
            if (el.selectFirst("div.fd-infor > span.fdi-item")?.text() == "Movie") Movie(id = sId, title = sTitle, overview = sOverview, runtime = sRuntime, poster = sPoster)
            else TvShow(id = sId, title = sTitle, overview = sOverview, runtime = sRuntime, poster = sPoster)
        })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPeople(id)
        if (page > 1) return People(id = id, name = doc.selectFirst("h4.name")?.text() ?: "", image = doc.selectFirst("div.avatar img")?.attr("src"))
        return People(id = id, name = doc.selectFirst("h4.name")?.text() ?: "", image = doc.selectFirst("div.avatar img")?.attr("src"), filmography = doc.select("div.bac-item").map { el ->
            val sId = el.selectFirst("div.anime-info a")?.attr("href")?.substringAfterLast("/") ?: ""; val sTitle = el.selectFirst("div.anime-info h4.pi-name")?.text() ?: ""
            val sReleased = el.selectFirst("div.anime-info div.pi-detail span.pi-cast")?.text()?.substringAfterLast(", "); val sPoster = el.selectFirst("div.anime-info img")?.attr("src")
            if (el.selectFirst("div.anime-info div.pi-detail span.pi-cast")?.text()?.substringBefore(", ") == "Movie") Movie(id = sId, title = sTitle, released = sReleased, poster = sPoster)
            else TvShow(id = sId, title = sTitle, released = sReleased, poster = sPoster)
        }.distinctBy { when (it) { is Movie -> it.id; is TvShow -> it.id; else -> "" } })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val epId = if (videoType is Video.Type.Movie) Jsoup.parse(service.getTvShowEpisodes(id.substringAfterLast("-")).html).selectFirst("div.ss-list > a[href].ssl-item.ep-item")?.attr("href")?.substringAfterLast("=") ?: "" else id
        Jsoup.parse(service.getServers(epId).html).select("div.server-item[data-type][data-id]").map { Video.Server(id = it.attr("data-id"), name = "${it.text().trim()} - ${it.attr("data-type").uppercase()}") }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(service.getLink(server.id).link)

    private interface HiAnimeService {
        companion object {
            fun build(): HiAnimeService = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(HiAnimeService::class.java)
        }
        @GET("home") suspend fun getHome(): Document
        @GET("search") suspend fun search(@Query("keyword", encoded = true) q: String, @Query("page") p: Int): Document
        @GET("movie") suspend fun getMovies(@Query("page") p: Int): Document
        @GET("tv") suspend fun getTvSeries(@Query("page") p: Int): Document
        @GET("{id}") suspend fun getMovie(@Path("id") id: String): Document
        @GET("{id}") suspend fun getTvShow(@Path("id") id: String): Document
        @GET("ajax/v2/episode/list/{id}") suspend fun getTvShowEpisodes(@Path("id") id: String): Response
        @GET("genre/{id}") suspend fun getGenre(@Path("id") id: String, @Query("page") p: Int): Document
        @GET("people/{id}") suspend fun getPeople(@Path("id") id: String): Document
        @GET("ajax/v2/episode/servers") suspend fun getServers(@Query("episodeId") id: String): Response
        @GET("ajax/v2/episode/sources") suspend fun getLink(@Query("id") id: String): Link

        data class Response(val status: Boolean, val html: String, val totalItems: Int? = null, val continueWatch: Boolean? = null)
        data class Link(val type: String = "", val link: String = "", val sources: List<String> = listOf(), val tracks: List<String> = listOf(), val title: String = "")
    }
}