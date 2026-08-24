package com.glasscast.sender

import java.net.URI

/** Pure Kotlin URL normalization shared by paste, share, and Open with GlassCast flows. */
object SupportedLinkNormalizer {
    private val webUrlPattern = Regex("""https?://[^\s<>\"']+""", RegexOption.IGNORE_CASE)
    private val explicitlyUnsafeSchemes = setOf("file", "content", "javascript", "data", "intent")

    fun normalize(input: String?): String? {
        val text = input.orEmpty().trim().trimSurroundingQuotes()
        if (text.isBlank() || startsWithUnsafeScheme(text)) return null

        val candidate = (webUrlPattern.find(text)?.value ?: text)
            .trim()
            .trimEnd(')', ']', '}', ',', ';', '.', '"', '\'')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (uri.userInfo != null || host.isBlank()) return null

        return when {
            host == "youtu.be" || host == "www.youtu.be" -> normalizeYouTubeShortLink(uri)
            isYouTubeHost(host) -> normalizeYouTube(uri)
            host == "dai.ly" || host == "www.dai.ly" -> normalizeDailymotionShortLink(uri)
            host == "m.dailymotion.com" -> rebuild(uri, host = "www.dailymotion.com", scheme = "https")
            else -> uri.toASCIIString()
        }
    }

    private fun normalizeYouTubeShortLink(uri: URI): String? {
        val videoId = uri.path.orEmpty().trim('/').substringBefore('/').takeIf(::isSafeVideoId) ?: return null
        return youtubeWatchUrl(videoId, uri.rawQuery, uri.rawFragment)
    }

    private fun normalizeYouTube(uri: URI): String? {
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.firstOrNull() in setOf("shorts", "live", "embed")) {
            val videoId = segments.getOrNull(1)?.takeIf(::isSafeVideoId) ?: return null
            return youtubeWatchUrl(videoId, uri.rawQuery, uri.rawFragment)
        }
        return rebuild(uri, host = "www.youtube.com", scheme = "https")
    }

    private fun normalizeDailymotionShortLink(uri: URI): String? {
        val videoId = uri.path.orEmpty().trim('/').substringBefore('/').takeIf(::isSafeVideoId) ?: return null
        return rebuild(uri, host = "www.dailymotion.com", path = "/video/$videoId", scheme = "https")
    }

    private fun youtubeWatchUrl(videoId: String, rawQuery: String?, rawFragment: String?): String {
        val preserved = rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() && !it.startsWith("v=", ignoreCase = true) }
            .orEmpty()
        val query = (listOf("v=$videoId") + preserved).joinToString("&")
        return render("https", "www.youtube.com", -1, "/watch", query, rawFragment)
    }

    private fun rebuild(
        uri: URI,
        host: String,
        path: String = uri.rawPath.orEmpty(),
        scheme: String = uri.scheme.lowercase()
    ): String = render(scheme, host, uri.port, path.ifBlank { "/" }, uri.rawQuery, uri.rawFragment)

    private fun render(
        scheme: String,
        host: String,
        port: Int,
        rawPath: String,
        rawQuery: String?,
        rawFragment: String?
    ): String = buildString {
        append(scheme)
        append("://")
        append(host)
        if (port >= 0) append(":$port")
        append(rawPath)
        if (!rawQuery.isNullOrEmpty()) append("?$rawQuery")
        if (!rawFragment.isNullOrEmpty()) append("#$rawFragment")
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" ||
            host == "music.youtube.com"

    private fun isSafeVideoId(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun startsWithUnsafeScheme(text: String): Boolean {
        val scheme = text.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in explicitlyUnsafeSchemes
    }

    private fun String.trimSurroundingQuotes(): String {
        if (length < 2) return this
        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, length - 1).trim()
        } else {
            this
        }
    }
}
