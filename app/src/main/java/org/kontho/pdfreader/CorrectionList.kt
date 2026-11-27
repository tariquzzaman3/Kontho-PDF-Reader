package org.kontho.pdfreader

/**
 * The person's own pronunciation corrections: a word as it is written in the book, and how it
 * should sound (spelled the way it is said). This file has no Android classes, so the rules can be
 * tested on a plain JVM. Saving to the phone is done by [PronunciationCorrections].
 */
object CorrectionList {
    const val MAX_ENTRIES = 500

    enum class Check { OK, BAD_WORD, BAD_SPOKEN }

    /** A written word, trimmed, with ড় ঢ় য় in their single-character form so both spellings match. */
    fun cleanWord(raw: String): String = BanglaTextPrep.fixNukta(raw.trim(), emptySet())

    /** How it should sound: one line, no tabs, single spaces. */
    fun cleanSpoken(raw: String): String =
        raw.replace(Regex("[\\t\\r\\n]+"), " ").replace(Regex(" +"), " ").trim()

    /**
     * The written side must be one Bangla word (that is how words are matched while reading),
     * and the spoken side must be something different from it.
     */
    fun check(word: String, spoken: String): Check {
        if (word.isEmpty() || !word.all { it in 'ঀ'..'৿' }) return Check.BAD_WORD
        if (spoken.isEmpty() || spoken == word) return Check.BAD_SPOKEN
        return Check.OK
    }

    /** One line per correction, written word and spoken form separated by a tab. */
    fun serialize(entries: Map<String, String>): String =
        entries.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    /** Reads what [serialize] wrote. Lines that are not a valid correction are skipped. */
    fun parse(text: String?): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (text.isNullOrBlank()) return result
        for (line in text.lines()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val word = cleanWord(line.substring(0, tab))
            val spoken = cleanSpoken(line.substring(tab + 1))
            if (check(word, spoken) == Check.OK && result.size < MAX_ENTRIES) result[word] = spoken
        }
        return result
    }

    /** A plain-text list the person can send: "word > how it should sound", one per line. */
    fun shareText(entries: Map<String, String>): String {
        val out = StringBuilder()
        out.append("Kontho pronunciation corrections (").append(entries.size).append(")\n")
        out.append("শব্দ > উচ্চারণ\n")
        for ((word, spoken) in entries) out.append(word).append(" > ").append(spoken).append('\n')
        return out.toString()
    }
}
