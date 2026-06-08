package com.lagradost

import android.util.Log
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
import com.lagradost.models.Ajax
import com.lagradost.models.Link
import com.lagradost.models.PlayerJson
import com.lagradost.models.videoConstructor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnitubeinuaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://anitube.in.ua"
    override var name = "Anitubeinua"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes =
            setOf(
                    TvType.AnimeMovie,
                    TvType.Anime,
            )

    // Sections
    // Fix #1: removed "Популярні" section — URL /f/sort=rating/order=desc/page/ no longer exists on the site
    override val mainPage =
            mainPageOf(
                    "$mainUrl/anime/page/" to "Нові",
            )

    private var dle_login_hash = ""

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        // Fix #2: site redesigned HTML — cards now use article.short, not .story
        val home = document.select("article.short").map { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    // Fix #2: updated selectors to match the new site layout
    // Old: .story_c h2 a / .story_c_l span.story_post img / .box .sub / .box .ukr
    // New: a.short-title h2 / .short-img img / .short-meta text content
    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst("a.short-title h2")?.text()?.trim().toString()
        val href = this.selectFirst("a.short-title")?.attr("href").toString()
        var posterUrl = this.selectFirst(".short-img img")?.attr("src")
        // For recommendations
        if (posterUrl.isNullOrEmpty()) posterUrl = this.selectFirst("a img")?.attr("data-src")

        // New site uses .short-meta text: "Озв+Суб", "Озвучування", "Субтитри"
        val labelText = this.selectFirst(".short-meta")?.text()?.trim() ?: ""
        val isDub = labelText.contains("Озв", ignoreCase = true)
        val isSub = labelText.contains("Суб", ignoreCase = true)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = if (posterUrl?.startsWith("/") == true) mainUrl + posterUrl else posterUrl
            addDubStatus(isDub, isSub)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
                app.post(
                        url = mainUrl,
                        data =
                        mapOf(
                                "do" to "search",
                                "subaction" to "search",
                                "story" to query.replace(" ", "+")),
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to mainUrl
                        ))
                        .document

        // Search results also use article.short
        return document.select("article.short").map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): AnimeLoadResponse {
        val document = app.get(url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        // Fix #2: updated selectors to match the new site layout
        // Old selectors: .story_c h2, .story_c_left span.story_post img, .story_c_r, div.my-text, .lexington-box
        // New selectors: .full_title h1, .full_poster img, .full_list-col, .full_desc, (no rating visible)
        val title = document.selectFirst(".full_title h1")?.text()?.trim().toString()
        val poster = document.selectFirst(".full_poster img")?.attr("src")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        }

        val infoList = document.select(".full_list-col li")
        val tags = infoList
            .filter { it.text().contains("Жанр", ignoreCase = true) }
            .flatMap { it.select("a") }
            .map { it.text() }
        val year = infoList
            .filter { it.text().contains("Рік", ignoreCase = true) }
            .firstOrNull()
            ?.selectFirst("a")
            ?.text()
            ?.toIntOrNull()

        val tvType = TvType.Anime
        val description = document.selectFirst(".full_desc")?.ownText()?.trim()
        val trailer = document.selectFirst(".rcol a.rollover")?.attr("href").toString()
        // Rating is no longer present in the new layout
        val rating = null

        val recommendations = document.select(".horizontal ul li").map { it.toSearchResponse() }

        // Players, Episodes, Number of episodes
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val id = url.split("/").last().split("-").first()
        dle_login_hash =
                document
                        .body()
                        .selectFirst("script")!!
                        .html()
                        .substringAfterLast("dle_login_hash = '")
                        .substringBefore("';")

        val ajax =
                fromPlaylistAjax(
                        "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&user_hash=$dle_login_hash", referer = url)

        if (!ajax.isNullOrEmpty()) { // Ajax list
            ajax
                    .groupBy { it.name }
                    .forEach { episodes -> // Group by name
                        episodes.value.forEach lit@{
                            // UFDub player, drop
                            if (it.urls.url.contains("video.ufdub")) return@lit

                            if (it.urls.isDub) {
                                dubEpisodes.add(
                                    newEpisode("${it.name}, $id, ${it.urls.isDub}") {
                                        this.name = it.name
                                        this.episode = it.numberEpisode
                                    }
                                )
                            } else {
                                subEpisodes.add(
                                    newEpisode("${it.name}, $id, ${it.urls.isDub}") {
                                        this.name = it.name
                                        this.episode = it.numberEpisode
                                    }
                                )
                            }
                        }
                    }
        } else {
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    val episodesList = fromVideoContructor(script)

                    episodesList.forEach { episode ->
                        var varEpisodeNumber = episode.episodeNumber
                        if (episode.episodeName == "ПЛЕЙЛИСТ") return@forEach
                        if (varEpisodeNumber == null) {
                            varEpisodeNumber = episodesList.last().episodeNumber?.plus(1)
                        }
                        dubEpisodes.add(
                            newEpisode("$varEpisodeNumber, $url") {
                                this.name = episode.episodeName
                                this.episode = episode.episodeNumber
                                this.data = "$varEpisodeNumber, $url"
                            }
                        )
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating as String?)
            addTrailer(trailer)
            this.recommendations = recommendations
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
            data: String, // (Ajax) Name, id title, isDub | (Two) Episode name, url title
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        Log.d("CakesTwix-Debug", data)
        if (dataList[1].toIntOrNull() != null) { // Its ajax list
            if (dle_login_hash.isEmpty()) {
                val animeUrl = "$mainUrl/${dataList[1]}-temp.html"
                val pageDoc = app.get(animeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl
                    )
                ).document

                dle_login_hash = pageDoc
                    .body()
                    .selectFirst("script")
                    ?.html()
                    ?.substringAfterLast("dle_login_hash = '")
                    ?.substringBefore("';")
                    ?: ""
            }

            val ajax =
                    fromPlaylistAjax(
                            "$mainUrl/engine/ajax/playlists.php?news_id=${dataList[1]}&xfield=playlist&user_hash=$dle_login_hash")

            // Filter by name and isDub
            ajax
                    ?.filter { it.name == dataList[0] }
                    ?.filter { it.urls.isDub == dataList[2].toBoolean() }
                    ?.forEach {
                        with(it) {
                            when {
                                // Fix #2: ashdi.vip handler — kept as-is, still a valid extractor
                                it.urls.url.contains("https://ashdi.vip/vod") -> {
                                    M3u8Helper.generateM3u8(
                                            source = "${it.urls.playerName} (${it.urls.name})",
                                            streamUrl = AshdiExtractor().ParseM3U8(this.urls.url),
                                            referer = "https://qeruya.cyou")
                                            .dropLast(1).forEach(callback)
                                }
                                it.urls.url.contains("https://www.udrop.com") -> {
                                    callback.invoke(
                                        newExtractorLink(
                                            this.urls.url,
                                            "${it.urls.playerName} (${it.urls.name})",
                                            this.urls.url,
                                            ExtractorLinkType.M3U8
                                        )
                                    )
                                }
                                it.urls.url.contains("https://csst.online/embed/") ||
                                        it.urls.url.contains("https://monstro.site/embed/") -> {
                                    csstExtractor().ParseUrl(it.urls.url).split(",").forEach { csstUrl ->
                                        callback.invoke(
                                            newExtractorLink(
                                                this.urls.url,
                                                "${it.urls.playerName} (${it.urls.name}) ${csstUrl.substringBefore("]").drop(1)}",
                                                csstUrl.substringAfter("]"),
                                                ExtractorLinkType.M3U8
                                            )
                                        )
                                    }
                                }
                                it.urls.url.contains("https://www.mp4upload.com/") -> {
                                    getExtractorApiFromName("Mp4Upload").getUrl(it.urls.url)?.forEach { extlink ->
                                        callback.invoke(
                                            newExtractorLink(
                                                    extlink.source,
                                                    "${it.urls.playerName} (${it.urls.name})",
                                                    extlink.url,
                                            )
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
        } else {
            val document = app.get(dataList[1],
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).document
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    var latestNumber: Int? = 0
                    fromVideoContructor(script).forEach { dub ->
                        if (dub.episodeName == "ПЛЕЙЛИСТ") return@forEach

                        if (dub.episodeNumber == null) {
                            dub.episodeNumber = latestNumber?.plus(1)
                        }

                        latestNumber = dub.episodeNumber
                        if (latestNumber != dataList[0].toIntOrNull()) return@forEach

                        with(dub.episodeUrl) {
                            when {
                                // Fix #2: ashdi.vip handler — kept as-is
                                contains("https://ashdi.vip/vod") -> {
                                    M3u8Helper.generateM3u8(
                                            source = dub.playerName,
                                            streamUrl = AshdiExtractor().ParseM3U8(this),
                                            referer = "https://qeruya.cyou")
                                            .dropLast(1).forEach(callback)
                                }
                                contains("https://www.udrop.com") -> {
                                    callback.invoke(
                                        newExtractorLink(
                                                dub.playerName,
                                                name = dub.playerName,
                                                this,
                                            )
                                    )
                                }
                                contains("https://monstro.site/embed/") ||
                                        contains("https://csst.online/embed/") -> {
                                    csstExtractor().ParseUrl(this).split(",").forEach {
                                        callback.invoke(
                                            newExtractorLink(
                                                dub.playerName,
                                                name =
                                                "${dub.playerName.replace("\"", "")} ${it.substringBefore("]").drop(1)}",
                                                it.substringAfter("]"),
                                            )
                                        )
                                    }
                                }
                                contains("https://www.mp4upload.com/") -> {
                                    getExtractorApiFromName("Mp4Upload").getUrl(this)?.forEach { extlink ->
                                        callback.invoke(
                                            newExtractorLink(
                                                extlink.source,
                                                dub.playerName,
                                                extlink.url,
                                            )
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun decode(input: String): String {
        val hexRegex = Regex("\\\\u([0-9a-fA-F]{4})")
        return hexRegex.replace(input) { matchResult ->
            Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
        }
    }

    data class Responses(val success: Boolean?, val response: String?, val message: String?)

    private suspend fun fromPlaylistAjax(url: String, referer: String = "https://anitube.in.ua/"): List<Ajax>? {
        val responseGet = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT
            )
        ).parsedSafe<Responses>()

        if (responseGet?.success == false) {
            return null
        }

        val returnEpisodes = mutableListOf<Ajax>()

        val playlist = Jsoup.parse(responseGet?.response!!)

        // List 0: ОЗВУЧЕННЯ / СУБТИТРИ  — determines isDub
        // List 1: studio name (e.g. "Робота Голосом") — ignored, was wrongly used as dub/sub before
        // List 2: player names (ПЛЕЄР ASHDI / ПЛЕЄР MOON) — always the last playlists-items
        val allLists = playlist.select(".playlists-lists .playlists-items")

        val audios = mutableListOf<Pair<String, String>>()   // (name, data-id prefix)
        val listPlayers = mutableListOf<Pair<String, String>>() // (name, data-id prefix)

        allLists.firstOrNull()?.select("li")?.forEach {
            audios.add(Pair(it.text(), it.attr("data-id")))
        }
        allLists.lastOrNull()?.select("li")?.forEach {
            listPlayers.add(Pair(it.text(), it.attr("data-id")))
        }

        playlist.select(".playlists-videos .playlists-items li").forEach { element ->
            val audioId = element.attr("data-id")
            val episodeId = extractIntFromString(element.text())
            val url = element.attr("data-file")

            // Skip moonanime.art — not supported
            if (url.contains("moonanime.art")) return@forEach

            var isDub = true
            var audio: String? = null
            var playerName = ""

            // isDub is derived directly from the audios list:
            // "ОЗВУЧЕННЯ" -> isDub=true, "СУБТИТРИ" -> isDub=false
            audios.forEach {
                if (audioId.startsWith(it.second)) {
                    audio = it.first
                    isDub = !it.first.contains("СУБТИТРИ", ignoreCase = true)
                }
            }

            listPlayers.forEach {
                if (audioId.startsWith(it.second)) {
                    playerName = it.first
                }
            }

            returnEpisodes.add(
                    Ajax(
                            episodeId,
                            element.text(),
                            Link(
                                    isDub,
                                    url,
                                    audio.toString(),
                                    playerName,
                            )))
        }

        return returnEpisodes.toList()
    }

    private fun fromVideoContructor(script: Element): List<videoConstructor> {
        val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
        val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
        val playerNamesArray =
                (playerScriptRawJson.substringBefore("],") + "]")
                        .dropLast(1)
                        .drop(1)
                        .replace("\",\"", ",,,")
                        .split(",,,")

        val jsonEpisodes = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
        val episodes = mutableListOf<videoConstructor>()

        jsonEpisodes.forEachIndexed { index, episode ->
            val playerName = decode(playerNamesArray[index])
            episode.forEach {
                episodes.add(
                        videoConstructor(
                                playerName,
                                it.name,
                                extractIntFromString(it.name),
                                Jsoup.parse(it.code).select("iframe").attr("src")))
            }
        }
        return episodes.toList()
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}
