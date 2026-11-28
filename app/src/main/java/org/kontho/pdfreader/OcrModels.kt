// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Which text-recognition engine reads scanned pages, listed best first:
 *   1. Kontho OCR   (Tesseract with Bangla, English and Arabic models; a small one-time download)
 *   2. Phone default (the text already inside the PDF; works at once, offline, no download)
 *
 * Kontho OCR is the default choice. While it is not downloaded, reading falls back to the phone
 * default when offline, and offers the download when there is internet.
 */
object OcrEngines {
    const val KONTHO = "kontho"
    const val DEVICE = "device"

    const val KEY_ENGINE = "ocr_engine"
    const val KEY_LANGUAGE = "ocr_language"
    const val KEY_CACHE_LIMIT_MB = "ocr_cache_limit_mb"
    const val KEY_STOP_ASKING = "ocr_download_prompt_off"
    const val KEY_CACHE_CLEARED_AT = "ocr_cache_cleared_at"
    const val DEFAULT_LANGUAGE = "ben+eng+ara"

    private fun prefs(context: Context) = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)

    fun selected(context: Context): String =
        if (prefs(context).getString(KEY_ENGINE, KONTHO) == DEVICE) DEVICE else KONTHO

    fun select(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_ENGINE, if (engine == DEVICE) DEVICE else KONTHO).apply()
    }

    /** Languages in a fixed order (ben, eng, ara) joined with '+', e.g. "ben+eng". */
    fun language(context: Context): String {
        val saved = prefs(context).getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        return normalize(saved.split('+'))
    }

    fun languages(context: Context): List<String> = language(context).split('+')

    fun saveLanguages(context: Context, codes: Collection<String>) {
        prefs(context).edit().putString(KEY_LANGUAGE, normalize(codes)).apply()
    }

    private fun normalize(codes: Collection<String>): String {
        val wanted = OcrModels.ORDER.filter { it in codes }
        return (if (wanted.isEmpty()) OcrModels.ORDER else wanted).joinToString("+")
    }

    fun title(context: Context, engine: String): String = when (engine) {
        KONTHO -> VoiceEngines.t(context, "Kontho OCR", "কণ্ঠ OCR")
        else -> VoiceEngines.t(context, "Phone default", "ফোনের ডিফল্ট")
    }

    fun languageName(context: Context, code: String): String = when (code) {
        "ben" -> VoiceEngines.t(context, "Bangla", "বাংলা")
        "eng" -> "English"
        else -> VoiceEngines.t(context, "Arabic", "আরবি")
    }

    fun stopAsking(context: Context): Boolean = prefs(context).getBoolean(KEY_STOP_ASKING, false)

    fun setStopAsking(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOP_ASKING, value).apply()
    }

    /** True when the phone reports a connection that can reach the internet. */
    fun isOnline(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false
    }

    /** What happens when the person asks to read aloud right now. */
    enum class Plan { USE_KONTHO, USE_DEVICE, OFFER_DOWNLOAD, USE_DEVICE_OFFLINE }

    fun plan(context: Context): Plan {
        if (selected(context) == DEVICE) return Plan.USE_DEVICE
        if (OcrModels.isInstalled(context, languages(context))) return Plan.USE_KONTHO
        if (!isOnline(context)) return Plan.USE_DEVICE_OFFLINE
        return if (stopAsking(context)) Plan.USE_DEVICE else Plan.OFFER_DOWNLOAD
    }
}

internal data class OcrModelIntegrity(
    val repository: String,
    val revision: String,
    val expectedSize: Long,
    val gitBlobSha1: String
)

/**
 * Downloads, checks and removes the Tesseract language models used by Kontho OCR.
 * The models are not part of the APK. They download once, either from Settings → OCR or the first
 * time a scanned page is read, and then work offline.
 */
object OcrModels {
    val ORDER = listOf("ben", "eng", "ara")

    sealed class State {
        object Idle : State()
        class Downloading(val label: String, val percent: Int) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val cancelFlag = AtomicBoolean(false)
    private val verified = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false

    fun addListener(listener: (State) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (State) -> Unit) { listeners.remove(listener) }
    fun isBusy(): Boolean = running
    fun cancel() { cancelFlag.set(true) }

    private fun publish(newState: State) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }

    private fun spec(repository: String, code: String): OcrModelIntegrity = when ("$repository/$code") {
        "tessdata_best/ben" -> OcrModelIntegrity(
            "tessdata_best", "e12c65a915945e4c28e237a9b52bc4a8f39a0cec",
            11_045_427L, "b16a79b0bf5a66e70a0f73165ed0eb92c688ee20"
        )
        "tessdata_best/eng" -> OcrModelIntegrity(
            "tessdata_best", "e12c65a915945e4c28e237a9b52bc4a8f39a0cec",
            15_400_601L, "176dc3220de7db34d3b3aecbfa42043a6038348b"
        )
        "tessdata_best/ara" -> OcrModelIntegrity(
            "tessdata_best", "e12c65a915945e4c28e237a9b52bc4a8f39a0cec",
            12_603_724L, "4b687c7d54c86cf6219aebe781b47284fd5d41f0"
        )
        "tessdata_fast/ben" -> OcrModelIntegrity(
            "tessdata_fast", "87416418657359cb625c412a48b6e1d6d41c29bd",
            855_841L, "7e9054d1de309714009a85555f5276168d41af9c"
        )
        "tessdata_fast/eng" -> OcrModelIntegrity(
            "tessdata_fast", "87416418657359cb625c412a48b6e1d6d41c29bd",
            4_113_088L, "bbef4675053b5b468cdb477053e28b1c698ba08e"
        )
        "tessdata_fast/ara" -> OcrModelIntegrity(
            "tessdata_fast", "87416418657359cb625c412a48b6e1d6d41c29bd",
            1_432_056L, "c8d129c67821c1592cac46836686b057ecc203dc"
        )
        else -> throw IOException("No trusted OCR model is configured for $repository/$code.")
    }

    private fun checkLanguages(languages: List<String>): List<String> {
        val unique = languages.distinct()
        if (unique.isEmpty() || unique.size != languages.size || unique.any { it !in ORDER }) {
            throw IOException("The selected OCR language combination is not supported.")
        }
        return unique
    }

    /** Bangla and Arabic use the more accurate "best" models; English alone uses the small fast one. */
    private fun repositoryFor(languages: List<String>): String =
        if (languages.any { it == "ben" || it == "ara" }) "tessdata_best" else "tessdata_fast"

    private fun rootFor(context: Context, languages: List<String>): File = when {
        "ben" in languages -> File(context.filesDir, "tesseract_bengali_best")
        "ara" in languages -> File(context.filesDir, "tesseract_arabic_best")
        else -> File(context.filesDir, "tesseract")
    }

    private fun modelFile(root: File, code: String) = File(File(root, "tessdata"), "$code.traineddata")

    /** Download size of the selected languages, for the "about N MB" note. */
    fun downloadBytes(languages: List<String>): Long {
        val list = languages.distinct().filter { it in ORDER }
        if (list.isEmpty()) return 0L
        val repository = repositoryFor(list)
        return list.sumOf { spec(repository, it).expectedSize }
    }

    /** Quick check (file size only, no hashing) that every selected language is on the phone. */
    fun isInstalled(context: Context, languages: List<String>): Boolean {
        val list = languages.distinct().filter { it in ORDER }
        if (list.isEmpty()) return false
        val repository = repositoryFor(list)
        val root = rootFor(context, list)
        if (list.all { sizeMatches(modelFile(root, it), spec(repository, it)) }) return true
        // Fast English/Bangla/Arabic models left from older versions still read offline.
        val fastRoot = File(context.filesDir, "tesseract")
        return list.all { sizeMatches(modelFile(fastRoot, it), spec("tessdata_fast", it)) }
    }

    fun isLanguageInstalled(context: Context, code: String): Boolean = isInstalled(context, listOf(code))

    fun installedBytes(context: Context): Long {
        var total = 0L
        for (name in listOf("tesseract", "tesseract_bengali_best", "tesseract_arabic_best")) {
            val folder = File(File(context.filesDir, name), "tessdata")
            folder.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".traineddata")) total += it.length() }
        }
        return total
    }

    fun delete(context: Context) {
        for (name in listOf("tesseract", "tesseract_bengali_best", "tesseract_arabic_best")) {
            File(context.filesDir, name).deleteRecursively()
        }
        verified.clear()
        publish(State.Idle)
    }

    private fun sizeMatches(file: File, spec: OcrModelIntegrity) = file.isFile && file.length() == spec.expectedSize

    private fun isVerified(file: File, spec: OcrModelIntegrity): Boolean {
        if (!sizeMatches(file, spec)) {
            verified.remove(file.absolutePath)
            return false
        }
        if (verified.contains(file.absolutePath)) return true
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${spec.expectedSize}\u0000".toByteArray(Charsets.US_ASCII))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val ok = actual.equals(spec.gitBlobSha1, ignoreCase = true)
        if (ok) verified.add(file.absolutePath)
        return ok
    }

    /** Starts a background download for Settings → OCR. Progress goes to the listeners. */
    @Synchronized
    fun start(context: Context, languages: List<String>) {
        if (running) return
        running = true
        cancelFlag.set(false)
        val app = context.applicationContext
        publish(State.Downloading("Starting…", 0))
        thread(name = "kontho-ocr-install") {
            try {
                ensure(app, languages) { label, percent -> publish(State.Downloading(label, percent)) }
                publish(State.Idle)
            } catch (error: Throwable) {
                val cancelled = cancelFlag.get()
                publish(if (cancelled) State.Idle else State.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                running = false
            }
        }
    }

    /**
     * Makes sure the models for [languages] are on the phone and returns the folder to give
     * Tesseract. Blocks while downloading; call it off the main thread.
     */
    @Synchronized
    fun ensure(context: Context, languages: List<String>, progress: (String, Int) -> Unit): File {
        val list = checkLanguages(languages)
        val repository = repositoryFor(list)
        val root = rootFor(context, list)
        try {
            download(root, list, repository, progress)
            return root
        } catch (error: Exception) {
            // Keep reading offline when every selected fast model is already on the phone.
            val fastRoot = File(context.filesDir, "tesseract")
            val fastOk = list.all { isVerified(modelFile(fastRoot, it), spec("tessdata_fast", it)) }
            if (repository == "tessdata_best" && fastOk) return fastRoot
            throw error
        }
    }

    private fun download(root: File, languages: List<String>, repository: String, progress: (String, Int) -> Unit) {
        val tessdata = File(root, "tessdata")
        if (!tessdata.exists() && !tessdata.mkdirs()) throw IOException("Cannot create the OCR model folder.")
        for (code in languages) {
            val spec = spec(repository, code)
            val model = modelFile(root, code)
            if (isVerified(model, spec)) continue

            val partial = File(tessdata, "$code.traineddata.part")
            if (partial.exists() && !partial.delete()) {
                throw IOException("Could not clear an incomplete OCR model download.")
            }
            val url = URL("https://raw.githubusercontent.com/tesseract-ocr/${spec.repository}/${spec.revision}/$code.traineddata")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 90_000
                requestMethod = "GET"
            }
            try {
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("OCR model download returned HTTP ${connection.responseCode}.")
                }
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0L && contentLength != spec.expectedSize) {
                    throw IOException("The OCR model size did not match the trusted version.")
                }
                val label = "Downloading OCR model: $code…"
                progress(label, 0)
                var copied = 0L
                var lastUpdate = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelFlag.get() && running) throw IOException("Download cancelled.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > spec.expectedSize) {
                                throw IOException("The OCR model exceeded its expected download size.")
                            }
                            output.write(buffer, 0, count)
                            if (copied - lastUpdate >= 256 * 1024) {
                                lastUpdate = copied
                                progress(label, (copied * 100L / spec.expectedSize).toInt())
                            }
                        }
                    }
                }
                if (copied != spec.expectedSize || !isVerified(partial, spec)) {
                    throw IOException("The downloaded OCR model failed its integrity check.")
                }
                if (model.exists() && !model.delete()) throw IOException("Could not replace the OCR model.")
                if (!partial.renameTo(model)) throw IOException("Could not save OCR model.")
                verified.remove(partial.absolutePath)
                verified.add(model.absolutePath)
            } catch (error: Exception) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }
}
