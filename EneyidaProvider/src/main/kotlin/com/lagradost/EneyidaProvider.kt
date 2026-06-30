package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class EneyidaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://eneyida.tv"
    override var name = "Eneyida"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // Витягує JSON-рядок з JS-коду плеєра.
    // JSON може бути обгорнутий в одинарні АБО подвійні лапки:
    //   file: '[{"title":"1 сезон",...}]'   <- серіал (масив)
    //   file: 'https://example.com/video.m3u8' <- фільм (пряме посилання)
    private fun extractFileValue(scriptHtml: String): String {
        // Знаходимо позицію ключа file: (з можливими пробілами)
        val keyRegex = Regex("""file\s*:\s*(['"])""")
        val match = keyRegex.find(scriptHtml) ?: return ""
        val quote = match.groupValues[1] // одинарна або подвійна лапка
        val start = match.range.last + 1 // індекс після відкриваючої лапки

        // Шукаємо закриваючу лапку тієї ж самої рядку,
        // пропускаючи екрановані послідовності (\' або \")
        var i = start
        val sb = StringBuilder()
        while (i < scriptHtml.length) {
            val c = scriptHtml[i]
            if (c == '\\' && i + 1 < scriptHtml.length) {
                // Екранований символ — пропускаємо обидва
                sb.append(scriptHtml[i + 1])
                i += 2
                continue
            }
            if (c.toString() == quote) break // Кінець рядка
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/cartoon-series/page/" to "Мультсеріали",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("article.short").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("a.short_title")?.text()?.trim().toString()
        val href = this.selectFirst("a.short_title")?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst("a.short_img img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select("article.short").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        val fullInfo = document.select(".full_info li")
        val title = document.selectFirst("div.full_header-title h1")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".full_content-poster img")?.attr("src")
        val banner = document.select(".full_header__bg-img").attr("style").substringAfterLast("url(").substringBefore(");")
        val tags = fullInfo[1].select("a").map { it.text() }
        val year = fullInfo[0].select("a").text().toIntOrNull()
        val playerUrl = document.select(".tabs_b.visible iframe").attr("src")

        val tvType = if (tags.contains("фільм") or tags.contains("мультьфільм") or playerUrl.contains("/vod/")) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst(".full_content-desc p")?.text()?.trim()
        val trailer = document.selectFirst("div#trailer_place iframe")?.attr("src").toString()
        val rating = document.selectFirst(".r_kp span, .r_imdb span")?.text()
        val actors = fullInfo[4].select("a").map { it.text() }

        val recommendations = document.select(".short.related_item").map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()

            val scriptHtml = app.get(playerUrl).document.select("script").html()
            val playerRawJson = extractFileValue(scriptHtml)

            // Реальна ієрархія JSON:
            // Корінь (List<PlayerJson>) -> Сезон (title: "1 сезон", folder: List<DubFolder>)
            //   -> Озвучка (title: "Цікава Ідея", folder: List<EpisodeFolder>)
            //     -> Серія (title: "1 серія", file: "url.m3u8")
            //
            // Щоб уникнути дублювання: для кожної серії всередині сезону
            // створюємо одну кнопку в UI (ключ = seasonTitle|episodeTitle),
            // а всі озвучки зберігаємо у data через "|dub1_url|dub2_url|..."
            // (розпарсюємо у loadLinks).

            tryParseJson<List<PlayerJson>>(playerRawJson)?.forEach { seasonItem ->
                val seasonTitle = seasonItem.title // "1 сезон", "2 сезон" ...
                val seasonNum = seasonTitle.replace(" сезон", "").trim().toIntOrNull()

                // episodeMap: episodeTitle -> poster (беремо з першої доступної озвучки)
                val episodeMap = linkedMapOf<String, String?>()

                seasonItem.folder?.forEach { dub ->
                    dub.folder?.forEach { episode ->
                        if (!episodeMap.containsKey(episode.title)) {
                            episodeMap[episode.title] = episode.poster
                        }
                    }
                }

                // Створюємо одну кнопку на серію; всі URL озвучок передаємо в data
                for ((episodeTitle, episodePoster) in episodeMap) {
                    val episodeNum = episodeTitle.replace(" серія", "").trim().toIntOrNull()

                    // data формат: "seasonTitle|episodeTitle|playerUrl"
                    val dataStr = "$seasonTitle|$episodeTitle|$playerUrl"

                    episodes.add(
                        newEpisode(dataStr) {
                            this.name = episodeTitle
                            this.season = seasonNum
                            this.episode = episodeNum
                            this.posterUrl = episodePoster
                            this.data = dataStr
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "${title.replace("|", "")}|$playerUrl") {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serisl) [Season, Episode, Player Url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split("|")

        val scriptHtml = app.get(dataList.last()).document.select("script").html()
        val playerRawJson = extractFileValue(scriptHtml)

        // Its film, parse one m3u8
        if (dataList.size == 2) {
            val m3u8Url = playerRawJson
            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url.replace("https://", "http://"),
                referer = "https://tortuga.wtf/"
            ).dropLast(1).forEach(callback)

            val subtitleUrl = subtitleRegex.find(scriptHtml)?.groupValues?.get(1) ?: ""

            if (subtitleUrl.isBlank()) return true
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitleUrl.substringAfterLast("[").substringBefore("]"),
                    subtitleUrl.substringAfter("]")
                )
            )
            return true
        }

        // dataList[0] = seasonTitle ("1 сезон")
        // dataList[1] = episodeTitle ("1 серія")
        // dataList[2] = playerUrl
        val targetSeason = dataList[0]
        val targetEpisode = dataList[1]

        // Ієрархія: Сезон -> Озвучка -> Серія
        // Проходимо всі озвучки для обраної серії і генеруємо окремий ExtractorLink на кожну
        tryParseJson<List<PlayerJson>>(playerRawJson)?.forEach { seasonItem ->
            if (seasonItem.title != targetSeason) return@forEach

            seasonItem.folder?.forEach { dub ->
                val dubTitle = dub.title // "Цікава Ідея", "MGG", "HDrezka Studio" ...

                dub.folder
                    ?.filter { it.title == targetEpisode && !it.file.isNullOrBlank() }
                    ?.forEach { episode ->
                        M3u8Helper.generateM3u8(
                            source = dubTitle,
                            streamUrl = episode.file!!,
                            referer = "https://tortuga.wtf/"
                        ).dropLast(1).forEach(callback)

                        episode.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleRaw ->
                            subtitleRaw.indexOf(']').takeIf { it > 0 }?.let { endIndex ->
                                subtitleCallback(
                                    newSubtitleFile(
                                        subtitleRaw.substring(subtitleRaw.lastIndexOf('[') + 1, endIndex),
                                        subtitleRaw.substring(endIndex + 1)
                                    )
                                )
                            }
                        }
                    }
            }
        }
        return true
    }
}
