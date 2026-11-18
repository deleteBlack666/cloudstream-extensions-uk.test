package com.lagradost.models

import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request

object ProviderUtils {

    fun isImageBytes(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 12) return false

        fun b(i: Int): Int = bytes[i].toInt() and 0xFF

        return (b(0) == 0x89 && b(1) == 0x50) ||
            (b(0) == 0xFF && b(1) == 0xD8) ||
            (b(0) == 0x52 && b(1) == 0x49 && b(8) == 0x57 && b(9) == 0x45) ||
            (b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70) ||
            (b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46)
    }

    fun imageContentType(bytes: ByteArray): String {
        fun b(i: Int): Int = bytes[i].toInt() and 0xFF

        return when {
            bytes.size > 8 && b(0) == 0x89 && b(1) == 0x50 -> "image/png"
            bytes.size > 2 && b(0) == 0xFF && b(1) == 0xD8 -> "image/jpeg"
            bytes.size > 9 && b(0) == 0x52 && b(1) == 0x49 -> "image/webp"
            bytes.size > 8 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    fun filterStreams(streams: List<ExtractorLink>): List<ExtractorLink> {
        val filtered = streams.dropLast(1)
        return if (filtered.isNotEmpty()) filtered else streams
    }

    fun fixMovieExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/") -> 720
            link.url.contains("/480/") -> 480
            link.url.contains("/360/") -> 360
            else -> when (link.quality) {
                in 900..1150 -> 1080
                in 600..899 -> 720
                in 400..599 -> 480
                in 240..399 -> 360
                else -> link.quality
            }
        }

        return ExtractorLink(
            source = link.source,
            name = sourceName,
            url = link.url,
            referer = link.referer,
            quality = cleanQuality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
    }

    fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun buildEpisodeDataJson(sources: List<EpisodeSource>): String {
        val sb = StringBuilder("[")
        sources.forEachIndexed { index, s ->
            if (index > 0) sb.append(",")
            sb.append("{\"translationName\":\"${escapeJson(s.translationName)}\",")
            sb.append("\"playerName\":\"${escapeJson(s.playerName)}\",")
            sb.append("\"episodeId\":${s.episodeId},")
            sb.append("\"episodeNum\":${s.episodeNum ?: 0}}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun subtitleExtFromUrl(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")

        return when {
            path.endsWith(".ass", true) -> ".ass"
            path.endsWith(".ssa", true) -> ".ass"
            path.endsWith(".vtt", true) -> ".vtt"
            path.endsWith(".srt", true) -> ".srt"
            else -> ".vtt"
        }
    }

    fun subtitleContentType(ext: String): String {
        return when (ext) {
            ".ass" -> "text/x-ssa"
            ".srt" -> "application/x-subrip"
            else -> "text/vtt"
        }
    }

    fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }
        return value.value.toIntOrNull()
    }

    fun currentSeasonLabel(): String {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1

        val seasonUa = when (month) {
            12, 1, 2 -> "зимового"
            3, 4, 5 -> "весняного"
            6, 7, 8 -> "літнього"
            else -> "осіннього"
        }

        return "Аніме ${seasonUa} сезону"
    }

    fun checkHevcMain10(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 21) return false
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                if (info.isEncoder) {
                    false
                } else {
                    try {
                        val caps = info.getCapabilitiesForType("video/hevc") ?: return@any false
                        val videoCaps = caps.videoCapabilities ?: return@any false
                        val hasMain10 = caps.profileLevels?.any {
                            it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        } == true
                        hasMain10 && videoCaps.isSizeSupported(1920, 1080)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private val vp9ProfileCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun vp9Profile(url: String, headers: Map<String, String>): Int? {
        vp9ProfileCache[url]?.let { return it }

        val bytes = try {
            val req = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Range", "bytes=0-65535")
                .get()
                .build()
            OkHttpClient().newCall(req).execute().use { it.body?.bytes() }
                ?: return null
        } catch (e: Exception) {
            return null
        }

        val profile = parseVp9Profile(bytes)
        if (profile != null) vp9ProfileCache[url] = profile
        return profile
    }

    private fun parseVp9Profile(b: ByteArray): Int? {
        if (b.size < 3) return null
        for (i in 0 until b.size - 2) {
            if (b[i] == 0x25.toByte() && b[i + 1] == 0x86.toByte() && b[i + 2] == 0x81.toByte()) {
                return b[i + 3].toInt() and 0xFF
            }
        }
        return null
    }

    private fun vp9ProfileToConstant(profile: Int): Int? = when (profile) {
        0 -> android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile0
        1 -> android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile1
        2 -> android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2
        3 -> android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile3
        else -> null
    }

    fun vp9ProfileUnsupportedByHardware(profile: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 21) return false

        val constant = vp9ProfileToConstant(profile) ?: return false

        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)

            fun isSoftware(n: String) = n.startsWith("c2.android.") || n.startsWith("omx.google.")

            fun supports(info: android.media.MediaCodecInfo): Boolean {
                val caps = info.getCapabilitiesForType("video/x-vnd.on2.vp9") ?: return false
                return caps.profileLevels?.any { it.profile == constant } == true
            }

            val decoders = list.codecInfos.filter { info ->
                !info.isEncoder && try {
                    info.getCapabilitiesForType("video/x-vnd.on2.vp9") != null
                } catch (e: Exception) {
                    false
                }
            }

            val hw = decoders.filter { !isSoftware(it.name) }
            val sw = decoders.filter { isSoftware(it.name) }

            hw.isNotEmpty() && hw.none { supports(it) } && sw.any { supports(it) }
        } catch (e: Exception) {
            false
        }
    }
}
