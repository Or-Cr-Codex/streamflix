package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.VixcloudExtractor
import com.streamflixreborn.streamflix.utils.NetworkClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Body
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object AnimeUnityProvider : Provider {
    override val name = "AnimeUnity"
    override val baseUrl = "https://www.animeunity.so"
    override val logo: String get() = "$baseUrl/images/scritta2.png"
    override val language = "it"
    
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun getImageUrl(imageurl: String): String {
        return AnimeUnityService.getImageUrl(imageurl, baseUrl)
    }
    
    private interface KitsuService {
        @POST("graphql")
        suspend fun getEpisodes(@Body body: RequestBody): ResponseBody
    }
    
    private val kitsuService by lazy {
        Retrofit.Builder()
            .baseUrl("https://kitsu.io/api/")
            .client(NetworkClient.default)
            .build()
            .create(KitsuService::class.java)
    }
    
    private suspend fun fetchEpisodeThumbnails(anilistId: Int): Map<Int, String> {
        return try {
            val query = """
                query {
                  lookupMapping(externalId: $anilistId, externalSite: ANILIST_ANIME) {
                    __typename
                    ... on Anime {
                      id
                      episodes(first: 2000) {
                        nodes {
                          number
                          thumbnail {
                            original {
                              url
                            }
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
            
            val requestBody = JSONObject().apply {
                put("query", query)
            }.toString().toRequestBody("application/json".toMediaType())
            
            val response = kitsuService.getEpisodes(requestBody)
            val jsonResponse = JSONObject(response.string())
            
            val episodes = jsonResponse
                .optJSONObject("data")
                ?.optJSONObject("lookupMapping")
                ?.optJSONObject("episodes")
                ?.optJSONArray("nodes")
                ?: return emptyMap()
            
            val thumbnails = mutableMapOf<Int, String>()
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                val number = episode.optInt("number", 0)
                val thumbnail = episode.optJSONObject("thumbnail")?.optJSONObject("original")?.optString("url", "") ?: ""
                if (number > 0 && thumbnail.isNotEmpty()) thumbnails[number] = thumbnail
            }
            thumbnails
        } catch (e: Exception) { emptyMap() }
    }

    private var savedCookieHeader = ""
    private var savedCsrfToken = ""

    private interface AnimeUnityService {
        @Headers("User-Agent: $USER_AGENT")
        @GET(".")
        suspend fun getHome(): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("archivio")
        suspend fun getArchivio(): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("anime/{id}")
        suspend fun getAnime(@Path("id") id: String): Document

        @Headers("User-Agent: $USER_AGENT")
        @GET("embed-url/{episodeId}")
        suspend fun getEmbedUrl(@Path("episodeId") episodeId: String): Document

        @Headers("User-Agent: $USER_AGENT", "Content-Type: application/json")
        @POST("archivio/get-animes")
        suspend fun getAnimes(@Body body: RequestBody): ResponseBody

        @Headers("User-Agent: $USER_AGENT")
        @GET("info_api/{animeId}/1")
        suspend fun getEpisodesByRange(
            @Path("animeId") animeId: String,
            @Query("start_range") startRange: Int,
            @Query("end_range") endRange: Int
        ): ResponseBody

        companion object {
            fun getImageUrl(imageurl: String, baseUrl: String): String {
                if (imageurl.isEmpty()) return ""
                val parts = imageurl.split(Regex("[\\\\/]"))
                val filename = parts.lastOrNull() ?: ""
                val domain = baseUrl.replace("https://", "").replace("www.", "")
                return "https://img.$domain/anime/$filename"
            }
            
            fun build(baseUrl: String): AnimeUnityService {
                val client = NetworkClient.default.newBuilder()
                    .addInterceptor { chain ->
                        val originalRequest = chain.request()
                        val requestBuilder = originalRequest.newBuilder()
                        
                        if (originalRequest.url.toString().endsWith("/archivio/get-animes")) {
                            requestBuilder.header("Cookie", savedCookieHeader)
                            requestBuilder.header("X-CSRF-TOKEN", savedCsrfToken)
                        }
                        
                        val response = chain.proceed(requestBuilder.build())
                        
                        if (originalRequest.url.toString().endsWith("/archivio")) {
                            val cookies = response.headers("Set-Cookie")
                            savedCookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }
                            val responseBody = response.body
                            if (responseBody != null) {
                                val html = responseBody.string()
                                val csrfMeta = Jsoup.parse(html).selectFirst("meta[name=csrf-token]")
                                savedCsrfToken = csrfMeta?.attr("content") ?: ""
                                val newResponseBody = html.toResponseBody(responseBody.contentType())
                                return@addInterceptor response.newBuilder().body(newResponseBody).build()
                            }
                        }
                        response
                    }.build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(AnimeUnityService::class.java)
            }
        }
    }

    private val service = AnimeUnityService.build(baseUrl)

    override suspend fun getHome(): List<Category> = coroutineScope {
        try {
            val document = service.getHome()
            
            val latestEpisodes = async { parseLatestEpisodes(document) }
            val latestAdditions = async { parseLatestAdditions(document) }
            val featuredAnime = async { parseFeaturedAnime(document) }
            
            val categories = mutableListOf<Category>()
            
            latestEpisodes.await().takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Ultimi Episodi", list = it)) }
            latestAdditions.await().takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = "Ultime Aggiunte", list = it)) }
            featuredAnime.await().takeIf { it.isNotEmpty() }?.let { categories.add(Category(name = Category.FEATURED, list = it)) }
            
            categories
        } catch (e: Exception) { emptyList() }
    }
    
    private fun parseLatestEpisodes(document: Element): List<TvShow> {
        val tvShows = mutableListOf<TvShow>()
        val seenAnimeIds = mutableSetOf<String>()
        try {
            val layoutItems = document.select("layout-items[items-json]").firstOrNull() ?: return emptyList()
            val dataArray = JSONObject(layoutItems.attr("items-json")).getJSONArray("data")

            for (i in 0 until dataArray.length()) {
                val animeData = dataArray.getJSONObject(i).getJSONObject("anime")
                val animeId = animeData.getString("id")
                if (seenAnimeIds.add(animeId)) {
                    val animeTitle = if (animeData.has("title_eng") && !animeData.isNull("title_eng")) animeData.getString("title_eng") else animeData.getString("title")
                    tvShows.add(TvShow(id = "$animeId-${animeData.getString("slug")}", title = animeTitle, poster = getImageUrl(animeData.getString("imageurl"))))
                }
            }
        } catch (_: Exception) {}
        return tvShows
    }
    
    private fun parseLatestAdditions(document: Element): List<AppAdapter.Item> {
        val items = mutableListOf<AppAdapter.Item>()
        try {
            document.select("div.home-sidebar div.latest-anime-container").forEach { container ->
                val linkElement = container.selectFirst("a.unstile-a") ?: return@forEach
                val animeId = linkElement.attr("href").substringAfterLast("/")
                val animeTitle = container.selectFirst("strong.latest-anime-title")?.text()?.trim() ?: return@forEach
                val poster = getImageUrl(container.selectFirst("img")?.attr("src") ?: "")
                val isMovie = container.selectFirst("div.latest-anime-info")?.text()?.contains("Movie", ignoreCase = true) == true
                
                if (isMovie) items.add(Movie(id = animeId, title = animeTitle, poster = poster))
                else items.add(TvShow(id = animeId, title = animeTitle, poster = poster))
            }
        } catch (_: Exception) {}
        return items
    }
    
    private fun parseFeaturedAnime(document: Element): List<AppAdapter.Item> {
        val items = mutableListOf<AppAdapter.Item>()
        try {
            val carouselTag = document.selectFirst("the-carousel[animes]") ?: return emptyList()
            val jsonArray = org.json.JSONArray(carouselTag.attr("animes").replace("&quot;", "\""))

            for (i in 0 until jsonArray.length()) {
                val animeData = jsonArray.getJSONObject(i)
                val Title = if (animeData.optString("title_eng").isNotEmpty()) animeData.getString("title_eng") else animeData.optString("title")
                val isMovie = animeData.optString("type") == "Movie"
                val id = "${animeData.getInt("id")}-${animeData.getString("slug")}"
                
                val item = if (isMovie) Movie(id = id, title = Title, banner = getImageUrl(animeData.optString("imageurl_cover")), overview = animeData.optString("plot"), rating = animeData.optString("score").toDoubleOrNull(), released = animeData.optString("date"))
                else TvShow(id = id, title = Title, banner = getImageUrl(animeData.optString("imageurl_cover")), overview = animeData.optString("plot"), rating = animeData.optString("score").toDoubleOrNull(), released = animeData.optString("date"))
                items.add(item)
            }
        } catch (_: Exception) {}
        return items
    }

    private fun parseGenresFromJson(allGenresData: String): List<Genre> {
        if (allGenresData.isEmpty()) return emptyList()
        val jsonArray = org.json.JSONArray(allGenresData.replace("&quot;", "\""))
        return (0 until jsonArray.length()).map { i ->
            val genreObj = jsonArray.getJSONObject(i)
            Genre(id = genreObj.getInt("id").toString(), name = genreObj.getString("name"))
        }
    }

    private fun parseAnimeFromJson(records: org.json.JSONArray): List<AppAdapter.Item> {
        val results = mutableListOf<AppAdapter.Item>()
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val Title = if (record.optString("title_eng").isNotEmpty()) record.getString("title_eng") else record.optString("title")
            val id = "${record.optInt("id")}-${record.optString("slug")}"
            val poster = getImageUrl(record.optString("imageurl"))
            
            if (record.optString("type").contains("Movie", ignoreCase = true)) results.add(Movie(id = id, title = Title, poster = poster))
            else results.add(TvShow(id = id, title = Title, poster = poster))
        }
        return results
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            if (query.isBlank()) {
                if (page > 1) return emptyList()
                return parseGenresFromJson(service.getArchivio().selectFirst("archivio")?.attr("all_genres") ?: "")
            }
            service.getArchivio()
            val payload = JSONObject().apply {
                put("title", query); put("type", false); put("year", false); put("order", false); put("status", false); put("genres", false); put("offset", (page - 1) * 30); put("dubbed", false); put("season", false)
            }
            val response = service.getAnimes(payload.toString().toRequestBody("application/json".toMediaType()))
            parseAnimeFromJson(JSONObject(response.string()).getJSONArray("records"))
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            service.getArchivio()
            val payload = JSONObject().apply {
                put("title", false); put("type", "Movie"); put("year", false); put("order", false); put("status", false); put("genres", false); put("offset", (page - 1) * 30); put("dubbed", false); put("season", false)
            }
            val records = JSONObject(service.getAnimes(payload.toString().toRequestBody("application/json".toMediaType())).string()).getJSONArray("records")
            (0 until records.length()).mapNotNull { i ->
                val record = records.getJSONObject(i)
                Movie(id = "${record.optInt("id")}-${record.optString("slug")}", title = if (record.optString("title_eng").isNotEmpty()) record.getString("title_eng") else record.getString("title"), poster = getImageUrl(record.optString("imageurl")))
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            service.getArchivio()
            val payload = JSONObject().apply {
                put("title", false); put("type", "TV"); put("year", false); put("order", false); put("status", false); put("genres", false); put("offset", (page - 1) * 30); put("dubbed", false); put("season", false)
            }
            val records = JSONObject(service.getAnimes(payload.toString().toRequestBody("application/json".toMediaType())).string()).getJSONArray("records")
            (0 until records.length()).mapNotNull { i ->
                val record = records.getJSONObject(i)
                TvShow(id = "${record.optInt("id")}-${record.optString("slug")}", title = if (record.optString("title_eng").isNotEmpty()) record.getString("title_eng") else record.getString("title"), poster = getImageUrl(record.optString("imageurl")))
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie {
        try {
            val doc = service.getAnime(id)
            val Title = doc.selectFirst("h1.title")?.text()?.trim() ?: ""
            val recommendations = doc.select("div.related-wrapper div.related-item").mapNotNull { item ->
                val rUrl = item.selectFirst("a.unstile-a")?.attr("href") ?: return@mapNotNull null
                val rId = rUrl.substringAfterLast("/")
                val rTitle = item.selectFirst("strong.related-anime-title")?.text()?.trim() ?: ""
                val rPoster = getImageUrl(item.selectFirst("img")?.attr("src") ?: "")
                if (item.selectFirst("div.related-info")?.text()?.contains("Movie", ignoreCase = true) == true) Movie(id = rId, title = rTitle, poster = rPoster)
                else TvShow(id = rId, title = rTitle, poster = rPoster)
            }
            
            return Movie(
                id = id, title = Title, poster = getImageUrl(doc.selectFirst("img.cover")?.attr("src") ?: ""), overview = doc.selectFirst("div.description")?.text()?.trim() ?: "", 
                rating = doc.selectFirst("div.info-item:has(strong:contains(Valutazione)) small")?.text()?.trim()?.toDoubleOrNull(), 
                released = doc.selectFirst("div.info-item:has(strong:contains(Anno)) small")?.text()?.trim() ?: "", 
                runtime = doc.selectFirst("div.info-item:has(strong:contains(Durata)) small")?.text()?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull(), 
                genres = doc.select("div.info-wrapper:has(strong:contains(Generi)) a.genre-link").map { Genre(id = it.text().trim().trimEnd(','), name = it.text().trim().trimEnd(',')) },
                recommendations = recommendations
            )
        } catch (e: Exception) { return Movie(id = id, title = "", poster = "") }
    }

    override suspend fun getTvShow(id: String): TvShow {
        try {
            val doc = service.getAnime(id)
            val Title = doc.selectFirst("h1.title")?.text()?.trim() ?: ""
            val count = doc.selectFirst("video-player")?.attr("episodes_count")?.toIntOrNull() ?: 0
            val seasons = if (count > 120) calculateEpisodeRanges(count).map { (s, e) -> Season(id = "$id-$s-$e", number = 0, title = "$s-$e") }
            else listOf(Season(id = id, number = 0, title = "Episodi"))

            return TvShow(
                id = id, title = Title, poster = getImageUrl(doc.selectFirst("img.cover")?.attr("src") ?: ""), overview = doc.selectFirst("div.description")?.text()?.trim() ?: "", 
                rating = doc.selectFirst("div.info-item:has(strong:contains(Valutazione)) small")?.text()?.trim()?.toDoubleOrNull(), 
                released = doc.selectFirst("div.info-item:has(strong:contains(Anno)) small")?.text()?.trim() ?: "", 
                runtime = doc.selectFirst("div.info-item:has(strong:contains(Durata)) small")?.text()?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull(), 
                genres = doc.select("div.info-wrapper:has(strong:contains(Generi)) a.genre-link").map { Genre(id = it.text().trim().trimEnd(','), name = it.text().trim().trimEnd(',')) },
                recommendations = doc.select("div.related-wrapper div.related-item").mapNotNull { item ->
                    val rUrl = item.selectFirst("a.unstile-a")?.attr("href") ?: return@mapNotNull null
                    val rId = rUrl.substringAfterLast("/")
                    val rTitle = item.selectFirst("strong.related-anime-title")?.text()?.trim() ?: ""
                    val rPoster = getImageUrl(item.selectFirst("img")?.attr("src") ?: "")
                    if (item.selectFirst("div.related-info")?.text()?.contains("Movie", ignoreCase = true) == true) Movie(id = rId, title = rTitle, poster = rPoster)
                    else TvShow(id = rId, title = rTitle, poster = rPoster)
                }, seasons = seasons
            )
        } catch (e: Exception) { return TvShow(id = id, title = "", poster = "") }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        try {
            val parts = seasonId.split("-")
            val hasRange = parts.size >= 4 && parts[parts.size - 2].toIntOrNull() != null && parts[parts.size - 1].toIntOrNull() != null
            val animeId = if (hasRange) parts.dropLast(2).joinToString("-") else seasonId
            val animeIdClean = animeId.split("-")[0]
            val videoPlayer = service.getAnime(animeId).selectFirst("video-player") ?: return emptyList()
            
            if (hasRange) return getEpisodesFromApiRange(animeIdClean, animeId, parts[parts.size - 2].toInt(), parts[parts.size - 1].toInt())
            if (videoPlayer.attr("episodes_count").toIntOrNull() ?: 0 <= 120) return getEpisodesNormal(animeId, videoPlayer)
            return emptyList()
        } catch (e: Exception) { return emptyList() }
    }
    
    private suspend fun getEpisodesNormal(seasonId: String, videoPlayer: Element): List<Episode> = coroutineScope {
        try {
            val episodesJson = org.json.JSONArray(java.net.URLDecoder.decode(videoPlayer.attr("episodes"), "UTF-8"))
            val thumbnailsDeferred = async { try { JSONObject(videoPlayer.attr("anime")).optInt("anilist_id", 0).takeIf { it > 0 }?.let { fetchEpisodeThumbnails(it) } } catch(_:Exception) { null } }
            val thumbnails = thumbnailsDeferred.await() ?: emptyMap()

            (0 until episodesJson.length()).map { i ->
                val epData = episodesJson.getJSONObject(i)
                val num = epData.optString("number").toIntOrNull() ?: (i + 1)
                Episode(id = "$seasonId/${epData.optString("id")}", number = num, title = extractEpisodeNameFromFileName(epData.optString("file_name")).ifEmpty { "Episodio $num" }, poster = thumbnails[num])
            }
        } catch (e: Exception) { emptyList() }
    }
    
    private fun calculateEpisodeRanges(count: Int): List<Pair<Int, Int>> {
        if (count <= 120) return listOf(1 to count)
        val ranges = mutableListOf(1 to 120)
        for (i in 0 until (count - 120) / 120 + if ((count - 120) % 120 > 0) 1 else 0) {
            val s = 121 + (i * 120)
            ranges.add(s to minOf(s + 119, count))
        }
        return ranges
    }
    
    private suspend fun getEpisodesFromApiRange(animeId: String, animeIdFull: String, sR: Int, eR: Int): List<Episode> = coroutineScope {
        try {
            val thumbnailsDeferred = async { try { JSONObject(service.getAnime(animeIdFull).selectFirst("video-player")?.attr("anime") ?: "").optInt("anilist_id", 0).takeIf { it > 0 }?.let { fetchEpisodeThumbnails(it) } } catch(_:Exception) { null } }
            val episodesData = JSONObject(service.getEpisodesByRange(animeId, sR, eR).string()).optJSONArray("episodes") ?: return@coroutineScope emptyList()
            val thumbnails = thumbnailsDeferred.await() ?: emptyMap()

            (0 until episodesData.length()).map { i ->
                val epData = episodesData.getJSONObject(i)
                val nStr = epData.optString("number", "0")
                val num = if (nStr.contains("-")) nStr.split("-")[0].toIntOrNull() ?: 0 else nStr.toIntOrNull() ?: 0
                val title = extractEpisodeNameFromFileName(epData.optString("file_name")).ifEmpty { "Episodio $nStr" }
                Episode(id = "$animeIdFull/${epData.optString("id")}", number = num, title = title, poster = thumbnails[num])
            }
        } catch (e: Exception) { emptyList() }
    }
    
    private suspend fun findEpisodeInPaginatedData(animeId: String, epNum: Int, count: Int): org.json.JSONObject? {
        calculateEpisodeRanges(count).forEach { (s, e) ->
            if (epNum in s..e) {
                val eps = JSONObject(service.getEpisodesByRange(animeId, s, e).string()).optJSONArray("episodes") ?: return@forEach
                for (i in 0 until eps.length()) {
                    val ep = eps.getJSONObject(i)
                    val nStr = ep.optString("number", "0")
                    if (if (nStr.contains("-")) nStr.split("-")[0].toIntOrNull() == epNum else nStr.toIntOrNull() == epNum) return ep
                }
            }
        }
        return null
    }
    
    private fun extractEpisodeNameFromFileName(f: String): String {
        if (f.isEmpty()) return ""
        return try {
            val match = Regex("""(?:\.S\d+E\d+\.(.+?)(?:\.\d+p|\.CR\.WEB-DL|\.WEB-DL|\.JPN|\.ITA|\.AAC2\.0|\.H\.264|\.mkv|\.AMZN)|Ep_\d+_(.+?)(?:_SUB_ITA|\.mp4|\.mkv)|ep\s+\d+\s+(.+?)(?:\.mp4|\.mkv))""").find(f)
            if (match != null) {
                val name = (match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }).replace(".", " ").replace("_", " ").trim()
                name.replace(Regex("""^Episodio\s+\d+\s*-?\s*""", RegexOption.IGNORE_CASE), "").trim()
            } else ""
        } catch (e: Exception) { "" }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val doc = service.getArchivio()
        var genreName = "Genre $id"
        val archivioAttr = doc.selectFirst("archivio")?.attr("all_genres") ?: ""
        if (archivioAttr.isNotEmpty()) {
            val jsonArray = org.json.JSONArray(archivioAttr.replace("&quot;", "\""))
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getInt("id").toString() == id) { genreName = obj.getString("name"); break }
            }
        }
        return Genre(id = id, name = genreName, shows = getAnimeByGenre(id.toIntOrNull() ?: 0, genreName, (page - 1) * 30).mapNotNull { if (it is Movie) it else if (it is TvShow) it else null })
    }

    private suspend fun getAnimeByGenre(gId: Int, gName: String, offset: Int = 0): List<AppAdapter.Item> {
        try {
            service.getArchivio()
            val payload = JSONObject().apply {
                put("title", false); put("type", false); put("year", false); put("order", false); put("status", false); put("offset", offset); put("dubbed", false); put("season", false)
                put("genres", org.json.JSONArray().put(JSONObject().apply { put("id", gId); put("name", gName) }))
            }
            return parseAnimeFromJson(JSONObject(service.getAnimes(payload.toString().toRequestBody("application/json".toMediaType())).string()).getJSONArray("records"))
        } catch (e: Exception) { return emptyList() }
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("AnimeUnity doesn't support people search")
    override suspend fun getVideo(server: Video.Server): Video = VixcloudExtractor().extract(server.src)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        try {
            val epNum = if (videoType is Video.Type.Episode) videoType.number else 1
            val animeIdFull = if (id.contains("/")) id.split("/")[0] else id
            val animeIdClean = animeIdFull.split("-")[0]
            val vPlayer = service.getAnime(animeIdFull).selectFirst("video-player") ?: return emptyList()
            val count = vPlayer.attr("episodes_count").toIntOrNull() ?: 0
            
            val embedUrl = if (videoType !is Video.Type.Episode || epNum == 1) vPlayer.attr("embed_url")
            else {
                val target = if (count <= 120) {
                    val eps = org.json.JSONArray(java.net.URLDecoder.decode(vPlayer.attr("episodes"), "UTF-8"))
                    var found: org.json.JSONObject? = null
                    for (i in 0 until eps.length()) {
                        val nStr = eps.getJSONObject(i).optString("number", "")
                        if (if (nStr.contains("-")) nStr.split("-")[0].toIntOrNull() == epNum else eps.getJSONObject(i).optInt("number") == epNum) { found = eps.getJSONObject(i); break }
                    }
                    found
                } else findEpisodeInPaginatedData(animeIdClean, epNum, count)
                
                target?.optString("id")?.takeIf { it.isNotEmpty() }?.let { service.getEmbedUrl(it).text().trim() } ?: ""
            }
            return if (embedUrl.isNotEmpty()) listOf(Video.Server(id = id, name = "Vixcloud", src = embedUrl)) else emptyList()
        } catch (e: Exception) { return emptyList() }
    }
}