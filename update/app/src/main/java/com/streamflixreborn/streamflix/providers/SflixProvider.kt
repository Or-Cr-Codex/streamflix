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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object SflixProvider : Provider {

    private const val URL = "https://sflix.to/"
    override val baseUrl = URL
    override val name = "SFlix"
    override val logo = "https://img.sflix.to/xxrz/400x400/100/66/35/66356c25ce98cb12993249e21742b129/66356c25ce98cb12993249e21742b129.png"
    override val language = "en"

    private val service = SflixService.build()

    override suspend fun getHome(): List<Category> {
        val document = service.getHome()
        val categories = mutableListOf<Category>()

        categories.add(Category(name = Category.FEATURED, list = document.select("div.swiper-wrapper > div.swiper-slide").map {
            val info = it.select("div.sc-detail > div.scd-item").toInfo()
            val id = it.selectFirst("a")?.attr("href") ?: ""
            val title = it.selectFirst("h2.film-title")?.text() ?: ""
            val overview = it.selectFirst("p.sc-desc")?.text()
            val poster = it.selectFirst("img.film-poster-img")?.attr("src")
            val banner = it.selectFirst("div.slide-photo img")?.attr("src")

            if (it.isMovie()) Movie(id = id, title = title, overview = overview, released = info.released, quality = info.quality, rating = info.rating, poster = poster, banner = banner)
            else TvShow(id = id, title = title, overview = overview, quality = info.quality, rating = info.rating, poster = poster, banner = banner, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
        }))

        listOf("Trending Movies" to "div#trending-movies div.flw-item", "Trending TV Shows" to "div#trending-tv div.flw-item").forEach { (name, selector) ->
            categories.add(Category(name = name, list = document.select(selector).map { el ->
                val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
                val id = el.selectFirst("a")?.attr("href") ?: ""
                val title = el.selectFirst("h3.film-name")?.text() ?: ""
                val poster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
                if (name.contains("Movies")) Movie(id = id, title = title, released = info.released, quality = info.quality, rating = info.rating, poster = poster)
                else TvShow(id = id, title = title, quality = info.quality, rating = info.rating, poster = poster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
            }))
        }

        document.select("section.section-id-02").forEach { section ->
            val catName = section.selectFirst("h2.cat-heading")?.ownText() ?: return@forEach
            if (catName == "Latest Movies" || catName == "Latest TV Shows") {
                categories.add(Category(name = catName, list = section.select("div.flw-item").map { el ->
                    val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
                    val id = el.selectFirst("a")?.attr("href") ?: ""
                    val title = el.selectFirst("h3.film-name")?.text() ?: ""
                    val poster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
                    if (catName.contains("Movies")) Movie(id = id, title = title, released = info.released, quality = info.quality, rating = info.rating, poster = poster)
                    else TvShow(id = id, title = title, quality = info.quality, rating = info.rating, poster = poster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
                }))
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return service.getHome().select("div#sidebar_subs_genre li.nav-item a.nav-link").map { Genre(id = it.attr("href").substringAfterLast("/"), name = it.text()) }.sortedBy { it.name }
        return service.search(query.replace(" ", "-"), page).select("div.flw-item").map { el ->
            val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
            val id = el.selectFirst("a")?.attr("href") ?: ""
            val title = el.selectFirst("h2.film-name")?.text() ?: ""
            val poster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
            if (el.isMovie()) Movie(id = id, title = title, released = info.released, quality = info.quality, rating = info.rating, poster = poster)
            else TvShow(id = id, title = title, quality = info.quality, rating = info.rating, poster = poster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = service.getMovies(page).select("div.flw-item").map { el ->
        val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
        Movie(id = el.selectFirst("a")?.attr("href") ?: "", title = el.selectFirst("h2.film-name")?.text() ?: "", released = info.released, quality = info.quality, rating = info.rating, poster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src"))
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getTvShows(page).select("div.flw-item").map { el ->
        val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
        TvShow(id = el.selectFirst("a")?.attr("href") ?: "", title = el.selectFirst("h2.film-name")?.text() ?: "", quality = info.quality, rating = info.rating, poster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src"), seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getMovie(id)
        val elements = doc.select("div.elements > .row > div > .row-line")
        return Movie(
            id = id, title = doc.selectFirst("h2.heading-name")?.text() ?: "", overview = doc.selectFirst("div.description")?.ownText(),
            released = elements.find { it.select(".type").text().contains("Released") }?.ownText()?.trim(),
            runtime = elements.find { it.select(".type").text().contains("Duration") }?.ownText()?.removeSuffix("min")?.trim()?.toIntOrNull(),
            trailer = doc.selectFirst("iframe#iframe-trailer")?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            quality = doc.selectFirst(".fs-item > .quality")?.text()?.trim(),
            rating = doc.selectFirst(".fs-item > .imdb")?.text()?.trim()?.removePrefix("IMDB:")?.toDoubleOrNull(),
            poster = doc.selectFirst("div.detail_page-watch img.film-poster-img")?.attr("src"),
            banner = doc.selectFirst("div.detail-container > div.cover_follow")?.attr("style")?.substringAfter("background-image: url(")?.substringBefore(");"),
            genres = elements.find { it.select(".type").text().contains("Genre") }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = elements.find { it.select(".type").text().contains("Cast") }?.select("a")?.map { People(id = it.attr("href").substringAfter("/cast/"), name = it.text()) } ?: listOf(),
            recommendations = doc.select("div.film_related div.flw-item").map { el ->
                val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
                val rId = el.selectFirst("a")?.attr("href") ?: ""
                val rTitle = el.selectFirst("h3.film-name")?.text() ?: ""
                val rPoster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
                if (el.isMovie()) Movie(id = rId, title = rTitle, released = info.released, quality = info.quality, rating = info.rating, poster = rPoster)
                else TvShow(id = rId, title = rTitle, quality = info.quality, rating = info.rating, poster = rPoster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val docDeferred = async { service.getTvShow(id) }
        val seasonsDeferred = async { service.getTvShowSeasons(id.substringAfterLast("-")) }
        
        val doc = docDeferred.await()
        val seasonsDoc = seasonsDeferred.await()
        val elements = doc.select("div.elements > .row > div > .row-line")

        return@coroutineScope TvShow(
            id = id, title = doc.selectFirst("h2.heading-name")?.text() ?: "", overview = doc.selectFirst("div.description")?.ownText(),
            released = elements.find { it.select(".type").text().contains("Released") }?.ownText()?.trim(),
            runtime = elements.find { it.select(".type").text().contains("Duration") }?.ownText()?.removeSuffix("min")?.trim()?.toIntOrNull(),
            trailer = doc.selectFirst("iframe#iframe-trailer")?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" },
            quality = doc.selectFirst(".fs-item > .quality")?.text()?.trim(),
            rating = doc.selectFirst(".fs-item > .imdb")?.text()?.trim()?.removePrefix("IMDB:")?.toDoubleOrNull(),
            poster = doc.selectFirst("div.detail_page-watch img.film-poster-img")?.attr("src"),
            banner = doc.selectFirst("div.detail-container > div.cover_follow")?.attr("style")?.substringAfter("background-image: url(")?.substringBefore(");"),
            seasons = seasonsDoc.select("div.dropdown-menu.dropdown-menu-model > a").mapIndexed { i, el -> Season(id = el.attr("data-id"), number = i + 1, title = el.text()) },
            genres = elements.find { it.select(".type").text().contains("Genre") }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = elements.find { it.select(".type").text().contains("Cast") }?.select("a")?.map { People(id = it.attr("href").substringAfter("/cast/"), name = it.text()) } ?: listOf(),
            recommendations = doc.select("div.film_related div.flw-item").map { el ->
                val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
                val rId = el.selectFirst("a")?.attr("href") ?: ""
                val rTitle = el.selectFirst("h3.film-name")?.text() ?: ""
                val rPoster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
                if (el.isMovie()) Movie(id = rId, title = rTitle, released = info.released, quality = info.quality, rating = info.rating, poster = rPoster)
                else TvShow(id = rId, title = rTitle, quality = info.quality, rating = info.rating, poster = rPoster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
            }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = service.getSeasonEpisodes(seasonId).select("div.flw-item.film_single-item.episode-item.eps-item").mapIndexed { i, el ->
        Episode(id = el.attr("data-id"), number = el.selectFirst("div.episode-number")?.text()?.substringAfter("Episode ")?.substringBefore(":")?.toIntOrNull() ?: i, title = el.selectFirst("h3.film-name")?.text(), poster = el.selectFirst("img")?.attr("src"))
    }

    override suspend fun getGenre(id: String, page: Int): Genre = service.getGenre(id, page).let { doc ->
        Genre(id = id, name = doc.selectFirst("h2.cat-heading")?.text()?.removeSuffix(" Movies and TV Shows") ?: "", shows = doc.select("div.flw-item").map { el ->
            val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
            val sId = el.selectFirst("a")?.attr("href") ?: ""; val sTitle = el.selectFirst("h2.film-name")?.text() ?: ""; val sPoster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
            if (el.isMovie()) Movie(id = sId, title = sTitle, released = info.released, quality = info.quality, rating = info.rating, poster = sPoster)
            else TvShow(id = sId, title = sTitle, quality = info.quality, rating = info.rating, poster = sPoster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
        })
    }

    override suspend fun getPeople(id: String, page: Int): People = service.getPeople(id, page).let { doc ->
        People(id = id, name = doc.selectFirst("h2.cat-heading")?.text() ?: "", filmography = doc.select("div.flw-item").map { el ->
            val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
            val sId = el.selectFirst("a")?.attr("href") ?: ""; val sTitle = el.selectFirst("h2.film-name")?.text() ?: ""; val sPoster = el.selectFirst("div.film-poster > img.film-poster-img")?.attr("data-src")
            if (el.isMovie()) Movie(id = sId, title = sTitle, released = info.released, quality = info.quality, rating = info.rating, poster = sPoster)
            else TvShow(id = sId, title = sTitle, quality = info.quality, rating = info.rating, poster = sPoster, seasons = info.lastEpisode?.let { le -> listOf(Season(id = "", number = le.season, episodes = listOf(Episode(id = "", number = le.episode)))) } ?: listOf())
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = (if (videoType is Video.Type.Movie) service.getMovieServers(id.substringAfterLast("-")) else service.getEpisodeServers(id)).select("a").map { Video.Server(id = it.attr("data-id"), name = it.selectFirst("span")?.text()?.trim() ?: "") }.takeIf { it.isNotEmpty() } ?: throw Exception("No links found")

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(service.getLink(server.id).link, server)

    private fun Element.isMovie(): Boolean = this.selectFirst("a")?.attr("href")?.contains("/movie/") ?: false

    private fun Elements.toInfo() = this.map { it.text() }.let { list ->
        object {
            val rating = list.find { s -> s.matches("^\\d(?:\\.\\d)?\$".toRegex()) }?.toDoubleOrNull()
            val quality = list.find { s -> s in listOf("HD", "SD", "CAM", "TS", "HDRip") }
            val released = list.find { s -> s.matches("\\d{4}".toRegex()) }
            val lastEpisode = list.find { s -> s.matches("S\\d+\\s*:E\\d+".toRegex()) }?.let { s ->
                val res = Regex("S(\\d+)\\s*:E(\\d+)").find(s)?.groupValues
                object { val season = res?.getOrNull(1)?.toIntOrNull() ?: 0; val episode = res?.getOrNull(2)?.toIntOrNull() ?: 0 }
            }
        }
    }

    private interface SflixService {
        companion object {
            fun build(): SflixService = Retrofit.Builder()
                .baseUrl(URL)
                .addConverterFactory(JsoupConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .client(NetworkClient.default)
                .build()
                .create(SflixService::class.java)
        }

        @GET("home") suspend fun getHome(): Document
        @GET("search/{query}") suspend fun search(@Path("query") query: String, @Query("page") page: Int): Document
        @GET("movie") suspend fun getMovies(@Query("page") page: Int): Document
        @GET("tv-show") suspend fun getTvShows(@Query("page") page: Int): Document
        @GET("{id}") suspend fun getMovie(@Path("id") id: String): Document
        @GET("ajax/episode/list/{id}") suspend fun getMovieServers(@Path("id") movieId: String): Document
        @GET("{id}") suspend fun getTvShow(@Path("id") id: String): Document
        @GET("ajax/season/list/{id}") suspend fun getTvShowSeasons(@Path("id") tvShowId: String): Document
        @GET("ajax/season/episodes/{id}") suspend fun getSeasonEpisodes(@Path("id") seasonId: String): Document
        @GET("ajax/episode/servers/{id}") suspend fun getEpisodeServers(@Path("id") episodeId: String): Document
        @GET("genre/{id}") suspend fun getGenre(@Path("id") id: String, @Query("page") page: Int): Document
        @GET("cast/{id}") suspend fun getPeople(@Path("id") id: String, @Query("page") page: Int): Document
        @GET("ajax/episode/sources/{id}") suspend fun getLink(@Path("id") id: String): Link

        data class Link(val type: String = "", val link: String = "", val sources: List<String> = listOf(), val tracks: List<String> = listOf(), val title: String = "")
    }
}