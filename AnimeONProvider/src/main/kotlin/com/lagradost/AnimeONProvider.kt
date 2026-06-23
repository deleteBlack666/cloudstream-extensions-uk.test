package com.lagradost

import com.google.gson.annotations.SerializedName
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
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    // --- БЕЗПЕЧНІ МОДЕЛІ ---
    private data class SafeResult(
        @SerializedName("id") val id: Int,
        @SerializedName("titleUa") val titleUa: String,
        @SerializedName("description") val description: String? = null,
        @SerializedName("image") val image: Image,
        @SerializedName("malId") val malId: Int? = null,
        @SerializedName("rating") val rating: Double? = 0.0,
        @SerializedName("status") val status: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("genres") val genres: List<Genres>? = null,
        @SerializedName("episodes") val episodes: Int? = null
    )

    private data class SafeNewAnimeModel(
        @SerializedName("results") val results: List<SafeResult>,
        @SerializedName("totalCount") val totalCount: Int? = 0
    )

    private data class SafeSearchApiResponse(
        @SerializedName("results") val results: List<SafeResult>,
        @SerializedName("totalCount") val totalCount: Int? = 0
    )

    private data class SafeAnimeInfoModel(
        @SerializedName("id") val id: Int,
        @SerializedName("titleUa") val titleUa: String,
        @SerializedName("titleEn") val titleEn: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("image") val image: Image? = null,
        @SerializedName("backgroundImage") val backgroundImage: String? = null,
        @SerializedName("trailer") val trailer: String? = null,
        @SerializedName("rating") val rating: Double? = 0.0,
        @SerializedName("status") val status: String? = "completed",
        @SerializedName("type") val type: String? = "tv",
        @SerializedName("genres") val genres: List<Genres>? = null,
        @SerializedName("episodes") val episodes: Int? = 0,
        @SerializedName("episodeTime") val episodeTime: String? = "",
        @SerializedName("releaseDate") val releaseDate: String? = null,
        @SerializedName("malId") val malId: Int? = 0
    )

    private data class SafeTranslationsResponse(
        @SerializedName("translations") val translations: List<TranslationItem>
    )

    private data class SafePlayerEpisodes(
        @SerializedName("episodes") val episodes: List<FundubEpisode>
    )
    // ----------------------

    // ... (залишаються класи LocalResult, RedirectResponse, EpisodeSource, DirectPlayerResponse, FranchiseItem)

    // ... (всі методи, такі як fixMovieExtractorLink, buildFranchise, fetchJsonOrNull, etc., залишаються без змін)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false") ?: return newHomePageResponse(request.name, emptyList())
            
            val parsedJSON = AppUtils.parseJson<List<LocalResult>>(jsonText)
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(if (request.data.contains("%d")) request.data.format(page) else request.data) ?: return newHomePageResponse(request.name, emptyList())
        
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

    override suspend fun search(query: String): List<SearchResponse> {
        val id = query.toIntOrNull()
        if (id != null) {
            val animeById = searchById(id)
            if (animeById != null) return listOf(animeById)
        }
        val url = "$searchApi${query}"
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()
        return try {
            val response = AppUtils.parseJson<SafeSearchApiResponse>(jsonText)
            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        val realUrl = resolveAnimeApiUrl(id)
        val jsonText = fetchJsonOrNull(realUrl) ?: return null
        val anime = try { AppUtils.parseJson<SafeAnimeInfoModel>(jsonText) } catch (e: Exception) { return null }
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = anime.image?.preview?.let { posterApi.format(it) }
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: throw Exception("Invalid anime ID in URL: $url")

        val realApiUrl = resolveAnimeApiUrl(animeId)
        val jsonText = fetchJsonOrNull(realApiUrl) ?: throw Exception("Failed to load anime $animeId")
        val animeJSON = AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
            ?: throw Exception("Failed to parse anime $animeId")

        val posterUrl = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres = animeJSON.genres?.map { it.nameUa } ?: emptyList()

        val showStatus = if (animeJSON.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = with(animeJSON.type ?: "") {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {
            try {
                val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
                // ... (інша логіка збору епізодів)
                
                // Зверніть увагу: при виклику playerEpisodes переконайтеся, що ви використовуєте SafePlayerEpisodes:
                // val eps = try { AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes } catch (e: Exception) { null }

                // ... (інший код методу load без змін)
            } catch (e: Exception) { }
        }
        
        // ... (решта логіки load)
        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
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
            this.recommendations = buildFranchise(animeId)
        }
    }
}
