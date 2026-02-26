package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object CineCalidadProvider : Provider {

    override val name = "CineCalidad"
    override val baseUrl = "https://www.cinecalidad.ec"
    override val language = "es"
    override val logo = "https://www.cinecalidad.ec/wp-content/themes/Cinecalidad/assets/img/logo.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(CineCalidadService::class.java)

    private interface CineCalidadService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    private fun parseShows(elements: List<Element>): List<Show> = elements.mapNotNull { el ->
        val anchor = el.selectFirst("a") ?: return@mapNotNull null; val href = anchor.attr("href")
        val img = el.selectFirst("div.poster img") ?: return@mapNotNull null; val title = img.attr("alt")
        val poster = img.attr("data-src").ifEmpty { img.attr("src") }
        if (href.contains("/ver-pelicula/")) Movie(id = href, title = title, poster = poster)
        else if (href.contains("/ver-serie/")) TvShow(id = href, title = title, poster = poster)
        else null
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val mainDef = async { try { service.getPage(baseUrl) } catch(_:Exception) { null } }
        val actionDef = async { try { service.getPage("$baseUrl/genero-de-la-pelicula/accion/") } catch(_:Exception) { null } }
        val comedyDef = async { try { service.getPage("$baseUrl/genero-de-la-pelicula/comedia/") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        mainDef.await()?.let { doc ->
            doc.select("aside#dtw_content_featured-3 li").mapNotNull {
                val a = it.selectFirst("a") ?: return@mapNotNull null; val h = a.attr("href")
                if (h.contains("/ver-pelicula/")) Movie(id = h, title = a.attr("title"), banner = a.selectFirst("img")?.attr("data-src"))
                else if (h.contains("/ver-serie/")) TvShow(id = h, title = a.attr("title"), banner = a.selectFirst("img")?.attr("data-src"))
                else null
            }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }
            parseShows(doc.select("article.item[id^=post-]")).takeIf { it.isNotEmpty() }?.let { categories.add(Category("Últimos Estrenos", it)) }
        }
        actionDef.await()?.let { parseShows(it.select("article.item[id^=post-]")).takeIf { it.isNotEmpty() }?.let { categories.add(Category("Acción", it)) } }
        comedyDef.await()?.let { parseShows(it.select("article.item[id^=post-]")).takeIf { it.isNotEmpty() }?.let { categories.add(Category("Comedia", it)) } }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "animacion", "anime", "aventura", "belica", "ciencia-ficcion", "crimen", "comedia", "documental", "drama", "familia", "fantasia", "historia", "musica", "misterio", "terror", "suspense", "romance", "universo-marvel").map { Genre(id = "genero-de-la-pelicula/$it", name = it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        return try { parseShows(service.getPage("$baseUrl/page/$page?s=$query").select("article.item[id^=post-]")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { parseShows(service.getPage("$baseUrl/page/$page").select("article.item[id^=post-]")).filterIsInstance<Movie>() } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(service.getPage("$baseUrl/ver-serie/page/$page").select("article.item[id^=post-]")).filterIsInstance<TvShow>() } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = service.getPage(id).let { doc ->
        val title = doc.selectFirst(".single_left h1")?.text() ?: ""
        val td = doc.selectFirst(".single_left td[style*=justify]"); val rating = doc.selectFirst("span:contains(TMDB) b")?.text()?.trim()?.toDoubleOrNull()
        var genres = emptyList<Genre>(); var cast = emptyList<People>()
        td?.selectFirst("p:has(span:contains(Género))")?.children()?.forEach { if (it.text().startsWith("Género:")) genres = it.select("a").map { a -> Genre(id = a.attr("href"), name = a.text()) } else if (it.text().startsWith("Elenco:")) cast = it.select("a").map { a -> People(id = a.attr("href"), name = a.text()) } }
        Movie(id = id, title = title, poster = doc.selectFirst(".single_left table img")?.attr("data-src"), overview = td?.selectFirst("p:not(:has(span))")?.text()?.trim(), rating = rating, genres = genres, cast = cast, trailer = doc.selectFirst("#playeroptionsul li.dooplay_player_option_trailer[data-option]")?.attr("data-option"))
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage(id).let { doc ->
        val td = doc.selectFirst(".single_left td[style*=justify]"); var genres = emptyList<Genre>(); var cast = emptyList<People>()
        td?.selectFirst("p:has(span:contains(Género))")?.children()?.forEach { if (it.text().startsWith("Género:")) genres = it.select("a").map { a -> Genre(id = a.attr("href"), name = a.text()) } else if (it.text().startsWith("Elenco:")) cast = it.select("a").map { a -> People(id = a.attr("href"), name = a.text()) } }
        val seasons = doc.select(".mark-1 .numerando").mapNotNull { it.text().substringAfter("S").substringBefore("-").toIntOrNull() }.distinct().sorted().map { Season(id = "$id|$it", number = it, title = "Temporada $it") }
        val trailer = doc.select("script").map { it.data() }.firstNotNullOfOrNull { js -> if (js.contains("#trailerazo") && js.contains(".src")) js.lineSequence().firstOrNull { l -> l.contains(".src") }?.let { l -> Regex("""['"](https?://[^'\"]+)['"]""").find(l)?.groupValues?.get(1) } else null }
        TvShow(id = id, title = doc.selectFirst(".single_left h1")?.text() ?: "", poster = doc.selectFirst(".single_left table img")?.attr("data-src"), overview = td?.select("p")?.find { it.hasText() && it.selectFirst("span") == null }?.text()?.trim(), rating = doc.selectFirst("span:contains(TMDB) b")?.text()?.trim()?.toDoubleOrNull(), genres = genres, cast = cast, seasons = seasons, trailer = trailer)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (showId, sNum) = seasonId.split('|'); return service.getPage(showId).select(".mark-1").filter { it.selectFirst(".numerando")?.text()?.startsWith("S$sNum-") == true }.map { el ->
            val a = el.selectFirst(".episodiotitle a"); val num = el.selectFirst(".numerando")?.text()?.substringAfter("-E")?.toIntOrNull() ?: 0
            Episode(id = a?.attr("href") ?: "", number = num, title = a?.text() ?: "Episodio $num", poster = el.selectFirst("div.imagen img")?.attr("data-src"))
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }, shows = parseShows(service.getPage("$baseUrl/$id/page/$page").select("article.item[id^=post-]")))

    override suspend fun getPeople(id: String, page: Int): People {
        val url = (if (id.startsWith("http")) id.trimEnd('/') else "$baseUrl/${id.removePrefix(baseUrl).trim('/')}").let { if (page > 1) "$it/page/$page" else it }
        val doc = try { service.getPage(url) } catch (_: Exception) { return People(id = id, name = "") }
        return People(id = id, name = doc.selectFirst("#contenedor .module h1 span, .single_left h1, h1")?.text() ?: "", filmography = parseShows(doc.select("article.item[id^=post-]")))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try { service.getPage(id).select("#playeroptionsul li[data-option]").filterNot { it.hasClass("dooplay_player_option_trailer") || it.text().contains("trailer", true) }.map { Video.Server(id = it.attr("data-option"), name = it.text()) } } catch (_: Exception) { emptyList() }
    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
}