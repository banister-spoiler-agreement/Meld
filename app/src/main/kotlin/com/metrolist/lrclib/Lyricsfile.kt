/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.lrclib

import com.metrolist.music.betterlyrics.TTMLParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts an LRCLIB Lyricsfile (YAML or JSON) with word timings into Meld's word-synced LRC
 * (`[mm:ss.cc]line` followed by `<word:start:end|...>`), via [TTMLParser.toLRC].
 *
 * Lyricsfile "words" may be syllables. Like the TTML path, a fragment without trailing
 * whitespace is joined with the next one, so `"so" + "da"` becomes the word `soda`.
 */
internal object Lyricsfile {
    /** Word-synced LRC, or null if the file has no word timings or can't be read. */
    fun toWordSyncedLrc(lyricsfile: String?): String? {
        if (lyricsfile.isNullOrBlank()) return null
        val root = runCatching { parse(lyricsfile) }.getOrNull() as? Map<*, *> ?: return null
        val metadata = root["metadata"] as? Map<*, *>
        if (metadata?.get("instrumental") == true) return null
        val offsetMs = metadata?.get("offset_ms").asLong() ?: 0L

        val lines = (root["lines"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toParsedLine(offsetMs) }
        if (lines.none { it.words.isNotEmpty() }) return null
        return TTMLParser.toLRC(lines.sortedBy { it.startTime })
    }

    private class Fragment(val text: String, val startMs: Long, val endMs: Long?)

    private fun Map<*, *>.toParsedLine(offsetMs: Long): TTMLParser.ParsedLine? {
        val fragments = (this["words"] as? List<*>).orEmpty().mapNotNull { raw ->
            val word = raw as? Map<*, *> ?: return@mapNotNull null
            Fragment(
                text = word["text"] as? String ?: return@mapNotNull null,
                startMs = word["start_ms"].asLong() ?: return@mapNotNull null,
                endMs = word["end_ms"].asLong(),
            )
        }
        val words = mergeIntoWords(fragments, offsetMs)
        val startMs = this["start_ms"].asLong()?.plus(offsetMs)
            ?: words.firstOrNull()?.let { (it.startTime * 1000).toLong() }
            ?: return null
        val text = if (words.isNotEmpty()) words.joinToString(" ") { it.text } else (this["text"] as? String).orEmpty().trim()
        return TTMLParser.ParsedLine(text = text, startTime = startMs / 1000.0, words = words)
    }

    private fun mergeIntoWords(fragments: List<Fragment>, offsetMs: Long): List<TTMLParser.ParsedWord> {
        val words = mutableListOf<TTMLParser.ParsedWord>()
        val text = StringBuilder()
        var startMs = 0L
        fragments.forEachIndexed { i, fragment ->
            if (text.isEmpty()) startMs = fragment.startMs
            text.append(fragment.text)
            val isLast = i == fragments.lastIndex
            if (!isLast && fragment.text.lastOrNull()?.isWhitespace() != true) return@forEachIndexed
            val endMs = fragment.endMs ?: fragments.getOrNull(i + 1)?.startMs ?: (fragment.startMs + DEFAULT_WORD_MS)
            text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                words += TTMLParser.ParsedWord(it, (startMs + offsetMs) / 1000.0, (endMs + offsetMs) / 1000.0)
            }
            text.clear()
        }
        return words
    }

    private const val DEFAULT_WORD_MS = 300L

    private val json = Json { isLenient = true }

    private fun parse(text: String): Any? {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{")) json.parseToJsonElement(trimmed).toPlain() else LyricsfileYaml.parse(text)
    }

    private fun JsonElement.toPlain(): Any? = when (this) {
        is JsonObject -> mapValues { it.value.toPlain() }
        is JsonArray -> map { it.toPlain() }
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else content.toLongOrNull() ?: content.toDoubleOrNull() ?: content.toBooleanStrictOrNull()
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}
