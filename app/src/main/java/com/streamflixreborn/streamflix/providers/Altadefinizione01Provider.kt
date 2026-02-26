package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import java.net.URLEncoder
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import retrofit2.http.Path
import retrofit2.http.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object Altadefinizione01Provider : Provider {

    override val name: String = "Altadefinizione01"
    override val baseUrl: String = "https://altadefinizione-01.homes"
    override val logo: String get() = "$baseUrl/templates/Darktemplate_pagespeed/images/logo.png"
    override val language: String = "it"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(Altadefinizione01Service::class.java)

    private interface Altadefinizione01Service {
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun getHome(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("cinema/") suspend fun getCinema(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("cinema/page/{page}/") suspend fun getCinema(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serie-tv/") suspend fun getSerieTv(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serie-tv/page/{page}/") suspend fun getSerieTv(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET("index.php") suspend fun searchFirst(@Query("do") d: String = "search", @Query("subaction") s: String = "search", @Query("titleonly") t: Int = 3, @Query(value = "story", encoded = true) q: String, @Query("full_search") f: Int = 0): Document
        @Headers("User-Agent: $USER_AGENT") @GET("index.php") suspend fun searchPaged(@Query("do") d: String = "search", @Query("subaction") s: String = "search", @Query("titleonly") t: Int = 3, @Query("full_search") f: Int = 0, @Query("search_start") ss: Int, @Query("result_from") rf: Int, @Query(value = "story", encoded = true) q: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome(); val categories = mutableListOf<Category>()
        doc.selectFirst("div.slider")?.let { s ->
            val title = s.selectFirst(".slider-strip b")?.text()?.trim() ?: return@let
            val items = s.select("#slider .boxgrid.caption, .boxgrid.caption").mapNotNull { parseGridItem(it) }
            if (items.isNotEmpty()) categories.add(Category(name = title, list = items))
        }
        doc.selectFirst("div.son_eklenen_head")?.let { h ->
            val items = (h.nextElementSibling()?.takeIf { it.id() == "son_eklenen_kapsul" } ?: h.parent()?.selectFirst("#son_eklenen_kapsul"))?.select(".boxgrid.caption")?.mapNotNull { parseGridItem(it) } ?: emptyList()
            if (items.isNotEmpty()) categories.add(Category(name = "Ultimi inseriti", list = items))
        }
        return categories
    }

    private fun parseGridItem(el: Element): AppAdapter.Item? {
        val anchor = el.selectFirst(".cover.boxcaption h2 a, h3 a, .boxcaption h2 a") ?: return null
        val title = anchor.text().trim(); val href = anchor.attr("href").trim()
        val poster = normalizeUrl(el.selectFirst("a > img")?.attr("data-src") ?: "")
        val isTv = el.selectFirst(".se_num") != null || el.selectFirst(".ml-cat a[href*='/serie-tv/']") != null
        return if (isTv) TvShow(id = href, title = title, poster = poster) else Movie(id = href, title = title, poster = poster)
    }

    private fun normalizeUrl(url: String): String = when { url.startsWith("http") -> url; url.startsWith("//") -> "https:$url"; url.startsWith("/") -> baseUrl.trimEnd('/') + url; else -> url }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return try {
                service.getHome().select(".widget-title:matches(^Categorie in Altadefinizione$)").firstOrNull()?.parent()?.select("#wtab1 .kategori_list li > a[href]")?.mapNotNull { a ->
                    val h = a.attr("href").trim(); if (h.isBlank() || a.text().isBlank()) return@mapNotNull null
                    Genre(id = if (h.startsWith("http")) h else baseUrl + "/" + h.removePrefix("/").removePrefix(baseUrl.removeSuffix("/")), name = a.text().trim())
                }?.sortedBy { it.name } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        val encoded = URLEncoder.encode(query, "UTF-8"); val first = service.searchFirst(q = encoded)
        if (page > 1 && first.selectFirst("div.page_nav") == null) return emptyList()
        val doc = if (page <= 1) first else service.searchPaged(ss = page, rf = (page - 1) * 50 + 1, q = encoded)
        return doc.select("#dle-content .boxgrid.caption").mapNotNull { parseGridItem(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = (if (page > 1) service.getCinema(page) else service.getCinema()).select("#dle-content .boxgrid.caption").mapNotNull { parseGridItem(it) as? Movie }
    override suspend fun getTvShows(page: Int): List<TvShow> = (if (page > 1) service.getSerieTv(page) else service.getSerieTv()).select("#dle-content .boxgrid.caption").mapNotNull { parseGridItem(it) as? TvShow }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: doc.selectFirst("h1,h2,title")?.text()?.trim() ?: ""
        val tmdbDeferred = async { TmdbUtils.getMovie(title, language = language) }; val tmdb = tmdbDeferred.await()
        return@coroutineScope Movie(
            id = id, title = title, overview = tmdb?.overview ?: doc.selectFirst(".sbox .entry-content p")?.ownText()?.trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" } ?: doc.select("p.meta_dd:has(b.icon-clock)").text().replace(Regex("[^0-9]"), "").takeIf { it.isNotBlank() },
            runtime = tmdb?.runtime ?: doc.select("p.meta_dd:has(b.icon-time)").text().replace(Regex("[^0-9]"), "").toIntOrNull(),
            trailer = tmdb?.trailer ?: doc.selectFirst(".btn_trailer a[href]")?.attr("href")?.takeIf { it.contains("youtube", true) },
            rating = tmdb?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            poster = tmdb?.poster ?: normalizeUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: ""),
            quality = doc.select("p.meta_dd:has(b.icon-playback-play)").text().replace("Qualita", "").trim().takeIf { it.isNotBlank() },
            banner = tmdb?.banner, imdbId = tmdb?.imdbId,
            genres = tmdb?.genres ?: doc.select("p.meta_dd b[title=Genere]").firstOrNull()?.parent()?.select("a")?.map { Genre(it.attr("href"), it.text().trim()) } ?: emptyList(),
            cast = doc.select("p.meta_dd.limpiar:has(b.icon-male) a[href]").map { el -> People(id = el.attr("href").trim(), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) }
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getPage(id); val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: doc.selectFirst("h1,h2,title")?.text()?.trim() ?: ""
        val tmdbDeferred = async { TmdbUtils.getTvShow(title, language = language) }; val tmdb = tmdbDeferred.await()
        val seasons = doc.select("#tt_holder .tt_season ul li a[data-toggle=tab]").map { a ->
            val sId = a.attr("href").removePrefix("#"); val sNum = a.text().trim().toIntOrNull() ?: 0
            val eps = doc.selectFirst("#${sId}")?.select("ul > li > a[allowfullscreen][data-link]")?.map { ep ->
                val epNum = ep.attr("data-num").substringAfter('x').toIntOrNull() ?: ep.text().trim().toIntOrNull() ?: 0
                val parts = ep.attr("data-title").trim().let { if (it.contains(":")) it.substringBefore(":").trim() to it.substringAfter(":").trim() else it to null }
                Episode(id = "$id#s${sNum}e$epNum", number = epNum, title = parts.first, overview = parts.second)
            } ?: emptyList()
            Season(id = "$id#season-$sNum", number = sNum, episodes = eps, poster = tmdb?.seasons?.find { it.number == sNum }?.poster)
        }
        return@coroutineScope TvShow(
            id = id, title = title, overview = tmdb?.overview ?: doc.selectFirst(".sbox .entry-content p")?.ownText()?.trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = tmdb?.runtime, trailer = tmdb?.trailer ?: doc.selectFirst(".btn_trailer a[href]")?.attr("href")?.takeIf { it.contains("youtube", true) },
            rating = tmdb?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            poster = tmdb?.poster ?: normalizeUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: ""), banner = tmdb?.banner, imdbId = tmdb?.imdbId, seasons = seasons,
            genres = tmdb?.genres ?: doc.select("p.meta_dd:has(b.icon-medal) a[href]").map { Genre(it.attr("href"), it.text().trim()) } ?: emptyList(),
            cast = doc.select("p.meta_dd.limpiar:has(b.icon-male) a[href]").map { el -> People(id = el.attr("href").trim(), name = el.text().trim(), image = tmdb?.cast?.find { it.name.equals(el.text().trim(), true) }?.image) }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doc = service.getPage(seasonId.substringBefore("#")); val sNum = seasonId.substringAfter("#season-").toIntOrNull() ?: 0
        return doc.selectFirst("#season-$sNum")?.select("ul > li > a[allowfullscreen][data-link]")?.map { ep ->
            val epNum = ep.attr("data-num").substringAfter('x').toIntOrNull() ?: ep.text().trim().toIntOrNull() ?: 0
            val parts = ep.attr("data-title").trim().let { if (it.contains(":")) it.substringBefore(":").trim() to it.substringAfter(":").trim() else it to null }
            val poster = ep.parent()?.select("a[data-link]")?.firstOrNull { it.text().contains("Dropload", true) }?.attr("data-link")?.let { "https://img.dropcdn.io/${it.substringAfter("/e/")}.jpg" }
            Episode(id = "${seasonId.substringBefore("#")}#s${sNum}e$epNum", number = epNum, title = parts.first, poster = poster, overview = parts.second)
        } ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = "", shows = service.getPage(if (page <= 1) id else id.trimEnd('/') + "/page/$page/").select("#dle-content .boxgrid.caption").mapNotNull { parseGridItem(it) as? com.streamflixreborn.streamflix.models.Show })

    override suspend fun getPeople(id: String, page: Int): People {
        val base = service.getPage(id); if (page > 1 && base.selectFirst("div.page_nav") == null) return People(id = id, name = "", filmography = emptyList())
        val doc = if (page <= 1) base else service.getPage("${id.trimEnd('/').let { if (it.contains("/xfsearch/attori/")) it.replace("/xfsearch/attori/", "/find/") else it }}/page/$page/")
        return People(id = id, name = "", filmography = doc.select("#dle-content .boxgrid.caption").mapNotNull { parseGridItem(it) as? com.streamflixreborn.streamflix.models.Show })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = if (videoType is Video.Type.Episode) {
        try {
            val sNum = id.substringAfter('#').substringAfter('s').substringBefore('e').toIntOrNull() ?: 0; val epNum = id.substringAfter('e').toIntOrNull() ?: 0
            service.getPage(id.substringBefore('#')).selectFirst("#season-$sNum")?.select("ul > li > a[allowfullscreen][data-link]")?.firstOrNull { (it.attr("data-num").substringAfter('x').toIntOrNull() ?: it.text().trim().toIntOrNull() ?: -1) == epNum }?.parent()?.select(".mirrors a[data-link]")
                ?.filterNot { it.text().contains("4K", true) }?.mapNotNull { m -> val l = m.attr("data-link").trim(); if (l.isBlank()) null else Video.Server(id = normalizeUrl(l), name = m.text().trim().ifBlank { "Server" }, src = normalizeUrl(l)) } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    } else {
        try {
            val embedUrl = normalizeUrl(service.getPage(id).selectFirst("iframe[src*='mostraguarda.stream']")?.attr("src") ?: throw Exception())
            service.getPage(embedUrl).select("ul._player-mirrors li[data-link]").filterNot { it.text().contains("4K", true) }.mapNotNull { li ->
                val dl = li.attr("data-link").trim(); if (dl.isBlank()) null else { val norm = normalizeUrl(dl); Video.Server(id = norm, name = li.ownText().ifBlank { li.text() }.trim().ifBlank { "Server" }, src = norm) }
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)
}