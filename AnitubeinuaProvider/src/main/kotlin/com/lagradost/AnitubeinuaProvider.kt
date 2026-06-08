package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.util.ArrayList

class AnitubeinuaProvider : MainAPI() {
    override var mainUrl = "https://anitube.in.ua"
    override var name = "AniTube.in.ua"
    override val hasMainPage = true
    override var lang = "uk"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = ArrayList<HomePageList>()
        
        val animeList = document.select("article.story").mapNotNull {
            it.toSearchResult()
        }
        if (animeList.isNotEmpty()) {
            home.add(HomePageList("Останні оновлення", animeList))
        }
        return newHomePageResponse(home, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to "1",
                "full_search" to "0",
                "result_from" to "1",
                "story" to query
            )
        ).document

        return document.select("article.story").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.story_title a")?.text() ?: return null
        val href = this.selectFirst("h2.story_title a")?.attr("href") ?: return null
        val poster = this.selectFirst("div.story_poster img")?.attr("src")?.let { fixUrl(it) } ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val document = Jsoup.parse(response)

        val title = document.selectFirst("header.full_title h1")?.text() ?: ""
        val poster = document.selectFirst("div.full_poster img")?.attr("src")?.let { fixUrl(it) } ?: ""
        val description = document.selectFirst("div.full_desc")?.text() ?: ""
        
        val newsId = document.selectFirst(".playlists-ajax")?.attr("data-news_id") ?: ""
        val userHash = "51200fa6cf59dd05ff4679d4d70672a59c07da2c"

        val episodesList = ArrayList<Episode>()

        if (newsId.isNotEmpty()) {
            val playlistUrl = "$mainUrl/engine/ajax/playlists.php?news_id=$newsId&xfield=playlist&user_hash=$userHash"
            val playlistResponse = app.get(playlistUrl).text
            val playlistDoc = Jsoup.parse(playlistResponse)

            val audios = mutableListOf<Pair<String, String>>()
            playlistDoc.select("ul.playlists-audio li").forEach {
                audios.add(Pair(it.text(), it.attr("data-audio_id")))
            }

            val episodesNames = playlistDoc.select("ul.playlists-videos li").map { it.text() }.distinct()

            for (epName in episodesNames) {
                var hasDub = false
                var hasSub = false

                playlistDoc.select("ul.playlists-videos li").forEach { element ->
                    if (element.text() == epName) {
                        val audioId = element.attr("data-audio_id") ?: ""
                        audios.forEach {
                            if (audioId == it.second || audioId.startsWith(it.second)) {
                                if (it.first.contains("СУБТИТРИ", ignoreCase = true)) {
                                    hasSub = true
                                } else {
                                    hasDub = true
                                }
                            }
                        }
                    }
                }

                if (hasDub) {
                    episodesList.add(newEpisode("$epName, $newsId, true") {
                        this.name = "$epName (Озвучення)"
                        this.episode = epName.replace(Regex("\\D+"), "").toIntOrNull()
                    })
                }
                if (hasSub) {
                    episodesList.add(newEpisode("$epName, $newsId, false") {
                        this.name = "$epName (Субтитри)"
                        this.episode = epName.replace(Regex("\\D+"), "").toIntOrNull()
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(TvType.Anime, episodesList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        if (dataList.size < 3) return false
        val episodeName = dataList[0]
        val newsId = dataList[1]
        val isDubParam = dataList[2].toBoolean()

        val userHash = "51200fa6cf59dd05ff4679d4d70672a59c07da2c"
        val playlistUrl = "$mainUrl/engine/ajax/playlists.php?news_id=$newsId&xfield=playlist&user_hash=$userHash"
        
        val response = app.get(playlistUrl).text
        val document = Jsoup.parse(response)

        val audios = mutableListOf<Pair<String, String>>()
        document.select("ul.playlists-audio li").forEach {
            audios.add(Pair(it.text(), it.attr("data-audio_id")))
        }

        val listPlayers = mutableListOf<Pair<String, String>>()
        document.select("ul.playlists-player li").forEach {
            listPlayers.add(Pair(it.text(), it.attr("data-player_id")))
        }

        val linksList = mutableListOf<EpisodeLink>()
        document.select("ul.playlists-videos li").forEach { element ->
            if (element.text() == episodeName) {
                val audioId = element.attr("data-audio_id") ?: ""
                val playerId = element.attr("data-player_id") ?: ""
                val url = element.attr("data-file") ?: ""

                var audioName = ""
                var isDub = true
                audios.forEach {
                    if (audioId == it.second || audioId.startsWith(it.second)) {
                        audioName = it.first
                        isDub = !it.first.contains("СУБТИТРИ", ignoreCase = true)
                    }
                }

                var playerName = ""
                listPlayers.forEach {
                    if (playerId == it.second || playerId.startsWith(it.second)) {
                        playerName = it.first
                    }
                }

                if (isDub == isDubParam && url.isNotEmpty()) {
                    linksList.add(EpisodeLink(url, audioName, playerName))
                }
            }
        }

        val addedLinks = HashSet<String>()

        linksList.forEach { item ->
            if (item.url.contains("ashdi")) {
                M3u8Helper.generateM3u8(
                    source = item.playerName,
                    streamUrl = item.url,
                    referer = "$mainUrl/"
                ).forEach { link ->
                    val uniqueKey = "${link.url}_${link.quality}_${item.audioName}"
                    if (!addedLinks.contains(uniqueKey)) {
                        addedLinks.add(uniqueKey)
                        callback.invoke(
                            ExtractorLink(
                                link.source,
                                "${link.name} (${item.audioName})",
                                link.url,
                                link.referer,
                                link.quality,
                                link.isM3u8,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
                }
            } else {
                loadExtractor(item.url) { link ->
                    val uniqueKey = "${link.url}_${link.quality}_${item.audioName}"
                    if (!addedLinks.contains(uniqueKey)) {
                        addedLinks.add(uniqueKey)
                        callback.invoke(
                            ExtractorLink(
                                link.source,
                                "${link.name} (${item.audioName})",
                                link.url,
                                link.referer,
                                link.quality,
                                link.isM3u8,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
                }
            }
        }

        return true
    }

    data class EpisodeLink(
        val url: String,
        val audioName: String,
        val playerName: String
    )
}
