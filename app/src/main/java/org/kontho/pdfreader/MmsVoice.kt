package org.kontho.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Downloads and manages the optional offline Bangla voice (Meta MMS-TTS, Bengali, run through
 * sherpa-onnx). The voice is not part of the APK: the person chooses to download it, and can
 * delete it again. Licence of the voice: CC BY-NC 4.0 (non-commercial use only).
 */
object MmsInstaller {
    const val PACK_URL = "https://huggingface.co/datasets/Mr66/itantra-packs/resolve/main/bn-tts.itpack"

    /** SHA-256 of the pack. Null until it is pinned; the downloaded hash is shown in Voice settings. */
    val PACK_SHA256: String? = null

    const val APPROX_PACK_BYTES = 114L * 1024L * 1024L
    private const val MIN_FREE_BYTES = 330L * 1024L * 1024L
    private const val MIN_MODEL_BYTES = 20L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 400L * 1024L * 1024L
    private const val KEY_PACK_HASH = "mms_pack_sha256"

    sealed class State {
        object Idle : State()
        class Downloading(val doneBytes: Long, val totalBytes: Long) : State()
        object Installing : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val cancelFlag = AtomicBoolean(false)
    @Volatile private var running = false

    fun addListener(listener: (State) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (State) -> Unit) { listeners.remove(listener) }

    private fun publish(newState: State) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }

    fun voiceDir(context: Context): File = File(context.filesDir, "voices/mms-ben")
    fun modelFile(context: Context): File = File(voiceDir(context), "model.onnx")
    fun tokensFile(context: Context): File = File(voiceDir(context), "tokens.txt")

    fun isInstalled(context: Context): Boolean {
        val model = modelFile(context)
        val tokens = tokensFile(context)
        return model.isFile && model.length() >= MIN_MODEL_BYTES && tokens.isFile && tokens.length() > 0L
    }

    fun installedBytes(context: Context): Long =
        if (isInstalled(context)) modelFile(context).length() + tokensFile(context).length() else 0L

    fun packHash(context: Context): String? =
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE).getString(KEY_PACK_HASH, null)

    fun isBusy(): Boolean = running

    fun cancel() {
        cancelFlag.set(true)
    }

    fun delete(context: Context) {
        VoiceRuntime.releaseAll()
        voiceDir(context).deleteRecursively()
        publish(State.Idle)
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        cancelFlag.set(false)
        val app = context.applicationContext
        thread(name = "kontho-mms-install") {
            try {
                download(app)
            } catch (error: Throwable) {
                File(app.filesDir, "voices/mms-ben.pack.part").delete()
                val message = error.message ?: error.javaClass.simpleName
                publish(State.Failed(message))
            } finally {
                running = false
            }
        }
    }

    private fun download(context: Context) {
        val root = File(context.filesDir, "voices")
        root.mkdirs()
        if (root.usableSpace < MIN_FREE_BYTES) {
            publish(State.Failed(
                VoiceEngines.t(
                    context,
                    "Not enough free storage. About 330 MB is needed while installing.",
                    "পর্যাপ্ত জায়গা নেই। ইনস্টলের সময় প্রায় ৩৩০ MB লাগে।"
                )
            ))
            return
        }
        val part = File(root, "mms-ben.pack.part")
        part.delete()
        publish(State.Downloading(0L, APPROX_PACK_BYTES))

        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(PACK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KonthoPDFReader")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                publish(State.Failed("The download server answered HTTP $code."))
                return
            }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: APPROX_PACK_BYTES
            var done = 0L
            var lastPublished = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelFlag.get()) {
                            part.delete()
                            publish(State.Idle)
                            return
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastPublished > 200L) {
                            publish(State.Downloading(done, total))
                            lastPublished = now
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACK_HASH, hash).apply()
        val pinned = PACK_SHA256
        if (pinned != null && !pinned.equals(hash, ignoreCase = true)) {
            part.delete()
            publish(State.Failed(
                VoiceEngines.t(
                    context,
                    "The downloaded file did not match the expected checksum, so it was discarded.",
                    "ডাউনলোড করা ফাইলের চেকসাম মেলেনি, তাই ফেলে দেওয়া হয়েছে।"
                )
            ))
            return
        }

        publish(State.Installing)
        extract(part, context)
        part.delete()
        if (!isInstalled(context)) throw IllegalStateException("The voice files could not be installed.")
        publish(State.Idle)
    }

    /** Pull model.onnx and tokens.txt out of the pack. Entry names are never used as paths. */
    private fun extract(pack: File, context: Context) {
        val dir = voiceDir(context)
        dir.mkdirs()
        ZipFile(pack).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val model = entries.filter { it.name.endsWith(".onnx", ignoreCase = true) }.maxByOrNull { it.size }
            val tokens = entries.firstOrNull { File(it.name).name.equals("tokens.txt", ignoreCase = true) }
            if (model == null || tokens == null) {
                throw IllegalStateException("The voice pack has an unexpected layout.")
            }
            val modelTmp = File(dir, "model.onnx.tmp")
            val tokensTmp = File(dir, "tokens.txt.tmp")
            copyEntry(zip, model, modelTmp)
            copyEntry(zip, tokens, tokensTmp)
            val modelFinal = modelFile(context)
            val tokensFinal = tokensFile(context)
            modelFinal.delete()
            tokensFinal.delete()
            if (!modelTmp.renameTo(modelFinal) || !tokensTmp.renameTo(tokensFinal)) {
                throw IllegalStateException("The voice files could not be saved.")
            }
        }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, target: File) {
        if (entry.size > MAX_ENTRY_BYTES) throw IllegalStateException("The voice pack is larger than expected.")
        zip.getInputStream(entry).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_ENTRY_BYTES) throw IllegalStateException("The voice pack is larger than expected.")
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}

/** Offline Bangla neural voice: MMS-TTS (VITS) executed by sherpa-onnx on the phone's CPU. */
class MmsSpeaker(context: Context) : PipelinedSpeaker(context) {
    private var tts: OfflineTts? = null
    private var symbols: Set<String> = emptySet()

    override fun load(): Int {
        tts?.let { return it.sampleRate() }
        if (!MmsInstaller.isInstalled(appContext)) {
            throw IllegalStateException(
                VoiceEngines.t(
                    appContext,
                    "The offline Bangla voice is not installed.",
                    "অফলাইন বাংলা কণ্ঠ ইনস্টল করা নেই।"
                )
            )
        }
        val model = MmsInstaller.modelFile(appContext)
        val tokens = MmsInstaller.tokensFile(appContext)
        symbols = BanglaTextPrep.parseSymbols(tokens.readText(Charsets.UTF_8))
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu"
            )
        )
        val created = OfflineTts(config = config)
        tts = created
        return created.sampleRate()
    }

    override fun chunksFor(paragraph: String): List<SpeechChunk> =
        BanglaTextPrep.toChunks(paragraph, symbols, VoiceEngines.vowelFixEnabled(appContext))

    override fun synthesize(text: String, rate: Float): ShortArray {
        val engine = tts ?: throw IllegalStateException("The voice is not loaded.")
        val audio = engine.generate(text = text, sid = 0, speed = rate.coerceIn(0.6f, 1.4f))
        return floatToPcm(audio.samples)
    }

    override fun unload() {
        tts?.free()
        tts = null
    }
}
