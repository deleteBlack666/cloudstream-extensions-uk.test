package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*

class AnimeONProvider : MainAPI() {

    private val TAG = "AnimeOn"

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val newRequest = when {
                url.contains("moonanime.net") || url.contains("s.moonanime.net") -> {
                    request.newBuilder()
                        .header("Referer", "https://moonanime.net/")
                        .header("Origin", "https://moonanime.net")
                        .header("User-Agent", userAgent)
                        .build()
                }
                url.contains("fenixplay.xyz") || url.contains("cdn.fenixplay.xyz") -> {
                    request.newBuilder()
                        .header("Referer", "https://fenixplay.xyz/")
                        .header("Origin", "https://fenixplay.xyz")
                        .header("User-Agent", userAgent)
                        .build()
                }
                else -> request
            }

            chain.proceed(newRequest)
        }
    }

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

    private var posterProxyPort: Int = 0
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var moonCookieHeader: String? = null
    private var lastFenixPlaylist: FenixPlaylist? = null
    private var lastFenixMediaAccess: FenixMediaAccess? = null

    private val episodePosterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private data class PosterFetchTask(val episodeId: Int, val animeId: Int)
    private val posterFetchTasks = java.util.concurrent.ConcurrentHashMap<String, PosterFetchTask>()

    private data class CollectedEpisodes(val translationName: String, val playerName: String, val episodes: List<FundubEpisode>)

    private val posterHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        }
        .build()

    private val htmlHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun currentSeasonLabel(): String {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1

        val seasonUa = when (month) {
            12, 1, 2 -> "зимового"
            3, 4, 5 -> "весняного"
            6, 7, 8 -> "літнього"
            else -> "осіннього"
        }

        return "Аніме ${seasonUa} сезону"
    }

    override val mainPage = mainPageOf(
        "$apiUrl/seasons" to currentSeasonLabel(),
        "$mainUrl/api/stats/anime/popular/" to "Популярні аніме",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private data class SafeResult(
        val id: Int,
        val titleUa: String,
        val description: String? = null,
        val image: Image,
        val malId: Int? = null,
        val rating: Double? = 0.0,
        val status: String? = null,
        val type: String? = null,
        val genres: List<Genres>? = null,
        val episodes: Int? = null
    )

    private data class SafeNewAnimeModel(
        val results: List<SafeResult>,
        val totalCount: Int? = 0
    )

    private data class SafeSearchApiResponse(
        val results: List<SafeResult>,
        val totalCount: Int? = 0
    )

    private data class SafeAnimeInfoModel(
        val id: Int,
        val titleUa: String,
        val titleEn: String? = null,
        val description: String? = null,
        val image: Image? = null,
        val backgroundImage: String? = null,
        val trailer: String? = null,
        val rating: Double? = 0.0,
        val status: String? = "completed",
        val type: String? = "tv",
        val genres: List<Genres>? = null,
        val episodes: Int? = 0,
        val episodeTime: String? = "",
        val releaseDate: String? = null,
        val malId: Int? = 0
    )

    private data class PopularAnime(
        val id: Int,
        val slug: String? = null,
        val titleUa: String,
        val titleEn: String? = null,
        val type: String? = null,
        val image: Image? = null
    )

    private data class PopularAnimeItem(
        val views: Int? = null,
        val anime: PopularAnime
    )

    private data class PopularAnimeResponse(
        val results: List<PopularAnimeItem>
    )

    private data class SafeTranslationsResponse(
        val translations: List<TranslationItem>
    )

    private data class SafePlayerEpisodes(
        val episodes: List<FundubEpisode>
    )

    private data class LocalResult(
        val id: Int,
        val titleUa: String,
        val slug: String?,
        val episodesAired: Int?,
        val rating: String?,
        val image: Image,
        val description: String? = null
    )

    private data class RedirectResponse(
        val moved: Boolean? = null,
        val redirectTo: String? = null,
        val slug: String? = null,
    )

    private data class EpisodeSource(
        val translationName: String,
        val playerName: String,
        val episodeId: Int,
        val episodeNum: Int? = null,
        val apiPoster: String? = null
    )

    private data class DirectPlayerResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
        val selection: FenixSelection? = null
    )

    private data class FenixSelection(
        val episode: Int? = null,
        val edition: String? = null,
        val track: String? = null
    )

    private data class EpisodeVideoResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
        val selection: FenixSelection? = null
    )

    private data class FranchiseItem(
        val id: Int,
        val slug: String?,
        val titleUa: String,
        val type: String?,
        val image: Image?,
        val releaseDate: String?,
    )

    private data class EpisodeInfo(
        val id: Int? = null,
        val episode: Int,
        val title: String? = null,
        val titleUa: String? = null,
        val aired: String? = null,
        val filler: Boolean? = null,
        val recap: Boolean? = null,
    )

    private data class FenixSubtitle(
        val label: String? = null,
        val language: String? = null,
        val url: String? = null,
        val styled_url: String? = null,
        val fonts_url: String? = null
    )

    private data class FenixTrack(
        val id: String? = null,
        val label: String? = null,
        val language: String? = null,
        val track_type: String? = null,
        val hls_url: String? = null,
        val cast_avc_url: String? = null,
        val is_default: Boolean? = null
    )

    private data class FenixEpisode(
        val id: String? = null,
        val number: Int = 0,
        val edition: String? = null,
        val edition_label: String? = null,
        val title: String? = null,
        val hls_master_url: String? = null,
        val cast_avc_url: String? = null,
        val thumbnail_url: String? = null,
        val video_segment_format: String? = null,
        val tracks: List<FenixTrack>? = null,
        val subtitles: List<FenixSubtitle>? = null
    )

    private data class FenixMediaAccess(
        val origin: String? = null,
        val token: String? = null
    )

    private data class FenixPlayback(
        val version: Int? = 1,
        val token: String? = null,
    )

    private data class FenixPlaybackTrack(
        val id: String? = null,
        val label: String? = null,
        val language: String? = null,
        val track_type: String? = null,
        val hls_url: String? = null,
        val cast_avc_url: String? = null,
        val is_default: Boolean? = null,
        val voice_studio_id: String? = null,
    )

    private data class FenixPlaybackEpisode(
        val id: String? = null,
        val number: Int = 0,
        val edition: String? = null,
        val title: String? = null,
        val thumbnail_url: String? = null,
        val hls_master_url: String? = null,
        val cast_avc_url: String? = null,
        val video_segment_format: String? = null,
        val tracks: List<FenixPlaybackTrack>? = null,
        val subtitles: List<FenixSubtitle>? = null,
    )

    private data class FenixPlaybackEpisodeResponse(
        val episode: FenixPlaybackEpisode? = null,
        val token: String? = null,
    )

    private data class FenixPlaylist(
        val anime_id: String? = null,
        val slug: String? = null,
        val title_ua: String? = null,
        val poster_url: String? = null,
        val type: String? = null,
        val default_edition: String? = null,
        val episodes: List<FenixEpisode>? = null,
        val mini_episodes: List<FenixEpisode>? = null,
        val playback: FenixPlayback? = null,
    )

    private fun fixMovieExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/") -> 720
            link.url.contains("/480/") -> 480
            link.url.contains("/360/") -> 360
            else -> when (link.quality) {
                in 900..1150 -> 1080
                in 600..899 -> 720
                in 400..599 -> 480
                in 240..399 -> 360
                else -> link.quality
            }
        }

        return ExtractorLink(
            source = link.source,
            name = sourceName,
            url = link.url,
            referer = link.referer,
            quality = cleanQuality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t")
    }

    private fun buildEpisodeDataJson(sources: List<EpisodeSource>): String {
        val sb = StringBuilder("[")
        sources.forEachIndexed { index, s ->
            if (index > 0) sb.append(",")
            sb.append("{\"translationName\":\"${escapeJson(s.translationName)}\",")
            sb.append("\"playerName\":\"${escapeJson(s.playerName)}\",")
            sb.append("\"episodeId\":${s.episodeId},")
            sb.append("\"episodeNum\":${s.episodeNum ?: 0}}")
        }
        sb.append("]")
        return sb.toString()
    }

    @Synchronized
    private fun ensurePosterProxy() {
        if (posterProxyPort != 0) return
        Log.e(TAG, "ensurePosterProxy: starting poster proxy")

        val serverSocket = java.net.ServerSocket(0)
        posterProxyPort = serverSocket.localPort
        Log.e(TAG, "ensurePosterProxy: proxy port=$posterProxyPort")

        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()

                    Thread {
                        try {
                            val input = client.getInputStream()
                            val reader = input.bufferedReader()
                            val line = reader.readLine() ?: return@Thread

                            val key = line.substringAfter("?").substringBefore(" ").trim()
                            Log.e(TAG, "posterProxy: request key=$key")
                            val out = client.getOutputStream()

                            var body = posterCache[key]

                            if (body == null) {
                                val task = posterFetchTasks[key]
                                if (task != null) {
                                    Log.e(TAG, "posterProxy: fetching ep=${task.episodeId} anime=${task.animeId}")
                                    body = fetchEpisodePosterBytes(task.episodeId)
                                    if (body != null && body.isNotEmpty()) {
                                        posterCache[key] = body
                                        episodePosterCache["${task.animeId}:${task.episodeId}"] = "http://127.0.0.1:$posterProxyPort/poster?$key"
                                    }
                                }
                            }

                            val finalBody = body ?: ByteArray(0)

                            if (finalBody.isEmpty()) {
                                Log.e(TAG, "posterProxy: 404 for key=$key")
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val contentType = when {
                                    finalBody.size > 8 && finalBody[0] == 0x89.toByte() && finalBody[1] == 0x50.toByte() -> "image/png"
                                    finalBody.size > 2 && finalBody[0] == 0xFF.toByte() && finalBody[1] == 0xD8.toByte() -> "image/jpeg"
                                    finalBody.size > 4 && finalBody[0] == 0x52.toByte() && finalBody[1] == 0x49.toByte() -> "image/webp"
                                    else -> "application/octet-stream"
                                }

                                val response = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: $contentType\r\n")
                                    append("Content-Length: ${finalBody.size}\r\n")
                                    append("Cache-Control: public, max-age=86400\r\n")
                                    append("Access-Control-Allow-Origin: *\r\n")
                                    append("Connection: close\r\n")
                                    append("\r\n")
                                }

                                out.write(response.toByteArray())
                                out.write(finalBody)
                            }

                            out.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "posterProxy error: ${e.message}")
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {
                            }
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                    Log.e(TAG, "posterProxy accept error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun getMoonCookieHeader(): String? {
        moonCookieHeader?.let { return it }

        return try {
            val request = okhttp3.Request.Builder()
                .url("https://moonanime.net/")
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val response = posterHttpClient.newCall(request).execute()
            val setCookies = response.headers.toMultimap()["set-cookie"] ?: emptyList()
            response.close()

            val cookiePairs = setCookies.mapNotNull { cookie ->
                val value = cookie.substringBefore(";").trim()
                if (value.isNotEmpty()) value else null
            }

            val header = cookiePairs.joinToString("; ")
            Log.e(TAG, "getMoonCookieHeader: cookies=${cookiePairs.size} header=${header.take(100)}")

            if (header.isNotBlank()) {
                moonCookieHeader = header
                header
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMoonCookieHeader error: ${e.message}")
            null
        }
    }

    private fun fetchPosterBytes(originalUrl: String): ByteArray {
        return try {
            Log.e(TAG, "fetchPosterBytes: url=$originalUrl")
            val requestBuilder = okhttp3.Request.Builder()
                .url(originalUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site")
                .get()

            when {
                originalUrl.contains("s.moonanime.net") ||
                originalUrl.contains("moonanime.net") ||
                originalUrl.contains("mooncdn.") -> {
                    requestBuilder.header("Referer", "https://moonanime.net/")
                    requestBuilder.header("Origin", "https://moonanime.net")
                }
                originalUrl.contains("ashdi.vip") -> {
                    requestBuilder.header("Referer", "https://ashdi.vip/")
                    requestBuilder.header("Origin", "https://ashdi.vip")
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Site", "none")
                    requestBuilder.header("Cookie", "_ga=GA1.1.1899212404.1785951203; _gid=GA1.2.1071268545.1786650637")
                }
                originalUrl.contains("fenixplay.xyz") -> {
                    requestBuilder.header("Referer", "https://fenixplay.xyz/")
                    requestBuilder.header("Origin", "https://fenixplay.xyz")
                }
            }

            getMoonCookieHeader()?.let { cookie ->
                if (originalUrl.contains("moon")) {
                    requestBuilder.header("Cookie", cookie)
                }
            }

            val response = posterHttpClient.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "fetchPosterBytes: HTTP ${response.code} for $originalUrl")
                response.close()
                return ByteArray(0)
            }

            val bytes = response.body?.bytes() ?: ByteArray(0)
            response.close()
            Log.e(TAG, "fetchPosterBytes: got ${bytes.size} bytes")

            bytes
        } catch (e: Exception) {
            Log.e(TAG, "fetchPosterBytes error: ${e.message}")
            ByteArray(0)
        }
    }

    private suspend fun buildFranchise(animeId: Int): List<SearchResponse> {
        Log.e(TAG, "buildFranchise: animeId=$animeId")
        val json = fetchJsonOrNull("$mainUrl/api/franchise/full/$animeId") ?: run {
            Log.e(TAG, "buildFranchise: no json for animeId=$animeId")
            return emptyList()
        }

        return try {
            val items = AppUtils.parseJson<List<FranchiseItem>>(json)
            Log.e(TAG, "buildFranchise: items=${items.size}")

            items.filter { it.id != animeId }.map { item ->
                newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                    this.posterUrl = item.image?.preview?.let { posterApi.format(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildFranchise parse error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                ),
                timeout = 10
            ).text

            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) {
                Log.e(TAG, "fetchJsonOrNull: NON_JSON url=$url body=${response.take(200)}")
                null
            } else {
                Log.e(TAG, "fetchJsonOrNull: OK url=$url len=${response.length}")
                response
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchJsonOrNull ERROR url=$url err=${e.message}")
            null
        }
    }

    private fun fetchJsonOrNullSync(url: String): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Referer", mainUrl)
                .header("User-Agent", userAgent)
                .get()
                .build()

            val response = htmlHttpClient.newCall(request).execute()
            val text = if (response.isSuccessful) response.body?.string() else null
            response.close()

            if (text != null && !text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) {
                Log.e(TAG, "fetchJsonOrNullSync: NON_JSON url=$url body=${text.take(200)}")
                null
            } else {
                Log.e(TAG, "fetchJsonOrNullSync: OK url=$url len=${text?.length}")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchJsonOrNullSync ERROR url=$url err=${e.message}")
            null
        }
    }

    private suspend fun fetchJsonWithRetry(url: String, retries: Int = 3): String? {
        repeat(retries) { i ->
            val result = fetchJsonOrNull(url)
            if (result != null) return result
            Log.e(TAG, "fetchJsonWithRetry: retry ${i + 1}/$retries for $url")
        }
        Log.e(TAG, "fetchJsonWithRetry: FAILED after $retries retries for $url")
        return null
    }

    private suspend fun resolveAnimeSlug(animeId: Int): String {
        Log.e(TAG, "resolveAnimeSlug: animeId=$animeId")
        val initial = fetchJsonOrNull("$apiUrl/$animeId") ?: run {
            Log.e(TAG, "resolveAnimeSlug: null json, use id")
            return animeId.toString()
        }

        return try {
            val redirect = AppUtils.parseJson<RedirectResponse>(initial)
            Log.e(TAG, "resolveAnimeSlug: moved=${redirect?.moved} slug=${redirect?.slug}")
            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) redirect.slug!! else animeId.toString()
        } catch (e: Exception) {
            Log.e(TAG, "resolveAnimeSlug parse error: ${e.message}")
            animeId.toString()
        }
    }

    private suspend fun fetchEpisodeInfoMap(slug: String): Map<Int, String> {
        Log.e(TAG, "fetchEpisodeInfoMap: slug=$slug")
        val infoJson = fetchJsonOrNull("$mainUrl/api/anime/$slug/episodes-info") ?: return emptyMap()

        return try {
            val list = AppUtils.parseJson<List<EpisodeInfo>>(infoJson)
            Log.e(TAG, "fetchEpisodeInfoMap: count=${list.size}")
            list.associate { ep ->
                ep.episode to (ep.titleUa?.takeIf { it.isNotBlank() } ?: ep.title?.takeIf { it.isNotBlank() } ?: "")
            }.filter { it.value.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchEpisodeInfoMap error: ${e.message}")
            emptyMap()
        }
    }

    private fun httpGetText(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val builder = okhttp3.Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            val response = htmlHttpClient.newCall(builder.build()).execute()
            val body = if (response.isSuccessful) response.body?.string() ?: "" else ""
            response.close()
            Log.e(TAG, "httpGetText: ${response.code} url=$url len=${body.length}")
            body
        } catch (e: Exception) {
            Log.e(TAG, "httpGetText ERROR url=$url err=${e.message}")
            null
        }
    }

    private fun fetchEpisodeVideoUrlSync(episodeId: Int): String? {
        val json = httpGetText(
            "$mainUrl/api/player/$episodeId/episode",
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
            )
        ) ?: run {
            Log.e(TAG, "fetchEpisodeVideoUrlSync: no json ep=$episodeId")
            return null
        }

        return try {
            val resp = AppUtils.parseJson<EpisodeVideoResponse>(json)
            Log.e(TAG, "fetchEpisodeVideoUrlSync: ep=$episodeId videoUrl=${resp.videoUrl} fileUrl=${resp.fileUrl}")
            resp.videoUrl ?: resp.fileUrl
        } catch (e: Exception) {
            Log.e(TAG, "fetchEpisodeVideoUrlSync parse error: ${e.message}")
            null
        }
    }

    private fun getAshdiRawPosterUrl(videoUrl: String): String? {
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        Log.e(TAG, "getAshdiRawPosterUrl: url=$url")

        val html = httpGetText(
            url,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none"
            ),
            mapOf(
                "_ga" to "GA1.1.1899212404.1785951203",
                "_gid" to "GA1.2.1071268545.1786650637"
            )
        )

        if (html.isNullOrEmpty() || html.contains("недоступний")) {
            Log.e(TAG, "getAshdiRawPosterUrl: empty or unavailable")
            return null
        }
        if (!html.contains("new Playerjs") && html.contains("country")) {
            Log.e(TAG, "getAshdiRawPosterUrl: country block")
            return null
        }

        val patterns = listOf(
            Regex("""poster["']?\s*:\s*["']([^"']+)["']"""),
            Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)"""),
            Regex("""((?:https?:)?//[^"'\s]+\.ashdi\.vip[^"'\s]*(?:screen|poster)[^"'\s]*)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val posterUrl = match.groupValues[1]
                val result = if (posterUrl.startsWith("http")) posterUrl else "https:$posterUrl"
                Log.e(TAG, "getAshdiRawPosterUrl: found=$result")
                return result
            }
        }
        Log.e(TAG, "getAshdiRawPosterUrl: not found")
        return null
    }

    private fun getMoonRawPosterUrl(iframeUrl: String): String? {
        if (!iframeUrl.contains("/iframe/")) {
            Log.e(TAG, "getMoonRawPosterUrl: not iframe url=$iframeUrl")
            return null
        }

        val cleanUrl = if (iframeUrl.contains("player=")) {
            iframeUrl
        } else {
            "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"
        }
        Log.e(TAG, "getMoonRawPosterUrl: cleanUrl=$cleanUrl")

        val html = httpGetText(
            cleanUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://animeon.club/",
                "X-Requested-With" to "mark.via.gp",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Dest" to "document",
                "Upgrade-Insecure-Requests" to "1"
            )
        ) ?: run {
            Log.e(TAG, "getMoonRawPosterUrl: no html")
            return null
        }

        val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
        var posterUrl: String? = null

        for (match in atobRegex.findAll(html)) {
            val decoded = moonOuterDecode(match.groupValues[1])
            if (!decoded.contains("poster")) continue

            posterUrl = Regex("""poster\s*:\s*["'](https?://[^"']+)["']""")
                .find(decoded)?.groupValues?.get(1)

            if (posterUrl != null) break

            val xorKey = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                .find(decoded)?.groupValues?.get(1) ?: continue

            val posterEnc = Regex("""poster\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                .find(decoded)?.groupValues?.get(1) ?: continue

            val result = moonDecrypt(posterEnc, xorKey)
            if (result.startsWith("http")) {
                posterUrl = result
                break
            }
        }

        if (posterUrl.isNullOrEmpty() || posterUrl.contains("mooncdn.")) {
            Log.e(TAG, "getMoonRawPosterUrl: no valid poster (raw=$posterUrl)")
            return null
        }
        Log.e(TAG, "getMoonRawPosterUrl: found=$posterUrl")
        return posterUrl
    }

    private fun fenixExtractCode(embedUrl: String): String? {
        return embedUrl.substringAfter("/embed/", "").substringBefore("?").ifBlank { null }
    }

    private fun fenixExtractEpisodeNumber(embedUrl: String): Int? {
        return Regex("""[?&]ep=(\d+)""").find(embedUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun fenixExtractEdition(embedUrl: String): String {
        return Regex("""[?&]edition=([^&]+)""").find(embedUrl)?.groupValues?.get(1) ?: "tv"
    }

    private fun isSignedFenixCode(code: String): Boolean {
        return Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""").matches(code)
    }

    private fun fenixOrigin(embedUrl: String): String {
        return try {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            Log.e(TAG, "fenixOrigin error: ${e.message}")
            "https://fenixplay.xyz"
        }
    }

    private fun extractFenixMediaAccess(html: String): FenixMediaAccess? {
        val regex = Regex(
            """window\.FENIX_MEDIA_ACCESS\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = regex.find(html) ?: return null
        return try {
            val access = AppUtils.parseJson<FenixMediaAccess>(match.groupValues[1])
            Log.e(TAG, "extractFenixMediaAccess: origin=${access.origin} token=${access.token?.take(10)}")
            access
        } catch (e: Exception) {
            Log.e(TAG, "extractFenixMediaAccess parse error: ${e.message}")
            null
        }
    }

    private fun authorizeFenixUrl(url: String?, access: FenixMediaAccess?): String? {
        if (url.isNullOrEmpty() || access == null) return url
        if (access.origin.isNullOrEmpty() || access.token.isNullOrEmpty()) return url
        return try {
            val targetUri = java.net.URI(url)
            val originUri = java.net.URI(access.origin)
            if (targetUri.host != originUri.host) return url
            val sep = if (url.contains("?")) "&" else "?"
            "$url${sep}token=${access.token}"
        } catch (e: Exception) {
            Log.e(TAG, "authorizeFenixUrl error: ${e.message}")
            url
        }
    }

    private fun authorizeFenixPlaylist(playlist: FenixPlaylist, access: FenixMediaAccess?): FenixPlaylist {
        if (access == null) {
            Log.e(TAG, "authorizeFenixPlaylist: no access token")
            return playlist
        }
        if (access.origin.isNullOrEmpty() || access.token.isNullOrEmpty()) {
            Log.e(TAG, "authorizeFenixPlaylist: empty origin/token")
            return playlist
        }

        fun ep(episode: FenixEpisode): FenixEpisode = episode.copy(
            hls_master_url = authorizeFenixUrl(episode.hls_master_url, access),
            cast_avc_url = authorizeFenixUrl(episode.cast_avc_url, access),
            tracks = episode.tracks?.map { t ->
                t.copy(
                    hls_url = authorizeFenixUrl(t.hls_url, access),
                    cast_avc_url = authorizeFenixUrl(t.cast_avc_url, access)
                )
            },
            subtitles = episode.subtitles?.map { s ->
                s.copy(
                    url = authorizeFenixUrl(s.url, access),
                    styled_url = authorizeFenixUrl(s.styled_url, access),
                    fonts_url = authorizeFenixUrl(s.fonts_url, access)
                )
            }
        )

        return playlist.copy(
            episodes = playlist.episodes?.map { ep(it) },
            mini_episodes = playlist.mini_episodes?.map { ep(it) }
        )
    }

    private suspend fun fetchFenixPlaylist(embedUrl: String): FenixPlaylist? {
        Log.e(TAG, "fetchFenixPlaylist: embedUrl=$embedUrl")
        val code = fenixExtractCode(embedUrl) ?: run {
            Log.e(TAG, "fetchFenixPlaylist: code is NULL")
            return null
        }
        val origin = fenixOrigin(embedUrl)
        Log.e(TAG, "fetchFenixPlaylist: code=$code origin=$origin signed=${isSignedFenixCode(code)}")

        val html = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "fetchFenixPlaylist: embed html error: ${e.message}")
            ""
        }
        Log.e(TAG, "fetchFenixPlaylist: embed html len=${html.length}")

        val mediaAccess = extractFenixMediaAccess(html)
        lastFenixMediaAccess = mediaAccess
        Log.e(TAG, "fetchFenixPlaylist: mediaAccess=${mediaAccess != null}")

        val inlineRegex = Regex("""window\.FENIX_PLAYLIST\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val inlineMatch = inlineRegex.find(html)
        if (inlineMatch != null) {
            Log.e(TAG, "fetchFenixPlaylist: found inline playlist")
            try {
                val p = AppUtils.parseJson<FenixPlaylist>(inlineMatch.groupValues[1])
                lastFenixPlaylist = p
                Log.e(TAG, "fetchFenixPlaylist: inline eps=${p.episodes?.size} mini=${p.mini_episodes?.size}")
                if ((p.episodes?.isNotEmpty() == true) || (p.mini_episodes?.isNotEmpty() == true)) {
                    return authorizeFenixPlaylist(p, mediaAccess)
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchFenixPlaylist: inline parse error: ${e.message}")
            }
        }

        val apiBase = Regex("""window\.FENIX_API_BASE\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val finalApiBase = apiBase ?: origin
        Log.e(TAG, "fetchFenixPlaylist: apiBase=$finalApiBase")

        val apiPath = if (isSignedFenixCode(code))
            "$finalApiBase/api/playlist-code/$code"
        else
            "$finalApiBase/api/playlist/$code"
        Log.e(TAG, "fetchFenixPlaylist: apiPath=$apiPath")

        return try {
            val json = app.get(
                apiPath,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Accept" to "application/json, */*"
                ),
                cacheTime = 0
            ).text
            Log.e(TAG, "fetchFenixPlaylist: playlist json len=${json.length} preview=${json.take(200)}")

            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            lastFenixPlaylist = playlist
            Log.e(TAG, "fetchFenixPlaylist: parsed eps=${playlist.episodes?.size} mini=${playlist.mini_episodes?.size} defaultEdition=${playlist.default_edition}")
            authorizeFenixPlaylist(playlist, mediaAccess)
        } catch (e: Exception) {
            Log.e(TAG, "fetchFenixPlaylist: api error: ${e.message}")
            null
        }
    }

    private fun fetchFenixPlaylistSync(embedUrl: String): FenixPlaylist? {
        Log.e(TAG, "fetchFenixPlaylistSync: embedUrl=$embedUrl")
        val code = fenixExtractCode(embedUrl) ?: run {
            Log.e(TAG, "fetchFenixPlaylistSync: code NULL")
            return null
        }
        val origin = fenixOrigin(embedUrl)
        Log.e(TAG, "fetchFenixPlaylistSync: code=$code origin=$origin")

        val html = httpGetText(
            embedUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ) ?: ""

        val mediaAccess = extractFenixMediaAccess(html)
        lastFenixMediaAccess = mediaAccess

        // Спробувати inline FENIX_PLAYLIST (містить playback.token!)
        val inlineRegex = Regex("""window\.FENIX_PLAYLIST\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        inlineRegex.find(html)?.let { m ->
            try {
                val p = AppUtils.parseJson<FenixPlaylist>(m.groupValues[1])
                if ((p.episodes?.isNotEmpty() == true) || (p.mini_episodes?.isNotEmpty() == true)) {
                    lastFenixPlaylist = p
                    Log.e(TAG, "fetchFenixPlaylistSync: inline eps=${p.episodes?.size} playbackToken=${p.playback?.token != null}")
                    return authorizeFenixPlaylist(p, mediaAccess)
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchFenixPlaylistSync: inline parse error: ${e.message}")
            }
        }

        val apiPath = if (isSignedFenixCode(code))
            "$origin/api/playlist-code/$code"
        else
            "$origin/api/playlist/$code"
        Log.e(TAG, "fetchFenixPlaylistSync: apiPath=$apiPath")

        val json = httpGetText(
            apiPath,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "Accept" to "application/json, */*"
            )
        ) ?: run {
            Log.e(TAG, "fetchFenixPlaylistSync: no json")
            return null
        }

        return try {
            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            lastFenixPlaylist = playlist
            Log.e(TAG, "fetchFenixPlaylistSync: api eps=${playlist.episodes?.size} playbackToken=${playlist.playback?.token != null}")
            authorizeFenixPlaylist(playlist, mediaAccess)
        } catch (e: Exception) {
            Log.e(TAG, "fetchFenixPlaylistSync parse error: ${e.message}")
            null
        }
    }

    private suspend fun fetchFenixEpisodePlayback(
        slug: String,
        playbackToken: String,
        edition: String,
        number: Int
    ): FenixPlaybackEpisodeResponse? {
        val url = "https://fenixplay.xyz/api/playback/" +
            java.net.URLEncoder.encode(slug, "UTF-8") + "/episodes/" +
            java.net.URLEncoder.encode(edition, "UTF-8") + "/" + number
        Log.e(TAG, "fetchFenixEpisodePlayback: url=$url")
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Fenix-Playback" to playbackToken,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "https://fenixplay.xyz/"
                ),
                cacheTime = 0
            ).text
            Log.e(TAG, "fetchFenixEpisodePlayback: OK len=${response.length}")
            AppUtils.parseJson<FenixPlaybackEpisodeResponse>(response)
        } catch (e: Exception) {
            Log.e(TAG, "fetchFenixEpisodePlayback error: ${e.message}")
            null
        }
    }

    private fun pickFenixEpisode(playlist: FenixPlaylist, embedUrl: String): FenixEpisode? {
        val epNum = fenixExtractEpisodeNumber(embedUrl)
        val edition = fenixExtractEdition(embedUrl)
        val allEps = (playlist.episodes ?: emptyList()) + (playlist.mini_episodes ?: emptyList())
        Log.e(TAG, "pickFenixEpisode: epNum=$epNum edition=$edition allEps=${allEps.size}")

        if (allEps.isEmpty()) {
            Log.e(TAG, "pickFenixEpisode: playlist EMPTY")
            return null
        }

        val editions = allEps.map { it.edition ?: "tv" }.distinct()
        Log.e(TAG, "pickFenixEpisode: available editions=$editions numbers=${allEps.map { it.number }}")

        return allEps.find {
            (epNum == null || it.number == epNum) &&
            (it.edition ?: "tv").equals(edition, ignoreCase = true)
        } ?: allEps.firstOrNull().also {
            Log.e(TAG, "pickFenixEpisode: fallback to first ep=${it?.number} ed=${it?.edition}")
        }
    }

    private suspend fun processFenixEpisodeSync(
        episode: FenixEpisode,
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        preferredTrack: String? = null
    ) {
        Log.e(TAG, "processFenixEpisodeSync: source=$sourceName ep=${episode.number} ed=${episode.edition}")
        Log.e(TAG, "processFenixEpisodeSync: tracks=${episode.tracks?.size} subs=${episode.subtitles?.size} hlsMaster=${episode.hls_master_url?.take(80)}")

        val fenixReferer = "https://fenixplay.xyz/"

        val fenixHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to fenixReferer,
            "Origin" to "https://fenixplay.xyz",
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        val allTrackMasters = episode.tracks
            ?.mapNotNull { track ->
                val url = track.hls_url
                if (url.isNullOrBlank()) return@mapNotNull null
                if (!url.contains("master.m3u8", ignoreCase = true)) return@mapNotNull null
                val label = track.label ?: track.language ?: "track"
                Triple(url, label, track.is_default == true)
            }
            ?: emptyList()

        Log.e(TAG, "processFenixEpisodeSync: trackMasters=${allTrackMasters.map { it.second to it.third }}")

        val selectedTrack: Triple<String, String, Boolean>? = run {
            if (allTrackMasters.isEmpty()) return@run null

            if (!preferredTrack.isNullOrBlank()) {
                val exact = allTrackMasters.firstOrNull {
                    it.second.equals(preferredTrack, ignoreCase = true)
                }
                if (exact != null) {
                    Log.e(TAG, "processFenixEpisodeSync: exact preferred track=${exact.second}")
                    return@run exact
                }

                val partial = allTrackMasters.firstOrNull {
                    it.second.contains(preferredTrack, ignoreCase = true) ||
                    preferredTrack.contains(it.second, ignoreCase = true)
                }
                if (partial != null) {
                    Log.e(TAG, "processFenixEpisodeSync: partial preferred track=${partial.second}")
                    return@run partial
                }
            }

            val bySourceName = allTrackMasters.firstOrNull {
                sourceName.contains(it.second, ignoreCase = true) ||
                it.second.contains(sourceName.substringBefore(" ("), ignoreCase = true)
            }
            if (bySourceName != null) {
                Log.e(TAG, "processFenixEpisodeSync: sourceName matched track=${bySourceName.second}")
                return@run bySourceName
            }

            val byDefault = allTrackMasters.firstOrNull { it.third }
            if (byDefault != null) {
                Log.e(TAG, "processFenixEpisodeSync: default track=${byDefault.second}")
                return@run byDefault
            }

            Log.e(TAG, "processFenixEpisodeSync: fallback first track=${allTrackMasters.first().second}")
            allTrackMasters.first()
        }

        if (selectedTrack != null) {
            val (url, label, _) = selectedTrack

            val displayName = if (label.isNotBlank() &&
                !sourceName.contains(label, ignoreCase = true) &&
                !label.contains(sourceName.substringBefore(" ("), ignoreCase = true)
            ) {
                "$sourceName · $label"
            } else {
                sourceName
            }

            Log.e(TAG, "processFenixEpisodeSync: emitting track link display=$displayName url=${url.take(100)}")
            val link = ExtractorLink(
                source = name,
                name = displayName,
                url = url,
                referer = fenixReferer,
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                headers = fenixHeaders
            )
            callback(link)
        } else {
            episode.hls_master_url?.takeIf { it.isNotBlank() }?.let { url ->
                Log.e(TAG, "processFenixEpisodeSync: emitting hls_master url=${url.take(100)}")
                val link = ExtractorLink(
                    source = name,
                    name = sourceName,
                    url = url,
                    referer = fenixReferer,
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                    headers = fenixHeaders
                )
                callback(link)
            } ?: Log.e(TAG, "processFenixEpisodeSync: NO tracks and NO hls_master_url!")
        }

        ensureSubtitleProxy()

        episode.subtitles?.forEach { sub ->
            val url = sub.url ?: return@forEach
            val lang = sub.label ?: sub.language ?: "UA"
            Log.e(TAG, "processFenixEpisodeSync: subtitle lang=$lang url=${url.take(80)}")
            try {
                val bytes = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to fenixReferer,
                        "Origin" to "https://fenixplay.xyz",
                        "Accept" to "*/*"
                    ),
                    cacheTime = 0
                ).body.bytes()

                if (bytes.isEmpty()) {
                    Log.e(TAG, "processFenixEpisodeSync: empty subtitle bytes, emitting direct url")
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                    return@forEach
                }

                val key = java.util.UUID.randomUUID().toString().replace("-", "")
                subtitleCache[key] = bytes
                val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
            } catch (e: Exception) {
                Log.e(TAG, "processFenixEpisodeSync subtitle error: ${e.message}")
                subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
        }

        episode.tracks?.forEach { track ->
            val url = track.hls_url ?: return@forEach
            if (url.contains(".vtt") || url.contains(".srt")) {
                val lang = track.label ?: track.language ?: "UA"
                Log.e(TAG, "processFenixEpisodeSync: track subtitle lang=$lang url=${url.take(80)}")
                try {
                    val bytes = app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to fenixReferer,
                            "Origin" to "https://fenixplay.xyz",
                            "Accept" to "*/*"
                        ),
                        cacheTime = 0
                    ).body.bytes()

                    if (bytes.isEmpty()) {
                        subtitleCallback.invoke(newSubtitleFile(lang, url))
                        return@forEach
                    }

                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                    subtitleCache[key] = bytes
                    val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                    subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                } catch (e: Exception) {
                    Log.e(TAG, "processFenixEpisodeSync track sub error: ${e.message}")
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                }
            }
        }
    }

    private fun fetchEpisodePosterBytes(episodeId: Int): ByteArray? {
        Log.e(TAG, "fetchEpisodePosterBytes: epId=$episodeId")
        val videoUrl = fetchEpisodeVideoUrlSync(episodeId) ?: run {
            Log.e(TAG, "fetchEpisodePosterBytes: no videoUrl")
            return null
        }

        if (videoUrl.contains("fenixplay.xyz")) {
            Log.e(TAG, "fetchEpisodePosterBytes: fenix branch")
            val playlist = fetchFenixPlaylistSync(videoUrl)
            val episode = playlist?.let { pickFenixEpisode(it, videoUrl) }
            val thumb = episode?.thumbnail_url
            Log.e(TAG, "fetchEpisodePosterBytes: fenix thumb=$thumb")
            if (!thumb.isNullOrBlank()) {
                val bytes = fetchPosterBytes(thumb)
                if (bytes.isNotEmpty()) return bytes
            }
            return null
        }

        val rawPosterUrl = when {
            videoUrl.contains("ashdi.vip") -> getAshdiRawPosterUrl(videoUrl)
            videoUrl.contains("moonanime.net") -> getMoonRawPosterUrl(videoUrl)
            else -> null
        } ?: run {
            Log.e(TAG, "fetchEpisodePosterBytes: no rawPosterUrl for $videoUrl")
            return null
        }
        Log.e(TAG, "fetchEpisodePosterBytes: rawPosterUrl=$rawPosterUrl")

        return fetchPosterBytes(rawPosterUrl).takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        Log.e(TAG, "resolveMoonContent: url=$contentUrl")
        return try {
            val cookieResponse = app.get(
                "https://moonanime.net/",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9",
                ),
                cacheTime = 0
            )

            val cookies = cookieResponse.cookies

            val response = app.get(
                contentUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://moonanime.net/",
                    "Origin" to "https://moonanime.net",
                    "Sec-Fetch-Site" to "same-site",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty",
                ),
                cookies = cookies,
                allowRedirects = false,
                cacheTime = 0
            )

            val location = response.headers["location"] ?: response.headers["Location"]
            Log.e(TAG, "resolveMoonContent: status=${response.code} location=${location?.take(100)}")

            if (!location.isNullOrEmpty()) {
                location
            } else {
                val body = response.text.trim()
                if (body.startsWith("http")) body else {
                    Log.e(TAG, "resolveMoonContent: no location/url in body=${body.take(100)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveMoonContent error: ${e.message}")
            null
        }
    }

    private suspend fun getEpisodeVideoUrl(episodeId: Int): String? {
        return getEpisodeVideoInfo(episodeId)?.first
    }

    private suspend fun getEpisodeVideoInfo(episodeId: Int): Pair<String?, String?>? {
        val url = "$mainUrl/api/player/$episodeId/episode"
        val json = fetchJsonOrNull(url) ?: run {
            Log.e(TAG, "getEpisodeVideoInfo: no json ep=$episodeId")
            return null
        }

        return try {
            val response = AppUtils.parseJson<EpisodeVideoResponse>(json)
            val videoUrl = response.videoUrl ?: response.fileUrl
            val track = response.selection?.track
            Log.e(TAG, "getEpisodeVideoInfo: ep=$episodeId videoUrl=${videoUrl?.take(80)} track=$track")
            videoUrl to track
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodeVideoInfo parse error: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.e(TAG, "getMainPage: page=$page name=${request.name} data=${request.data}")
        if (request.name == "Популярні аніме") {
            val currentDate = java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.ENGLISH
            ).format(java.util.Date())

            val skip = (page - 1) * 24
            val url = "${request.data}$currentDate?skip=$skip&take=24&sort=views-desc"
            Log.e(TAG, "getMainPage popular: url=$url")

            val jsonText = fetchJsonOrNull(url)
                ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = try {
                AppUtils.parseJson<PopularAnimeResponse>(jsonText)
            } catch (e: Exception) {
                Log.e(TAG, "getMainPage popular parse error: ${e.message}")
                null
            }

            val items = parsedJSON?.results ?: emptyList()
            Log.e(TAG, "getMainPage popular: items=${items.size}")

            if (items.isEmpty()) {
                return newHomePageResponse(request.name, emptyList())
            }

            return newHomePageResponse(
                request.name,
                items.map { item ->
                    newAnimeSearchResponse(item.anime.titleUa, "anime/${item.anime.id}", TvType.Anime) {
                        this.posterUrl = item.anime.image?.preview?.let { posterApi.format(it) }
                    }
                },
                hasNext = items.size >= 24
            )
        }

        if (request.data.contains("seasons") && page != 1) {
            Log.e(TAG, "getMainPage: seasons page!=1 -> empty")
            return newHomePageResponse(emptyList())
        }

        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page) else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = try {
                AppUtils.parseJson<SafeNewAnimeModel>(jsonText)
            } catch (e: Exception) {
                Log.e(TAG, "getMainPage newanime parse error: ${e.message}")
                return newHomePageResponse(request.name, emptyList())
            }
            Log.e(TAG, "getMainPage newanime: results=${parsedJSON.results.size}")

            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = try {
                AppUtils.parseJson<List<LocalResult>>(jsonText)
            } catch (e: Exception) {
                Log.e(TAG, "getMainPage seasons parse error: ${e.message}")
                return newHomePageResponse(request.name, emptyList())
            }
            Log.e(TAG, "getMainPage seasons: results=${parsedJSON.size}")

            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        Log.e(TAG, "search: query=$query")
        val id = query.toIntOrNull()

        if (id != null) {
            val animeById = searchById(id)
            if (animeById != null) return listOf(animeById)
        }

        val url = "$searchApi$query"
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()

        return try {
            val response = AppUtils.parseJson<SafeSearchApiResponse>(jsonText)
            Log.e(TAG, "search: results=${response.results.size}")

            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search parse error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        Log.e(TAG, "searchById: id=$id")
        val slug = resolveAnimeSlug(id)
        val realUrl = "$apiUrl/$slug"
        val jsonText = fetchJsonOrNull(realUrl) ?: return null

        val anime = try {
            AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "searchById parse error: ${e.message}")
            return null
        }

        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = anime.image?.preview?.let { posterApi.format(it) }
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.e(TAG, "load: url=$url")
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: run {
                Log.e(TAG, "load: invalid anime ID in url=$url")
                throw Exception("Invalid anime ID in URL: $url")
            }

        val slug = resolveAnimeSlug(animeId)
        val realApiUrl = "$apiUrl/$slug"
        Log.e(TAG, "load: animeId=$animeId slug=$slug realApiUrl=$realApiUrl")

        val jsonText = fetchJsonOrNull(realApiUrl)
            ?: run {
                Log.e(TAG, "load: failed to load anime $animeId (json null)")
                throw Exception("Failed to load anime $animeId")
            }

        val animeJSON = AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
            ?: run {
                Log.e(TAG, "load: parse returned null for $animeId")
                throw Exception("Failed to parse anime $animeId")
            }
        Log.e(TAG, "load: title=${animeJSON.titleUa} type=${animeJSON.type} status=${animeJSON.status}")

        val posterUrl = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres = animeJSON.genres?.map { it.nameUa } ?: emptyList()

        val showStatus = if (animeJSON.status?.contains("ongoing") == true) {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }

        val tvType = with(animeJSON.type ?: "") {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        Log.e(TAG, "load: tvType=$tvType")

        val episodeInfoMap = fetchEpisodeInfoMap(slug)
        Log.e(TAG, "load: episodeInfoMap count=${episodeInfoMap.size}")

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsUrl = "$mainUrl/api/player/$animeId/translations"
        Log.e(TAG, "load: fetching translations url=$translationsUrl")
        val translationsJson = fetchJsonOrNull(translationsUrl)
        Log.e(TAG, "load: translationsJson=${if (translationsJson == null) "NULL" else "len=${translationsJson.length}"}")

        if (translationsJson != null) {
            try {
                val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
                Log.e(TAG, "load: translations count=${translations.size} for animeId=$animeId")
                translations.forEach {
                    Log.e(TAG, "load:   translation=${it.translation.id}/${it.translation.name} players=${it.player.map { p -> p.id to p.name }}")
                }

                val episodeSources = java.util.concurrent.ConcurrentHashMap<Int, MutableList<EpisodeSource>>()
                val seenSourceKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                val poolSize = (translations.sumOf { it.player.size } * 2).coerceIn(4, 24)
                Log.e(TAG, "load: episode collection poolSize=$poolSize")
                val episodeExecutor = java.util.concurrent.Executors.newFixedThreadPool(poolSize)
                val futures = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.Future<CollectedEpisodes>>()

                try {
                    for (translation in translations) {
                        val translationId = translation.translation.id

                        for (player in translation.player) {
                            val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&playerId=${player.id}&translationId=$translationId"

                            for (includeAlt in listOf("true", "false")) {
                                val tName = translation.translation.name
                                val pName = player.name
                                Log.e(TAG, "load: submit task t=$tName p=$pName alt=$includeAlt baseUrl=$baseUrl")

                                futures.add(episodeExecutor.submit(java.util.concurrent.Callable<CollectedEpisodes> {
                                    val collected = mutableListOf<FundubEpisode>()
                                    val seenIDs = mutableSetOf<Int>()

                                    val epJsonMinus1 = fetchJsonOrNullSync("$baseUrl&skip=-1&includeAlternative=$includeAlt")
                                    if (epJsonMinus1 != null) {
                                        try {
                                            val eps = AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes
                                            Log.e(TAG, "load: [$tName/$pName/alt=$includeAlt] skip=-1 got ${eps.size} eps")
                                            eps.filter { it.episode <= 0 && seenIDs.add(it.id) }.let { collected.addAll(it) }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "load: [$tName/$pName/alt=$includeAlt] skip=-1 parse err: ${e.message}")
                                        }
                                    } else {
                                        Log.e(TAG, "load: [$tName/$pName/alt=$includeAlt] skip=-1 json NULL")
                                    }

                                    var skip = 0
                                    while (true) {
                                        val epJson = fetchJsonOrNullSync("$baseUrl&skip=$skip&includeAlternative=$includeAlt") ?: break
                                        try {
                                            val eps = AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                                            Log.e(TAG, "load: [$tName/$pName/alt=$includeAlt] skip=$skip got ${eps?.size} eps")
                                            if (eps.isNullOrEmpty()) break
                                            collected.addAll(eps.filter { seenIDs.add(it.id) })
                                            if (eps.size < 1000) break
                                        } catch (e: Exception) {
                                            Log.e(TAG, "load: [$tName/$pName/alt=$includeAlt] skip=$skip parse err: ${e.message}")
                                            break
                                        }
                                        skip += 1000
                                    }
                                    Log.e(TAG, "load: task done t=$tName p=$pName alt=$includeAlt total=${collected.size}")
                                    CollectedEpisodes(tName, pName, collected)
                                }))
                            }
                        }
                    }

                    for (future in futures) {
                        try {
                            val result = future.get(15, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                            Log.e(TAG, "load: result t=${result.translationName} p=${result.playerName} eps=${result.episodes.size}")
                            for (ep in result.episodes) {
                                val key = "${result.translationName}|${result.playerName}|${ep.id}"
                                if (!seenSourceKeys.add(key)) continue

                                val sources = episodeSources.getOrPut(ep.episode) { mutableListOf() }
                                sources.add(
                                    EpisodeSource(
                                        translationName = result.translationName,
                                        playerName = result.playerName,
                                        episodeId = ep.id,
                                        episodeNum = ep.episode,
                                        apiPoster = ep.poster
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "load: future.get error: ${e.message}")
                        }
                    }
                } finally {
                    episodeExecutor.shutdown()
                }

                Log.e(TAG, "load: episodeSources built epNums=${episodeSources.keys.sorted()}")

                ensurePosterProxy()

                val posterPrefetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                    (episodeSources.size / 2).coerceIn(4, 16)
                )

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach
                    Log.e(TAG, "load: building episode ep=$epNum sources=${sources.size}")

                    var epPoster: String? = sources.firstNotNullOfOrNull { s ->
                        s.apiPoster?.takeIf { it.isNotEmpty() && !it.contains("mooncdn.") }
                    }

                    if (epPoster == null) {
                        val cachedPoster = episodePosterCache["$animeId:$epNum"]
                        if (cachedPoster != null && !cachedPoster.contains("mooncdn.")) {
                            epPoster = cachedPoster
                            Log.e(TAG, "load: using cached poster ep=$epNum")
                        } else {
                            val episodeId = sources.firstOrNull()?.episodeId
                            if (episodeId != null) {
                                val key = java.util.UUID.randomUUID().toString().replace("-", "")
                                posterFetchTasks[key] = PosterFetchTask(episodeId, animeId)
                                epPoster = "http://127.0.0.1:$posterProxyPort/poster?$key"
                                Log.e(TAG, "load: queued poster fetch ep=$epNum epId=$episodeId key=$key")

                                val prefetchKey = key
                                val prefetchEpisodeId = episodeId
                                val prefetchAnimeId = animeId
                                posterPrefetchExecutor.submit {
                                    if (!posterCache.containsKey(prefetchKey)) {
                                        val bytes = fetchEpisodePosterBytes(prefetchEpisodeId)
                                        if (bytes != null && bytes.isNotEmpty()) {
                                            posterCache[prefetchKey] = bytes
                                            episodePosterCache["$prefetchAnimeId:$prefetchEpisodeId"] =
                                                "http://127.0.0.1:$posterProxyPort/poster?$prefetchKey"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val dataJson = buildEpisodeDataJson(sources)
                    Log.e(TAG, "load: ep=$epNum dataJson=${dataJson.take(200)}")

                    val episodeName = episodeInfoMap[epNum]?.takeIf { it.isNotBlank() }

                    episodes.add(
                        newEpisode(dataJson).apply {
                            this.name = episodeName
                            this.episode = epNum
                            this.posterUrl = epPoster
                        }
                    )
                }

                posterPrefetchExecutor.shutdown()
                Log.e(TAG, "load: total episodes=${episodes.size}")

            } catch (e: Exception) {
                Log.e(TAG, "load: translations processing error: ${e.message}")
            }
        } else {
            Log.e(TAG, "load: translationsJson null for animeId=$animeId")
        }

        val franchise = buildFranchise(animeId)
        Log.e(TAG, "load: franchise size=${franchise.size}")

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterUrl
                this.engName = animeJSON.titleEn
                this.tags = genres
                this.plot = animeJSON.description

                addTrailer(animeJSON.trailer)

                this.showStatus = showStatus
                this.duration = animeJSON.episodeTime?.let { extractIntFromString(it) }
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)

                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId)

                this.recommendations = franchise
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) {
                posterUrl
            } else {
                animeJSON.backgroundImage
            }

            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, animeId.toString()) {
                this.posterUrl = posterUrl
                this.tags = genres
                this.plot = animeJSON.description

                addTrailer(animeJSON.trailer)

                this.duration = animeJSON.episodeTime?.let { extractIntFromString(it) }
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)

                addMalId(animeJSON.malId)

                this.recommendations = franchise
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks: data=${data.take(200)} isCasting=$isCasting")
        val animeId = data.trim().toIntOrNull()

        if (animeId != null) {
            Log.e(TAG, "loadLinks: movie path animeId=$animeId")
            return loadMovieLinks(animeId, subtitleCallback, callback)
        }

        val sources: List<EpisodeSource> = try {
            AppUtils.parseJson<List<EpisodeSource>>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: parse data error: ${e.message}")
            return false
        }
        Log.e(TAG, "loadLinks: sources=${sources.size}")

        if (sources.isEmpty()) return false

        var foundAny = false

        val seenLinks = mutableSetOf<String>()
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val cleanUrl = link.url.substringBefore("?")
            val key = "${link.name}|${link.quality}|$cleanUrl"
            if (seenLinks.add(key)) {
                Log.e(TAG, "loadLinks: emitting link name=${link.name} q=${link.quality} url=${link.url.take(100)}")
                callback(link)
            } else {
                Log.e(TAG, "loadLinks: duplicate link skipped name=${link.name} q=${link.quality}")
            }
        }

        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.net/",
            "Origin" to "https://moonanime.net",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        for (source in sources) {
            val sourceName = "${source.translationName} (${source.playerName})"
            val isAshdi = source.playerName.contains("Ashdi", ignoreCase = true)
            val isFenix = source.playerName.contains("Fenix", ignoreCase = true)
            Log.e(TAG, "loadLinks: processing source=$sourceName epId=${source.episodeId} isAshdi=$isAshdi isFenix=$isFenix")

            val videoUrl = getEpisodeVideoUrl(source.episodeId)
            if (videoUrl.isNullOrEmpty()) {
                Log.e(TAG, "loadLinks: no videoUrl for epId=${source.episodeId}")
                continue
            }
            Log.e(TAG, "loadLinks: videoUrl=${videoUrl.take(120)}")

            try {
                if (isFenix && videoUrl.contains("fenixplay.xyz")) {
                    Log.e(TAG, "loadLinks: FENIX branch epId=${source.episodeId}")

                    if (lastFenixPlaylist == null) {
                        fetchFenixPlaylist(videoUrl)
                    }

                    val slug = lastFenixPlaylist?.slug
                    val playbackToken = lastFenixPlaylist?.playback?.token
                    val epNum = fenixExtractEpisodeNumber(videoUrl) ?: source.episodeNum
                    val edition = fenixExtractEdition(videoUrl)

                    Log.e(TAG, "loadLinks: FENIX slug=$slug epNum=$epNum edition=$edition playbackToken=${!playbackToken.isNullOrEmpty()}")

                    if (slug.isNullOrEmpty() || playbackToken.isNullOrEmpty() || epNum == null) {
                        Log.e(TAG, "loadLinks: FENIX missing data, fallback to old method")
                    } else {
                        val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                        val ep = details?.episode

                        if (ep == null) {
                            Log.e(TAG, "loadLinks: FENIX no episode details")
                        } else {
                            Log.e(TAG, "loadLinks: FENIX tracks=${ep.tracks?.size} subs=${ep.subtitles?.size}")

                            val mediaToken = lastFenixMediaAccess?.token
                            val fenixReferer = "https://fenixplay.xyz/"
                            val fenixHeaders2 = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to fenixReferer,
                                "Origin" to "https://fenixplay.xyz",
                                "Accept" to "*/*",
                                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                            )

                            fun addToken(u: String): String {
                                if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                val sep = if (u.contains("?")) "&" else "?"
                                return "$u${sep}token=$mediaToken"
                            }

                            ep.tracks?.forEach { track ->
                                val hlsUrl = track.hls_url
                                if (hlsUrl.isNullOrEmpty()) return@forEach
                                val fullUrl = addToken(hlsUrl)
                                val label = track.label ?: track.language ?: "Fenix"
                                val trackName = "$label · Fenix"
                                Log.e(TAG, "loadLinks: FENIX emit track=$trackName")
                                safeCallback(ExtractorLink(
                                    source = name,
                                    name = trackName,
                                    url = fullUrl,
                                    referer = fenixReferer,
                                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                    headers = fenixHeaders2
                                ))
                            }

                            if (ep.tracks.isNullOrEmpty() && !ep.hls_master_url.isNullOrEmpty()) {
                                val fullUrl = addToken(ep.hls_master_url)
                                Log.e(TAG, "loadLinks: FENIX emit master fallback")
                                safeCallback(ExtractorLink(
                                    source = name,
                                    name = sourceName,
                                    url = fullUrl,
                                    referer = fenixReferer,
                                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                    headers = fenixHeaders2
                                ))
                            }

                            ep.subtitles?.forEach { sub ->
                                val subUrl = sub.url ?: return@forEach
                                val lang = sub.label ?: sub.language ?: "UA"
                                val fullSubUrl = addToken(subUrl)
                                try {
                                    val bytes = app.get(
                                        fullSubUrl,
                                        headers = fenixHeaders2,
                                        cacheTime = 0
                                    ).body.bytes()
                                    if (bytes.isEmpty()) {
                                        subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                        return@forEach
                                    }
                                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                                    subtitleCache[key] = bytes
                                    val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                                    subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                                } catch (e: Exception) {
                                    Log.e(TAG, "loadLinks: FENIX subtitle err: ${e.message}")
                                    subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                }
                            }

                            foundAny = true
                            continue
                        }
                    }

                    // Fallback — старий метод (якщо новий не спрацював)
                    val info = getEpisodeVideoInfo(source.episodeId)
                    val preferredTrack = info?.second
                    val playlist = fetchFenixPlaylist(videoUrl)
                    if (playlist != null) {
                        val episode = pickFenixEpisode(playlist, videoUrl)
                        if (episode != null) {
                            processFenixEpisodeSync(
                                episode,
                                videoUrl,
                                sourceName,
                                subtitleCallback,
                                safeCallback,
                                preferredTrack
                            )
                            foundAny = true
                        }
                    }
                    continue
                }

                if (isAshdi) {
                    if (videoUrl.contains("ashdi.vip")) {
                        Log.e(TAG, "loadLinks: ASHDI iframe")
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, safeCallback)
                        foundAny = true
                    } else if (videoUrl.contains(".m3u8")) {
                        Log.e(TAG, "loadLinks: ASHDI m3u8")
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = videoUrl,
                            referer = "https://ashdi.vip"
                        )
                        Log.e(TAG, "loadLinks: ASHDI m3u8 streams=${streams.size}")

                        val filtered = streams.dropLast(1)

                        if (filtered.isNotEmpty()) {
                            filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                        } else {
                            streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                        }

                        foundAny = true
                    }
                } else {
                    if (videoUrl.contains("moonanime.net")) {
                        if (videoUrl.contains("m3u8")) {
                            Log.e(TAG, "loadLinks: MOON m3u8")
                            val streams = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = videoUrl,
                                referer = "https://moonanime.net/",
                                headers = moonVideoHeaders
                            )
                            Log.e(TAG, "loadLinks: MOON m3u8 streams=${streams.size}")

                            val filtered = streams.dropLast(1)

                            if (filtered.isNotEmpty()) {
                                filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                            } else {
                                streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                            }

                            foundAny = true
                        } else {
                            Log.e(TAG, "loadLinks: MOON raw file")
                            val (rawFile, subUrl) = getMoonFile(videoUrl)
                            Log.e(TAG, "loadLinks: MOON raw=${rawFile.take(100)} subUrl=${subUrl?.take(100)}")

                            if (rawFile.isNotEmpty()) {
                                invokeSubtitles(subUrl, subtitleCallback)
                                processMoonRawFile(rawFile, sourceName, isMovie = false, safeCallback)
                                foundAny = true
                            } else {
                                Log.e(TAG, "loadLinks: MOON raw EMPTY")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: source error: ${e.message}")
            }
        }

        Log.e(TAG, "loadLinks: done foundAny=$foundAny")
        return foundAny
    }

    private suspend fun loadMovieLinks(
        animeId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadMovieLinks: animeId=$animeId")
        val translationsUrl = "$mainUrl/api/player/$animeId/translations"
        val translationsJson = fetchJsonOrNull(translationsUrl) ?: run {
            Log.e(TAG, "loadMovieLinks: translations null for $animeId")
            return false
        }
        Log.e(TAG, "loadMovieLinks: translationsJson len=${translationsJson.length}")

        var foundAny = false

        val seenLinks = mutableSetOf<String>()
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val cleanUrl = link.url.substringBefore("?")
            val key = "${link.name}|${link.quality}|$cleanUrl"
            if (seenLinks.add(key)) {
                Log.e(TAG, "loadMovieLinks: emitting name=${link.name} q=${link.quality} url=${link.url.take(100)}")
                callback(link)
            }
        }

        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.net/",
            "Origin" to "https://moonanime.net",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        try {
            val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
            Log.e(TAG, "loadMovieLinks: translations=${translations.size}")

            for (translation in translations) {
                val translationId = translation.translation.id

                for (player in translation.player) {
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIDs = mutableSetOf<Int>()

                    val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&playerId=${player.id}&translationId=$translationId"
                    Log.e(TAG, "loadMovieLinks: t=${translation.translation.name} p=${player.name} baseUrl=$baseUrl")

                    for (includeAlt in listOf("true", "false")) {
                        val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1&includeAlternative=$includeAlt")

                        if (epJsonMinus1 != null) {
                            val eps = try {
                                AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes
                            } catch (e: Exception) {
                                Log.e(TAG, "loadMovieLinks: parse err(alt=$includeAlt): ${e.message}")
                                null
                            }
                            Log.e(TAG, "loadMovieLinks: skip=-1 alt=$includeAlt got=${eps?.size}")

                            eps?.filter { it.episode <= 0 && seenIDs.add(it.id) }?.let {
                                collected.addAll(it)
                            }
                        } else {
                            Log.e(TAG, "loadMovieLinks: skip=-1 alt=$includeAlt json NULL")
                        }

                        var skip = 0

                        while (true) {
                            val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip&includeAlternative=$includeAlt") ?: break

                            val eps = try {
                                AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                            } catch (e: Exception) {
                                Log.e(TAG, "loadMovieLinks: skip=$skip parse err: ${e.message}")
                                null
                            }
                            Log.e(TAG, "loadMovieLinks: skip=$skip alt=$includeAlt got=${eps?.size}")

                            if (eps.isNullOrEmpty()) break

                            val newEps = eps.filter { seenIDs.add(it.id) }
                            collected.addAll(newEps)

                            if (eps.size < 1000) break

                            skip += 1000
                        }
                    }

                    val sourceName = "${translation.translation.name} (${player.name})"
                    val isAshdi = player.name.contains("Ashdi", ignoreCase = true)
                    val isFenix = player.name.contains("Fenix", ignoreCase = true)
                    Log.e(TAG, "loadMovieLinks: collected=${collected.size} isAshdi=$isAshdi isFenix=$isFenix")

                    if (collected.isEmpty()) {
                        val directUrl = "$mainUrl/api/player/${player.id}/${translation.translation.id}"
                        Log.e(TAG, "loadMovieLinks: no episodes, trying direct: $directUrl")
                        val directJson = fetchJsonOrNull(directUrl)

                        if (directJson != null) {
                            try {
                                val directSource = AppUtils.parseJson<DirectPlayerResponse>(directJson)
                                val videoUrl = directSource.videoUrl
                                val fileUrl = directSource.fileUrl
                                Log.e(TAG, "loadMovieLinks: direct videoUrl=${videoUrl?.take(100)} fileUrl=${fileUrl?.take(100)}")

                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    val finalUrl = videoUrl ?: fileUrl!!

                                    if (isFenix && finalUrl.contains("fenixplay.xyz")) {
                                        Log.e(TAG, "loadMovieLinks: FENIX direct url=${finalUrl.take(120)}")
                                        
                                        if (lastFenixPlaylist == null) {
                                            fetchFenixPlaylist(finalUrl)
                                        }

                                        val slug = lastFenixPlaylist?.slug
                                        val playbackToken = lastFenixPlaylist?.playback?.token
                                        val epNum = fenixExtractEpisodeNumber(finalUrl) ?: 1
                                        val edition = fenixExtractEdition(finalUrl)

                                        Log.e(TAG, "loadMovieLinks: FENIX direct slug=$slug epNum=$epNum edition=$edition playbackToken=${!playbackToken.isNullOrEmpty()}")

                                        if (slug.isNullOrEmpty() || playbackToken.isNullOrEmpty() || epNum == null) {
                                            Log.e(TAG, "loadMovieLinks: FENIX direct missing data, fallback to old method")
                                        } else {
                                            val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                                            val ep = details?.episode

                                            if (ep == null) {
                                                Log.e(TAG, "loadMovieLinks: FENIX direct no episode details")
                                            } else {
                                                Log.e(TAG, "loadMovieLinks: FENIX direct tracks=${ep.tracks?.size} subs=${ep.subtitles?.size}")

                                                val mediaToken = lastFenixMediaAccess?.token
                                                val fenixReferer = "https://fenixplay.xyz/"
                                                val fenixHeaders2 = mapOf(
                                                    "User-Agent" to userAgent,
                                                    "Referer" to fenixReferer,
                                                    "Origin" to "https://fenixplay.xyz",
                                                    "Accept" to "*/*",
                                                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                                                )

                                                fun addToken(u: String): String {
                                                    if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                                    val sep = if (u.contains("?")) "&" else "?"
                                                    return "$u${sep}token=$mediaToken"
                                                }

                                                ep.tracks?.forEach { track ->
                                                    val hlsUrl = track.hls_url
                                                    if (hlsUrl.isNullOrEmpty()) return@forEach
                                                    val fullUrl = addToken(hlsUrl)
                                                    val label = track.label ?: track.language ?: "Fenix"
                                                    val trackName = "$label · Fenix"
                                                    Log.e(TAG, "loadMovieLinks: FENIX direct emit track=$trackName")
                                                    safeCallback(ExtractorLink(
                                                        source = name,
                                                        name = trackName,
                                                        url = fullUrl,
                                                        referer = fenixReferer,
                                                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                        headers = fenixHeaders2
                                                    ))
                                                }

                                                if (ep.tracks.isNullOrEmpty() && !ep.hls_master_url.isNullOrEmpty()) {
                                                    val fullUrl = addToken(ep.hls_master_url)
                                                    Log.e(TAG, "loadMovieLinks: FENIX direct emit master fallback")
                                                    safeCallback(ExtractorLink(
                                                        source = name,
                                                        name = sourceName,
                                                        url = fullUrl,
                                                        referer = fenixReferer,
                                                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                        headers = fenixHeaders2
                                                    ))
                                                }

                                                ep.subtitles?.forEach { sub ->
                                                    val subUrl = sub.url ?: return@forEach
                                                    val lang = sub.label ?: sub.language ?: "UA"
                                                    val fullSubUrl = addToken(subUrl)
                                                    try {
                                                        val bytes = app.get(
                                                            fullSubUrl,
                                                            headers = fenixHeaders2,
                                                            cacheTime = 0
                                                        ).body.bytes()
                                                        if (bytes.isEmpty()) {
                                                            subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                                            return@forEach
                                                        }
                                                        val key = java.util.UUID.randomUUID().toString().replace("-", "")
                                                        subtitleCache[key] = bytes
                                                        val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                                                        subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "loadMovieLinks: FENIX direct subtitle err: ${e.message}")
                                                        subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                                    }
                                                }

                                                foundAny = true
                                                continue
                                            }
                                        }

                                        // Fallback
                                        val preferredTrack = directSource.selection?.track
                                        val playlist = fetchFenixPlaylist(finalUrl)
                                        if (playlist != null) {
                                            val episode = pickFenixEpisode(playlist, finalUrl)
                                            if (episode != null) {
                                                processFenixEpisodeSync(
                                                    episode,
                                                    finalUrl,
                                                    sourceName,
                                                    subtitleCallback,
                                                    safeCallback,
                                                    preferredTrack
                                                )
                                                foundAny = true
                                            }
                                        }
                                        continue
                                    }

                                    if (isAshdi) {
                                        if (finalUrl.contains("ashdi.vip")) {
                                            Log.e(TAG, "loadMovieLinks: ASHDI iframe direct")
                                            processAshdiIframe(finalUrl, sourceName, isMovie = true, safeCallback)
                                            foundAny = true
                                        } else if (finalUrl.contains(".m3u8")) {
                                            Log.e(TAG, "loadMovieLinks: ASHDI m3u8 direct")
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = finalUrl,
                                                referer = "https://ashdi.vip"
                                            )
                                            Log.e(TAG, "loadMovieLinks: ASHDI streams=${streams.size}")
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) {
                                                filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                            } else {
                                                streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                            }
                                            foundAny = true
                                        }
                                    } else {
                                        if (finalUrl.contains("moonanime.net")) {
                                            if (finalUrl.contains("m3u8")) {
                                                Log.e(TAG, "loadMovieLinks: MOON m3u8 direct")
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = finalUrl,
                                                    referer = "https://moonanime.net/",
                                                    headers = moonVideoHeaders
                                                )
                                                Log.e(TAG, "loadMovieLinks: MOON streams=${streams.size}")
                                                val filtered = streams.dropLast(1)
                                                if (filtered.isNotEmpty()) {
                                                    filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                                } else {
                                                    streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                                }
                                                foundAny = true
                                            } else {
                                                Log.e(TAG, "loadMovieLinks: MOON raw direct")
                                                val (rawFile, subUrl) = getMoonFile(finalUrl)
                                                Log.e(TAG, "loadMovieLinks: MOON raw=${rawFile.take(100)}")
                                                if (rawFile.isNotEmpty()) {
                                                    invokeSubtitles(subUrl, subtitleCallback)
                                                    processMoonRawFile(rawFile, sourceName, isMovie = true, safeCallback)
                                                    foundAny = true
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "loadMovieLinks: direct parse error: ${e.message}")
                            }
                        }
                        continue
                    }

                    for (ep in collected) {
                        Log.e(TAG, "loadMovieLinks: processing ep id=${ep.id} num=${ep.episode}")
                        val videoUrl = getEpisodeVideoUrl(ep.id)

                        if (videoUrl.isNullOrEmpty()) {
                            Log.e(TAG, "loadMovieLinks: no videoUrl ep=${ep.id}")
                            continue
                        }
                        Log.e(TAG, "loadMovieLinks: ep=${ep.id} videoUrl=${videoUrl.take(120)}")

                        try {
                            if (isFenix && videoUrl.contains("fenixplay.xyz")) {
                                Log.e(TAG, "loadMovieLinks: FENIX ep branch")
                                
                                if (lastFenixPlaylist == null) {
                                    fetchFenixPlaylist(videoUrl)
                                }

                                val slug = lastFenixPlaylist?.slug
                                val playbackToken = lastFenixPlaylist?.playback?.token
                                val epNum = fenixExtractEpisodeNumber(videoUrl) ?: ep.episode
                                val edition = fenixExtractEdition(videoUrl)

                                Log.e(TAG, "loadMovieLinks: FENIX ep slug=$slug epNum=$epNum edition=$edition playbackToken=${!playbackToken.isNullOrEmpty()}")

                                if (slug.isNullOrEmpty() || playbackToken.isNullOrEmpty() || epNum == null) {
                                    Log.e(TAG, "loadMovieLinks: FENIX ep missing data, fallback to old method")
                                } else {
                                    val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                                    val epDetails = details?.episode

                                    if (epDetails == null) {
                                        Log.e(TAG, "loadMovieLinks: FENIX ep no episode details")
                                    } else {
                                        Log.e(TAG, "loadMovieLinks: FENIX ep tracks=${epDetails.tracks?.size} subs=${epDetails.subtitles?.size}")

                                        val mediaToken = lastFenixMediaAccess?.token
                                        val fenixReferer = "https://fenixplay.xyz/"
                                        val fenixHeaders2 = mapOf(
                                            "User-Agent" to userAgent,
                                            "Referer" to fenixReferer,
                                            "Origin" to "https://fenixplay.xyz",
                                            "Accept" to "*/*",
                                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                                        )

                                        fun addToken(u: String): String {
                                            if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                            val sep = if (u.contains("?")) "&" else "?"
                                            return "$u${sep}token=$mediaToken"
                                        }

                                        epDetails.tracks?.forEach { track ->
                                            val hlsUrl = track.hls_url
                                            if (hlsUrl.isNullOrEmpty()) return@forEach
                                            val fullUrl = addToken(hlsUrl)
                                            val label = track.label ?: track.language ?: "Fenix"
                                            val trackName = "$label · Fenix"
                                            Log.e(TAG, "loadMovieLinks: FENIX ep emit track=$trackName")
                                            safeCallback(ExtractorLink(
                                                source = name,
                                                name = trackName,
                                                url = fullUrl,
                                                referer = fenixReferer,
                                                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                headers = fenixHeaders2
                                            ))
                                        }

                                        if (epDetails.tracks.isNullOrEmpty() && !epDetails.hls_master_url.isNullOrEmpty()) {
                                            val fullUrl = addToken(epDetails.hls_master_url)
                                            Log.e(TAG, "loadMovieLinks: FENIX ep emit master fallback")
                                            safeCallback(ExtractorLink(
                                                source = name,
                                                name = sourceName,
                                                url = fullUrl,
                                                referer = fenixReferer,
                                                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                headers = fenixHeaders2
                                            ))
                                        }

                                        epDetails.subtitles?.forEach { sub ->
                                            val subUrl = sub.url ?: return@forEach
                                            val lang = sub.label ?: sub.language ?: "UA"
                                            val fullSubUrl = addToken(subUrl)
                                            try {
                                                val bytes = app.get(
                                                    fullSubUrl,
                                                    headers = fenixHeaders2,
                                                    cacheTime = 0
                                                ).body.bytes()
                                                if (bytes.isEmpty()) {
                                                    subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                                    return@forEach
                                                }
                                                val key = java.util.UUID.randomUUID().toString().replace("-", "")
                                                subtitleCache[key] = bytes
                                                val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                                                subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                                            } catch (e: Exception) {
                                                Log.e(TAG, "loadMovieLinks: FENIX ep subtitle err: ${e.message}")
                                                subtitleCallback.invoke(newSubtitleFile(lang, fullSubUrl))
                                            }
                                        }

                                        foundAny = true
                                        continue
                                    }
                                }

                                // Fallback
                                val info = getEpisodeVideoInfo(ep.id)
                                val preferredTrack = info?.second
                                val playlist = fetchFenixPlaylist(videoUrl)
                                if (playlist != null) {
                                    val episode = pickFenixEpisode(playlist, videoUrl)
                                    if (episode != null) {
                                        processFenixEpisodeSync(
                                            episode,
                                            videoUrl,
                                            sourceName,
                                            subtitleCallback,
                                            safeCallback,
                                            preferredTrack
                                        )
                                        foundAny = true
                                    }
                                }
                                continue
                            }

                            if (isAshdi) {
                                if (videoUrl.contains("ashdi.vip")) {
                                    Log.e(TAG, "loadMovieLinks: ASHDI iframe ep")
                                    processAshdiIframe(videoUrl, sourceName, isMovie = true, safeCallback)
                                    foundAny = true
                                } else if (videoUrl.contains(".m3u8")) {
                                    Log.e(TAG, "loadMovieLinks: ASHDI m3u8 ep")
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = videoUrl,
                                        referer = "https://ashdi.vip"
                                    )
                                    Log.e(TAG, "loadMovieLinks: ASHDI streams=${streams.size}")
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) {
                                        filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                    } else {
                                        streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                    }
                                    foundAny = true
                                }
                            } else {
                                if (videoUrl.contains("moonanime.net")) {
                                    if (videoUrl.contains("m3u8")) {
                                        Log.e(TAG, "loadMovieLinks: MOON m3u8 ep")
                                        val streams = M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = videoUrl,
                                            referer = "https://moonanime.net/",
                                            headers = moonVideoHeaders
                                        )
                                        Log.e(TAG, "loadMovieLinks: MOON streams=${streams.size}")
                                        val filtered = streams.dropLast(1)
                                        if (filtered.isNotEmpty()) {
                                            filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                        } else {
                                            streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                        }
                                        foundAny = true
                                    } else {
                                        Log.e(TAG, "loadMovieLinks: MOON raw ep")
                                        val (rawFile, subUrl) = getMoonFile(videoUrl)
                                        Log.e(TAG, "loadMovieLinks: MOON raw=${rawFile.take(100)}")
                                        if (rawFile.isNotEmpty()) {
                                            invokeSubtitles(subUrl, subtitleCallback)
                                            processMoonRawFile(rawFile, sourceName, isMovie = true, safeCallback)
                                            foundAny = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadMovieLinks: ep error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadMovieLinks: outer error: ${e.message}")
        }

        Log.e(TAG, "loadMovieLinks: done foundAny=$foundAny")
        return foundAny
    }

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "processMoonRawFile: source=$sourceName isMovie=$isMovie raw=${rawFile.take(150)}")

        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.net/",
            "Origin" to "https://moonanime.net",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
            val all = qualityRegex.findAll(rawFile).toList()
            Log.e(TAG, "processMoonRawFile: quality entries=${all.size}")

            all.forEach { match ->
                val qualityStr = match.groupValues[1]
                val qUrl = match.groupValues[2]
                Log.e(TAG, "processMoonRawFile: quality=$qualityStr url=${qUrl.take(100)}")

                val qualityInt = qualityStr.replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value

                when {
                    qUrl.contains(".m3u8") -> {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = qUrl,
                            referer = "https://moonanime.net/",
                            headers = moonVideoHeaders
                        )
                        Log.e(TAG, "processMoonRawFile: m3u8 streams=${streams.size}")

                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams

                        finalStreams.forEach {
                            callback(fixMovieExtractorLink(it, sourceName))
                        }
                    }

                    qUrl.contains("s.moonanime.net") || qUrl.contains("moonanime.net/content") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        Log.e(TAG, "processMoonRawFile: resolved=$finalUrl")

                        if (!finalUrl.isNullOrEmpty()) {
                            val link = ExtractorLink(
                                source = name,
                                name = sourceName,
                                url = finalUrl,
                                referer = "https://moonanime.net/",
                                quality = qualityInt,
                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                headers = moonVideoHeaders
                            )

                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }

                    else -> {
                        val link = ExtractorLink(
                            source = name,
                            name = sourceName,
                            url = qUrl,
                            referer = "https://moonanime.net/",
                            quality = qualityInt,
                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                            headers = moonVideoHeaders
                        )

                        callback(fixMovieExtractorLink(link, sourceName))
                    }
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            Log.e(TAG, "processMoonRawFile: single m3u8")
            val streams = M3u8Helper.generateM3u8(
                source = sourceName,
                streamUrl = rawFile,
                referer = "https://moonanime.net/",
                headers = moonVideoHeaders
            )
            Log.e(TAG, "processMoonRawFile: streams=${streams.size}")

            val filtered = streams.dropLast(1)
            val finalStreams = if (filtered.isNotEmpty()) filtered else streams

            finalStreams.forEach {
                callback(fixMovieExtractorLink(it, sourceName))
            }
        } else if (rawFile.contains("s.moonanime.net") || rawFile.contains("moonanime.net/content")) {
            val finalUrl = resolveMoonContent(rawFile)
            Log.e(TAG, "processMoonRawFile: resolved single=$finalUrl")

            if (!finalUrl.isNullOrEmpty()) {
                val link = ExtractorLink(
                    source = name,
                    name = sourceName,
                    url = finalUrl,
                    referer = "https://moonanime.net/",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = moonVideoHeaders
                )

                callback(fixMovieExtractorLink(link, sourceName))
            }
        } else {
            Log.e(TAG, "processMoonRawFile: raw direct url")
            val link = ExtractorLink(
                source = name,
                name = sourceName,
                url = rawFile,
                referer = "https://moonanime.net/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders
            )

            callback(fixMovieExtractorLink(link, sourceName))
        }
    }

    private suspend fun processAshdiIframe(
        iframeUrl: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "processAshdiIframe: source=$sourceName isMovie=$isMovie iframe=$iframeUrl")
        try {
            val cleanUrl = iframeUrl
                .replace(Regex("""\?season=null\?"""), "?")
                .replace(Regex("""\?season=null$"""), "")

            val url = if (cleanUrl.contains("?")) {
                cleanUrl
            } else {
                "$cleanUrl?player=animeon.club"
            }
            Log.e(TAG, "processAshdiIframe: cleanUrl=$url")

            val html = app.get(
                url,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                cacheTime = 0
            ).text
            Log.e(TAG, "processAshdiIframe: html len=${html.length}")

            val fileIndex = html.indexOf("file:'")

            if (fileIndex != -1) {
                val urlStart = fileIndex + 6
                val urlEnd = html.indexOf('\'', urlStart)

                if (urlEnd != -1) {
                    val masterUrl = html.substring(urlStart, urlEnd)
                    Log.e(TAG, "processAshdiIframe: masterUrl=${masterUrl.take(120)}")

                    if (masterUrl.isNotEmpty() && masterUrl.endsWith(".m3u8")) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = masterUrl,
                            referer = "https://ashdi.vip/"
                        )
                        Log.e(TAG, "processAshdiIframe: streams=${streams.size}")

                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams

                        finalStreams.forEach { link ->
                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }
                }
            } else {
                Log.e(TAG, "processAshdiIframe: file:' NOT FOUND")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processAshdiIframe error: ${e.message}")
        }
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val cleanEncoded = encoded.replace("\\s".toRegex(), "")
            val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
            val decryptedBytes = ByteArray(decoded.size)

            for (i in decoded.indices) {
                decryptedBytes[i] = ((decoded[i].toInt() and 0xFF) xor key[i % key.length].code).toByte()
            }

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "moonDecrypt error: ${e.message}")
            ""
        }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)

            if (raw.size < 33) {
                Log.e(TAG, "moonOuterDecode: raw too small ${raw.size}")
                return ""
            }

            val state0 = raw[0].toInt() and 0xFF
            val key = raw.sliceArray(1 until 33)
            val data = raw.sliceArray(33 until raw.size)
            val result = ByteArray(data.size)

            var state = state0

            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF

                result[i] = (d xor k xor state).toByte()
                state = (d + k) and 0xFF
            }

            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "moonOuterDecode error: ${e.message}")
            ""
        }
    }

    private suspend fun getMoonFile(iframeUrl: String): Pair<String, String?> {
        Log.e(TAG, "getMoonFile: iframeUrl=$iframeUrl")
        val cleanUrl = if (iframeUrl.contains("player=")) {
            iframeUrl
        } else {
            "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"
        }

        val html = try {
            app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://animeon.club/",
                    "X-Requested-With" to "mark.via.gp",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-User" to "?1",
                    "Sec-Fetch-Dest" to "document",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "getMoonFile: html error: ${e.message}")
            ""
        }
        Log.e(TAG, "getMoonFile: html len=${html.length}")

        if (html.isNotEmpty()) {
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            var decodedJs = ""

            for (m in atobRegex.findAll(html)) {
                val d = moonOuterDecode(m.groupValues[1])

                if (d.contains("_0xd") || d.contains("file")) {
                    decodedJs = d
                    break
                }
            }
            Log.e(TAG, "getMoonFile: decodedJs len=${decodedJs.length} preview=${decodedJs.take(200)}")

            if (decodedJs.isNotEmpty()) {
                val keyRegex = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                val xorKey = keyRegex.find(decodedJs)?.groupValues?.get(1)
                Log.e(TAG, "getMoonFile: xorKey=$xorKey")

                var subtitleUrl: String? = null

                if (!xorKey.isNullOrEmpty()) {
                    val subtitleEncRegex = Regex("""subtitle\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                    val subtitleEncMatch = subtitleEncRegex.find(decodedJs)?.groupValues?.get(1)

                    if (!subtitleEncMatch.isNullOrEmpty()) {
                        val subtitleDecoded = moonDecrypt(subtitleEncMatch, xorKey)
                        Log.e(TAG, "getMoonFile: subtitleDecoded=${subtitleDecoded.take(200)}")
                        val subtitleEntries = mutableListOf<Pair<String, String>>()

                        val subtitleEntryRegex = Regex("""\[([^\]]+)\](https?://[^\[,]+)""")
                        val entryMatches = subtitleEntryRegex.findAll(subtitleDecoded).toList()

                        if (entryMatches.isNotEmpty()) {
                            entryMatches.forEach { m2 ->
                                subtitleEntries.add(
                                    Pair(
                                        m2.groupValues[1],
                                        m2.groupValues[2].trim(',', ' ')
                                    )
                                )
                            }
                        } else if (subtitleDecoded.startsWith("http")) {
                            subtitleEntries.add(Pair("UA", subtitleDecoded.trim()))
                        }

                        if (subtitleEntries.isNotEmpty()) {
                            subtitleUrl = subtitleEntries.joinToString("|||") { "${it.first}::${it.second}" }
                            Log.e(TAG, "getMoonFile: subtitleUrl=$subtitleUrl")
                        }
                    }

                    val encodedRegex = Regex("""_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                    val encMatches = encodedRegex.findAll(decodedJs).toList()
                    Log.e(TAG, "getMoonFile: encMatches=${encMatches.size}")

                    val allDecoded = mutableListOf<String>()

                    for (match in encMatches) {
                        val decoded = moonDecrypt(match.groupValues[1], xorKey)

                        if (decoded.isNotEmpty()) {
                            allDecoded.add(decoded)
                            Log.e(TAG, "getMoonFile: decoded variant=${decoded.take(120)}")
                        }
                    }

                    for (decoded in allDecoded) {
                        val isVideoOrPlaylist = decoded.contains(".m3u8") ||
                                decoded.contains(".mp4") ||
                                decoded.contains(".webm") ||
                                decoded.startsWith("[")

                        val isMoonDomain = decoded.contains("mooncdn") ||
                                decoded.contains("moonanime.net/content") ||
                                decoded.contains("s.moonanime.net")

                        val isStaticAsset = decoded.contains(
                            Regex("""\.(jpg|jpeg|png|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE)
                        )

                        if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                            Log.e(TAG, "getMoonFile: FOUND via decrypt -> ${decoded.take(150)}")
                            return Pair(decoded, subtitleUrl)
                        }
                    }
                }

                val contentUrlRegex = Regex("""(https?://s\.moonanime\.net/content/[^\s"'`]+)""")
                val contentMatch = contentUrlRegex.find(decodedJs)?.groupValues?.get(1)
                Log.e(TAG, "getMoonFile: contentMatch=$contentMatch")

                if (!contentMatch.isNullOrEmpty() && !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))) {
                    val resolved = resolveMoonContent(contentMatch)
                    Log.e(TAG, "getMoonFile: resolved contentMatch=$resolved")

                    if (!resolved.isNullOrEmpty()) {
                        return Pair(resolved, subtitleUrl)
                    }
                }
            } else {
                Log.e(TAG, "getMoonFile: decodedJs EMPTY")
            }
        }

        val hashRegex = Regex("""/iframe/([a-zA-Z0-9]+)/?""")
        val hash = hashRegex.find(cleanUrl)?.groupValues?.get(1)
        Log.e(TAG, "getMoonFile: hash fallback=$hash")

        if (!hash.isNullOrEmpty()) {
            val qualityResults = mutableListOf<String>()

            for (quality in listOf(1080, 720, 480, 360)) {
                val contentUrl = "https://s.moonanime.net/content/v/$hash/$quality/"
                val resolved = resolveMoonContent(contentUrl)
                Log.e(TAG, "getMoonFile: quality=$quality resolved=$resolved")

                if (!resolved.isNullOrEmpty()) {
                    qualityResults.add("[${quality}p]$resolved")
                }
            }

            if (qualityResults.isNotEmpty()) {
                val result = qualityResults.joinToString(".")
                Log.e(TAG, "getMoonFile: hash fallback result=${result.take(200)}")
                return Pair(result, null)
            }
        }

        Log.e(TAG, "getMoonFile: NOTHING FOUND")
        return Pair("", null)
    }

    private var subtitleProxyPort: Int = 0
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun ensureSubtitleProxy() {
        if (subtitleProxyPort != 0) return
        Log.e(TAG, "ensureSubtitleProxy: starting subtitle proxy")

        val serverSocket = java.net.ServerSocket(0)
        subtitleProxyPort = serverSocket.localPort
        Log.e(TAG, "ensureSubtitleProxy: port=$subtitleProxyPort")

        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()

                    Thread {
                        try {
                            val line = client.getInputStream().bufferedReader().readLine() ?: return@Thread
                            val key = line.substringAfter("?").substringBefore(" ")
                            Log.e(TAG, "subtitleProxy: key=$key")
                            val body = subtitleCache[key]
                            val out = client.getOutputStream()

                            if (body != null) {
                                out.write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/vtt; charset=utf-8\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray()
                                )
                                out.write(body)
                            } else {
                                Log.e(TAG, "subtitleProxy: 404 for key=$key")
                                out.write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                            }

                            out.flush()
                            client.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "subtitleProxy error: ${e.message}")
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                    Log.e(TAG, "subtitleProxy accept error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private suspend fun invokeSubtitles(subUrl: String?, subtitleCallback: (SubtitleFile) -> Unit) {
        if (subUrl == null) {
            return
        }
        Log.e(TAG, "invokeSubtitles: subUrl=$subUrl")

        ensureSubtitleProxy()

        subUrl.split("|||").forEach { entry ->
            val parts = entry.split("::", limit = 2)

            if (parts.size == 2) {
                val lang = parts[0]
                val url = parts[1]
                Log.e(TAG, "invokeSubtitles: lang=$lang url=${url.take(120)}")

                try {
                    val bytes = app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://moonanime.net/",
                            "Origin" to "https://moonanime.net",
                            "Accept" to "*/*",
                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                        ),
                        cacheTime = 0
                    ).body.bytes()
                    Log.e(TAG, "invokeSubtitles: bytes=${bytes.size}")

                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                    subtitleCache[key] = bytes

                    val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"

                    subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                } catch (e: Exception) {
                    Log.e(TAG, "invokeSubtitles error: ${e.message}")
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                }
            }
        }
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null

        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}
