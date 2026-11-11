package org.kontho.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests for the text preparation used by the offline and cloud voices. */
class BanglaTextPrepTest {
    private fun symbolSet(chars: String): Set<String> = chars.map { it.toString() }.toSet()

    // A stand-in for the model's tokens.txt: Bangla letters, signs, nukta and space, but no digits.
    private val bangla = symbolSet(
        "ঀঁংঃঅআইঈউঊঋএঐওঔকখগঘঙচছজঝঞটঠডঢণতথদধনপফবভমযরলশষসহ়ািীুূৃেৈোৌ্ড়ঢ়য় "
    )

    // ---------------------------------------------------------------- unwritten vowel

    @Test fun unwrittenVowelIsMadeExplicit() {
        assertEquals("আশাকোরি", BanglaTextPrep.harmonizeVowels("আশাকরি"))
        assertEquals("কোরি", BanglaTextPrep.harmonizeVowels("করি"))
        assertEquals("বোলি", BanglaTextPrep.harmonizeVowels("বলি"))
        assertEquals("ঘোড়ি", BanglaTextPrep.harmonizeVowels("ঘড়ি"))
        assertEquals("সুন্দোরী", BanglaTextPrep.harmonizeVowels("সুন্দরী"))
    }

    @Test fun nuktaWrittenAsTwoCharactersIsHandled() {
        // ঘ + ড + nukta + ি
        assertEquals("ঘোড়ি", BanglaTextPrep.harmonizeVowels("ঘড়ি"))
    }

    @Test fun ordinaryWordsAreLeftAlone() {
        for (word in listOf(
            "কলম", "বাংলা", "আমি", "ভালো", "বই", "মানুষ", "আমরা", "পাখি",
            "দিন", "তুমি", "স্কুল", "বাড়ি", "কাজ", "হাত"
        )) {
            assertEquals(word, BanglaTextPrep.harmonizeVowels(word))
        }
    }

    @Test fun latinTextAndPunctuationSurviveTheVowelFix() {
        assertEquals(
            "আশাকোরি, he said 2024 কোরি।",
            BanglaTextPrep.harmonizeVowels("আশাকরি, he said 2024 করি।")
        )
    }

    // ---------------------------------------------------------------- nukta letters

    private val single = "ড়ঢ়য়"
    private val pair = "ড়ঢ়য়"

    @Test fun nuktaIsNormalisedWhenTheModelVocabularyIsUnknown() {
        assertEquals(single, BanglaTextPrep.fixNukta(pair, emptySet()))
    }

    @Test fun nuktaFollowsTheSpellingTheModelKnows() {
        assertEquals(single, BanglaTextPrep.fixNukta(pair, symbolSet("ড়ঢ়য়")))
        assertEquals(pair, BanglaTextPrep.fixNukta(single, symbolSet("ডঢয়")))
        assertEquals("ররয", BanglaTextPrep.fixNukta(single, symbolSet("ডঢয")))
    }

    // ---------------------------------------------------------------- punctuation and pauses

    @Test fun punctuationBecomesPauses() {
        val chunks = BanglaTextPrep.toChunks("আমি ভালো আছি। তুমি কেমন আছো? ঠিক আছে, ধন্যবাদ", bangla, vowelFix = false)
        assertEquals(4, chunks.size)
        assertEquals(BanglaTextPrep.PAUSE_SENTENCE_MS, chunks[0].pauseMs)
        assertEquals(BanglaTextPrep.PAUSE_SENTENCE_MS, chunks[1].pauseMs)
        assertEquals(BanglaTextPrep.PAUSE_PHRASE_MS, chunks[2].pauseMs)
        assertTrue(chunks.last().pauseMs >= BanglaTextPrep.PAUSE_PARAGRAPH_MS)
    }

    @Test fun emptyAndPunctuationOnlyParagraphsProduceNothing() {
        assertTrue(BanglaTextPrep.toChunks("   ", bangla, true).isEmpty())
        assertTrue(BanglaTextPrep.toChunks("।।।", bangla, true).isEmpty())
    }

    @Test fun longTextIsSplitWithoutLosingWords() {
        val long = (1..60).joinToString(" ") { "আমি ভালো" }
        val chunks = BanglaTextPrep.toChunks(long, bangla, false)
        assertTrue(chunks.all { it.text.length <= 150 })
        assertEquals(long.replace(" ", ""), chunks.joinToString("") { it.text }.replace(" ", ""))
    }

    // ---------------------------------------------------------------- numbers

    @Test fun numbersAreSpokenInBangla() {
        assertEquals("শূন্য", BanglaTextPrep.numberToWords(0))
        assertEquals("সাত", BanglaTextPrep.numberToWords(7))
        assertEquals("দশ", BanglaTextPrep.numberToWords(10))
        assertEquals("একুশ", BanglaTextPrep.numberToWords(21))
        assertEquals("নিরানব্বই", BanglaTextPrep.numberToWords(99))
        assertEquals("একশো", BanglaTextPrep.numberToWords(100))
        assertEquals("একশো এক", BanglaTextPrep.numberToWords(101))
        assertEquals("এক হাজার নয়শো একাত্তর", BanglaTextPrep.numberToWords(1971))
        assertEquals("দুই হাজার চব্বিশ", BanglaTextPrep.numberToWords(2024))
        assertEquals("এক লাখ", BanglaTextPrep.numberToWords(100_000))
        assertEquals(
            "বারো লাখ চৌত্রিশ হাজার পাঁচশো সাতষট্টি",
            BanglaTextPrep.numberToWords(1_234_567)
        )
        assertEquals("এক কোটি", BanglaTextPrep.numberToWords(10_000_000))
    }

    @Test fun everyNumberFromZeroTo99HasAWord() {
        val seen = HashSet<String>()
        for (n in 0L..99L) {
            val word = BanglaTextPrep.numberToWords(n)
            assertTrue("no word for $n", word.isNotBlank())
            assertTrue("duplicate word for $n", seen.add(word))
        }
    }

    @Test fun digitsInTextAreExpandedWhenTheModelHasNoDigits() {
        assertEquals(
            "সাল  এক হাজার নয়শো একাত্তর  এ",
            BanglaTextPrep.expandNumbers("সাল ১৯৭১ এ", bangla)
        )
        assertEquals(" তিন দশমিক এক চার ", BanglaTextPrep.expandNumbers("3.14", bangla))
        assertEquals(" এক হাজার দুইশো চৌত্রিশ ", BanglaTextPrep.expandNumbers("1,234", bangla))
        // leading zero: a phone number or code is read digit by digit
        assertEquals(" শূন্য এক সাত এক দুই ", BanglaTextPrep.expandNumbers("০১৭১২", bangla))
    }

    @Test fun digitsAreLeftAloneWhenTheModelKnowsThem() {
        assertEquals("সাল ১৯৭১ এ", BanglaTextPrep.expandNumbers("সাল ১৯৭১ এ", bangla + symbolSet("১৯৭")))
    }

    @Test fun aYearNoLongerVanishesFromTheSpeech() {
        val chunks = BanglaTextPrep.toChunks("সাল ১৯৭১ এ যুদ্ধ হয়", bangla, vowelFix = false)
        val spoken = chunks.joinToString(" ") { it.text }
        assertTrue(spoken, spoken.contains("একাত্তর"))
    }

    @Test fun aSentenceEndingInANumberStillGetsASentencePause() {
        val chunks = BanglaTextPrep.toChunks("সাল ১৯৭১. আমরা জিতেছি", bangla, vowelFix = false)
        assertEquals(2, chunks.size)
        assertEquals(BanglaTextPrep.PAUSE_SENTENCE_MS, chunks[0].pauseMs)
    }

    // ---------------------------------------------------------------- misc

    @Test fun tokensFileIsParsedIncludingASpaceSymbol() {
        assertEquals(
            setOf("অ", "আ", " ", "-"),
            BanglaTextPrep.parseSymbols("অ 0\nআ 1\n  2\r\n- 3\n")
        )
    }

    @Test fun cloudGroupsKeepAllTextAndEndWithAPause() {
        val text = (1..80).joinToString(" ") { "আমি ভালো আছি।" }
        val groups = BanglaTextPrep.toSentenceGroups(text)
        assertTrue(groups.all { it.text.length <= 450 })
        assertEquals(text.replace(" ", ""), groups.joinToString("") { it.text }.replace(" ", ""))
        assertEquals(BanglaTextPrep.PAUSE_SENTENCE_MS, groups.last().pauseMs)
    }
}
