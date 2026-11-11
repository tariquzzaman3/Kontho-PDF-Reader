package org.kontho.pdfreader

/*
 * Text preparation for neural voices. This file is deliberately free of Android classes so the
 * rules can be unit-tested on a plain JVM.
 */

/** One piece of text to synthesise, followed by a pause in milliseconds. */
class SpeechChunk(val text: String, val pauseMs: Int)

object BanglaTextPrep {
    private const val NUKTA = '়'
    private const val HASANTA = '্'
    private const val O_KAR = 'ো'

    // Silence added at punctuation. All zero on purpose: the reader waited too long at every
    // comma and full stop. Text is still split at punctuation, so each piece starts cleanly;
    // raise these (e.g. 120 / 200 / 250) to bring a small pause back.
    const val PAUSE_PHRASE_MS = 0
    const val PAUSE_SENTENCE_MS = 0
    const val PAUSE_PARAGRAPH_MS = 0

    /** Letter, its decomposed (base + nukta) spelling, and a fallback if the model knows neither. */
    private class NuktaLetter(val single: Char, val pair: String, val fallback: String)

    private val nuktaLetters = listOf(
        NuktaLetter('ড়', "ড়", "র"), // ড়
        NuktaLetter('ঢ়', "ঢ়", "র"), // ঢ়
        NuktaLetter('য়', "য়", "য")  // য়
    )

    /**
     * ড় ঢ় য় can be written as one character or as a letter plus a nukta mark, and PDF text uses
     * both. A character-based voice only knows the spelling it was trained on, so we convert to
     * whichever spelling appears in the model's own symbol list.
     */
    fun fixNukta(text: String, symbols: Set<String>): String {
        var out = text
        for (letter in nuktaLetters) {
            val single = letter.single.toString()
            out = out.replace(letter.pair, single)
            val singleKnown = symbols.isEmpty() || symbols.contains(single)
            if (!singleKnown) {
                val pairKnown = symbols.contains(letter.pair.substring(0, 1)) &&
                    symbols.contains(NUKTA.toString())
                out = out.replace(single, if (pairKnown) letter.pair else letter.fallback)
            }
        }
        return out
    }

    private fun isConsonant(c: Char): Boolean =
        c in 'ক'..'হ' || c == 'ড়' || c == 'ঢ়' || c == 'য়'

    private fun isVowelSign(c: Char): Boolean = c in 'া'..'ৌ'

    private fun isModifier(c: Char): Boolean = c == 'ঁ' || c == 'ং' || c == 'ঃ'

    private fun isHighVowelSign(c: Char): Boolean =
        c == 'ি' || c == 'ী' || c == 'ু' || c == 'ূ'

    /**
     * Bangla spelling leaves the vowel "ô" unwritten after many consonants, and it is spoken as "o"
     * when the next syllable carries ই ঈ উ ঊ: আশাকরি is said আশাকোরি, not "আশাক্রি". A character
     * model cannot see that rule, so we write the ো-kar in explicitly. This is a heuristic and is
     * switchable in the voice settings.
     */
    fun harmonizeVowels(text: String): String =
        Regex("[\\u0980-\\u09FF]+").replace(text) { match -> harmonizeWord(match.value) }

    private fun harmonizeWord(word: String): String {
        val n = word.length
        if (n < 3) return word
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var i = 0
        while (i < n) {
            val start = i
            if (isConsonant(word[i])) {
                i++
                if (i < n && word[i] == NUKTA) i++
                while (i + 1 < n && word[i] == HASANTA && isConsonant(word[i + 1])) {
                    i += 2
                    if (i < n && word[i] == NUKTA) i++
                }
                if (i < n && isVowelSign(word[i])) i++
                while (i < n && isModifier(word[i])) i++
                if (i < n && word[i] == HASANTA) i++
            } else {
                i++
                while (i < n && (isVowelSign(word[i]) || isModifier(word[i]) || word[i] == NUKTA)) i++
            }
            starts.add(start)
            ends.add(i)
        }
        if (starts.size < 2) return word

        val out = StringBuilder()
        for (k in starts.indices) {
            val s = starts[k]
            val e = ends[k]
            out.append(word, s, e)
            val last = word[e - 1]
            val bare = isConsonant(word[s]) && (isConsonant(last) || last == NUKTA)
            if (bare && k + 1 < starts.size && isConsonant(word[starts[k + 1]])) {
                var nextHigh = false
                for (p in starts[k + 1] until ends[k + 1]) {
                    if (isHighVowelSign(word[p])) {
                        nextHigh = true
                        break
                    }
                }
                if (nextHigh && !closesPreviousSyllable(word, starts, ends, k)) out.append(O_KAR)
            }
        }
        return out.toString()
    }

    /**
     * Some bare consonants belong to the syllable before them and take no extra vowel:
     * এক|টি, তিন|টি, দশ|টি (a counted word plus the -টি / -টু ending) and এক|দিন, এক|চিলতে
     * (words that start with এক). Without this guard the vowel fix said "একোটি".
     */
    private fun closesPreviousSyllable(word: String, starts: List<Int>, ends: List<Int>, k: Int): Boolean {
        if (k == 0) return false
        if (k == 1 && starts[1] == 1 && word[0] == 'এ' && word[1] == 'ক') return true
        val next = k + 1
        if (next == starts.size - 1 && ends[next] - starts[next] == 2 && word[starts[next]] == 'ট') {
            val sign = word[starts[next] + 1]
            if (sign == 'ি' || sign == 'ু') return true
        }
        return false
    }

    // ------------------------------------------------------------------ word exceptions

    /**
     * Words the general rules get wrong, written as: spelling → how it should be spoken.
     * The right-hand side is what the voice is given, so it is spelled the way it sounds.
     * Add a word here when the reader says it wrongly.
     */
    private val pronunciationExceptions = mapOf(
        "ইসলামী" to "ইছলামি",
        "একটি" to "এক টি"
    )

    /** Endings that may be attached to an exception word (ইসলামীর, একটিতে). */
    private val exceptionSuffixes = listOf("দের", "ের", "কে", "রা", "তে", "র", "ে")

    /** The spoken form for a word in the exception list, or null if it is not listed. */
    fun spokenForm(word: String): String? {
        pronunciationExceptions[word]?.let { return it }
        for (suffix in exceptionSuffixes) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val stem = word.substring(0, word.length - suffix.length)
                pronunciationExceptions[stem]?.let { return it + suffix }
            }
        }
        return null
    }

    /**
     * Respells every Bangla word for the voice: listed exceptions first, then (if enabled) the
     * unwritten-vowel rule. Exceptions are applied even when the vowel fix is switched off.
     */
    fun respellWords(text: String, vowelFix: Boolean): String =
        Regex("[\\u0980-\\u09FF]+").replace(text) { match ->
            spokenForm(match.value) ?: if (vowelFix) harmonizeWord(match.value) else match.value
        }

    // ------------------------------------------------------------------ numbers

    private val digitWords = arrayOf("শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়")

    /** Spoken names of 0..99. Bangla has a separate word for every one of them. */
    private val below100 = arrayOf(
        "শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়",
        "দশ", "এগারো", "বারো", "তেরো", "চৌদ্দ", "পনেরো", "ষোলো", "সতেরো", "আঠারো", "উনিশ",
        "বিশ", "একুশ", "বাইশ", "তেইশ", "চব্বিশ", "পঁচিশ", "ছাব্বিশ", "সাতাশ", "আটাশ", "উনত্রিশ",
        "ত্রিশ", "একত্রিশ", "বত্রিশ", "তেত্রিশ", "চৌত্রিশ", "পঁয়ত্রিশ", "ছত্রিশ", "সাঁইত্রিশ", "আটত্রিশ", "উনচল্লিশ",
        "চল্লিশ", "একচল্লিশ", "বিয়াল্লিশ", "তেতাল্লিশ", "চুয়াল্লিশ", "পঁয়তাল্লিশ", "ছেচল্লিশ", "সাতচল্লিশ", "আটচল্লিশ", "উনপঞ্চাশ",
        "পঞ্চাশ", "একান্ন", "বাহান্ন", "তিপ্পান্ন", "চুয়ান্ন", "পঞ্চান্ন", "ছাপ্পান্ন", "সাতান্ন", "আটান্ন", "উনষাট",
        "ষাট", "একষট্টি", "বাষট্টি", "তেষট্টি", "চৌষট্টি", "পঁয়ষট্টি", "ছেষট্টি", "সাতষট্টি", "আটষট্টি", "উনসত্তর",
        "সত্তর", "একাত্তর", "বাহাত্তর", "তিয়াত্তর", "চুয়াত্তর", "পঁচাত্তর", "ছিয়াত্তর", "সাতাত্তর", "আটাত্তর", "উনআশি",
        "আশি", "একাশি", "বিরাশি", "তিরাশি", "চুরাশি", "পঁচাশি", "ছিয়াশি", "সাতাশি", "আটাশি", "উননব্বই",
        "নব্বই", "একানব্বই", "বিরানব্বই", "তিরানব্বই", "চুরানব্বই", "পঁচানব্বই", "ছিয়ানব্বই", "সাতানব্বই", "আটানব্বই", "নিরানব্বই"
    )

    private val hundreds = arrayOf(
        "", "একশো", "দুইশো", "তিনশো", "চারশো", "পাঁচশো", "ছয়শো", "সাতশো", "আটশো", "নয়শো"
    )

    private fun isDigitSymbol(c: Char): Boolean = c in '0'..'9' || c in '০'..'৯'

    private fun asciiDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '০'..'৯') '0' + (c - '০') else c)
        return sb.toString()
    }

    /** A whole number as spoken Bangla, using হাজার / লাখ / কোটি. Intended for up to 9 digits. */
    fun numberToWords(value: Long): String {
        if (value == 0L) return below100[0]
        var rest = value
        val parts = ArrayList<String>()
        val crore = rest / 10_000_000L
        rest %= 10_000_000L
        val lakh = rest / 100_000L
        rest %= 100_000L
        val thousand = rest / 1_000L
        rest %= 1_000L
        if (crore > 0) parts.add(numberToWords(crore) + " কোটি")
        if (lakh > 0) parts.add(below100[lakh.toInt()] + " লাখ")
        if (thousand > 0) parts.add(below100[thousand.toInt()] + " হাজার")
        if (rest > 0) {
            val h = (rest / 100).toInt()
            val r = (rest % 100).toInt()
            if (h > 0) parts.add(hundreds[h])
            if (r > 0) parts.add(below100[r])
        }
        return parts.joinToString(" ")
    }

    private fun spokenNumber(raw: String): String {
        val dot = raw.indexOf('.')
        val whole = asciiDigits(if (dot >= 0) raw.substring(0, dot) else raw).replace(",", "")
        val fraction = if (dot >= 0) asciiDigits(raw.substring(dot + 1)) else ""
        // Very long numbers and numbers with a leading zero (phone numbers, codes) are read digit by digit.
        val head = if (whole.length > 9 || (whole.length > 1 && whole[0] == '0')) {
            whole.map { digitWords[it - '0'] }.joinToString(" ")
        } else {
            numberToWords(whole.toLong())
        }
        if (fraction.isEmpty()) return head
        return head + " দশমিক " + fraction.map { digitWords[it - '0'] }.joinToString(" ")
    }

    /**
     * A character-based voice has no symbols for digits, so they would simply vanish from the
     * speech ("সাল ১৯৭১ এ" would be read "সাল এ"). Write numbers out as words instead. If the model
     * does know digits, the text is left alone.
     */
    fun expandNumbers(text: String, symbols: Set<String>): String {
        if (symbols.any { it.length == 1 && isDigitSymbol(it[0]) }) return text
        val number = Regex("[0-9০-৯]+(?:,[0-9০-৯]{2,3})*(?:\\.[0-9০-৯]+)?")
        return number.replace(text) { " " + spokenNumber(it.value) + " " }
    }

    private fun isStrongStop(text: String, index: Int): Boolean {
        val c = text[index]
        if (c == '।' || c == '॥' || c == '?' || c == '!' || c == '…') return true
        if (c == '.') {
            val prevDigit = index > 0 && text[index - 1].isDigit()
            val nextBreak = index + 1 >= text.length || text[index + 1].isWhitespace()
            return nextBreak && !prevDigit
        }
        return false
    }

    private fun isWeakStop(c: Char): Boolean =
        c == ',' || c == ';' || c == ':' || c == '—' || c == '–' || c == '(' || c == ')'

    /**
     * Prepare one paragraph for a character-based offline voice: fix letters, optionally respell the
     * unwritten vowel, then split at punctuation so each piece can be followed by a real pause.
     */
    fun toChunks(
        paragraph: String,
        symbols: Set<String>,
        vowelFix: Boolean,
        maxChunkChars: Int = 150
    ): List<SpeechChunk> {
        var prepared = paragraph.replace("‌", "").replace("‍", "")
        prepared = fixNukta(prepared, symbols)
        prepared = respellWords(prepared, vowelFix)
        // After the vowel fix, so the number words below keep their standard spelling.
        prepared = expandNumbers(prepared, symbols)

        val raw = ArrayList<SpeechChunk>()
        val current = StringBuilder()
        fun flush(pause: Int) {
            val cleaned = filterSymbols(current.toString(), symbols)
            current.setLength(0)
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetterOrDigit() }) {
                val pieces = splitLong(cleaned, maxChunkChars)
                pieces.forEachIndexed { index, piece ->
                    raw.add(SpeechChunk(piece, if (index == pieces.lastIndex) pause else 60))
                }
            } else if (raw.isNotEmpty() && pause > 0) {
                // Punctuation with no words of its own lengthens the previous pause.
                val previous = raw.removeAt(raw.lastIndex)
                raw.add(SpeechChunk(previous.text, maxOf(previous.pauseMs, pause)))
            }
        }

        for (index in prepared.indices) {
            val c = prepared[index]
            when {
                isStrongStop(prepared, index) -> flush(PAUSE_SENTENCE_MS)
                isWeakStop(c) -> flush(PAUSE_PHRASE_MS)
                else -> current.append(c)
            }
        }
        flush(0)

        if (raw.isEmpty()) return raw
        val last = raw.removeAt(raw.lastIndex)
        raw.add(SpeechChunk(last.text, maxOf(last.pauseMs, PAUSE_PARAGRAPH_MS)))
        return raw
    }

    /** Keep only what the voice has symbols for (plus spaces), and tidy the spacing. */
    fun filterSymbols(text: String, symbols: Set<String>): String {
        if (symbols.isEmpty()) return text.replace(Regex("\\s+"), " ").trim()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch == ' ' || ch == '\n' || ch == '\t') sb.append(' ')
            else if (symbols.contains(ch.toString())) sb.append(ch)
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    private fun splitLong(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val pieces = ArrayList<String>()
        var remaining = text
        while (remaining.length > maxLen) {
            var cut = remaining.lastIndexOf(' ', maxLen)
            if (cut < maxLen / 2) cut = maxLen
            pieces.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) pieces.add(remaining)
        return pieces
    }

    /**
     * Cloud voices have their own text front end and pause handling, so they get whole sentences
     * grouped into pieces of at most [maxChunkChars].
     */
    fun toSentenceGroups(paragraph: String, maxChunkChars: Int = 450): List<SpeechChunk> {
        val text = paragraph.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return emptyList()
        val groups = ArrayList<String>()
        val current = StringBuilder()
        for (index in text.indices) {
            current.append(text[index])
            if (isStrongStop(text, index) && current.length >= maxChunkChars / 2) {
                groups.add(current.toString().trim())
                current.setLength(0)
            } else if (current.length >= maxChunkChars) {
                val soft = current.lastIndexOf(" ")
                if (soft > maxChunkChars / 2) {
                    groups.add(current.substring(0, soft).trim())
                    val rest = current.substring(soft + 1)
                    current.setLength(0)
                    current.append(rest)
                } else {
                    groups.add(current.toString().trim())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotBlank()) groups.add(current.toString().trim())
        return groups.mapIndexed { index, piece ->
            SpeechChunk(piece, if (index == groups.lastIndex) PAUSE_SENTENCE_MS else 0)
        }
    }

    /** Parse sherpa-onnx tokens.txt ("symbol id" per line; the symbol itself may be a space). */
    fun parseSymbols(tokensFileText: String): Set<String> {
        val symbols = HashSet<String>()
        for (rawLine in tokensFileText.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue
            val separator = line.lastIndexOf(' ')
            if (separator < 0) continue
            symbols.add(if (separator == 0) " " else line.substring(0, separator))
        }
        return symbols
    }
}
