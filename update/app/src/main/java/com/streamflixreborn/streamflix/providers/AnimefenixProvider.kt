package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

object AnimefenixProvider : Provider {

    override val name = "Animefenix"
    override val baseUrl = "https://animefenix2.tv"
    override val language = "es"
    override val logo = "$baseUrl/themes/fenix-neo/images/AveFenix.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(AnimefenixService::class.java)

    private interface AnimefenixService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    private fun parseShows(elements: List<Element>): List<TvShow> = elements.mapNotNull { el ->
        val a = el.selectFirst("a") ?: el; val t = el.selectFirst("h3, p:not(.gray)")?.text() ?: ""
        val p = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        TvShow(id = a.attr("href"), title = t, poster = p)
    }

    private fun parseMovies(elements: List<Element>): List<Movie> = elements.mapNotNull { el ->
        val a = el.selectFirst("a") ?: return@mapNotNull null; val p = el.selectFirst(".main-img img")
        Movie(id = a.attr("href"), title = el.selectFirst("p:not(.gray)")?.text() ?: "", poster = p?.attr("data-src")?.ifEmpty { p.attr("src") })
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val y25Def = async { try { service.getPage("$baseUrl/directorio/anime?estreno=2025") } catch(_:Exception) { null } }
        val y24Def = async { try { service.getPage("$baseUrl/directorio/anime?estreno=2024") } catch(_:Exception) { null } }
        
        val categories = mutableListOf<Category>()
        
        y25Def.await()?.select(".grid-animes li article")?.let { 
            val featured = parseShows(it).take(10).map { it.copy(banner = it.poster) }
            if (featured.isNotEmpty()) categories.add(Category(Category.FEATURED, featured)) 
        }
        
        y24Def.await()?.select(".grid-animes li article")?.let { 
            val latest = parseShows(it)
            if (latest.isNotEmpty()) categories.add(Category("Estrenos 2024", latest)) 
        }
        
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("1" to "Acción", "23" to "Aventuras", "20" to "Ciencia Ficción", "5" to "Comedia", "8" to "Deportes", "38" to "Demonios", "6" to "Drama", "11" to "Ecchi", "2" to "Escolares", "13" to "Fantasía", "28" to "Harem", "24" to "Historico", "47" to "Horror", "25" to "Infantil", "51" to "Isekai", "29" to "Josei", "14" to "Magia", "26" to "Artes Marciales", "21" to "Mecha", "22" to "Militar", "17" to "Misterio", "36" to "Música", "30" to "Parodia", "31" to "Policía", "18" to "Psicológico", "10" to "Recuentos de la vida", "3" to "Romance", "34" to "Samurai", "7" to "Seinen", "4" to "Shoujo", "9" to "Shounen", "12" to "Sobrenatural", "15" to "Superpoderes", "19" to "Suspenso", "27" to "Terror", "39" to "Vampiros", "40" to "Yaoi", "37" to "Yuri").map { Genre(id = it.first, name = it.second) }
        return try { parseShows(service.getPage("$baseUrl/directorio/anime?q=$query&p=$page").select(".grid-animes li article")).distinctBy { it.id } } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(service.getPage("$baseUrl/directorio/anime?p=$page").select(".grid-animes li article")) } catch (_: Exception) { emptyList() }
    override suspend fun getMovies(page: Int): List<Movie> = try { parseMovies(service.getPage("$baseUrl/directorio/anime?tipo=2&p=$page").select(".grid-animes li article")) } catch (_: Exception) { emptyList() }
    
    override suspend fun getGenre(id: String, page: Int): Genre = try { 
        val doc = service.getPage("$baseUrl/directorio/anime?genero=$id&p=$page")
        Genre(id = id, name = doc.selectFirst("h1.text-4xl")?.ownText()?.trim() ?: "Género", shows = parseShows(doc.select(".grid-animes li article"))) 
    } catch (_: Exception) { Genre(id = id, name = "Error") }

    override suspend fun getMovie(id: String): Movie = service.getPage(id).let { doc ->
        val p = doc.selectFirst("#anime_image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        Movie(id = id, title = doc.selectFirst("h1.text-4xl")?.ownText() ?: "", poster = p, overview = doc.selectFirst(".mb-6 p.text-gray-300")?.text(), genres = doc.select("a.bg-gray-800").map { Genre(it.attr("href").substringAfterLast("/"), it.text()) })
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage(id).let { doc ->
        val p = doc.selectFirst("#anime_image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val eps = doc.select(".divide-y li > a").mapNotNull { a -> 
            val t = a.selectFirst(".font-semibold")?.text() ?: return@mapNotNull null
            Episode(id = a.attr("href"), number = t.substringAfter("Episodio ").toIntOrNull() ?: 0, title = t) 
        }.reversed()
        TvShow(id = id, title = doc.selectFirst("h1.text-4xl")?.ownText() ?: "", poster = p, overview = doc.selectFirst(".mb-6 p.text-gray-300")?.text(), genres = doc.select("a.bg-gray-800").map { Genre(it.attr("href").substringAfterLast("/"), it.text()) }, seasons = listOf(Season(id = id, number = 1, title = "Episodios", episodes = eps)))
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = getTvShow(seasonId).seasons.firstOrNull()?.episodes ?: emptyList()

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val url = if (videoType is Video.Type.Movie) service.getPage(id).selectFirst(".divide-y li > a")?.attr("href") ?: id else id
        val doc = service.getPage(url); val script = doc.selectFirst("script:containsData(var tabsArray)") ?: throw Exception()
        val names = doc.select(".episode-page__servers-list li a").map { it.select("span").last()?.text()?.trim() ?: "" }
        script.data().substringAfter("<iframe").split("src='").drop(1).mapIndexedNotNull { i, it -> 
            val u = it.substringBefore("'").substringAfter("redirect.php?id=").trim()
            if (i < names.size && names[i].isNotBlank()) Video.Server(id = u, name = names[i]) else null 
        }
    } catch (_: Exception) { emptyList() }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}