package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder

object LaCartoonsProvider : Provider {

    override val name = "La Cartoons"
    override val baseUrl = "https://www.lacartoons.com"
    override val language = "es"
    override val logo: String get() = "https://images2.imgbox.com/fc/26/S7f7dn42_o.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(Service::class.java)

    private interface Service {
        @GET suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = try { listOf(Category(name = "Series", list = getTvShows(page = 1))) } catch (_: Exception) { emptyList() }

    private fun parseHomeShows(doc: Document): List<TvShow> = doc.select("div.conjuntos-series").flatMap { it.select("a[href^=/serie/], a[href*=/serie/]") }.mapNotNull { a ->
        val h = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null; val card = a.selectFirst("div.serie") ?: return@mapNotNull null
        val img = card.selectFirst("img")?.attr("src") ?: ""; val t = card.selectFirst("p.nombre-serie")?.text() ?: ""
        val p = if (img.startsWith("http")) img else "$baseUrl$img"; val id = if (h.startsWith("http")) h else "$baseUrl$h"
        TvShow(id = id, title = t, poster = p, banner = p)
    }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.id }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return try { service.getPage(baseUrl).select("ul.botontes-categorias li form").mapNotNull { f -> val v = f.selectFirst("input[name=Categoria_id]")?.attr("value"); val n = f.selectFirst("input[type=submit]")?.attr("value"); if (v != null && n != null) Genre(id = v, name = n) else null } } catch (_: Exception) { emptyList() }
        if (page > 1) return emptyList()
        return try { parseHomeShows(service.getPage("$baseUrl/?utf8=%E2%9C%93&Titulo=${URLEncoder.encode(query, "UTF-8")}")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseHomeShows(service.getPage(if (page <= 1) baseUrl else "$baseUrl/?page=$page")) } catch (_: Exception) { emptyList() }
    override suspend fun getMovie(id: String): Movie = throw Exception("Series only")

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(if (id.startsWith("http")) id else "$baseUrl$id")
        val p = doc.selectFirst("div.contenedor-informacion-serie img")?.attr("src") ?: ""
        val t = doc.selectFirst("h2.subtitulo-serie-seccion")?.ownText()?.trim() ?: doc.selectFirst("p.nombre-serie")?.text()?.trim() ?: doc.selectFirst("h1,h2,h3")?.text()?.trim() ?: ""
        val info = doc.selectFirst("div.informacion-serie-seccion")
        var counter = 0; val seasons = doc.select("section.contenedor-episodio-temporada h4.accordion").map { h -> val n = Regex("Temporada\\s+(\\d+)").find(h.text())?.groupValues?.get(1)?.toIntOrNull() ?: (++counter); Season(id = "${if (id.startsWith("http")) id else "$baseUrl$id"}?t=$n", number = n, title = "Temporada $n") }.takeIf { it.isNotEmpty() } ?: listOf(Season("${if (id.startsWith("http")) id else "$baseUrl$id"}?t=1", 1, "Temporada 1"))
        return TvShow(id = id, title = t, poster = if (p.startsWith("http")) p else "$baseUrl$p", overview = info?.select("p")?.find { it.text().startsWith("Reseña") }?.selectFirst("span")?.text(), rating = info?.selectFirst("span.valoracion1")?.ownText()?.trim()?.toDoubleOrNull(), seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doc = service.getPage(seasonId); val sNum = seasonId.substringAfterLast("?t=").toIntOrNull(); val panels = doc.select("section.contenedor-episodio-temporada div.episodio-panel")
        val links = if (sNum != null && panels.size >= sNum) panels[sNum - 1].select("ul.listas-de-episodion li a") else if (sNum != null) doc.select("ul.listas-de-episodion li a[href*=?t=$sNum]") else doc.select("ul.listas-de-episodion li a")
        return links.mapNotNull { a -> val h = a.attr("href"); if (h.isBlank()) null else { val t = a.text().trim(); Episode(id = if (h.startsWith("http")) h else "$baseUrl$h", number = t.substringAfter("Capitulo ", "").substringBefore("-").trim().toIntOrNull() ?: 0, title = t) } }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = try {
        val doc = service.getPage(if (page <= 1) "$baseUrl/?Categoria_id=$id" else "$baseUrl/?Categoria_id=$id&page=$page")
        val name = service.getPage(baseUrl).select("ul.botontes-categorias li form").find { it.selectFirst("input[name=Categoria_id]")?.attr("value") == id }?.selectFirst("input[type=submit]")?.attr("value") ?: id
        Genre(id = id, name = name, shows = parseHomeShows(doc))
    } catch (_: Exception) { Genre(id = id, name = id, shows = emptyList()) }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = service.getPage(if (id.startsWith("http")) id else "$baseUrl$id").selectFirst("iframe[src]")?.attr("src")?.let { src ->
        try { val name = src.trim().toHttpUrl().host.replace("www.", "").substringBefore(".").replaceFirstChar { it.titlecase() }; listOf(Video.Server(id = src.trim(), name = name, src = src.trim())) } catch (_: Exception) { emptyList() }
    } ?: emptyList()

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
}