package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.Locale
import androidx.core.net.toUri

object CuevanaDosProvider : Provider {

    private const val URL = "https://www.cuevana2espanol.net/"
    override val baseUrl = URL
    override val name = "Cuevana2 Español"
    override val logo = "https://www.cuevana2espanol.net/_next/image?url=%2F_next%2Fstatic%2Fmedia%2Flogo.33e4f182.png&w=640&q=7"
    override val language = "es"

    private val service = CuevanaDosService.build()

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome(); val categories = mutableListOf<Category>()
        val rows = doc.select("div.row.row-cols-xl-5.row-cols-lg-4.row-cols-3")
        val names = listOf("Películas destacadas", "Series destacadas", "Últimas películas", "Últimas series", "Últimos episodios")
        
        rows.forEachIndexed { i, row ->
            if (i < names.size) {
                val list = row.select("div.col").mapNotNull { col ->
                    val a = col.selectFirst("a") ?: return@mapNotNull null; val t = col.selectFirst("h3")?.text() ?: ""; val p = col.selectFirst("img")?.attr("src") ?: ""
                    val id = a.attr("href").substringAfterLast("/"); val poster = "https://www.cuevana2espanol.net$p"
                    if (i % 2 == 1 || i == 4) TvShow(id = id, title = t, poster = poster) else Movie(id = id, title = t, poster = poster)
                }
                if (list.isNotEmpty()) categories.add(Category(name = names[i], list = list))
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return listOf("accion", "animacion", "crimen", "familia", "misterio", "suspenso", "aventura", "ciencia-ficcion", "drama", "fantasia", "romance", "terror").map { Genre(it, it.replaceFirstChar { c -> c.uppercase() }) }
        if (page > 1) return emptyList()
        return service.search(query).select("div.col article:not(.MovieSidebarItem_item__U15hi):not(.SerieSidebarItem_item__Y_r4w)").mapNotNull { el ->
            val a = el.selectFirst("a"); val h = a?.attr("href") ?: return@mapNotNull null; val t = el.selectFirst("h3")?.text()?.trim() ?: ""
            val img = a.selectFirst("img")?.attr("src")?.let { it.toUri().getQueryParameter("url") ?: it } ?: ""
            val id = h.removePrefix("/movies/").removePrefix("/series/"); val y = el.selectFirst("span")?.text() ?: ""
            if (h.startsWith("/movies/")) Movie(id = id, title = t, released = y, poster = img) else TvShow(id = id, title = t, released = y, poster = img)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = service.getMovies(page).select("div.col article").mapNotNull { el ->
        val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null; val p = el.selectFirst("img")?.attr("src") ?: ""
        Movie(id = h.substringAfterLast("/"), title = el.selectFirst("h3")?.text() ?: "", poster = if (p.startsWith("http")) p else "https://www.cuevana2espanol.net$p", released = el.selectFirst("span")?.text())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = service.getTvShows(page).select("div.col article").mapNotNull { el ->
        val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null; val p = el.selectFirst("img")?.attr("src") ?: ""
        TvShow(id = h.substringAfterLast("/"), title = el.selectFirst("h3")?.text() ?: "", poster = if (p.startsWith("http")) p else "https://www.cuevana2espanol.net$p", released = el.selectFirst("span")?.text())
    }

    override suspend fun getMovie(id: String): Movie = service.getMovie(id).let { doc ->
        val ext = doc.select("div.movieInfo_extra__bP44U span"); val p = doc.selectFirst("div.movieInfo_image__LJrqk img")?.attr("src")?.let { "https://www.cuevana2espanol.net$it" }
        Movie(id = id, title = doc.selectFirst("h1")?.text() ?: "", overview = doc.select("div.movieInfo_data__HL5zl > div.row").lastOrNull()?.text(), released = doc.selectFirst("div.movieInfo_extra__bP44U span")?.text(), runtime = ext.find { it.text().contains("Min.") }?.text()?.substringBefore(" Min.")?.trim()?.toIntOrNull(), rating = ext.find { it.text().contains("⭐") }?.text()?.substringAfter("⭐")?.substringBefore("/")?.trim()?.toDoubleOrNull(), poster = p,
            genres = doc.select("tr:contains(Géneros) td a").map { Genre(it.attr("href").substringAfterLast("/"), it.text()) },
            cast = doc.select("tr:contains(Actores) td").text().split(",").map { People("", it.trim().replace("Actores", ""), null) })
    }

    override suspend fun getTvShow(id: String): TvShow = service.getTvShow(id).let { doc ->
        val json = JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").getJSONObject("props").getJSONObject("pageProps").getJSONObject("post")
        val sJson = json.optJSONArray("seasons") ?: JSONArray(); val seasons = mutableListOf<Season>()
        for (i in 0 until sJson.length()) { val obj = sJson.getJSONObject(i); val num = obj.getInt("number"); seasons.add(Season("${obj.getJSONArray("episodes").optJSONObject(0)?.optJSONObject("slug")?.getString("name") ?: id}/$num", num, "Temporada $num")) }
        TvShow(id = id, title = json.getJSONObject("titles").getString("name"), overview = json.optString("overview"), released = json.optString("releaseDate", "").take(10), rating = json.optJSONObject("rate")?.optDouble("average", 0.0) ?: 0.0, poster = json.optJSONObject("images")?.optString("poster")?.let { "https://image.tmdb.org/t/p/original$it" }, seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (slug, sNum) = seasonId.split("/"); val sNumInt = sNum.toIntOrNull() ?: 1
        val json = JSONObject(service.getTvShow(slug).selectFirst("script#__NEXT_DATA__")?.data() ?: "").getJSONObject("props").getJSONObject("pageProps").getJSONObject("post")
        val sJson = json.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until sJson.length()) {
            val obj = sJson.getJSONObject(i); if (obj.optInt("number") != sNumInt) continue
            val eJson = obj.optJSONArray("episodes") ?: return emptyList()
            return (0 until eJson.length()).mapNotNull { idx -> val ep = eJson.getJSONObject(idx); val sObj = ep.optJSONObject("slug") ?: return@mapNotNull null; Episode(id = "${sObj.optString("name")}/${sObj.optString("season")}/${sObj.optString("episode")}", number = ep.optInt("number"), title = ep.optString("title"), poster = ep.optString("image"), released = ep.optString("releaseDate").take(10)) }
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, id.replaceFirstChar { it.uppercase() }, service.getGenre(id, page).select("div.col article:not(.MovieSidebarItem_item__U15hi):not(.SerieSidebarItem_item__Y_r4w)").mapNotNull { el ->
        val h = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null; val t = el.selectFirst("h3")?.text()?.trim() ?: ""; val img = el.selectFirst("img")?.attr("src")?.let { it.toUri().getQueryParameter("url") ?: it } ?: ""
        val sId = h.removePrefix("/movies/").removePrefix("/series/"); val y = el.selectFirst("span")?.text() ?: ""
        if (h.startsWith("/movies/")) Movie(id = sId, title = t, released = y, poster = img) else if (h.startsWith("/series/")) TvShow(id = sId, title = t, released = y, poster = img) else null
    })

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = if (videoType is Video.Type.Movie) service.getMovie(id) else { val p = id.split("/"); service.getEpisode(p[0], p[1], p[2]) }
        val pJson = JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").getJSONObject("props").getJSONObject("pageProps").let { if (videoType is Video.Type.Episode) it.getJSONObject("episode") else it.getJSONObject("post") }.getJSONObject("players")
        val res = mutableListOf<Video.Server>()
        for (l in listOf("latino", "spanish", "english")) if (pJson.has(l)) { val arr = pJson.getJSONArray(l); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val embed = obj.getString("result"); val real = try { Jsoup.connect(embed).header("Referer", URL).get().select("script").find { it.data().contains("var url =") }?.data()?.let { Regex("var url\\s*=\\s*['\"](https?://[^'\"]+)['\"]").find(it)?.groupValues?.get(1) } } catch(_:Exception) { null }; res.add(Video.Server(id = real ?: embed, name = "${obj.getString("cyberlocker")} (${l.replaceFirstChar { it.titlecase(Locale.ROOT) }})", src = real ?: embed)) } }
        return res
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = (service.getRedirectLink(server.src).raw() as okhttp3.Response).request.url.let { if (server.name.startsWith("VOE")) "https://voe.sx/e/${it.encodedPath.trimStart('/')}?" else it.toString() }
        return Extractor.extract(url, server)
    }

    private interface CuevanaDosService {
        companion object {
            fun build(): CuevanaDosService = Retrofit.Builder().baseUrl(URL).addConverterFactory(JsoupConverterFactory.create()).addConverterFactory(GsonConverterFactory.create()).client(NetworkClient.default).build().create(CuevanaDosService::class.java)
        }
        @GET(".") suspend fun getHome(): Document
        @GET("/search") suspend fun search(@Query("q") q: String): Document
        @GET("movies/{n}") suspend fun getMovie(@Path("n") id: String): Document
        @GET @Headers("User-Agent: Mozilla/5.0") suspend fun getRedirectLink(@Url u: String): Response<ResponseBody>
        @GET("archives/movies/page/{p}") suspend fun getMovies(@Path("p") p: Int): Document
        @GET("archives/series/page/{p}") suspend fun getTvShows(@Path("p") p: Int): Document
        @GET("series/{n}") suspend fun getTvShow(@Path("n") id: String): Document
        @GET("series/{n}/seasons/{s}/episodes/{e}") suspend fun getEpisode(@Path("n") n: String, @Path("s") s: String, @Path("e") e: String): Document
        @GET("/genres/{g}/page/{p}") suspend fun getGenre(@Path("g") g: String, @Path("p") p: Int): Document
    }
}