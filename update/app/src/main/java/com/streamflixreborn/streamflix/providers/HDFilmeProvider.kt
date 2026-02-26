package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import retrofit2.Retrofit
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded

object HDFilmeProvider : Provider {

    override val name: String = "HDFilme"
    override val baseUrl: String = "https://hdfilme.legal"
    override val logo: String = "$baseUrl/templates/hdfilme/images/apple-touch-icon.png"
    override val language: String = "de"

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(HDFilmeService::class.java)

    private interface HDFilmeService {
        @Headers("User-Agent: $USER_AGENT") @GET(".") suspend fun getHome(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("filme1/") suspend fun getMovies(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("filme1/page/{page}/") suspend fun getMovies(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serien/") suspend fun getTvShows(): Document
        @Headers("User-Agent: $USER_AGENT") @GET("serien/page/{page}/") suspend fun getTvShows(@Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @GET suspend fun getPage(@Url url: String): Document
        @Headers("User-Agent: $USER_AGENT") @GET("{path}page/{page}/") suspend fun getPaged(@Path(value = "path", encoded = true) path: String, @Path("page") page: Int): Document
        @Headers("User-Agent: $USER_AGENT") @FormUrlEncoded @POST("index.php?do=search") suspend fun search(@Field("do") d: String = "search", @Field("subaction") s: String = "search", @Field("search_start") ss: Int, @Field("result_from") rf: Int, @Field("story") q: String): Document
        @GET @Headers("User-Agent: $USER_AGENT") suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()
        val sliderItems = doc.select("ul.glide__slides li.glide__slide").map { el ->
            async {
                val title = el.selectFirst("h3.title")?.text()?.trim() ?: return@async null
                val href = el.selectFirst("div.actions a.watchnow")?.attr("href")?.trim() ?: return@async null
                val banner = normalizeUrl(el.selectFirst("img")?.attr("data-src") ?: "")
                val tmdb = TmdbUtils.getMovie(title, language = language)
                Movie(id = href, title = title, banner = banner, rating = tmdb?.rating)
            }
        }.awaitAll().filterNotNull()
        
        if (sliderItems.isNotEmpty()) categories.add(Category(name = Category.FEATURED, list = sliderItems))
        doc.selectFirst("div.listing.grid[id=dle-content]")?.select("div.item.relative.mt-3")?.mapNotNull { parseGridItem(it) }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Filme", list = it)) }
        doc.selectFirst("section.sidebar-section:has(h3:containsOwn(neueste Filme))")?.select("div.listing > a")?.mapNotNull { parseSidebarItem(it, true) }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Neueste Filme Eingefügt", list = it)) }
        doc.selectFirst("section.sidebar-section:has(h3:containsOwn(neueste Serie))")?.select("div.listing > a")?.mapNotNull { parseSidebarItem(it, false) }?.takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Neueste Serie Eingefügt", list = it)) }
        categories
    }

    private fun parseGridItem(el: Element): Movie? {
        val t = el.selectFirst("h3.line-clamp-2")?.text()?.trim() ?: return null
        val h = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim() ?: return null
        return Movie(id = h, title = t, poster = normalizeUrl(el.selectFirst("img")?.attr("data-src") ?: ""), quality = el.selectFirst("span.absolute")?.text()?.trim())
    }

    private fun parseGridItemAsTvShow(el: Element): TvShow? {
        val t = el.selectFirst("h3.line-clamp-2")?.text()?.trim() ?: return null
        val h = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim() ?: return null
        return TvShow(id = h, title = t, poster = normalizeUrl(el.selectFirst("img")?.attr("data-src") ?: ""), quality = el.selectFirst("span.absolute")?.text()?.trim())
    }

    private fun parseSidebarItem(el: Element, isMovie: Boolean): Show? {
        val h = el.attr("href").trim(); if (h.isBlank()) return null
        val t = el.selectFirst("figcaption.hidden")?.text()?.trim() ?: el.selectFirst("h4.movie-title")?.text()?.trim() ?: return null
        val p = normalizeUrl(el.selectFirst("img")?.attr("data-src") ?: "")
        return if (isMovie) Movie(id = h, title = t, poster = p) else TvShow(id = h, title = t, poster = p)
    }

    private fun normalizeUrl(u: String): String = when { u.isBlank() -> ""; u.startsWith("http") -> u; u.startsWith("//") -> "https:$u"; else -> baseUrl + (if (u.startsWith("/")) u else "/$u") }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return service.getHome().selectFirst("div.dropdown-hover:has(span:containsOwn(Genre))")?.select("div.dropdown-content a[href]")?.mapNotNull { a ->
                val h = a.attr("href").trim(); if (h.isBlank() || a.text().isBlank()) null else Genre(id = h, name = a.text().trim())
            } ?: emptyList()
        }
        return coroutineScope {
            service.search(ss = page, rf = (page - 1) * 25 + 1, q = query).select("div.listing.grid[id=dle-content] div.item.relative.mt-3").map { el ->
                async {
                    val href = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim() ?: return@async null
                    if (service.getPage(href).select("div#se-accordion").isNotEmpty()) parseGridItemAsTvShow(el) else parseGridItem(el)
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { (if (page > 1) service.getMovies(page) else service.getMovies()).select("div.listing.grid[id=dle-content] div.item.relative.mt-3").mapNotNull { parseGridItem(it) } } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { (if (page > 1) service.getTvShows(page) else service.getTvShows()).select("div.listing.grid[id=dle-content] div.item.relative.mt-3").mapNotNull { parseGridItemAsTvShow(it) } } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val doc = service.getPage(id); val tRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = tRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()
        val tmdbDef = async { TmdbUtils.getMovie(title, language = language) }; val tmdb = tmdbDef.await()
        val meta = doc.selectFirst("div.border-b.border-gray-700.font-extralight")
        Movie(
            id = id, title = title, poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: ""), banner = tmdb?.banner,
            trailer = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.replace("/embed/", "/watch?v=")?.replace("?autoplay=1", ""),
            rating = tmdb?.rating ?: meta?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull(),
            overview = tmdb?.overview ?: doc.selectFirst("div.font-extralight.prose")?.select("p")?.firstOrNull()?.text()?.substringBefore("Referenzen von")?.trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: meta?.select("span")?.find { it.text().trim().matches(Regex("^\\d{4}$")) }?.text()?.trim(),
            runtime = tmdb?.runtime ?: meta?.select("span")?.find { it.text().contains("min") }?.text()?.replace("min", "")?.trim()?.toIntOrNull(),
            quality = meta?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }?.lastOrNull()?.text()?.trim()?.takeUnless { it.matches(Regex("^\\d{4}$")) || it.contains("min") },
            genres = tmdb?.genres ?: meta?.selectFirst("span")?.select("a")?.map { Genre(it.text().trim(), it.text().trim()) } ?: emptyList(),
            cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a").mapNotNull { a -> val n = a.text().trim(); if (n.isBlank() || n == "N/A") null else People(id = a.attr("href"), name = n, image = tmdb?.cast?.find { it.name.equals(n, true) }?.image) }
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val doc = service.getPage(id); val tRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = tRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()
        val tmdbDef = async { TmdbUtils.getTvShow(title, language = language) }; val tmdb = tmdbDef.await()
        val meta = doc.selectFirst("div.border-b.border-gray-700.font-extralight")
        val seasons = doc.select("div#se-accordion div.su-spoiler").mapNotNull { s ->
            val sTitle = s.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@mapNotNull null
            val sNum = Regex("Staffel\\s+(\\d+)").find(sTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val eps = s.selectFirst("div.su-spoiler-content")?.html()?.split("<br>")?.mapNotNull { l -> Regex("""(\d+)x(\d+)""").find(l)?.groupValues?.get(2)?.toIntOrNull()?.let { n -> Episode(id = "$id#s${sNum}e$n", number = n, title = "Episode $n") } } ?: emptyList()
            Season(id = "$id#season-$sNum", number = sNum, poster = tmdb?.seasons?.find { it.number == sNum }?.poster, episodes = eps.sortedBy { it.number })
        }
        TvShow(
            id = id, title = title, poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: ""), banner = tmdb?.banner,
            trailer = tmdb?.trailer ?: doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.replace("/embed/", "/watch?v="),
            rating = tmdb?.rating ?: meta?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull(),
            overview = tmdb?.overview ?: doc.selectFirst("div.font-extralight.prose")?.select("p")?.firstOrNull()?.text()?.substringBefore("Referenzen von")?.trim(),
            released = tmdb?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: meta?.select("span")?.find { it.text().trim().matches(Regex("^\\d{4}$")) }?.text()?.trim(),
            runtime = tmdb?.runtime ?: meta?.select("span")?.find { it.text().contains("min") }?.text()?.replace("min", "")?.trim()?.toIntOrNull(),
            quality = meta?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }?.lastOrNull()?.text()?.trim()?.takeUnless { it.matches(Regex("^\\d{4}$")) || it.contains("min") },
            genres = tmdb?.genres ?: meta?.selectFirst("span")?.select("a")?.map { Genre(it.text().trim(), it.text().trim()) } ?: emptyList(),
            cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a").mapNotNull { a -> val n = a.text().trim(); if (n.isBlank() || n == "N/A") null else People(id = a.attr("href"), name = n, image = tmdb?.cast?.find { it.name.equals(n, true) }?.image) },
            seasons = seasons, imdbId = tmdb?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val sId = seasonId.substringBefore("#"); val sNum = seasonId.substringAfter("#season-").toIntOrNull() ?: return@coroutineScope emptyList()
        val doc = service.getPage(sId); val title = doc.selectFirst("h1.font-bold")?.text()?.trim()?.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "") ?: ""
        val tmdbDef = async { TmdbUtils.getTvShow(title, language = language)?.let { TmdbUtils.getEpisodesBySeason(it.id, sNum, language = language) } ?: emptyList() }
        val tmdbEps = tmdbDef.await()
        doc.select("div#se-accordion div.su-spoiler").find { Regex("Staffel\\s+$sNum").containsMatchIn(it.selectFirst("div.su-spoiler-title")?.text() ?: "") }
            ?.selectFirst("div.su-spoiler-content")?.html()?.split("<br>")?.mapNotNull { l ->
                val epNum = Regex("""(\d+)x(\d+)""").find(l)?.groupValues?.get(2)?.toIntOrNull() ?: return@mapNotNull null
                val tE = tmdbEps.find { it.number == epNum }
                Episode(id = "$sId#s${sNum}e$epNum", number = epNum, title = tE?.title ?: "Episode $epNum", poster = tE?.poster, overview = tE?.overview)
            }?.sortedBy { it.number } ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = "", shows = (if (page <= 1) service.getPage(id) else service.getPaged(id.substringAfter(baseUrl).trimStart('/'), page)).select("div.listing.grid[id=dle-content] div.item.relative.mt-3").mapNotNull { parseGridItem(it) })

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPage(id); if (page > 1) return People(id = id, name = doc.selectFirst("h1")?.text() ?: "")
        return People(id = id, name = doc.selectFirst("h1")?.text() ?: "", filmography = doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3").mapNotNull { parseGridItem(it) })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (videoType is Video.Type.Episode) {
            val sId = id.substringBefore('#'); val p = id.substringAfter('#'); val sN = p.substringAfter('s').substringBefore('e').toIntOrNull(); val eN = p.substringAfter('e').toIntOrNull()
            if (sN == null || eN == null) return emptyList()
            return service.getPage(sId).select("div#se-accordion div.su-spoiler").find { Regex("Staffel\\s+$sN").containsMatchIn(it.selectFirst("div.su-spoiler-title")?.text() ?: "") }
                ?.selectFirst("div.su-spoiler-content")?.html()?.split("<br>")?.find { Regex("${sN}x${eN}").containsMatchIn(it) }?.let { l ->
                    Jsoup.parse(l).select("a[href]").filterNot { it.attr("href").contains("/engine/player.php") || it.text().contains("4K", true) }.map { a -> val u = normalizeUrl(a.attr("href")); Video.Server(id = u, name = a.text().trim().ifBlank { "Server" }, src = u) }
                } ?: emptyList()
        }
        val embed = normalizeUrl(service.getPage(id).selectFirst("iframe[src*='meinecloud.click']")?.attr("src") ?: throw Exception())
        return service.getPage(embed).select("ul._player-mirrors li[data-link]").filterNot { it.text().contains("4K", true) }.mapNotNull { li ->
            val dl = li.attr("data-link").trim(); if (dl.isBlank()) null else { val n = normalizeUrl(dl); Video.Server(id = n, name = li.ownText().ifBlank { li.text() }.trim().ifBlank { "Server" }, src = n) }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)
}