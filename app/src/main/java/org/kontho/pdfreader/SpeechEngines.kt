package org.kontho.pdfreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Something that can read a list of paragraphs aloud. The reader asks for "speak from paragraph N";
 * the engine reports which paragraph it has reached, when it is finished, or when it failed.
 * All callbacks arrive on the main thread, and stale ones from a stopped run are never delivered.
 */
interface SegmentSpeaker {
    interface Callback {
        fun onSegmentStarted(index: Int)
        fun onFinished()
        fun onError(message: String)
        /** Another app took the audio (call, alarm, other player). The reader should pause. */
        fun onInterrupted()
    }

    fun speak(segments: List<String>, startIndex: Int, rate: Float, callback: Callback)
    fun stop()
    fun shutdown()
}

/** Which voice is selected, and whether it is ready to use. */
object VoiceEngines {
    const val SYSTEM = "system"
    const val MMS = "mms"
    const val AZURE = "azure"

    const val KEY_ENGINE = "voice_engine"
    const val KEY_MMS_VOWEL_FIX = "mms_vowel_fix"

    private fun prefs(context: Context) = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)

    fun selected(context: Context): String {
        val value = prefs(context).getString(KEY_ENGINE, SYSTEM) ?: SYSTEM
        return if (value == MMS || value == AZURE) value else SYSTEM
    }

    fun select(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun vowelFixEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MMS_VOWEL_FIX, true)

    fun hasBangla(text: String): Boolean = text.any { it in 'ঀ'..'৿' }

    fun title(context: Context, engine: String): String = when (engine) {
        AZURE -> t(context, "Azure neural voice", "Azure নিউরাল কণ্ঠ")
        MMS -> t(context, "Offline Bangla voice", "অফলাইন বাংলা কণ্ঠ")
        else -> t(context, "Device voice", "ডিভাইসের কণ্ঠ")
    }

    /** Is this engine set up well enough to speak right now? System voice is always "ready". */
    fun isReady(context: Context, engine: String): Boolean = when (engine) {
        MMS -> MmsInstaller.isInstalled(context)
        AZURE -> AzureConfig.isConfigured(context)
        else -> true
    }

    /** Pick "ready" voice text in the app's language. */
    fun t(context: Context, en: String, bn: String): String = if (Ui.isBangla(context)) bn else en
}

/** Keeps one live copy of each heavy engine so settings screens and the reader share the model. */
object VoiceRuntime {
    private var mms: MmsSpeaker? = null
    private var azure: AzureSpeaker? = null

    @Synchronized
    fun speaker(context: Context, engine: String): SegmentSpeaker? {
        val app = context.applicationContext
        return when (engine) {
            VoiceEngines.MMS -> mms ?: MmsSpeaker(app).also { mms = it }
            VoiceEngines.AZURE -> azure ?: AzureSpeaker(app).also { azure = it }
            else -> null
        }
    }

    @Synchronized
    fun stopAll() {
        mms?.stop()
        azure?.stop()
    }

    @Synchronized
    fun releaseAll() {
        mms?.shutdown()
        azure?.shutdown()
        mms = null
        azure = null
    }
}

/**
 * Shared playback pipeline for engines that produce raw audio (offline MMS, Azure).
 *
 * One thread synthesises the next piece while another plays the current one, so there is no gap
 * between sentences. Pauses from punctuation are written as silence.
 */
abstract class PipelinedSpeaker(context: Context) : SegmentSpeaker {
    protected val appContext: Context = context.applicationContext

    private val main = Handler(Looper.getMainLooper())
    private val generator = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val currentRun = AtomicInteger(0)
    private val trackLock = Any()
    private var track: AudioTrack? = null

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var activeCallback: SegmentSpeaker.Callback? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            val callback = activeCallback
            main.post { callback?.onInterrupted() }
        }
    }

    /** Load models etc. Runs on the generator thread. Returns the sample rate of the audio. */
    protected abstract fun load(): Int

    /** Split one paragraph into pieces to synthesise. Runs on the generator thread. */
    protected abstract fun chunksFor(paragraph: String): List<SpeechChunk>

    /** Turn one piece of text into 16-bit mono PCM. Runs on the generator thread. */
    protected abstract fun synthesize(text: String, rate: Float): ShortArray

    /** Release native resources. Runs on the generator thread, after any synthesis in progress. */
    protected abstract fun unload()

    private class Item(
        val sampleRate: Int = 0,
        val segment: Int = -1,
        val firstOfSegment: Boolean = false,
        val pcm: ShortArray? = null,
        val pauseMs: Int = 0,
        val end: Boolean = false,
        val error: String? = null
    )

    override fun speak(segments: List<String>, startIndex: Int, rate: Float, callback: SegmentSpeaker.Callback) {
        val run = currentRun.incrementAndGet()
        releaseTrack()
        activeCallback = callback
        requestFocus()
        val queue: BlockingQueue<Item> = ArrayBlockingQueue(4)
        generator.execute { produce(run, queue, segments, startIndex, rate) }
        player.execute { consume(run, queue, callback) }
    }

    override fun stop() {
        currentRun.incrementAndGet()
        activeCallback = null
        releaseTrack()
        abandonFocus()
    }

    override fun shutdown() {
        stop()
        generator.execute { try { unload() } catch (_: Throwable) { } }
        generator.shutdown()
        player.shutdown()
    }

    private fun offer(run: Int, queue: BlockingQueue<Item>, item: Item): Boolean {
        while (run == currentRun.get()) {
            if (queue.offer(item, 200L, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun produce(run: Int, queue: BlockingQueue<Item>, segments: List<String>, start: Int, rate: Float) {
        try {
            if (run != currentRun.get()) return
            val sampleRate = load()
            if (!offer(run, queue, Item(sampleRate = sampleRate))) return
            for (index in start until segments.size) {
                if (run != currentRun.get()) return
                val chunks = chunksFor(segments[index])
                if (chunks.isEmpty()) {
                    if (!offer(run, queue, Item(segment = index, firstOfSegment = true, pcm = ShortArray(0)))) return
                    continue
                }
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    if (run != currentRun.get()) return
                    val pcm = synthesize(chunk.text, rate)
                    val item = Item(
                        segment = index,
                        firstOfSegment = chunkIndex == 0,
                        pcm = pcm,
                        pauseMs = chunk.pauseMs
                    )
                    if (!offer(run, queue, item)) return
                }
            }
            offer(run, queue, Item(end = true))
        } catch (error: Throwable) {
            offer(run, queue, Item(error = error.message ?: error.javaClass.simpleName))
        }
    }

    private fun consume(run: Int, queue: BlockingQueue<Item>, callback: SegmentSpeaker.Callback) {
        var audio: AudioTrack? = null
        var sampleRate = 0
        var framesWritten = 0L
        try {
            while (run == currentRun.get()) {
                val item = queue.poll(200L, TimeUnit.MILLISECONDS) ?: continue
                if (item.error != null) {
                    deliver(run) { callback.onError(item.error) }
                    return
                }
                if (item.sampleRate > 0) {
                    sampleRate = item.sampleRate
                    val created = createTrack(sampleRate)
                    synchronized(trackLock) {
                        if (run != currentRun.get()) {
                            created.release()
                            return
                        }
                        track = created
                    }
                    created.play()
                    audio = created
                    continue
                }
                if (item.end) {
                    drain(audio, framesWritten, sampleRate, run)
                    deliver(run) { callback.onFinished() }
                    return
                }
                if (item.firstOfSegment) {
                    val segment = item.segment
                    deliver(run) { callback.onSegmentStarted(segment) }
                }
                val out = audio ?: continue
                val pcm = item.pcm
                if (pcm != null && pcm.isNotEmpty()) {
                    framesWritten += writeAll(out, pcm, run)
                }
                if (item.pauseMs > 0 && sampleRate > 0) {
                    val silentFrames = sampleRate.toLong() * item.pauseMs / 1000L
                    framesWritten += writeSilence(out, silentFrames, run)
                }
            }
        } catch (error: Throwable) {
            if (run == currentRun.get()) {
                deliver(run) { callback.onError(error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private fun deliver(run: Int, action: () -> Unit) {
        main.post { if (run == currentRun.get()) action() }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buffer = maxOf(minBuffer, sampleRate * 2) // about one second of audio
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeAll(out: AudioTrack, pcm: ShortArray, run: Int): Long {
        var offset = 0
        while (offset < pcm.size && run == currentRun.get()) {
            val written = out.write(pcm, offset, pcm.size - offset)
            if (written < 0) break
            offset += written
        }
        return offset.toLong()
    }

    private fun writeSilence(out: AudioTrack, frames: Long, run: Int): Long {
        val block = ShortArray(2048)
        var remaining = frames
        while (remaining > 0 && run == currentRun.get()) {
            val count = minOf(remaining, block.size.toLong()).toInt()
            val written = out.write(block, 0, count)
            if (written < 0) break
            remaining -= written
        }
        return frames - remaining
    }

    private fun drain(out: AudioTrack?, frames: Long, sampleRate: Int, run: Int) {
        if (out == null || sampleRate <= 0) return
        try {
            out.stop() // in stream mode this plays what is already queued, then stops
        } catch (_: IllegalStateException) {
            return
        }
        val deadline = System.currentTimeMillis() + frames * 1000L / sampleRate + 3000L
        while (run == currentRun.get() && System.currentTimeMillis() < deadline) {
            val head = try { out.playbackHeadPosition.toLong() } catch (_: IllegalStateException) { return }
            if (head >= frames) return
            Thread.sleep(50L)
        }
    }

    private fun releaseTrack() {
        val old: AudioTrack?
        synchronized(trackLock) {
            old = track
            track = null
        }
        if (old != null) {
            try { old.pause() } catch (_: Throwable) { }
            try { old.flush() } catch (_: Throwable) { }
            try { old.release() } catch (_: Throwable) { }
        }
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Throwable) {
            // Playback still works without focus handling.
        }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (_: Throwable) {
        }
    }

    protected fun floatToPcm(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
