package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

object LatanimeProvider : Provider {

    override val name = "Latanime"
    override val baseUrl = "https://latanime.org"
    override val language = "es"
    override val logo: String get() = "https://latanime.org/public/img/logito.png"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(LatanimeService::class.java)

    private interface LatanimeService {
        @GET suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val hDef = async { try { service.getPage(baseUrl) } catch(_:Exception) { null } }
        val a25Def = async { try { service.getPage("$baseUrl/animes?fecha=2025") } catch(_:Exception) { null } }
        val a24Def = async { try { service.getPage("$baseUrl/animes?fecha=2024") } catch(_:Exception) { null } }

        val categories = mutableListOf<Category>()
        hDef.await()?.let { doc ->
            doc.select("div.carousel-item").map { el -> TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("span.span-slider")!!.text(), banner = el.selectFirst("img")?.attr("data-src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }) }.takeIf { it.isNotEmpty() }?.let { categories.add(Category(Category.FEATURED, it)) }
            doc.select("h2:contains(Añadidos recentemente) + div.row div.col-6").map { el ->
                val p = el.selectFirst("img")?.attr("data-src") ?: ""; TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("h2.mt-3")!!.text().substringAfter(" - "), poster = if (p.startsWith("http")) p else "$baseUrl$p")
            }.takeIf { it.isNotEmpty() }?.let { categories.add(Category("Añadidos Recientemente", it)) }
        }
        
        suspend fun addYearly(def: kotlinx.coroutines.Deferred<Document?>, year: String) {
            def.await()?.select("div.row > div:has(a)")?.map { el ->
                val p = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""
                TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("div.seriedetails > h3")!!.text(), poster = if (p.startsWith("http")) p else "$baseUrl$p")
            }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category("Animes del $year", it.take(12))) }
        }
        addYearly(a25Def, "2025"); addYearly(a24Def, "2024")
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "aventura", "carreras", "ciencia-ficcion", "comedia", "deportes", "drama", "escolares", "fantasia", "harem", "horror", "josei", "lucha", "magia", "mecha", "militar", "misterio", "musica", "psicologico", "romance", "seinen", "shojo", "shonen", "sobrenatural", "vampiros", "yaoi", "yuri").map { Genre(it, it.replaceFirstChar { c -> c.uppercase() }) }
        if (page > 1) return emptyList()
        return try { service.getPage("$baseUrl/buscar?q=$query").select("div.row > div:has(a)").map { el ->
            val p = el.selectFirst("img")!!.attr("src"); TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("div.seriedetails > h3")!!.text(), poster = if (p.startsWith("http")) p else "$baseUrl$p")
        } } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { service.getPage("$baseUrl/animes?fecha=false&genero=false&letra=false&categoria=Película&p=$page").select("div.row > div:has(a)").map { el ->
        val p = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""; Movie(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("div.seriedetails > h3")!!.text(), poster = if (p.startsWith("http")) p else "$baseUrl$p")
    } } catch (_: Exception) { emptyList() }

    override suspend fun getTvShows(page: Int): List<TvShow> = try { service.getPage("$baseUrl/animes?p=$page").select("div.row > div:has(a)").map { el ->
        val p = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""; TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("div.seriedetails > h3")!!.text(), poster = if (p.startsWith("http")) p else "$baseUrl$p")
    } } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = service.getPage(id).let { doc ->
        val p = doc.selectFirst("div.serieimgficha > img")?.attr("src")
        Movie(id = id, title = doc.selectFirst("div.row > div > h2")?.text() ?: "", poster = p?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, overview = doc.selectFirst("div.row > div > p.my-2")?.text(), genres = doc.select("div.row > div > a:has(div.btn)").map { Genre(it.attr("href").substringAfterLast("/"), it.text()) })
    }

    override suspend fun getTvShow(id: String): TvShow = service.getPage(id).let { doc ->
        val p = doc.selectFirst("div.serieimgficha > img")?.attr("src")
        TvShow(id = id, title = doc.selectFirst("div.row > div > h2")?.text() ?: "", poster = p?.let { if (it.startsWith("http")) it else "$baseUrl$it" }, overview = doc.selectFirst("div.row > div > p.my-2")?.text(), genres = doc.select("div.row > div > a:has(div.btn)").map { Genre(it.attr("href").substringAfterLast("/"), it.text()) }, seasons = listOf(Season(id = id, number = 1, title = "Episodios")))
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try { service.getPage(seasonId).select("div.row > div > div.row > div > a").map { el ->
        val t = el.text(); Episode(id = el.attr("href"), number = t.substringAfter("Capitulo ").toIntOrNull() ?: 0, title = t.replace("- ", ""), poster = el.selectFirst("img")?.attr("data-src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" })
    } } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try { service.getPage(id).select("li#play-video > a.play-video").map { Video.Server(id = String(Base64.decode(it.attr("data-player"), Base64.DEFAULT)), name = it.ownText().trim()) } } catch (_: Exception) { emptyList() }
    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override suspend fun getGenre(id: String, page: Int): Genre = try { val doc = service.getPage("$baseUrl/genero/$id?p=$page"); Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = doc.select("div.row > div:has(a)").map { el -> val p = el.selectFirst("img")!!.attr("src"); TvShow(id = el.selectFirst("a")!!.attr("href"), title = el.selectFirst("div.seriedetails > h3")!!.text(), poster = if (p.startsWith("http")) p else "$baseUrl$p") }) } catch (_: Exception) { Genre(id = id, name = id, shows = emptyList()) }
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}