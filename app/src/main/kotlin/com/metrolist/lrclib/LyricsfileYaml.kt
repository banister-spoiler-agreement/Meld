/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.lrclib

/**
 * Minimal block-style YAML reader, scoped to the Lyricsfile documents LRCLIB serves
 * (serde_yaml output): nested mappings, block sequences, plain / single- / double-quoted
 * scalars and `|` / `>` block scalars. Flow collections are only supported when empty.
 *
 * Returns maps, lists and scalars (String, Long, Double, Boolean, null).
 */
internal object LyricsfileYaml {
    private class Line(val indent: Int, val text: String)

    private class Cursor(val lines: List<Line>) {
        var pos = 0
        val current get() = lines.getOrNull(pos)
    }

    fun parse(source: String): Any? {
        val lines = source.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.trim() != "---" }
            .map { Line(it.length - it.trimStart().length, it.trimStart()) }
        val cursor = Cursor(lines)
        return cursor.current?.let { parseBlock(cursor, it.indent) }
    }

    private fun Line.isSeqItem() = text == "-" || text.startsWith("- ")

    private fun parseBlock(c: Cursor, indent: Int): Any? =
        if (c.current?.isSeqItem() == true) parseSeq(c, indent) else parseMap(c, indent)

    private fun parseSeq(c: Cursor, indent: Int): List<Any?> {
        val out = mutableListOf<Any?>()
        while (true) {
            val line = c.current ?: break
            if (line.indent != indent || !line.isSeqItem()) break
            val rest = line.text.removePrefix("-").trimStart()
            when {
                rest.isEmpty() -> {
                    c.pos++
                    out += c.current?.takeIf { it.indent > indent }?.let { parseBlock(c, it.indent) }
                }
                // "- key: value" opens a mapping whose remaining keys sit at the item's content column.
                keyOf(rest) != null -> out += parseMap(c, indent + (line.text.length - rest.length), firstEntry = rest)
                else -> {
                    c.pos++
                    out += scalar(rest)
                }
            }
        }
        return out
    }

    private fun parseMap(c: Cursor, indent: Int, firstEntry: String? = null): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        var pending = firstEntry
        while (true) {
            val line = c.current ?: break
            val text = pending ?: line.text.takeIf { line.indent == indent } ?: break
            pending = null
            val key = keyOf(text) ?: break
            val value = text.substring(key.length).trimStart().removePrefix(":").trim()
            c.pos++
            out[unquote(key)] = when {
                value.startsWith("|") || value.startsWith(">") -> blockScalar(c, indent, value)
                value.isNotEmpty() -> scalar(value)
                else -> c.current?.let { next ->
                    when {
                        next.indent > indent -> parseBlock(c, next.indent)
                        // serde_yaml writes sequence items at the same indent as their key.
                        next.indent == indent && next.isSeqItem() -> parseSeq(c, indent)
                        else -> null
                    }
                }
            }
        }
        return out
    }

    private fun blockScalar(c: Cursor, indent: Int, header: String): String {
        val parts = mutableListOf<String>()
        while (true) {
            val line = c.current?.takeIf { it.indent > indent } ?: break
            parts += line.text
            c.pos++
        }
        val body = if (header.startsWith(">")) parts.joinToString(" ") else parts.joinToString("\n")
        return if (header.endsWith("-")) body else "$body\n"
    }

    /** The raw (possibly quoted) key if [text] is a `key: value` or `key:` entry, else null. */
    private fun keyOf(text: String): String? {
        val keyEnd = when (text.firstOrNull()) {
            '"', '\'' -> closingQuote(text) + 1
            else -> 0
        }
        if (keyEnd <= 0 && (text.startsWith('"') || text.startsWith('\''))) return null
        var i = text.indexOf(':', keyEnd)
        while (i >= 0) {
            if (i == text.lastIndex || text[i + 1] == ' ') {
                return text.substring(0, i).trimEnd().takeIf { it.isNotEmpty() }
            }
            i = text.indexOf(':', i + 1)
        }
        return null
    }

    /** Index of the quote closing the quoted scalar that starts [s], or -1. */
    private fun closingQuote(s: String): Int {
        val q = s[0]
        var i = 1
        while (i < s.length) {
            when {
                q == '"' && s[i] == '\\' -> i += 2
                s[i] == q && q == '\'' && s.getOrNull(i + 1) == '\'' -> i += 2
                s[i] == q -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun unquote(s: String): String = when {
        s.length >= 2 && s.first() == '\'' && s.last() == '\'' -> s.substring(1, s.length - 1).replace("''", "'")
        s.length >= 2 && s.first() == '"' && s.last() == '"' -> unescape(s.substring(1, s.length - 1))
        else -> s
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'u' -> {
                    s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)?.let { append(it.toChar()) }
                    i += 4
                }
                else -> append(n) // \" \\ \/ and anything unrecognised
            }
            i += 2
        }
    }

    private fun scalar(raw: String): Any? {
        val v = raw.trim()
        if (v.startsWith('\'') || v.startsWith('"')) return unquote(v)
        return when (val plain = v.substringBefore(" #")) {
            "~", "null", "Null", "NULL" -> null
            "true", "True", "TRUE" -> true
            "false", "False", "FALSE" -> false
            "[]" -> emptyList<Any?>()
            "{}" -> emptyMap<String, Any?>()
            else -> plain.toLongOrNull() ?: plain.toDoubleOrNull() ?: plain
        }
    }
}
