package org.kontho.pdfreader

import android.content.Context

/** Saves the person's pronunciation corrections on the phone and hands them to the text preparation. */
object PronunciationCorrections {
    private const val KEY = "pronunciation_corrections"

    enum class Result { SAVED, BAD_WORD, BAD_SPOKEN, FULL }

    private fun prefs(context: Context) = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): LinkedHashMap<String, String> =
        CorrectionList.parse(prefs(context).getString(KEY, null))

    private fun save(context: Context, entries: Map<String, String>) {
        prefs(context).edit().putString(KEY, CorrectionList.serialize(entries)).apply()
        BanglaTextPrep.userCorrections = LinkedHashMap(entries)
    }

    /** Loads the saved corrections into the text preparation. Cheap; called before reading starts. */
    fun install(context: Context) {
        BanglaTextPrep.userCorrections = load(context)
    }

    /** Adds a correction, or replaces the one for the same word. */
    fun add(context: Context, rawWord: String, rawSpoken: String): Result {
        val word = CorrectionList.cleanWord(rawWord)
        val spoken = CorrectionList.cleanSpoken(rawSpoken)
        when (CorrectionList.check(word, spoken)) {
            CorrectionList.Check.BAD_WORD -> return Result.BAD_WORD
            CorrectionList.Check.BAD_SPOKEN -> return Result.BAD_SPOKEN
            CorrectionList.Check.OK -> Unit
        }
        val entries = load(context)
        if (!entries.containsKey(word) && entries.size >= CorrectionList.MAX_ENTRIES) return Result.FULL
        entries[word] = spoken
        save(context, entries)
        return Result.SAVED
    }

    fun remove(context: Context, word: String) {
        val entries = load(context)
        if (entries.remove(word) != null) save(context, entries)
    }

    /**
     * The saved corrections plus one that is not saved yet, for "Hear it". The result is not
     * stored; call [install] afterwards to go back to what is saved.
     */
    fun withPending(context: Context, rawWord: String, rawSpoken: String): Map<String, String>? {
        val word = CorrectionList.cleanWord(rawWord)
        val spoken = CorrectionList.cleanSpoken(rawSpoken)
        if (CorrectionList.check(word, spoken) != CorrectionList.Check.OK) return null
        val entries = load(context)
        entries[word] = spoken
        return entries
    }
}
