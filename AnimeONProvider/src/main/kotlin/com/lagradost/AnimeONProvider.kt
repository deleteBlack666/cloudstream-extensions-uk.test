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

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

private val TAG = "AnimeON"
    
    // GA cookies — аналітичні, не сесійні, підходять для moonanime.art
    private val moonGaCookies =
        "_ga=GA1.1.1111259978.1785599959; " +
        "_ga_YLF10H4CS1=GS2.1.s1785759415\$o3\$g0\$t1785759415\$j60\$l0\$h0"

    // Interceptor для відео — додає хедери і cookie для moonanime.art та ashdi.vip
    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            Log.d("AnimeON:VideoInterceptor", "url=$url")
            val newRequest = when {
                url.contains("moonanime.art") || url.contains("s.moonanime.art") || url.contains("mooncdn") -> {
                    Log.d("AnimeON:VideoInterceptor", "Injecting moon headers+cookies for $url")
                    request.newBuilder()
                        .header("Referer",            "https://moonanime.art/")
                        .header("Origin",             "https://moonanime.art")
                        .header("User-Agent",         userAgent)
                        .header("Accept",             "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .header("Accept-Language",    "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Sec-Ch-Ua-Platform", "\"Android\"")
                        .header("Sec-Fetch-Site",     "same-site")
                        .header("Sec-Fetch-Mode",     "no-cors")
                        .header("Sec-Fetch-Dest",     "image")
                        .header("X-Requested-With",   "mark.via.gp")
                        .header("Cookie",             moonGaCookies)
                        .build()
                }
                url.contains("ashdi.vip") -> {
                    Log.d("AnimeON:VideoInterceptor", "Injecting ashdi headers for $url")
                    request.newBuilder()
                        .header("Referer",    "https://ashdi.vip/")
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

    private val apiUrl    = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

    private fun currentSeasonLabel(): String {
        val cal    = java.util.Calendar.getInstance()
        val month  = cal.get(java.util.Calendar.MONTH) + 1
        val season = when (month) {
            12, 1, 2 -> "зимового"
            3, 4, 5  -> "весняного"
            6, 7, 8  -> "літнього"
            else     -> "осіннього"
        }
        return "Аніме ${season} сезону"
    }

    override val mainPage = mainPageOf(
        "$apiUrl/seasons"                    to currentSeasonLabel(),
        "$mainUrl/api/stats/anime/"          to "Популярні аніме",
        "$apiUrl?pageSize=24&pageIndex=%d"   to "Нове аніме на сайті",
    )

    // ── Data classes ──────────────────────────────────────────────────────────

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
        val videoUrl: String?,
        val fileUrl: String?,
    )

    private data class DirectPlayerResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fixMovieExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/")  -> 720
            link.url.contains("/480/")  -> 480
            link.url.contains("/360/")  -> 360
            else -> when (link.quality) {
                in 900..1150  -> 1080
                in 600..899   -> 720
                in 400..599   -> 480
                in 240..399   -> 360
                else          -> link.quality
            }
        }
        return ExtractorLink(
            source        = link.source,
            name          = sourceName,
            url           = link.url,
            referer       = link.referer,
            quality       = cleanQuality,
            type          = link.type,
            headers       = link.headers,
            extractorData = link.extractorData
        )
    }

    private suspend fun buildFranchise(animeId: Int): List<SearchResponse> {
        Log.d("AnimeON:Franchise", "Building franchise for animeId=$animeId")
        val json = fetchJsonOrNull("$mainUrl/api/franchise/full/$animeId") ?: return emptyList()
        return try {
            val items = AppUtils.parseJson<List<FranchiseItem>>(json)
            Log.d("AnimeON:Franchise", "Got ${items.size} franchise items")
            items.filter { it.id != animeId }.map { item ->
                newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                    this.posterUrl = item.image?.preview?.let { posterApi.format(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeON:Franchise", "Error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            Log.d("AnimeON:Fetch", "GET $url")
            val response = app.get(url, headers = mapOf(
                "Referer"    to mainUrl,
                "User-Agent" to userAgent
            )).text
            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) {
                Log.w("AnimeON:Fetch", "Non-JSON response for $url: ${response.take(100)}")
                null
            } else response
        } catch (e: Exception) {
            Log.e("AnimeON:Fetch", "Error fetching $url: ${e.message}")
            null
        }
    }

    private suspend fun fetchJsonWithRetry(url: String, retries: Int = 3): String? {
        repeat(retries) { attempt ->
            val result = fetchJsonOrNull(url)
            if (result != null) return result
            Log.w("AnimeON:Fetch", "Retry ${attempt + 1}/$retries for $url")
        }
        return null
    }

    private suspend fun resolveAnimeApiUrl(animeId: Int): String {
        val initial = fetchJsonOrNull("$apiUrl/$animeId") ?: return "$apiUrl/$animeId"
        return try {
            val redirect = AppUtils.parseJson<RedirectResponse>(initial)
            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) {
                Log.d("AnimeON:Resolve", "animeId=$animeId → slug=${redirect.slug}")
                "$apiUrl/${redirect.slug}"
            } else {
                "$apiUrl/$animeId"
            }
        } catch (e: Exception) {
            Log.e("AnimeON:Resolve", "Error: ${e.message}")
            "$apiUrl/$animeId"
        }
    }

    private suspend fun fetchEpisodeInfoMap(animeId: Int): Map<Int, String> {
        val slugJson = fetchJsonOrNull("$apiUrl/$animeId") ?: return emptyMap()
        return try {
            val redirect  = AppUtils.parseJson<RedirectResponse>(slugJson)
            val slugOrId  = if (redirect?.moved == true && !redirect.slug.isNullOrEmpty())
                redirect.slug!! else animeId.toString()
            val infoJson  = fetchJsonOrNull("$mainUrl/api/anime/$slugOrId/episodes-info") ?: return emptyMap()
            val list      = AppUtils.parseJson<List<EpisodeInfo>>(infoJson)
            val result    = list.associate { ep ->
                ep.episode to (ep.titleUa?.takeIf { it.isNotBlank() }
                    ?: ep.title?.takeIf { it.isNotBlank() } ?: "")
            }.filter { it.value.isNotEmpty() }
            Log.d("AnimeON:EpInfo", "Got ${result.size} episode titles for $slugOrId")
            result
        } catch (e: Exception) {
            Log.e("AnimeON:EpInfo", "Error: ${e.message}")
            emptyMap()
        }
    }

    // Отримує постер з Ashdi iframe (не потребує cookies)
    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty() || !videoUrl.contains("ashdi.vip")) return null
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        Log.d("AnimeON:AshdiPoster", "Fetching poster from $url")
        return try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer"    to "$mainUrl/"
            ), cacheTime = 0).text

            val posterRegex = Regex("""poster:\s*["']((?:https?:)?//[^"']+)["']""")
            val raw = posterRegex.find(html)?.groupValues?.get(1)
            if (!raw.isNullOrEmpty()) {
                val result = if (raw.startsWith("http")) raw else "https:$raw"
                Log.d("AnimeON:AshdiPoster", "Found poster: $result")
                return result
            }

            val screenRegex = Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)""")
            val screenMatch = screenRegex.find(html)?.groupValues?.get(1)
            if (screenMatch != null) {
                val result = if (screenMatch.startsWith("http")) screenMatch else "https:$screenMatch"
                Log.d("AnimeON:AshdiPoster", "Found screen poster: $result")
                result
            } else {
                Log.w("AnimeON:AshdiPoster", "No poster found in ashdi iframe")
                null
            }
        } catch (e: Exception) {
            Log.e("AnimeON:AshdiPoster", "Error: ${e.message}")
            null
        }
    }

    // Отримує постер з Moon iframe.
    // Повертає пряме посилання — GA cookies додає getVideoInterceptor або клієнтський interceptor.
    private suspend fun getMoonPoster(iframeUrl: String): String? {
        if (!iframeUrl.contains("/iframe/")) {
            Log.w("AnimeON:MoonPoster", "Not an iframe URL: $iframeUrl")
            return null
        }

        val cleanUrl = if (iframeUrl.contains("player=")) iframeUrl
            else "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"

        Log.d("AnimeON:MoonPoster", "Fetching iframe: $cleanUrl")

        return try {
            val html = app.get(cleanUrl, headers = mapOf(
                "User-Agent"                to userAgent,
                "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language"           to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer"                   to "https://animeon.club/",
                "X-Requested-With"          to "mark.via.gp",
                "Sec-Fetch-Site"            to "none",
                "Sec-Fetch-Mode"            to "navigate",
                "Sec-Fetch-User"            to "?1",
                "Sec-Fetch-Dest"            to "document",
                "Upgrade-Insecure-Requests" to "1",
                // GA cookies достатньо для обходу Cloudflare Vary: Cookie
                "Cookie"                    to moonGaCookies,
            ), cacheTime = 0).text

            if (html.isEmpty()) {
                Log.w("AnimeON:MoonPoster", "Empty HTML for $cleanUrl")
                return null
            }

            Log.d("AnimeON:MoonPoster", "HTML length=${html.length}, scanning atob blocks")

            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            var posterUrl: String? = null

            for (match in atobRegex.findAll(html)) {
                val decoded = moonOuterDecode(match.groupValues[1])
                if (!decoded.contains("poster")) continue

                Log.d("AnimeON:MoonPoster", "Found 'poster' in decoded block, length=${decoded.length}")

                // Варіант 1: відкритий рядок poster:'https://...'
                posterUrl = Regex("""poster\s*:\s*["'](https?://[^"']+)["']""")
                    .find(decoded)?.groupValues?.get(1)
                if (posterUrl != null) {
                    Log.d("AnimeON:MoonPoster", "Plain poster found: $posterUrl")
                    break
                }

                // Варіант 2: зашифрований через _0xd(...)
                val xorKey = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                    .find(decoded)?.groupValues?.get(1)
                if (xorKey.isNullOrEmpty()) {
                    Log.w("AnimeON:MoonPoster", "No xorKey found in decoded block")
                    continue
                }
                Log.d("AnimeON:MoonPoster", "xorKey=$xorKey")

                val posterEnc = Regex("""poster\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                    .find(decoded)?.groupValues?.get(1)
                if (posterEnc.isNullOrEmpty()) {
                    Log.w("AnimeON:MoonPoster", "No encrypted poster token found")
                    continue
                }

                val result = moonDecrypt(posterEnc, xorKey)
                Log.d("AnimeON:MoonPoster", "Decrypted poster: $result")
                if (result.startsWith("http")) {
                    posterUrl = result
                    break
                }
            }

            if (posterUrl == null) {
                Log.w("AnimeON:MoonPoster", "Poster not found for $cleanUrl")
            } else {
                Log.d("AnimeON:MoonPoster", "Returning posterUrl=$posterUrl (direct, GA cookie via interceptor)")
            }

            posterUrl
        } catch (e: Exception) {
            Log.e("AnimeON:MoonPoster", "Error: ${e.message}")
            null
        }
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        Log.d("AnimeON:MoonContent", "Resolving $contentUrl")
        return try {
            val cookieResponse = app.get(
                "https://moonanime.art/",
                headers = mapOf(
                    "User-Agent"     to userAgent,
                    "Accept"         to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9",
                ),
                cacheTime = 0
            )
            val cookies = cookieResponse.cookies
            Log.d("AnimeON:MoonContent", "Got ${cookies.size} cookies from moonanime.art")

            val response = app.get(
                contentUrl,
                headers = mapOf(
                    "User-Agent"      to userAgent,
                    "Accept"          to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer"         to "https://moonanime.art/",
                    "Origin"          to "https://moonanime.art",
                    "Sec-Fetch-Site"  to "same-site",
                    "Sec-Fetch-Mode"  to "cors",
                    "Sec-Fetch-Dest"  to "empty",
                ),
                cookies         = cookies,
                allowRedirects  = false,
                cacheTime       = 0
            )

            val location = response.headers["location"] ?: response.headers["Location"]
            if (!location.isNullOrEmpty()) {
                Log.d("AnimeON:MoonContent", "Redirect → $location")
                location
            } else {
                val body = response.text.trim()
                if (body.startsWith("http")) {
                    Log.d("AnimeON:MoonContent", "Body URL → $body")
                    body
                } else {
                    Log.w("AnimeON:MoonContent", "No URL found, body=${body.take(80)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeON:MoonContent", "Error: ${e.message}")
            null
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false")
                ?: return newHomePageResponse(request.name, emptyList())
            val parsedJSON = AppUtils.parseJson<List<LocalResult>>(jsonText)
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page) else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = AppUtils.parseJson<SafeNewAnimeModel>(jsonText)
            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = AppUtils.parseJson<List<LocalResult>>(jsonText)
            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val id = query.toIntOrNull()
        if (id != null) {
            val byId = searchById(id)
            if (byId != null) return listOf(byId)
        }
        val jsonText = fetchJsonOrNull("$searchApi$query") ?: return emptyList()
        return try {
            val response = AppUtils.parseJson<SafeSearchApiResponse>(jsonText)
            Log.d("AnimeON:Search", "Query='$query' → ${response.results.size} results")
            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeON:Search", "Error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        val realUrl  = resolveAnimeApiUrl(id)
        val jsonText = fetchJsonOrNull(realUrl) ?: return null
        val anime    = try { AppUtils.parseJson<SafeAnimeInfoModel>(jsonText) } catch (e: Exception) { return null }
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = anime.image?.preview?.let { posterApi.format(it) }
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: throw Exception("Invalid anime ID in URL: $url")

        Log.d("AnimeON:Load", "Loading animeId=$animeId")

        val realApiUrl = resolveAnimeApiUrl(animeId)
        val jsonText   = fetchJsonOrNull(realApiUrl) ?: throw Exception("Failed to load anime $animeId")
        val animeJSON  = AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
            ?: throw Exception("Failed to parse anime $animeId")

        val posterUrl  = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres     = animeJSON.genres?.map { it.nameUa } ?: emptyList()
        val showStatus = if (animeJSON.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType     = with(animeJSON.type ?: "") {
            when {
                contains("tv")                                                      -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie")                                                   -> TvType.AnimeMovie
                else                                                                -> TvType.Anime
            }
        }

        val episodeInfoMap   = fetchEpisodeInfoMap(animeId)
        val episodes         = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {
            try {
                val translations  = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
                Log.d("AnimeON:Load", "Found ${translations.size} translations for $animeId")

                val episodeSources = mutableMapOf<Int, MutableList<EpisodeSource>>()
                val episodePosters = mutableMapOf<Int, String?>()

                for (translation in translations) {
                    val translationId = translation.translation.id
                    for (player in translation.player) {
                        Log.d("AnimeON:Load", "Processing player='${player.name}' translationId=$translationId")

                        val collected = mutableListOf<FundubEpisode>()
                        val seenIds   = mutableSetOf<Int>()
                        val baseUrl   = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"

                        // Спецепізоди (skip=-1)
                        val epJsonMinus1 = fetchJsonOrNull("$baseUrl&skip=-1")
                        if (epJsonMinus1 != null) {
                            val eps = try { AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes } catch (e: Exception) { null }
                            eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                        }

                        val maxSkip = if (player.episodesCount > 0) (player.episodesCount / 100 + 1) * 100 else 11000
                        var skip = 0
                        while (skip <= maxSkip) {
                            val epJson = fetchJsonOrNull("$baseUrl&skip=$skip") ?: break
                            val eps    = try { AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes } catch (e: Exception) { null }
                            if (eps.isNullOrEmpty()) break
                            val newEps = eps.filter { seenIds.add(it.id) }
                            collected.addAll(newEps)
                            if (eps.size < 100) break
                            skip += 100
                        }

                        Log.d("AnimeON:Load", "player='${player.name}' collected ${collected.size} episodes")

                        for (ep in collected) {
                            episodeSources.getOrPut(ep.episode) { mutableListOf() }.add(
                                EpisodeSource(
                                    translationName = translation.translation.name,
                                    playerName      = player.name,
                                    videoUrl        = ep.videoUrl,
                                    fileUrl         = ep.fileUrl,
                                )
                            )
                            // Зберігаємо apiPoster якщо він не з mooncdn і ще не встановлений
                            val poster = ep.poster
                            if (!poster.isNullOrEmpty()
                                && !poster.contains("mooncdn.space")
                                && !episodePosters.containsKey(ep.episode)
                            ) {
                                Log.d("AnimeON:Load", "ep=${ep.episode} apiPoster=$poster")
                                episodePosters[ep.episode] = poster
                            }
                        }
                    }
                }

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach
                    var epPoster = episodePosters[epNum]

                    if (epPoster.isNullOrEmpty()) {
                        // Пробуємо Ashdi (не потребує cookies)
                        val ashdiSource = sources.firstOrNull {
                            it.playerName.contains("Ashdi", ignoreCase = true) && !it.videoUrl.isNullOrEmpty()
                        }
                        if (ashdiSource != null) {
                            Log.d("AnimeON:Load", "ep=$epNum: trying Ashdi poster from ${ashdiSource.videoUrl}")
                            epPoster = getAshdiPoster(ashdiSource.videoUrl!!)
                        }

                        // Пробуємо Moon (повертає прямий URL, cookie додає interceptor)
                        if (epPoster.isNullOrEmpty()) {
                            val moonSource = sources.firstOrNull {
                                !it.videoUrl.isNullOrEmpty() && it.videoUrl.contains("moonanime.art")
                            }
                            if (moonSource != null) {
                                Log.d("AnimeON:Load", "ep=$epNum: trying Moon poster from ${moonSource.videoUrl}")
                                epPoster = getMoonPoster(moonSource.videoUrl!!)
                            }
                        }
                    }

                    Log.d("AnimeON:Load", "ep=$epNum final poster=${epPoster ?: "null"}")

                    val dataJson = org.json.JSONArray().also { arr ->
                        sources.forEach { s ->
                            arr.put(org.json.JSONObject().apply {
                                put("translationName", s.translationName)
                                put("playerName",      s.playerName)
                                put("videoUrl",        s.videoUrl ?: org.json.JSONObject.NULL)
                                put("fileUrl",         s.fileUrl  ?: org.json.JSONObject.NULL)
                            })
                        }
                    }.toString()

                    val episodeName = episodeInfoMap[epNum]?.takeIf { it.isNotBlank() }

                    episodes.add(
                        newEpisode(dataJson).apply {
                            this.name      = episodeName
                            this.episode   = epNum
                            this.posterUrl = epPoster
                        }
                    )
                }

                Log.d("AnimeON:Load", "Total episodes built: ${episodes.size}")

            } catch (e: Exception) {
                Log.e("AnimeON:Load", "Error building episodes: ${e.message}")
            }
        }

        val franchise = buildFranchise(animeId)

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl   = posterUrl
                this.engName     = animeJSON.titleEn
                this.tags        = genres
                this.plot        = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus  = showStatus
                this.duration    = animeJSON.episodeTime?.let { extractIntFromString(it) }
                this.year        = animeJSON.releaseDate?.toIntOrNull()
                this.score       = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId)
                this.recommendations = franchise
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) posterUrl else animeJSON.backgroundImage
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, animeId.toString()) {
                this.posterUrl          = posterUrl
                this.tags               = genres
                this.plot               = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration           = animeJSON.episodeTime?.let { extractIntFromString(it) }
                this.year               = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score              = Score.from10(animeJSON.rating)
                addMalId(animeJSON.malId)
                this.recommendations    = franchise
            }
        }
    }

    // ── loadLinks ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val animeId = data.trim().toIntOrNull()
        if (animeId != null) {
            Log.d("AnimeON:Links", "Movie mode, animeId=$animeId")
            return loadMovieLinks(animeId, subtitleCallback, callback)
        }

        val sources: List<EpisodeSource> = try {
            AppUtils.parseJson<List<EpisodeSource>>(data)
        } catch (e: Exception) {
            Log.e("AnimeON:Links", "Failed to parse episode sources: ${e.message}")
            return false
        }

        if (sources.isEmpty()) return false
        Log.d("AnimeON:Links", "Episode mode, ${sources.size} sources")

        var foundAny = false

        val moonVideoHeaders = mapOf(
            "User-Agent"          to userAgent,
            "Accept"              to "*/*",
            "Accept-Language"     to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer"             to "https://moonanime.art/",
            "Origin"              to "https://moonanime.art",
            "Sec-Ch-Ua-Platform"  to "\"Android\"",
            "Sec-Fetch-Site"      to "cross-site",
            "Sec-Fetch-Mode"      to "no-cors",
            "Sec-Fetch-Dest"      to "video",
            "X-Requested-With"    to "mark.via.gp"
        )

        for (source in sources) {
            val sourceName = "${source.translationName} (${source.playerName})"
            val isAshdi    = source.playerName.contains("Ashdi", ignoreCase = true)
            val fileUrl    = source.fileUrl
            val videoUrl   = source.videoUrl
            Log.d("AnimeON:Links", "Source: $sourceName | isAshdi=$isAshdi | fileUrl=${fileUrl?.take(60)} | videoUrl=${videoUrl?.take(60)}")

            try {
                if (isAshdi) {
                    if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                        Log.d("AnimeON:Links", "Ashdi iframe: $videoUrl")
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, callback)
                        foundAny = true
                    } else if (!fileUrl.isNullOrEmpty()) {
                        Log.d("AnimeON:Links", "Ashdi fileUrl m3u8: $fileUrl")
                        val streams  = M3u8Helper.generateM3u8(sourceName, fileUrl, "https://ashdi.vip")
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        foundAny = true
                    }
                } else {
                    if (!fileUrl.isNullOrEmpty()) {
                        Log.d("AnimeON:Links", "Moon fileUrl m3u8: $fileUrl")
                        val streams  = M3u8Helper.generateM3u8(sourceName, fileUrl, "https://moonanime.art/", headers = moonVideoHeaders)
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        foundAny = true
                    } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                        if (videoUrl.contains("m3u8")) {
                            Log.d("AnimeON:Links", "Moon direct m3u8: $videoUrl")
                            val streams  = M3u8Helper.generateM3u8(sourceName, videoUrl, "https://moonanime.art/", headers = moonVideoHeaders)
                            val filtered = streams.dropLast(1)
                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            foundAny = true
                        } else {
                            Log.d("AnimeON:Links", "Moon iframe, calling getMoonFile: $videoUrl")
                            val (rawFile, subUrl) = getMoonFile(videoUrl)
                            if (rawFile.isNotEmpty()) {
                                invokeSubtitles(subUrl, subtitleCallback)
                                processMoonRawFile(rawFile, sourceName, isMovie = false, callback)
                                foundAny = true
                            } else {
                                Log.w("AnimeON:Links", "getMoonFile returned empty for $videoUrl")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeON:Links", "Error processing $sourceName: ${e.message}")
            }
        }

        Log.d("AnimeON:Links", "loadLinks result: foundAny=$foundAny")
        return foundAny
    }

    // ── loadMovieLinks ────────────────────────────────────────────────────────

    private suspend fun loadMovieLinks(
        animeId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        var foundAny = false

        val moonVideoHeaders = mapOf(
            "User-Agent"          to userAgent,
            "Accept"              to "*/*",
            "Accept-Language"     to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer"             to "https://moonanime.art/",
            "Origin"              to "https://moonanime.art",
            "Sec-Ch-Ua-Platform"  to "\"Android\"",
            "Sec-Fetch-Site"      to "cross-site",
            "Sec-Fetch-Mode"      to "no-cors",
            "Sec-Fetch-Dest"      to "video",
            "X-Requested-With"    to "mark.via.gp"
        )

        try {
            val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
            Log.d("AnimeON:Movie", "animeId=$animeId, ${translations.size} translations")

            for (translation in translations) {
                val translationId = translation.translation.id
                for (player in translation.player) {
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIds   = mutableSetOf<Int>()
                    val baseUrl   = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"

                    val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1")
                    if (epJsonMinus1 != null) {
                        val eps = try { AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes } catch (e: Exception) { null }
                        eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                    }

                    val maxSkip = if (player.episodesCount > 0) (player.episodesCount / 100 + 1) * 100 else 11000
                    var skip = 0
                    while (skip <= maxSkip) {
                        val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip") ?: break
                        val eps    = try { AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes } catch (e: Exception) { null }
                        if (eps.isNullOrEmpty()) break
                        val newEps = eps.filter { seenIds.add(it.id) }
                        collected.addAll(newEps)
                        if (eps.size < 100) break
                        skip += 100
                    }

                    val sourceName = "${translation.translation.name} (${player.name})"
                    val isAshdi    = player.name.contains("Ashdi", ignoreCase = true)
                    Log.d("AnimeON:Movie", "player='${player.name}' collected=${collected.size}")

                    if (collected.isEmpty()) {
                        Log.d("AnimeON:Movie", "No episodes, trying direct player endpoint")
                        val directJson = fetchJsonOrNull("$mainUrl/api/player/${player.id}/${translation.translation.id}")
                        if (directJson != null) {
                            try {
                                val directSource = AppUtils.parseJson<DirectPlayerResponse>(directJson)
                                val videoUrl     = directSource.videoUrl
                                val fileUrl      = directSource.fileUrl
                                Log.d("AnimeON:Movie", "Direct: videoUrl=${videoUrl?.take(60)} fileUrl=${fileUrl?.take(60)}")

                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    foundAny = processMovieSource(
                                        isAshdi, videoUrl, fileUrl, sourceName,
                                        moonVideoHeaders, subtitleCallback, callback
                                    ) || foundAny
                                }
                            } catch (e: Exception) {
                                Log.e("AnimeON:Movie", "Direct parse error: ${e.message}")
                            }
                        }
                        continue
                    }

                    for (ep in collected) {
                        try {
                            foundAny = processMovieSource(
                                isAshdi, ep.videoUrl, ep.fileUrl, sourceName,
                                moonVideoHeaders, subtitleCallback, callback
                            ) || foundAny
                        } catch (e: Exception) {
                            Log.e("AnimeON:Movie", "Error ep=${ep.episode}: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeON:Movie", "Error: ${e.message}")
        }

        Log.d("AnimeON:Movie", "loadMovieLinks result: foundAny=$foundAny")
        return foundAny
    }

    // Спільна логіка обробки одного джерела (movie + episode)
    private suspend fun processMovieSource(
        isAshdi: Boolean,
        videoUrl: String?,
        fileUrl: String?,
        sourceName: String,
        moonVideoHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        if (isAshdi) {
            if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                Log.d("AnimeON:Source", "Ashdi iframe: $videoUrl")
                processAshdiIframe(videoUrl, sourceName, isMovie = true, callback)
                found = true
            } else if (!fileUrl.isNullOrEmpty()) {
                Log.d("AnimeON:Source", "Ashdi fileUrl: $fileUrl")
                val streams  = M3u8Helper.generateM3u8(sourceName, fileUrl, "https://ashdi.vip")
                val filtered = streams.dropLast(1)
                if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                found = true
            }
        } else {
            if (!fileUrl.isNullOrEmpty()) {
                Log.d("AnimeON:Source", "Moon fileUrl: $fileUrl")
                val streams  = M3u8Helper.generateM3u8(sourceName, fileUrl, "https://moonanime.art/", headers = moonVideoHeaders)
                val filtered = streams.dropLast(1)
                if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                found = true
            } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                if (videoUrl.contains("m3u8")) {
                    Log.d("AnimeON:Source", "Moon direct m3u8: $videoUrl")
                    val streams  = M3u8Helper.generateM3u8(sourceName, videoUrl, "https://moonanime.art/", headers = moonVideoHeaders)
                    val filtered = streams.dropLast(1)
                    if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                    else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                    found = true
                } else {
                    Log.d("AnimeON:Source", "Moon iframe: $videoUrl")
                    val (rawFile, subUrl) = getMoonFile(videoUrl)
                    if (rawFile.isNotEmpty()) {
                        invokeSubtitles(subUrl, subtitleCallback)
                        processMoonRawFile(rawFile, sourceName, isMovie = true, callback)
                        found = true
                    } else {
                        Log.w("AnimeON:Source", "getMoonFile returned empty for $videoUrl")
                    }
                }
            }
        }
        return found
    }

    // ── processMoonRawFile ────────────────────────────────────────────────────

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        val moonVideoHeaders = mapOf(
            "User-Agent"          to userAgent,
            "Accept"              to "*/*",
            "Accept-Language"     to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer"             to "https://moonanime.art/",
            "Origin"              to "https://moonanime.art",
            "Sec-Ch-Ua-Platform"  to "\"Android\"",
            "Sec-Fetch-Site"      to "cross-site",
            "Sec-Fetch-Mode"      to "no-cors",
            "Sec-Fetch-Dest"      to "video",
            "X-Requested-With"    to "mark.via.gp"
        )

        Log.d("AnimeON:MoonRaw", "rawFile=${rawFile.take(120)}")

        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
            qualityRegex.findAll(rawFile).forEach { match ->
                val qualityStr = match.groupValues[1]
                val qUrl       = match.groupValues[2]
                val qualityInt = qualityStr.replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                Log.d("AnimeON:MoonRaw", "quality=$qualityStr url=${qUrl.take(80)}")

                when {
                    qUrl.contains(".m3u8") -> {
                        val streams     = M3u8Helper.generateM3u8(sourceName, qUrl, "https://moonanime.art/", headers = moonVideoHeaders)
                        val filtered    = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams
                        finalStreams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                    }
                    qUrl.contains("s.moonanime.art") || qUrl.contains("moonanime.art/content") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        if (!finalUrl.isNullOrEmpty()) {
                            callback(fixMovieExtractorLink(ExtractorLink(
                                source  = name,
                                name    = sourceName,
                                url     = finalUrl,
                                referer = "https://moonanime.art/",
                                quality = qualityInt,
                                type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                headers = moonVideoHeaders
                            ), sourceName))
                        }
                    }
                    else -> {
                        callback(fixMovieExtractorLink(ExtractorLink(
                            source  = name,
                            name    = sourceName,
                            url     = qUrl,
                            referer = "https://moonanime.art/",
                            quality = qualityInt,
                            type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                            headers = moonVideoHeaders
                        ), sourceName))
                    }
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            val streams     = M3u8Helper.generateM3u8(sourceName, rawFile, "https://moonanime.art/", headers = moonVideoHeaders)
            val filtered    = streams.dropLast(1)
            val finalStreams = if (filtered.isNotEmpty()) filtered else streams
            finalStreams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
        } else if (rawFile.contains("s.moonanime.art") || rawFile.contains("moonanime.art/content")) {
            val finalUrl = resolveMoonContent(rawFile)
            if (!finalUrl.isNullOrEmpty()) {
                callback(fixMovieExtractorLink(ExtractorLink(
                    source  = name,
                    name    = sourceName,
                    url     = finalUrl,
                    referer = "https://moonanime.art/",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = moonVideoHeaders
                ), sourceName))
            }
        } else {
            callback(fixMovieExtractorLink(ExtractorLink(
                source  = name,
                name    = sourceName,
                url     = rawFile,
                referer = "https://moonanime.art/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders
            ), sourceName))
        }
    }

    // ── processAshdiIframe ────────────────────────────────────────────────────

    private suspend fun processAshdiIframe(
        iframeUrl: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = iframeUrl
                .replace(Regex("""\?season=null\?"""), "?")
                .replace(Regex("""\?season=null$"""), "")
            val url  = if (cleanUrl.contains("?")) cleanUrl else "$cleanUrl?player=animeon.club"
            Log.d("AnimeON:Ashdi", "Fetching iframe: $url")

            val html = app.get(url, headers = mapOf(
                "Referer"         to "$mainUrl/",
                "User-Agent"      to userAgent,
                "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
            ), cacheTime = 0).text

            val fileIndex = html.indexOf("file:'")
            if (fileIndex != -1) {
                val urlStart  = fileIndex + 6
                val urlEnd    = html.indexOf('\'', urlStart)
                if (urlEnd != -1) {
                    val masterUrl = html.substring(urlStart, urlEnd)
                    Log.d("AnimeON:Ashdi", "masterUrl=$masterUrl")
                    if (masterUrl.isNotEmpty() && masterUrl.endsWith(".m3u8")) {
                        val streams     = M3u8Helper.generateM3u8(sourceName, masterUrl, "https://ashdi.vip/")
                        val filtered    = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams
                        finalStreams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                    }
                }
            } else {
                Log.w("AnimeON:Ashdi", "file:' not found in iframe HTML")
            }
        } catch (e: Exception) {
            Log.e("AnimeON:Ashdi", "Error: ${e.message}")
        }
    }

    // ── Moon crypto ───────────────────────────────────────────────────────────

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val cleanEncoded    = encoded.replace("\\s".toRegex(), "")
            val decoded         = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
            val decryptedBytes  = ByteArray(decoded.size)
            for (i in decoded.indices) {
                decryptedBytes[i] = ((decoded[i].toInt() and 0xFF) xor key[i % key.length].code).toByte()
            }
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("AnimeON:Crypto", "moonDecrypt error: ${e.message}")
            ""
        }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw    = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 33) return ""
            val state0 = raw[0].toInt() and 0xFF
            val key    = raw.sliceArray(1 until 33)
            val data   = raw.sliceArray(33 until raw.size)
            val result = ByteArray(data.size)
            var state  = state0
            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF
                result[i] = (d xor k xor state).toByte()
                state = (d + k) and 0xFF
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("AnimeON:Crypto", "moonOuterDecode error: ${e.message}")
            ""
        }
    }

    // ── getMoonFile ───────────────────────────────────────────────────────────

    private suspend fun getMoonFile(iframeUrl: String): Pair<String, String?> {
        val cleanUrl = if (iframeUrl.contains("player=")) iframeUrl
            else "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"

        Log.d("AnimeON:MoonFile", "Fetching $cleanUrl")

        val html = try {
            app.get(cleanUrl, headers = mapOf(
                "User-Agent"                to userAgent,
                "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language"           to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer"                   to "https://animeon.club/",
                "X-Requested-With"          to "mark.via.gp",
                "Sec-Fetch-Site"            to "none",
                "Sec-Fetch-Mode"            to "navigate",
                "Sec-Fetch-User"            to "?1",
                "Sec-Fetch-Dest"            to "document",
                "Upgrade-Insecure-Requests" to "1",
                "Cookie"                    to moonGaCookies,
            ), cacheTime = 0).text
        } catch (e: Exception) {
            Log.e("AnimeON:MoonFile", "Error fetching iframe: ${e.message}")
            ""
        }

        if (html.isNotEmpty()) {
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            val atobMatch = atobRegex.find(html)?.groupValues?.get(1)

            if (!atobMatch.isNullOrEmpty()) {
                val decodedJs = moonOuterDecode(atobMatch)
                Log.d("AnimeON:MoonFile", "decodedJs length=${decodedJs.length}")

                if (decodedJs.isNotEmpty()) {
                    val xorKey = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                        .find(decodedJs)?.groupValues?.get(1)

                    var subtitleUrl: String? = null

                    if (!xorKey.isNullOrEmpty()) {
                        Log.d("AnimeON:MoonFile", "xorKey=$xorKey")

                        // Субтитри
                        val subtitleEncMatch = Regex("""subtitle\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                            .find(decodedJs)?.groupValues?.get(1)
                        if (!subtitleEncMatch.isNullOrEmpty()) {
                            val subtitleDecoded = moonDecrypt(subtitleEncMatch, xorKey)
                            Log.d("AnimeON:MoonFile", "subtitleDecoded=$subtitleDecoded")
                            val subtitleEntries = mutableListOf<Pair<String, String>>()
                            val entryMatches    = Regex("""\[([^\]]+)\](https?://[^\[,]+)""")
                                .findAll(subtitleDecoded).toList()
                            if (entryMatches.isNotEmpty()) {
                                entryMatches.forEach { m ->
                                    subtitleEntries.add(Pair(m.groupValues[1], m.groupValues[2].trim(',', ' ')))
                                }
                            } else if (subtitleDecoded.startsWith("http")) {
                                subtitleEntries.add(Pair("UA", subtitleDecoded.trim()))
                            }
                            if (subtitleEntries.isNotEmpty()) {
                                subtitleUrl = subtitleEntries.joinToString("|||") { "${it.first}::${it.second}" }
                                Log.d("AnimeON:MoonFile", "subtitleUrl=$subtitleUrl")
                            }
                        }

                        // Файл відео
                        val encMatches = Regex("""_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                            .findAll(decodedJs).toList()
                        val allDecoded = encMatches.mapNotNull { m ->
                            val d = moonDecrypt(m.groupValues[1], xorKey)
                            d.ifEmpty { null }
                        }
                        Log.d("AnimeON:MoonFile", "Decoded ${allDecoded.size} tokens")

                        for (decoded in allDecoded) {
                            val isVideo       = decoded.contains(".m3u8") || decoded.contains(".mp4") || decoded.contains(".webm") || decoded.startsWith("[")
                            val isMoonDomain  = decoded.contains("mooncdn") || decoded.contains("moonanime.art/content") || decoded.contains("s.moonanime.art")
                            val isStaticAsset = decoded.contains(Regex("""\.(jpg|jpeg|png|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE))

                            if ((isVideo || isMoonDomain) && !isStaticAsset) {
                                Log.d("AnimeON:MoonFile", "Found video token: ${decoded.take(100)}")
                                return Pair(decoded, subtitleUrl)
                            }
                        }
                    }

                    // Fallback: шукаємо content URL напряму
                    val contentMatch = Regex("""(https?://s\.moonanime\.art/content/[^\s"'`]+)""")
                        .find(decodedJs)?.groupValues?.get(1)
                    if (!contentMatch.isNullOrEmpty() && !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))) {
                        Log.d("AnimeON:MoonFile", "Content URL fallback: $contentMatch")
                        val resolved = resolveMoonContent(contentMatch)
                        if (!resolved.isNullOrEmpty()) {
                            return Pair(resolved, subtitleUrl)
                        }
                    }
                }
            } else {
                Log.w("AnimeON:MoonFile", "No atob block found in HTML")
            }
        } else {
            Log.w("AnimeON:MoonFile", "Empty HTML from $cleanUrl")
        }

        // Fallback: перебираємо якості через hash
        val hash = Regex("""/iframe/([a-zA-Z0-9]+)/?""").find(cleanUrl)?.groupValues?.get(1)
        if (!hash.isNullOrEmpty()) {
            Log.d("AnimeON:MoonFile", "Hash fallback, hash=$hash")
            val qualityResults = mutableListOf<String>()
            for (quality in listOf(1080, 720, 480, 360)) {
                val contentUrl = "https://s.moonanime.art/content/v/$hash/$quality/"
                val resolved   = resolveMoonContent(contentUrl)
                if (!resolved.isNullOrEmpty()) {
                    Log.d("AnimeON:MoonFile", "quality=$quality → $resolved")
                    qualityResults.add("[${quality}p]$resolved")
                }
            }
            if (qualityResults.isNotEmpty()) {
                return Pair(qualityResults.joinToString("."), null)
            }
        }

        Log.w("AnimeON:MoonFile", "getMoonFile: nothing found for $cleanUrl")
        return Pair("", null)
    }

    // ── Subtitle proxy ────────────────────────────────────────────────────────

    private var subtitleProxyPort: Int = 0
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun ensureSubtitleProxy() {
        if (subtitleProxyPort != 0) return
        val serverSocket = java.net.ServerSocket(0)
        subtitleProxyPort = serverSocket.localPort
        Log.d("AnimeON:SubProxy", "Subtitle proxy started on port $subtitleProxyPort")
        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    Thread {
                        try {
                            val line = client.getInputStream().bufferedReader().readLine() ?: return@Thread
                            val key  = line.substringAfter("?").substringBefore(" ")
                            val body = subtitleCache[key]
                            val out  = client.getOutputStream()
                            if (body != null) {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/vtt; charset=utf-8\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            } else {
                                Log.w("AnimeON:SubProxy", "Cache miss for key=$key")
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            out.flush()
                            client.close()
                        } catch (e: Exception) { }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) { }
            }
        }.also { it.isDaemon = true }.start()
    }

    private suspend fun invokeSubtitles(subUrl: String?, subtitleCallback: (SubtitleFile) -> Unit) {
        if (subUrl == null) return
        ensureSubtitleProxy()
        subUrl.split("|||").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val lang = parts[0]
                val url  = parts[1]
                Log.d("AnimeON:Subtitles", "Fetching subtitle lang=$lang url=$url")
                try {
                    val bytes = app.get(url, headers = mapOf(
                        "User-Agent"      to userAgent,
                        "Referer"         to "https://moonanime.art/",
                        "Origin"          to "https://moonanime.art",
                        "Accept"          to "*/*",
                        "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    ), cacheTime = 0).body.bytes()
                    val key      = java.util.UUID.randomUUID().toString().replace("-", "")
                    subtitleCache[key] = bytes
                    val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
                    Log.d("AnimeON:Subtitles", "Subtitle cached, proxyUrl=$proxyUrl")
                    subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                } catch (e: Exception) {
                    Log.e("AnimeON:Subtitles", "Error fetching subtitle: ${e.message}, falling back to direct URL")
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                }
            }
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") return value.value.drop(1).toIntOrNull()
        return value.value.toIntOrNull()
    }
}
