package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.extractors.AshdiExtractor
import com.lagradost.extractors.csstExtractor
import com.lagradost.models.PlayerJson
import com.lagradost.models.videoConstructor
import org.json.JSONObject
import org.json.JSONException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnitubeinuaProvider : MainAPI() {
    override var mainUrl = "https://anitube.in.ua"
    override var name = "Anitubeinua"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AnimeMovie, TvType.Anime)

    override val mainPage = mainPageOf("$mainUrl/anime/page/" to "Нові")
    private var dle_login_hash = ""

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    }

    data class Responses(val success: Boolean?, val response: String?, val message: String?)
    data class StudioInfo(val name: String, val type: String, val dataId: String)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
        val home = document.select(".story").map { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(".story_c h2 a, div.text_content a")?.text()?.trim().toString()
        val href = this.selectFirst(".story_c h2 a, div.text_content a")?.attr("href").toString()
        var posterUrl = this.selectFirst(".story_c_l span.story_post img")?.attr("src")
        if (posterUrl.isNullOrEmpty()) posterUrl = this.selectFirst("a img")?.attr("data-src")

        var isSub = this.select(".box .sub").isNotEmpty()
        var isDub = this.select(".box .ukr").isNotEmpty()
        if (!isSub && !isDub) { isSub = true; isDub = true }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = mainUrl + posterUrl
            addDubStatus(isDub, isSub)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf("do" to "search", "subaction" to "search", "story" to query.replace(" ", "+")),
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
        ).document
        return document.select("article.story").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): AnimeLoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
        val someInfo = document.select(".story_c_r")
        val title = document.selectFirst(".story_c h2")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".story_c_left span.story_post img")?.attr("src")
        val tags = someInfo.select("a[href*=/anime/]").map { it.text() }
        val year = someInfo.select("a[href*=/xfsearch/year/]").text().toIntOrNull()
        val description = document.selectFirst("div.my-text")?.text()?.trim()
        val trailer = document.selectFirst(".rcol a.rollover")?.attr("href").toString()
        val rating = document.selectFirst(".lexington-box > div:last-child span")?.text()
        val recommendations = document.select(".horizontal ul li").map { it.toSearchResponse() }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val id = url.split("/").last().split("-").first()
        
        dle_login_hash = document.body().selectFirst("script")?.html()
            ?.substringAfterLast("dle_login_hash = '")?.substringBefore("';") ?: ""

        val ajaxUrl = "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&user_hash=$dle_login_hash"
        val responseGet = app.get(ajaxUrl, referer = url, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to USER_AGENT)).parsedSafe<Responses>()

        if (responseGet?.success == true && !responseGet.response.isNullOrEmpty()) {
            val playlistDoc = Jsoup.parse(responseGet.response!!)
            val playlistContainers = playlistDoc.select(".playlists-lists .playlists-items")
            
            if (playlistContainers.size >= 2) {
                // 1. Парсимо та валідуємо всі доступні студії
                val studios = playlistContainers[1].select("li").mapNotNull { element ->
                    val name = element.text().trim()
                    val dataId = element.attr("data-id").trim()
                    val segments = dataId.split("_")
                    
                    if (segments.size <= 2 || name.contains("СУБТИТРИ", true) || name.contains("ОЗВУЧЕННЯ", true)) null
                    else {
                        val type = when (segments.getOrNull(1)) {
                            "0" -> "СУБТИТРИ"
                            "1" -> "ОЗВУЧЕННЯ"
                            else -> return@mapNotNull null
                        }
                        StudioInfo(name, type, dataId)
                    }
                }

                // 2. Фільтруємо серії для кожної студії окремо
                val videoElements = playlistDoc.select(".playlists-videos .playlists-items li")
                studios.forEach { studio ->
                    videoElements.forEach { element ->
                        val videoDataId = element.attr("data-id").trim()
                        if (videoDataId.startsWith(studio.dataId)) {
                            val epName = element.text().trim()
                            val isDub = studio.type == "ОЗВУЧЕННЯ"
                            
                            // Пакуємо точні метадані через унікальний спліттер |||
                            val epData = "$epName|||${studio.dataId}|||$id|||$isDub"
                            val episodeObj = newEpisode(epData) {
                                this.name = "$epName (${studio.name})"
                                this.episode = extractIntFromString(epName)
                            }
                            if (isDub) dubEpisodes.add(episodeObj) else subEpisodes.add(episodeObj)
                        }
                    }
                }
            }
        } else {
            // Резервний статичний RalodePlayer плеєр
            document.select("script").forEach { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    fromVideoContructor(script).filter { it.episodeName != "ПЛЕЙЛИСТ" }.groupBy { it.episodeName }.forEach { entry ->
                        val firstEp = entry.value.first()
                        val varEpisodeNumber = firstEp.episodeNumber ?: 1
                        dubEpisodes.add(newEpisode("$varEpisodeNumber, $url") {
                            this.name = entry.key
                            this.episode = firstEp.episodeNumber
                            this.data = "$varEpisodeNumber, $url"
                        })
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            addTrailer(trailer)
            this.recommendations = recommendations
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val addedLinks = hashSetOf<String>()

        if (data.contains("|||")) {
            val dataList = data.split("|||")
            val targetEpName = dataList[0]
            val studioDataId = dataList[1]
            val newsId = dataList[2]

            val ajaxUrl = "$mainUrl/engine/ajax/playlists.php?news_id=$newsId&xfield=playlist&user_hash=$dle_login_hash"
            val responseGet = app.get(ajaxUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to USER_AGENT)).parsedSafe<Responses>()
            
            if (responseGet?.success == true && !responseGet.response.isNullOrEmpty()) {
                val doc = Jsoup.parse(responseGet.response!!)
                val videoElements = doc.select(".playlists-videos .playlists-items li")
                val playerElements = doc.select(".playlists-lists .playlists-items").getOrNull(2)?.select("li")

                videoElements.forEach { element ->
                    val videoDataId = element.attr("data-id").trim()
                    val videoName = element.text().trim()
                    val fileUrl = element.attr("data-file").trim()

                    // Точна валідація приналежності серії до обраної студії та імені
                    if (videoDataId.startsWith(studioDataId) && videoName.equals(targetEpName, ignoreCase = true)) {
                        if (fileUrl.contains("moonanime.art")) return@forEach

                        // Визначаємо назву плеєра з 3-го контейнера списків
                        val playerName = playerElements?.firstOrNull { videoDataId.startsWith(it.attr("data-id").trim()) }?.text()?.trim() ?: "Плеєр"

                        when {
                            fileUrl.contains("ashdi.vip") -> {
                                val fixedUrl = if (fileUrl.startsWith("//")) "https:$fileUrl" else fileUrl
                                val streamUrl = AshdiExtractor().ParseM3U8(fixedUrl.replace("/embed/", "/vod/"))
                                M3u8Helper.generateM3u8(source = playerName, streamUrl = streamUrl, referer = "https://qeruya.cyou")
                                    .dropLast(1).forEach { link -> if (addedLinks.add(link.url)) callback(link) }
                            }
                            fileUrl.contains("https://www.udrop.com") -> {
                                val link = newExtractorLink(playerName, name = playerName, fileUrl)
                                if (addedLinks.add(link.url)) callback.invoke(link)
                            }
                            fileUrl.contains("https://csst.online/embed/") || fileUrl.contains("https://monstro.site/embed/") -> {
                                csstExtractor().ParseUrl(fileUrl).split(",").forEach { csstUrl ->
                                    val quality = csstUrl.substringBefore("]").drop(1)
                                    val link = newExtractorLink(playerName, name = "$playerName $quality", csstUrl.substringAfter("]"), ExtractorLinkType.M3U8)
                                    if (addedLinks.add(link.url)) callback.invoke(link)
                                }
                            }
                            fileUrl.contains("https://www.mp4upload.com/") -> {
                                getExtractorApiFromName("Mp4Upload").getUrl(fileUrl)?.forEach { extlink ->
                                    val link = newExtractorLink(extlink.source, playerName, extlink.url)
                                    if (addedLinks.add(link.url)) callback.invoke(link)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Запасна гілка обробки посилань старого RalodePlayer плеєра
            val dataList = data.split(", ")
            if (dataList.size < 2) return false
            val document = app.get(dataList[1], headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
            document.select("script").forEach { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    var latestNumber = 0
                    fromVideoContructor(script).forEach { dub ->
                        if (dub.episodeName == "ПЛЕЙЛИСТ") return@forEach
                        if (dub.episodeNumber == null) { dub.episodeNumber = latestNumber + 1 }
                        latestNumber = dub.episodeNumber!!

                        val targetEpisodeNum = dataList[0].toIntOrNull()
                        if (latestNumber == targetEpisodeNum) {
                            val url = dub.episodeUrl
                            if (url.contains("ashdi.vip")) {
                                val fixedUrl = if (url.startsWith("//")) "https:$url" else url
                                val streamUrl = AshdiExtractor().ParseM3U8(fixedUrl.replace("/embed/", "/vod/"))
                                M3u8Helper.generateM3u8(source = dub.playerName, streamUrl = streamUrl, referer = "https://qeruya.cyou")
                                    .dropLast(1).forEach { link -> if (addedLinks.add(link.url)) callback(link) }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun decode(input: String): String {
        return Regex("\\\\u([0-9a-fA-F]{4})").replace(input) { match ->
            Integer.parseInt(match.groupValues[1], 16).toChar().toString()
        }
    }

    private fun fromVideoContructor(script: Element): List<videoConstructor> {
        val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
        val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
        val playerNamesArray = (playerScriptRawJson.substringBefore("],") + "]").drop(1).dropLast(1).replace("\",\"", ",,,").split(",,,")
        val jsonEpisodes = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson) ?: return emptyList()
        val episodes = mutableListOf<videoConstructor>()

        jsonEpisodes.forEachIndexed { index, episode ->
            val playerName = decode(playerNamesArray.getOrNull(index) ?: "")
            episode.forEach {
                episodes.add(videoConstructor(playerName, it.name, extractIntFromString(it.name), Jsoup.parse(it.code).select("iframe").attr("src")))
            }
        }
        return episodes
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        return if (value.value.startsWith("0") && value.value.length > 1) value.value.drop(1).toIntOrNull() else value.value.toIntOrNull()
    }
}
