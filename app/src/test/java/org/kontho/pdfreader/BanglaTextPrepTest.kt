package org.kontho.pdfreader

import org.junit.After
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

    @Test fun punctuationSplitsTheTextButAddsNoSilence() {
        val chunks = BanglaTextPrep.toChunks("আমি ভালো আছি। তুমি কেমন আছো? ঠিক আছে, ধন্যবাদ", bangla, vowelFix = false)
        assertEquals(4, chunks.size)
        assertTrue("no pause expected", chunks.all { it.pauseMs == 0 })
    }

    // ---------------------------------------------------------------- reported mistakes

    @Test fun countedWordsKeepTheirFinalConsonantBeforeTi() {
        // The vowel fix used to turn these into একোটি, তিনোটি ...
        for (word in listOf("একটি", "একটু", "একদিন", "তিনটি", "চারটি", "পাঁচটি", "দশটি", "কোনটি", "ছোটটি")) {
            assertEquals(word, BanglaTextPrep.harmonizeVowels(word))
        }
        // ...but a word that really starts with a bare consonant still gets its vowel
        assertEquals("ঘোটি", BanglaTextPrep.harmonizeVowels("ঘটি"))
        assertEquals("আশাকোরি", BanglaTextPrep.harmonizeVowels("আশাকরি"))
    }

    @Test fun listedWordsAreRespelledAsTheyAreSpoken() {
        assertEquals("ইছলামি", BanglaTextPrep.respellWords("ইসলামী", vowelFix = true))
        assertEquals("এক টি", BanglaTextPrep.respellWords("একটি", vowelFix = true))
        // exceptions apply even when the vowel fix is switched off
        assertEquals("ইছলামি", BanglaTextPrep.respellWords("ইসলামী", vowelFix = false))
        assertEquals("এক টি", BanglaTextPrep.respellWords("একটি", vowelFix = false))
        // with an ending attached, and inside a sentence
        assertEquals("ইছলামির", BanglaTextPrep.respellWords("ইসলামীর", vowelFix = true))
        assertEquals("এক টিতে", BanglaTextPrep.respellWords("একটিতে", vowelFix = true))
        assertEquals("ইছলামি, এক টি।", BanglaTextPrep.respellWords("ইসলামী, একটি।", vowelFix = true))
    }

    @Test fun otherWordsAreOnlyTouchedByTheVowelFixWhenItIsOn() {
        assertEquals("কোরি", BanglaTextPrep.respellWords("করি", vowelFix = true))
        assertEquals("করি", BanglaTextPrep.respellWords("করি", vowelFix = false))
    }

    @Test fun listedWordsReachTheVoiceRespelled() {
        val spoken = BanglaTextPrep.toChunks("ইসলামী একটি বই", bangla, vowelFix = true)
            .joinToString(" ") { it.text }
        assertEquals("ইছলামি এক টি বই", spoken)
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

    @Test fun aSentenceEndingInANumberStillEndsTheSentence() {
        val chunks = BanglaTextPrep.toChunks("সাল ১৯৭১. আমরা জিতেছি", bangla, vowelFix = false)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].pauseMs)
    }

    // ---------------------------------------------------------------- misc

    @Test fun tokensFileIsParsedIncludingASpaceSymbol() {
        assertEquals(
            setOf("অ", "আ", " ", "-"),
            BanglaTextPrep.parseSymbols("অ 0\nআ 1\n  2\r\n- 3\n")
        )
    }

    @Test fun cloudGroupsKeepAllTextAndAddNoSilence() {
        val text = (1..80).joinToString(" ") { "আমি ভালো আছি।" }
        val groups = BanglaTextPrep.toSentenceGroups(text)
        assertTrue(groups.all { it.text.length <= 450 })
        assertEquals(text.replace(" ", ""), groups.joinToString("") { it.text }.replace(" ", ""))
        assertTrue(groups.all { it.pauseMs == 0 })
    }

    // ---------------------------------------------------------------- user corrections

    @After fun resetUserCorrections() {
        BanglaTextPrep.userCorrections = emptyMap()
    }

    @Test fun userCorrectionsBeatTheBuiltInList() {
        BanglaTextPrep.userCorrections = mapOf("একটি" to "একটি্")
        assertEquals("একটি্", BanglaTextPrep.respellWords("একটি", vowelFix = true))
        BanglaTextPrep.userCorrections = mapOf("করি" to "কর্ই")
        assertEquals("কর্ই", BanglaTextPrep.respellWords("করি", vowelFix = true))
    }

    @Test fun userCorrectionsHandleEndingsAndBothNuktaSpellings() {
        BanglaTextPrep.userCorrections = mapOf(CorrectionList.cleanWord("বড়") to "বোরো")
        assertEquals("বোরোদের", BanglaTextPrep.respellWords("বড়দের", vowelFix = false))
        // the same word typed with a separate nukta still matches
        assertEquals("বোরো", BanglaTextPrep.respellWords("বড়", vowelFix = false))
    }

    @Test fun azureGetsUserCorrectionsButNoBuiltInOnes() {
        BanglaTextPrep.userCorrections = mapOf("আশাকরি" to "আশাকোরি")
        val spoken = BanglaTextPrep.toSentenceGroups("আশাকরি একটি বই।").joinToString(" ") { it.text }
        assertEquals("আশাকোরি একটি বই।", spoken)
    }

    @Test fun correctionValidation() {
        assertEquals(CorrectionList.Check.OK, CorrectionList.check("একটি", "এক টি"))
        assertEquals(CorrectionList.Check.BAD_WORD, CorrectionList.check("", "এক"))
        assertEquals(CorrectionList.Check.BAD_WORD, CorrectionList.check("a b", "এক"))
        assertEquals(CorrectionList.Check.BAD_WORD, CorrectionList.check("এক টি", "এক"))
        assertEquals(CorrectionList.Check.BAD_SPOKEN, CorrectionList.check("একটি", ""))
        assertEquals(CorrectionList.Check.BAD_SPOKEN, CorrectionList.check("একটি", "একটি"))
        assertEquals("এক টি", CorrectionList.cleanSpoken("  এক \t\n  টি "))
    }

    @Test fun correctionListRoundTripsAndSharesAsText() {
        val entries = linkedMapOf("একটি" to "এক টি", "ইসলামী" to "ইছলামি")
        assertEquals(entries, CorrectionList.parse(CorrectionList.serialize(entries)))
        assertTrue(CorrectionList.parse("junk\n\tnothing\nশব্দ\tশব্দ\n").isEmpty())
        val share = CorrectionList.shareText(entries)
        assertTrue(share, share.contains("একটি > এক টি"))
        assertTrue(share, share.contains("ইসলামী > ইছলামি"))
    }
}
