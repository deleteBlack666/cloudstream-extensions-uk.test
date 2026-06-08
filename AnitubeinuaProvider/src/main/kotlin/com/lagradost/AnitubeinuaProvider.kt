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
private val TAG = "Anitubeinua"
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

    override val mainPage =
            mainPageOf(
                    "$mainUrl/anime/page/" to "Нові",
            )

    private var dle_login_hash = ""

    companion object {
        private const val TAG = "Anitubeinua"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

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
        if (!isSub && !isDub) {
            isSub = true
            isDub = true
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = mainUrl + posterUrl
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

        return document.select("article.story").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): AnimeLoadResponse {
    Log.d(TAG, "==== START LOAD ====")
    Log.d(TAG, "Завантаження сторінки аніме: $url")

    val document = app.get(url,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )
    ).document

    val someInfo = document.select(".story_c_r")

    val title = document.selectFirst(".story_c h2")?.text()?.trim().toString()
    val poster = mainUrl + document.selectFirst(".story_c_left span.story_post img")?.attr("src")
    val tags = someInfo.select("a[href*=/anime/]").map { it.text() }
    val year = someInfo.select("a[href*=/xfsearch/year/]").text().toIntOrNull()

    val tvType = TvType.Anime
    val description = document.selectFirst("div.my-text")?.text()?.trim()
    val trailer = document.selectFirst(".rcol a.rollover")?.attr("href").toString()
    val rating = document.selectFirst(".lexington-box > div:last-child span")?.text()

    val recommendations = document.select(".horizontal ul li").map { it.toSearchResponse() }

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

    Log.d(TAG, "Знайдено dle_login_hash у load(): $dle_login_hash, ID новини: $id")

    val ajax =
            fromPlaylistAjax(
                    "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&user_hash=$dle_login_hash", referer = url)

    if (!ajax.isNullOrEmpty()) {
        Log.d(TAG, "Знайдено Ajax плейлист у load(), кількість елементів: ${ajax.size}")
        
        // Групуємо за назвою серії, щоб створити лише один епізод в інтерфейсі
        ajax.groupBy { it.name }.forEach { entry ->
            val epName = entry.key
            val playerItems = entry.value
            val numberEpisode = playerItems.firstOrNull()?.numberEpisode

            // Перевіряємо, чи є в цієї серії бодай один плеєр з дубляжем чи субтитрами
            val hasDub = playerItems.any { it.urls.isDub && !it.urls.url.contains("video.ufdub") }
            val hasSub = playerItems.any { !it.urls.isDub && !it.urls.url.contains("video.ufdub") }

            if (hasDub) {
                dubEpisodes.add(
                    newEpisode("$epName, $id, true") {
                        this.name = epName
                        this.episode = numberEpisode
                    }
                )
            }
            if (hasSub) {
                subEpisodes.add(
                    newEpisode("$epName, $id, false") {
                        this.name = epName
                        this.episode = numberEpisode
                    }
                )
            }
        }
        Log.d(TAG, "Завантажено епізодів у load(): Dubbed=${dubEpisodes.size}, Subbed=${subEpisodes.size}")
    } else {
        Log.d(TAG, "Ajax плейлист порожній або відсутній, спроба через RalodePlayer конструктор")
        document.select("script").map { script ->
            if (script.data().contains("RalodePlayer.init(")) {
                Log.d(TAG, "Знайдено RalodePlayer.init() в скриптах сторінки")
                val episodesList = fromVideoContructor(script)
                Log.d(TAG, "Розпарсено через конструктор епізодів: ${episodesList.size}")

                // Групуємо серії з конструктора за назвою, щоб відсікти дублі плеєрів
                episodesList
                    .filter { it.episodeName != "ПЛЕЙЛИСТ" }
                    .groupBy { it.episodeName }
                    .forEach { entry ->
                        val epName = entry.key
                        val firstEp = entry.value.first()
                        val varEpisodeNumber = firstEp.episodeNumber ?: (episodesList.lastOrNull()?.episodeNumber?.plus(1))

                        dubEpisodes.add(
                            newEpisode("$varEpisodeNumber, $url") {
                                this.name = epName
                                this.episode = firstEp.episodeNumber
                                this.data = "$varEpisodeNumber, $url"
                            }
                        )
                    }
                Log.d(TAG, "Завантажено через конструктор епізодів: Dubbed=${dubEpisodes.size}, Subbed=0")
            }
        }
    }

    Log.d(TAG, "==== END LOAD ====")
    return newAnimeLoadResponse(title, url, tvType) {
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
    Log.d(TAG, "==== START LOAD LINKS ====")
    Log.d(TAG, "Вхідні дані (data): $data")
    
    // Видаляємо автоматичний префікс сайту, який додає додаток
    val cleanData = data.removePrefix(mainUrl).removePrefix("/")
    Log.d(TAG, "Очищені дані для обробки: $cleanData")

    // Універсальний спліттер для підтримки різних форматів даних ("|||" або ", ")
    val dataList = if (cleanData.contains("|||")) cleanData.split("|||") else cleanData.split(", ")
    Log.d(TAG, "Розбитий dataList: $dataList (Елементів: ${dataList.size})")

    if (dataList.size < 2) {
        Log.w(TAG, "Недостатньо елементів у dataList для вибору гілки")
        Log.d(TAG, "==== END LOAD LINKS ====")
        return false
    }

    if (dataList[1].toIntOrNull() != null) {
        Log.d(TAG, "Гілка: Плейлист через AJAX (ID новини: ${dataList[1]})")
        if (dle_login_hash.isEmpty()) {
            val animeUrl = "$mainUrl/${dataList[1]}-temp.html"
            Log.d(TAG, "dle_login_hash пустий, спроба відновити через сторінку: $animeUrl")
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
            Log.d(TAG, "Відновлено dle_login_hash: $dle_login_hash")
        }

        val ajaxUrl = "$mainUrl/engine/ajax/playlists.php?news_id=${dataList[1]}&xfield=playlist&user_hash=$dle_login_hash"
        Log.d(TAG, "Запит плейлиста за адресою: $ajaxUrl")
        val ajax = fromPlaylistAjax(ajaxUrl)

        val targetIsDub = dataList.getOrNull(2)?.toBoolean() ?: true
        Log.d(TAG, "Фільтрація AJAX -> Шукаємо назву серії: '${dataList[0]}' | Озвучка (isDub): $targetIsDub")

        val filteredAjax = ajax
                ?.filter { it.name == dataList[0] }
                ?.filter { it.urls.isDub == targetIsDub }

        Log.d(TAG, "Знайдено елементів після фільтрації: ${filteredAjax?.size ?: 0}")

        filteredAjax?.forEach {
            with(it) {
                Log.d(TAG, "Обробка посилання плеєра: ПЛЕЄР ${it.urls.playerName} (${it.urls.name}) -> ${it.urls.url}")
                when {
                    it.urls.url.contains("ashdi.vip") -> {
                        Log.d(TAG, "Запуск AshdiExtractor для гілки AJAX...")
                        val fixedUrl = if (it.urls.url.startsWith("//")) "https:${it.urls.url}" else it.urls.url
                        val streamUrl = AshdiExtractor().ParseM3U8(fixedUrl.replace("/embed/", "/vod/"))
                        Log.d(TAG, "AshdiExtractor успішно повернув: $streamUrl")
                        M3u8Helper.generateM3u8(
                                source = "${it.urls.playerName} (${it.urls.name})",
                                streamUrl = streamUrl,
                                referer = "https://qeruya.cyou")
                                .dropLast(1).forEach { link ->
                                    Log.d(TAG, "Додано посилання m3u8 у плеєр: ${link.url}")
                                    callback(link)
                                }
                    }
                    it.urls.url.contains("https://www.udrop.com") -> {
                        val link = newExtractorLink(
                            this.urls.url,
                            "${it.urls.playerName} (${it.urls.name})",
                            this.urls.url,
                            ExtractorLinkType.M3U8
                        )
                        Log.d(TAG, "Додано посилання udrop у плеєр: ${link.url}")
                        callback.invoke(link)
                    }
                    it.urls.url.contains("https://csst.online/embed/") ||
                            it.urls.url.contains("https://monstro.site/embed/") -> {
                        csstExtractor().ParseUrl(it.urls.url).split(",").forEach { csstUrl ->
                            val link = newExtractorLink(
                                this.urls.url,
                                "${it.urls.playerName} (${it.urls.name}) ${csstUrl.substringBefore("]").drop(1)}",
                                csstUrl.substringAfter("]"),
                                ExtractorLinkType.M3U8
                            )
                            Log.d(TAG, "Додано посилання csst/monstro у плеєр: ${link.url}")
                            callback.invoke(link)
                        }
                    }
                    it.urls.url.contains("https://www.mp4upload.com/") -> {
                        getExtractorApiFromName("Mp4Upload").getUrl(it.urls.url)?.forEach { extlink ->
                            val link = newExtractorLink(
                                    extlink.source,
                                    "${it.urls.playerName} (${it.urls.name})",
                                    extlink.url,
                            )
                            Log.d(TAG, "Додано посилання Mp4Upload у плеєр: ${link.url}")
                            callback.invoke(link)
                        }
                    }
                    else -> {
                        Log.w(TAG, "Невідомий тип плеєра для URL: ${it.urls.url}")
                    }
                }
            }
        }
    } else {
        Log.d(TAG, "Гілка: RalodePlayer Конструктор (Адреса сторінки: ${dataList[1]})")
        val document = app.get(dataList[1],
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document
        document.select("script").map { script ->
            if (script.data().contains("RalodePlayer.init(")) {
                Log.d(TAG, "Знайдено RalodePlayer.init() у гілці конструктора")
                var latestNumber: Int? = 0
                val constructorList = fromVideoContructor(script)
                Log.d(TAG, "Усього епізодів знайдено в конструкторі: ${constructorList.size}")

                constructorList.forEach { dub ->
                    if (dub.episodeName == "ПЛЕЙЛИСТ") return@forEach

                    if (dub.episodeNumber == null) {
                        dub.episodeNumber = latestNumber?.plus(1)
                    }

                    latestNumber = dub.episodeNumber
                    val targetEpisodeNum = dataList[0].toIntOrNull()
                    if (latestNumber != targetEpisodeNum) return@forEach

                    with(dub.episodeUrl) {
                        Log.d(TAG, "Обробка посилання плеєра: ПЛЕЄР ${dub.playerName} (${dub.episodeName}) -> $this")
                        when {
                            contains("ashdi.vip") -> {
                                Log.d(TAG, "Запуск AshdiExtractor для гілки RalodePlayer...")
                                val fixedUrl = if (this.startsWith("//")) "https:$this" else this
                                val streamUrl = AshdiExtractor().ParseM3U8(fixedUrl.replace("/embed/", "/vod/"))
                                Log.d(TAG, "AshdiExtractor успішно повернув: $streamUrl")
                                M3u8Helper.generateM3u8(
                                        source = dub.playerName,
                                        streamUrl = streamUrl,
                                        referer = "https://qeruya.cyou")
                                        .dropLast(1).forEach { link ->
                                            Log.d(TAG, "Додано посилання m3u8 у плеєр: ${link.url}")
                                            callback(link)
                                        }
                            }
                            contains("https://www.udrop.com") -> {
                                val link = newExtractorLink(
                                        dub.playerName,
                                        name = dub.playerName,
                                        this,
                                    )
                                Log.d(TAG, "Додано посилання udrop у плеєр: ${link.url}")
                                callback.invoke(link)
                            }
                            contains("https://monstro.site/embed/") ||
                                    contains("https://csst.online/embed/") -> {
                                csstExtractor().ParseUrl(this).split(",").forEach {
                                    val link = newExtractorLink(
                                        dub.playerName,
                                        name =
                                        "${dub.playerName.replace("\"", "")} ${it.substringBefore("]").drop(1)}",
                                        it.substringAfter("]"),
                                    )
                                    Log.d(TAG, "Додано посилання csst/monstro у плеєр: ${link.url}")
                                    callback.invoke(link)
                                }
                            }
                            contains("https://www.mp4upload.com/") -> {
                                getExtractorApiFromName("Mp4Upload").getUrl(this)?.forEach { extlink ->
                                    val link = newExtractorLink(
                                        extlink.source,
                                        dub.playerName,
                                        extlink.url,
                                    )
                                    Log.d(TAG, "Додано посилання Mp4Upload у плеєр: ${link.url}")
                                    callback.invoke(link)
                                }
                            }
                            else -> {
                                Log.w(TAG, "Невідомий тип плеєра для URL: $this")
                            }
                        }
                    }
                }
            }
        }
    }
    Log.d(TAG, "==== END LOAD LINKS ====")
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
        Log.d(TAG, "Виклик віджера Ajax за URL: $url")
        val responseGet = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT
            )
        ).parsedSafe<Responses>()

        if (responseGet?.success == false || responseGet?.response.isNullOrEmpty()) {
            Log.w(TAG, "Відповідь AJAX неуспішна або порожня")
            return null
        }

        val returnEpisodes = mutableListOf<Ajax>()
        val playlist = Jsoup.parse(responseGet?.response!!)
        val allLists = playlist.select(".playlists-lists .playlists-items")
        Log.d(TAG, "Кількість контейнерів селектора у плейлисті: ${allLists.size}")

        val audios = mutableListOf<Pair<String, String>>()
        val listPlayers = mutableListOf<Pair<String, String>>()

        allLists.firstOrNull()?.select("li")?.forEach {
            audios.add(Pair(it.text(), it.attr("data-id")))
        }
        allLists.lastOrNull()?.select("li")?.forEach {
            listPlayers.add(Pair(it.text(), it.attr("data-id")))
        }
        Log.d(TAG, "Розпарсено категорій плейлиста -> Аудіо: ${audios.size}, Плеєри: ${listPlayers.size}")

        playlist.select(".playlists-videos .playlists-items li").forEach { element ->
            val audioId = element.attr("data-id")
            val episodeId = extractIntFromString(element.text())
            val url = element.attr("data-file")

            if (url.contains("moonanime.art")) return@forEach

            var isDub = true
            var audio: String? = null
            var playerName = ""

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
