package org.kontho.pdfreader

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Settings for the optional Microsoft Azure neural voice. Kontho has no server of its own, so the
 * person brings their own Azure Speech key; Azure's free tier (F0) covers 500,000 characters of
 * neural speech each month. The key is kept only in the app's private storage on this phone.
 */
object AzureConfig {
    const val KEY_API_KEY = "azure_api_key"
    const val KEY_REGION = "azure_region"
    const val KEY_VOICE = "azure_voice"
    private const val KEY_USED_CHARS = "azure_used_chars"
    private const val KEY_USED_MONTH = "azure_used_month"

    const val DEFAULT_REGION = "southeastasia"
    const val VOICE_FEMALE = "bn-BD-NabanitaNeural"
    const val VOICE_MALE = "bn-BD-PradeepNeural"
    const val FREE_MONTHLY_CHARS = 500_000L

    private fun prefs(context: Context) = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()

    fun region(context: Context): String =
        prefs(context).getString(KEY_REGION, DEFAULT_REGION)?.trim()?.lowercase().orEmpty()
            .ifBlank { DEFAULT_REGION }

    fun voice(context: Context): String {
        val saved = prefs(context).getString(KEY_VOICE, VOICE_FEMALE)
        return if (saved == VOICE_MALE) VOICE_MALE else VOICE_FEMALE
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotEmpty()

    fun save(context: Context, key: String, region: String) {
        prefs(context).edit()
            .putString(KEY_API_KEY, key.trim())
            .putString(KEY_REGION, region.trim().lowercase().ifBlank { DEFAULT_REGION })
            .apply()
    }

    fun saveVoice(context: Context, voice: String) {
        prefs(context).edit().putString(KEY_VOICE, if (voice == VOICE_MALE) VOICE_MALE else VOICE_FEMALE).apply()
    }

    fun clearKey(context: Context) {
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    private fun monthStamp(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.YEAR) * 100 + now.get(Calendar.MONTH) + 1
    }

    /** Characters this phone has sent to Azure this calendar month (the free quota is per Azure account). */
    @Synchronized
    fun usedThisMonth(context: Context): Long {
        val p = prefs(context)
        return if (p.getInt(KEY_USED_MONTH, 0) == monthStamp()) p.getLong(KEY_USED_CHARS, 0L) else 0L
    }

    @Synchronized
    fun addUsage(context: Context, characters: Int) {
        val p = prefs(context)
        val total = usedThisMonth(context) + characters
        p.edit().putInt(KEY_USED_MONTH, monthStamp()).putLong(KEY_USED_CHARS, total).apply()
    }
}

/** Reads text aloud with an Azure neural voice (bn-BD Nabanita or Pradeep) over the REST API. */
class AzureSpeaker(context: Context) : PipelinedSpeaker(context) {
    private companion object {
        const val SAMPLE_RATE = 24_000
        const val OUTPUT_FORMAT = "raw-24khz-16bit-mono-pcm"
    }

    override fun load(): Int {
        if (!AzureConfig.isConfigured(appContext)) {
            throw IllegalStateException(
                VoiceEngines.t(
                    appContext,
                    "Add your Azure key in Settings → Voice first.",
                    "আগে সেটিংস → কণ্ঠ-এ আপনার Azure কী যোগ করুন।"
                )
            )
        }
        return SAMPLE_RATE
    }

    override fun chunksFor(paragraph: String): List<SpeechChunk> = BanglaTextPrep.toSentenceGroups(paragraph)

    override fun synthesize(text: String, rate: Float): ShortArray {
        val key = AzureConfig.apiKey(appContext)
        val region = AzureConfig.region(appContext)
        val voice = AzureConfig.voice(appContext)
        val percent = ((rate - 1f) * 100f).toInt().coerceIn(-50, 50)
        val prosody = if (percent >= 0) "+$percent%" else "$percent%"
        val ssml = "<speak version='1.0' xml:lang='bn-BD' xmlns='http://www.w3.org/2001/10/synthesis'>" +
            "<voice name='$voice'><prosody rate='$prosody'>${escapeXml(text)}</prosody></voice></speak>"

        var attempt = 0
        while (true) {
            try {
                val pcm = request(key, region, ssml)
                AzureConfig.addUsage(appContext, text.length)
                return pcm
            } catch (error: IOException) {
                attempt++
                if (attempt >= 2) throw IllegalStateException(
                    VoiceEngines.t(
                        appContext,
                        "Could not reach Azure. Check your internet connection.",
                        "Azure-এ পৌঁছানো যায়নি। ইন্টারনেট সংযোগ দেখুন।"
                    )
                )
                Thread.sleep(400L)
            }
        }
    }

    override fun unload() {
        // Nothing is kept in memory between requests.
    }

    private fun request(key: String, region: String, ssml: String): ShortArray {
        val url = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            setRequestProperty("Content-Type", "application/ssml+xml")
            setRequestProperty("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            setRequestProperty("User-Agent", "KonthoPDFReader")
        }
        try {
            connection.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(explain(code))
            val bytes = connection.inputStream.use { it.readBytes() }
            val samples = ShortArray(bytes.size / 2)
            for (i in samples.indices) {
                val low = bytes[2 * i].toInt() and 0xFF
                val high = bytes[2 * i + 1].toInt()
                samples[i] = ((high shl 8) or low).toShort()
            }
            return samples
        } finally {
            connection.disconnect()
        }
    }

    private fun explain(code: Int): String = when (code) {
        401, 403 -> VoiceEngines.t(
            appContext,
            "Azure rejected the key or region. Check both in Settings → Voice.",
            "Azure কী বা অঞ্চল গ্রহণ করেনি। সেটিংস → কণ্ঠ-এ দুটোই দেখুন।"
        )
        404 -> VoiceEngines.t(
            appContext,
            "Azure region not found. Use the region shown next to your key, for example southeastasia.",
            "Azure অঞ্চল পাওয়া যায়নি। কী-এর পাশের অঞ্চল লিখুন, যেমন southeastasia।"
        )
        429 -> VoiceEngines.t(
            appContext,
            "Azure says the free quota or request limit is used up. Try again later.",
            "Azure জানাচ্ছে ফ্রি কোটা বা অনুরোধের সীমা শেষ। পরে আবার চেষ্টা করুন।"
        )
        else -> VoiceEngines.t(appContext, "Azure answered with error $code.", "Azure ত্রুটি $code দিয়েছে।")
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;")
}
