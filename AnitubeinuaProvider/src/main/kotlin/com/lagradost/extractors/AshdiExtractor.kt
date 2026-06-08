package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class AshdiExtractor {
    suspend fun ParseM3U8(url: String, referer: String = "https://anitube.in.ua/"): String {
        // Виконуємо запит із заголовками для обходу захисту плеєра
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0",
                "Referer" to referer
            )
        ).text

        // Надійний пошук m3u8 за допомогою регулярного виразу
        val regex = """file\s*:\s*['"](https?://[^'"]+\.m3u8.*?)['"]""".toRegex()
        
        return regex.find(html)?.groupValues?.get(1) 
            ?: throw Exception("Не вдалося витягнути посилання (.m3u8) з плеєра Ashdi")
    }
}
