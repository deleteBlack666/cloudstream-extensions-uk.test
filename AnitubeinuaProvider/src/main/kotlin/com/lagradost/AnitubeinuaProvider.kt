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
        
        try {
            dle_login_hash = document
                    .body()
                    .selectFirst("script")!!
                    .html()
                    .substringAfterLast("dle_login_hash = '")
                    .substringBefore("';")
            Log.d(TAG, "Знайдено dle_login_hash у load(): $dle_login_hash, ID новини: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Помилка парсингу dle_login_hash у load()", e)
        }

        val ajax = fromPlaylistAjax(
                "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&user_hash=$dle_login_hash", referer = url)

        if (!ajax.isNullOrEmpty()) {
            Log.d(TAG, "Знайдено Ajax плейлист у load(), кількість елементів: ${ajax.size}")
            ajax
                    .groupBy { it.name }
                    .forEach { episodes ->
                        episodes.value.forEach lit@{
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
            Log.d(TAG, "Ajax плейлист порожній або відсутній, перевіряємо RalodePlayer у скриптах...")
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    Log.d(TAG, "Знайдено блок RalodePlayer.init")
                    val episodesList = fromVideoContructor(script)
                    Log.d(TAG, "RalodePlayer повернув серій: ${episodesList.size}")

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

        Log.d(TAG, "Завантажено епізодів у load(): Dubbed=${dubEpisodes.size}, Subbed=${subEpisodes.size}")
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
        
        val dataList = data.split(", ")
        Log.d(TAG, "Розбитий dataList: $dataList (Елементів: ${dataList.size})")
        
        if (dataList.size < 2) {
            Log.e(TAG, "Недостатньо елементів у dataList для обробки посилань!")
            return false
        }

        if (dataList[1].toIntOrNull() != null) { // Ajax список
            Log.d(TAG, "Гілка: Плейлист через AJAX (dataList[1] є ID новини: ${dataList[1]})")
            
            if (dle_login_hash.isEmpty()) {
                val animeUrl = "$mainUrl/${dataList[1]}-temp.html"
                Log.d(TAG, "dle_login_hash порожній, робимо запит до тимчасової сторінки: $animeUrl")
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
                Log.d(TAG, "Отримано новий dle_login_hash: $dle_login_hash")
            }
            
            val ajaxUrl = "$mainUrl/engine/ajax/playlists.php?news_id=${dataList[1]}&xfield=playlist&user_hash=$dle_login_hash"
            Log.d(TAG, "Запит плейлиста для loadLinks за адресою: $ajaxUrl")
            val ajax = fromPlaylistAjax(ajaxUrl)
            
            Log.d(TAG, "Всього елементів в AJAX перед фільтрацією: ${ajax?.size ?: 0}")

            ajax?.forEach { 
                Log.d(TAG, "Елемент плейлиста -> Назва: '${it.name}', isDub: ${it.urls.isDub}, URL: ${it.urls.url}")
            }

            val targetName = dataList[0]
            val targetDub = dataList[2].toBoolean()
            Log.d(TAG, "Фільтрація за критеріями -> Шукаємо назву серії: '$targetName' та озвучку (isDub): $targetDub")

            val filteredAjax = ajax
                    ?.filter { it.name == targetName }
                    ?.filter { it.urls.isDub == targetDub }

            Log.d(TAG, "Кількість елементів після фільтрації: ${filteredAjax?.size ?: 0}")

            filteredAjax?.forEach {
                        with(it) {
                            Log.d(TAG, "Обробка посилання від плеєра: ${it.urls.playerName} (${it.urls.name}) | URL: ${it.urls.url}")
                            when {
                                it.urls.url.contains("ashdi") -> {
                                    Log.d(TAG, "Знайдено Ashdi у списку Ajax. Запуск AshdiExtractor...")
                                    try {
                                        val extractedM3u8 = AshdiExtractor().ParseM3U8(this.urls.url, referer = "https://qeruya.cyou")
                                        Log.d(TAG, "AshdiExtractor успішно повернув посилання: $extractedM3u8")
                                        
                                        M3u8Helper.generateM3u8(
                                                source = "${it.urls.playerName} (${it.urls.name})",
                                                streamUrl = extractedM3u8,
                                                referer = "https://qeruya.cyou")
                                                .dropLast(1).forEach { link ->
                                                    Log.d(TAG, "Додано згенероване посилання m3u8: ${link.url}")
                                                    callback(link)
                                                }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Помилка екстракції Ashdi у гілці AJAX", e)
                                    }
                                }
                                it.urls.url.contains("https://www.udrop.com") -> {
                                    Log.d(TAG, "Обробка udrop: ${this.urls.url}")
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
                                    Log.d(TAG, "Обробка csst/monstro: ${this.urls.url}")
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
                                    Log.d(TAG, "Обробка Mp4Upload: ${this.urls.url}")
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
                                else -> {
                                    Log.w(TAG, "Невідомий або непідтримуваний тип плеєра для URL: ${it.urls.url}")
                                }
                            }
                        }
                    }
        } else { // Прямий плеєр зі сторінки (RalodePlayer)
            Log.d(TAG, "Гілка: RalodePlayer (Прямий плеєр на сторінці). URL сторінки: ${dataList[1]}")
            val document = app.get(dataList[1],
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).document
            
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    Log.d(TAG, "Знайдено RalodePlayer.init всередині loadLinks")
                    var latestNumber: Int? = 0
                    val constructors = fromVideoContructor(script)
                    Log.d(TAG, "Усього конструкторів відео знайдено: ${constructors.size}")

                    constructors.forEach { dub ->
                        if (dub.episodeName == "ПЛЕЙЛИСТ") return@forEach

                        if (dub.episodeNumber == null) {
                            dub.episodeNumber = latestNumber?.plus(1)
                        }

                        latestNumber = dub.episodeNumber
                        Log.d(TAG, "Ralode серія -> Поточний номер: $latestNumber | Очікуваний номер: ${dataList[0]}")
                        
                        if (latestNumber != dataList[0].toIntOrNull()) return@forEach

                        Log.d(TAG, "Збіг серії знайдено у Ralode! Назва плеєра: ${dub.playerName}, URL: ${dub.episodeUrl}")

                        with(dub.episodeUrl) {
                            when {
                                contains("ashdi") -> {
                                    Log.d(TAG, "Знайдено Ashdi у Ralode. Запуск AshdiExtractor...")
                                    try {
                                        val extractedM3u8 = AshdiExtractor().ParseM3U8(this, referer = "https://qeruya.cyou")
                                        Log.d(TAG, "AshdiExtractor успішно повернув посилання (Ralode): $extractedM3u8")
                                        
                                        M3u8Helper.generateM3u8(
                                                source = dub.playerName,
                                                streamUrl = extractedM3u8,
                                                referer = "https://qeruya.cyou")
                                                .dropLast(1).forEach(callback)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Помилка екстракції Ashdi у гілці RalodePlayer", e)
                                    }
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
                                else -> {
                                    Log.w(TAG, "Невідомий тип плеєра у Ralode для URL: $this")
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
            Log.w(TAG, "AJAX відповідь повернула серверну помилку (success = false або відповідь пуста)")
            return null
        }

        val returnEpisodes = mutableListOf<Ajax>()
        val playlist = Jsoup.parse(responseGet?.response!!)
        val audios = mutableListOf<Pair<String, String>>()
        val listDubStatus = mutableListOf<Pair<String, String>>()
        val listPlayers = mutableListOf<Pair<String, String>>()
        
        playlist.select(".playlists-lists .playlists-items:first-child li").forEach {
            audios.add(Pair(it.text(), it.attr("data-id")))
        }
        
        val itemsCount = playlist.select(".playlists-lists .playlists-items").count()
        Log.d(TAG, "Кількість контейнерів селектора у плейлисті: $itemsCount")

        if (itemsCount == 3) {
            playlist.select(".playlists-lists .playlists-items:nth-child(3) li").forEach {
                listPlayers.add(Pair(it.text(), it.attr("data-id")))
            }
            playlist.select(".playlists-lists .playlists-items:nth-child(2) li").forEach {
                listDubStatus.add(Pair(it.text(), it.attr("data-id")))
            }
        } else {
            playlist.select(".playlists-lists .playlists-items:nth-child(2) li").forEach {
                listPlayers.add(Pair(it.text(), it.attr("data-id")))
            }
        }
        
        Log.d(TAG, "Розпарсено з AJAX плейлиста -> Аудіо-категорії: ${audios.size}, Плеєри: ${listPlayers.size}, Озвучки: ${listDubStatus.size}")

        playlist.select(".playlists-videos .playlists-items li").forEach { element ->
            val audioId = element.attr("data-id")
            val episodeId = extractIntFromString(element.text())
            val fileUrl = element.attr("data-file")

            var isDub = true
            var audio: String? = null
            var playerName = ""

            audios.forEach {
                if (audioId.startsWith(it.second)) {
                    audio = it.first
                }
            }

            if (audioId.count { it == '_' } == 3) {
                listDubStatus.forEach {
                    if (audioId.startsWith(it.second)) {
                        if (it.first == "СУБТИТРИ") {
                            isDub = false
                        }
                    }
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
                                    fileUrl,
                                    audio.toString(),
                                    playerName,
                            )))
        }

        return returnEpisodes.toList()
    }

    private fun fromVideoContructor(script: Element): List<videoConstructor> {
        val returnEpisodes = mutableListOf<videoConstructor>()
        try {
            val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
            val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
            val playerNamesArray =
                    (playerScriptRawJson.substringBefore("],") + "]")
                            .dropLast(1)
                            .drop(1)
                            .replace("\",\"", ",,,")
                            .split(",,,")

            val jsonEpisodes = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!

            jsonEpisodes.forEachIndexed { index, episode ->
                val playerName = decode(playerNamesArray[index])
                episode.forEach {
                    returnEpisodes.add(
                            videoConstructor(
                                    playerName,
                                    it.name,
                                    extractIntFromString(it.name),
                                    Jsoup.parse(it.code).select("iframe").attr("src")))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Помилка обробки у fromVideoContructor", e)
        }
        return returnEpisodes.toList()
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }
        return value.value.toIntOrNull()
    }
}
