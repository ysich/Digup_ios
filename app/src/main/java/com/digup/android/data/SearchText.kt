package com.digup.android.data

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Text the way keyword search keeps it and matches it. */
object SearchText {

    fun normalized(text: String): String {
        val compatible = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(compatible.length)
        var index = 0
        while (index < compatible.length) {
            val codePoint = compatible.codePointAt(index)
            when {
                codePoint == 0x200B -> out.append(' ')
                codePoint == 0x00AD || codePoint == 0x061C ||
                    codePoint in 0x200C..0x200F || codePoint in 0x202A..0x202E ||
                    codePoint in 0x2060..0x2064 || codePoint in 0x2066..0x2069 ||
                    codePoint == 0xFEFF -> Unit
                codePoint == 0x0640 || codePoint in 0x064B..0x065F || codePoint == 0x0670 -> Unit
                codePoint == 0x0622 || codePoint == 0x0623 || codePoint == 0x0625 || codePoint == 0x0671 ->
                    out.append('\u0627')
                else -> out.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /**
     * The text as it goes into FTS5. Scripts written without spaces between words (Chinese, Japanese,
     * Thai, ...) get a space between every character, otherwise unicode61 would index a whole sentence
     * as one token. The query side turns such a term back into an FTS5 phrase.
     */
    fun indexText(text: String): String {
        val normalized = normalized(text)
        val out = StringBuilder(normalized.length + normalized.length / 4)
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            if (isUnspaced(codePoint)) {
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.appendCodePoint(codePoint)
                out.append(' ')
            } else {
                out.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /** Whether normalizing changed the text; the original is kept for display and snippets. */
    fun originalIfChanged(text: String): String? = if (indexText(text) == text) null else text

    fun inflects(word: String): Boolean {
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            if (codePoint in 0x0900..0x0DFF || codePoint in 0x0600..0x06FF) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    fun isUnspaced(codePoint: Int): Boolean = when (codePoint) {
        in 0x2E80..0x2FFF, in 0x3000..0x303F, in 0x3040..0x30FF, in 0x31F0..0x31FF,
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, in 0x20000..0x2FA1F,
        in 0xAC00..0xD7AF, in 0x0E00..0x0E7F, in 0x0E80..0x0EFF, in 0x1000..0x109F,
        in 0x1780..0x17FF, in 0x0F00..0x0FFF -> true
        else -> false
    }

    fun hasUnspaced(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isUnspaced(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    /** FTS5 pattern for one query term: a phrase for unspaced scripts, a quoted word otherwise. */
    fun pattern(term: String, prefix: Boolean): String {
        val text = if (hasUnspaced(term)) {
            indexText(term).trim().replace(Regex("\\s+"), " ")
        } else {
            term
        }
        return "\"" + text.replace("\"", "\"\"") + "\"" + if (prefix) "*" else ""
    }

    /** At least a fifth of what isn't space is letters or marks. */
    fun hasWords(text: String): Boolean {
        var letters = 0
        var others = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!Character.isWhitespace(codePoint)) {
                val type = Character.getType(codePoint)
                if (Character.isLetter(codePoint) || type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
                ) letters++ else others++
            }
            index += Character.charCount(codePoint)
        }
        return letters > 0 && letters * 4 >= others
    }

    fun isWordCharacter(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }

    /** Ranges of `word` in `text` as written, with word boundaries and prefix matching for inflecting scripts. */
    fun matches(word: String, text: String): List<IntRange> {
        val needle = normalized(word).lowercase()
        if (needle.isEmpty()) return emptyList()
        val folded = Folded(text)
        val haystack = folded.text
        val prefix = inflects(word)
        val found = ArrayList<IntRange>()
        var start = 0
        while (found.size < 500 && start <= haystack.length - needle.length) {
            val hit = haystack.indexOf(needle, start, ignoreCase = true)
            if (hit < 0) break
            start = hit + max(needle.length, 1)
            val range = folded.original(hit, hit + needle.length) ?: continue
            if (range.first > 0 && isWordCharacter(text.codePointBefore(range.first))) continue
            if (range.last + 1 < text.length && isWordCharacter(text.codePointAt(range.last + 1))) {
                if (!prefix) continue
                var end = range.last + 1
                while (end < text.length) {
                    val cp = text.codePointAt(end)
                    if (!isWordCharacter(cp)) break
                    end += Character.charCount(cp)
                }
                val extended = range.first..(end - 1)
                if (found.none { it.first <= extended.last && extended.first <= it.last }) found.add(extended)
            } else {
                if (found.none { it.first <= range.last && range.first <= it.last }) found.add(range)
            }
        }
        return found
    }

    /** One line around the first term, terms marked « ». */
    fun snippet(text: String, terms: List<String>, words: Int = 12): String? {
        val marks = terms.flatMap { matches(it, text) }.sortedBy { it.first }
        if (marks.isEmpty()) return null
        val first = marks.first()
        var start = text.lastIndexOf('\n', first.first)
        start = if (start < 0) max(0, first.first - words * 16) else start + 1
        var end = text.indexOf('\n', first.last + 1)
        end = if (end < 0) min(text.length, first.last + words * 16) else end
        if (end - start < words * 8) {
            start = max(0, min(start, first.first - words * 16))
            end = min(text.length, max(end, first.last + words * 16))
        }
        val slice = text.substring(start, end)
        val local = marks.filter { it.first >= start && it.last < end }.map { (it.first - start)..(it.last - start) }
        val out = StringBuilder()
        var cursor = 0
        for (range in local) {
            if (range.first < cursor) continue
            out.append(slice, cursor, range.first)
            out.append('«').append(slice, range.first, range.last + 1).append('»')
            cursor = range.last + 1
        }
        out.append(slice, cursor, slice.length)
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private class Folded(val source: String) {
        val text: String
        private val origin: IntArray

        init {
            val builder = StringBuilder(source.length)
            val map = ArrayList<Int>(source.length)
            var index = 0
            while (index < source.length) {
                val codePoint = source.codePointAt(index)
                val piece = normalized(String(Character.toChars(codePoint))).lowercase()
                builder.append(piece)
                repeat(piece.length) { map.add(index) }
                index += Character.charCount(codePoint)
            }
            text = builder.toString()
            origin = map.toIntArray()
        }

        fun original(start: Int, endExclusive: Int): IntRange? {
            if (start < 0 || endExclusive > origin.size || start >= endExclusive) return null
            val first = origin[start]
            val lastStart = origin[endExclusive - 1]
            val last = source.offsetByCodePoints(lastStart, 1) - 1
            return first..last
        }
    }
}
