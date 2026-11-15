package es.m3ulive.player.data

import es.m3ulive.player.model.Channel
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest

/** Parser tolerant of common M3U playlist extensions used by live-TV providers. */
object M3uParser {
    private data class PendingEntry(
        var name: String,
        var group: String,
        val logo: String,
        val tvgId: String,
        val headers: MutableMap<String, String> = linkedMapOf()
    )

    private val attributePattern = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]*))""")

    fun parse(content: String): List<Channel> {
        val firstContent = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        require(firstContent.startsWith("#EXTM3U", ignoreCase = true)) {
            "El archivo no parece una lista M3U válida: falta #EXTM3U."
        }

        val result = ArrayList<Channel>()
        var pending: PendingEntry? = null

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    val comma = findUnquotedComma(info)
                    val attributesPart = if (comma >= 0) info.substring(0, comma) else info
                    val displayName = if (comma >= 0) info.substring(comma + 1).trim() else ""
                    val attributes = parseAttributes(attributesPart)
                    val name = displayName.ifBlank { attributes["tvg-name"].orEmpty() }
                        .ifBlank { "Canal sin nombre" }
                    pending = PendingEntry(
                        name = name,
                        group = attributes["group-title"].orEmpty().ifBlank { "Sin categoría" },
                        logo = attributes["tvg-logo"].orEmpty(),
                        tvgId = attributes["tvg-id"].orEmpty()
                    )
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pending?.group = line.substringAfter(':').trim().ifBlank { "Sin categoría" }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    pending?.let { parseVlcOption(line.substringAfter(':').trim(), it.headers) }
                }

                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    pending?.let { parseExtHttp(line.substringAfter(':').trim(), it.headers) }
                }

                line.startsWith("#") -> Unit // Ignore comments and tags not needed for channel indexing.

                pending != null -> {
                    val (url, inlineHeaders) = splitInlineHeaders(line)
                    if (url.isNotBlank()) {
                        val entry = pending!!
                        val headers = LinkedHashMap(entry.headers)
                        headers.putAll(inlineHeaders)
                        val identity = if (entry.tvgId.isNotBlank()) entry.tvgId else entry.name
                        result += Channel(
                            id = stableId("$url|$identity"),
                            name = entry.name,
                            streamUrl = url,
                            group = entry.group.ifBlank { "Sin categoría" },
                            logoUrl = entry.logo,
                            tvgId = entry.tvgId,
                            headers = headers
                        )
                    }
                    pending = null
                }
            }
        }

        return result
    }

    private fun findUnquotedComma(value: String): Int {
        var quote: Char? = null
        value.forEachIndexed { index, char ->
            when {
                quote == null && (char == '\"' || char == '\'') -> quote = char
                quote == char -> quote = null
                quote == null && char == ',' -> return index
            }
        }
        return -1
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        attributePattern.findAll(source).forEach { match ->
            val value = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: ""
            result[match.groupValues[1].lowercase()] = value
        }
        return result
    }

    private fun parseVlcOption(option: String, headers: MutableMap<String, String>) {
        val key = option.substringBefore('=', "").trim().lowercase()
        val value = option.substringAfter('=', "").trim().trim('"', '\'')
        if (value.isBlank()) return
        when (key) {
            "http-user-agent" -> headers["User-Agent"] = value
            "http-referrer", "http-referer" -> headers["Referer"] = value
            "http-origin" -> headers["Origin"] = value
            "http-cookie" -> headers["Cookie"] = value
            else -> Unit
        }
    }

    private fun parseExtHttp(json: String, headers: MutableMap<String, String>) {
        try {
            val root = JSONObject(json)
            val source = root.optJSONObject("headers") ?: root
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = source.optString(key, "")
                if (value.isNotBlank()) headers[key] = value
            }
        } catch (_: Exception) {
            // Some playlists contain non-standard EXTHTTP values; ignore them safely.
        }
    }

    private fun splitInlineHeaders(line: String): Pair<String, Map<String, String>> {
        val separator = line.indexOf('|')
        if (separator < 0) return line to emptyMap()
        val possibleHeaders = line.substring(separator + 1)
        val headers = linkedMapOf<String, String>()
        possibleHeaders.split('&').forEach { item ->
            val key = item.substringBefore('=', "").trim()
            val rawValue = item.substringAfter('=', "").trim()
            if (key.isBlank() || rawValue.isBlank()) return@forEach
            val headerName = when (key.lowercase()) {
                "user-agent", "http-user-agent" -> "User-Agent"
                "referer", "referrer", "http-referrer" -> "Referer"
                "origin" -> "Origin"
                "cookie" -> "Cookie"
                else -> return@forEach
            }
            val value = try {
                URLDecoder.decode(rawValue, Charsets.UTF_8.name())
            } catch (_: Exception) {
                rawValue
            }
            headers[headerName] = value
        }
        return if (headers.isEmpty()) line to emptyMap() else line.substring(0, separator) to headers
    }

    private fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
