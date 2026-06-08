package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class AshdiExtractor {
    suspend fun ParseM3U8(url: String, referer: String = "https://anitube.in.ua/"): String {
       // Надійний пошук m3u8 за допомогою регулярного виразу
        val regex = """file\s*:\s*['"](https?://[^'"]+\.m3u8.*?)['"]""".toRegex()
        
        return regex.find(html)?.groupValues?.get(1) 
            ?: throw Exception("Не вдалося витягнути посилання (.m3u8) з плеєра Ashdi")
    }
}
