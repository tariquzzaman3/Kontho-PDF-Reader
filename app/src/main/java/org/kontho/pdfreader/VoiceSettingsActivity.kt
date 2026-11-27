package org.kontho.pdfreader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/**
 * "Voice" screen: pick who reads books aloud. Best first:
 *   1. Azure neural voice  (online, the person's own free Azure key)
 *   2. MMS offline voice   (one-time download, no internet afterwards)
 *   3. Device voice        (Android's built-in engine, works immediately)
 * The device voice stays the default until the person chooses otherwise.
 */
class VoiceSettingsActivity : ComponentActivity() {
    private lateinit var palette: Palette
    private lateinit var column: LinearLayout

    private class EngineCard(
        val id: String,
        val outer: LinearLayout,
        val indicator: TextView,
        val body: LinearLayout
    )

    private val cards = LinkedHashMap<String, EngineCard>()
    private var testingEngine: String? = null
    private var mmsProgressBar: ProgressBar? = null
    private var mmsProgressText: TextView? = null
    private lateinit var corrWord: EditText
    private lateinit var corrSpoken: EditText
    private lateinit var corrList: LinearLayout
    private var corrHearing = false

    private val installerListener: (MmsInstaller.State) -> Unit = { state ->
        val bar = mmsProgressBar
        val text = mmsProgressText
        if (state is MmsInstaller.State.Downloading && bar != null && text != null) {
            bar.progress = (state.doneBytes * 1000L / state.totalBytes.coerceAtLeast(1L)).toInt()
            text.text = downloadText(state)
        } else {
            fillBody(VoiceEngines.MMS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        palette = Ui.palette(this)
        setTheme(
            if (palette.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        Ui.applySystemBars(window, palette)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        header.addView(
            Ui.iconButton(this, palette, R.drawable.ic_back, t("Back", "পেছনে")) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
        )
        header.addView(
            Ui.text(this, t("Voice", "কণ্ঠ"), 22f, palette.text, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        column.addView(
            Ui.text(
                this,
                t("Choose who reads your books aloud. Best quality first.", "কে আপনার বই পড়ে শোনাবে বেছে নিন। সেরা মান আগে।"),
                14f, palette.textMuted
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        )

        addCard(
            VoiceEngines.AZURE,
            t("Azure neural voice", "Azure নিউরাল কণ্ঠ"),
            t("Best", "সেরা"),
            t("Most natural Bangla. Needs internet and your own free Azure key.",
                "সবচেয়ে স্বাভাবিক বাংলা। ইন্টারনেট ও আপনার নিজের ফ্রি Azure কী লাগে।")
        )
        addCard(
            VoiceEngines.MMS,
            t("Offline Bangla voice", "অফলাইন বাংলা কণ্ঠ"),
            t("Better", "ভালো"),
            t("Works without internet after a one-time download.",
                "একবার ডাউনলোডের পর ইন্টারনেট ছাড়াই চলে।")
        )
        addCard(
            VoiceEngines.SYSTEM,
            t("Device voice", "ডিভাইসের কণ্ঠ"),
            t("Default", "ডিফল্ট"),
            t("Android's built-in voice. Works right now, no internet.",
                "Android-এর নিজস্ব কণ্ঠ। এখনই চলে, ইন্টারনেট লাগে না।")
        )

        addCorrectionsCard()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)

        updateSelection(animate = false)
        cards.values.forEachIndexed { index, card ->
            Ui.revealIn(card.outer, 60L * index, dp(14).toFloat())
        }
    }

    override fun onStart() {
        super.onStart()
        MmsInstaller.addListener(installerListener)
        fillBody(VoiceEngines.MMS)
    }

    override fun onStop() {
        MmsInstaller.removeListener(installerListener)
        if (testingEngine != null || corrHearing) {
            VoiceRuntime.stopAll()
            testingEngine = null
            corrHearing = false
        }
        PronunciationCorrections.install(this)
        super.onStop()
    }

    // ---------------------------------------------------------------- cards

    private fun addCard(id: String, title: String, badge: String, tagline: String) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(Ui.text(this, title, 17f, palette.text, bold = true))
        val badgeView = Ui.text(this, badge, 11f, palette.accent, bold = true).apply {
            letterSpacing = 0.06f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = Ui.shape(palette.accentSoft, dp(8).toFloat())
        }
        titleRow.addView(badgeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        texts.addView(titleRow)
        texts.addView(
            Ui.text(this, tagline, 13f, palette.textMuted),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val indicator = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        head.addView(indicator, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(12) })
        outer.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        outer.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        head.setOnClickListener {
            VoiceEngines.select(this, id)
            updateSelection(animate = true)
        }
        Ui.pressable(head, 0.985f)

        cards[id] = EngineCard(id, outer, indicator, body)
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        fillBody(id)
    }

    private fun updateSelection(animate: Boolean) {
        val selected = VoiceEngines.selected(this)
        if (animate) TransitionManager.beginDelayedTransition(column, AutoTransition().setDuration(220L))
        for ((id, card) in cards) {
            val on = id == selected
            card.outer.background = Ui.shape(
                palette.surface,
                dp(18).toFloat(),
                if (on) palette.accent else palette.outline,
                if (on) dp(2) else dp(1)
            )
            card.indicator.text = if (on) "✓" else ""
            card.indicator.setTextColor(palette.onAccent)
            card.indicator.background = if (on) {
                Ui.shape(palette.accent, dp(13).toFloat())
            } else {
                Ui.shape(palette.surface, dp(13).toFloat(), palette.outline, dp(2))
            }
            card.body.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun fillBody(id: String) {
        val card = cards[id] ?: return
        card.body.removeAllViews()
        when (id) {
            VoiceEngines.AZURE -> buildAzureBody(card.body)
            VoiceEngines.MMS -> buildMmsBody(card.body)
            else -> buildSystemBody(card.body)
        }
    }

    // ---------------------------------------------------------------- Azure

    private fun buildAzureBody(body: LinearLayout) {
        body.addView(note(
            t("Create a free \"Speech\" resource in the Azure portal (pricing tier F0). Copy its Key and Region here. " +
                "About 500,000 characters per month are free.",
                "Azure পোর্টালে একটি ফ্রি \"Speech\" রিসোর্স (প্রাইসিং টিয়ার F0) বানান। এর Key ও Region এখানে দিন। " +
                    "প্রতি মাসে প্রায় ৫,০০,০০০ অক্ষর ফ্রি।")
        ))
        val keyField = field(t("Azure Speech key", "Azure Speech কী"), AzureConfig.apiKey(this), secret = true)
        val regionField = field(t("Region, e.g. southeastasia", "অঞ্চল, যেমন southeastasia"), AzureConfig.region(this), secret = false)
        body.addView(keyField, fieldParams())
        body.addView(regionField, fieldParams())

        val voices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val current = AzureConfig.voice(this)
        fun chip(text: String, voice: String): TextView {
            val on = current == voice
            return Ui.text(this, text, 13f, if (on) palette.onAccent else palette.text, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = Ui.rippleShape(
                    if (on) palette.accent else palette.surfaceAlt, dp(20).toFloat(), palette.ripple
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AzureConfig.saveVoice(this@VoiceSettingsActivity, voice)
                    fillBody(VoiceEngines.AZURE)
                }
            }
        }
        voices.addView(chip(t("Nabanita · female", "নবনীতা · নারী"), AzureConfig.VOICE_FEMALE))
        voices.addView(chip(t("Pradeep · male", "প্রদীপ · পুরুষ"), AzureConfig.VOICE_MALE), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        body.addView(voices, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(10) })

        val used = AzureConfig.usedThisMonth(this)
        body.addView(note(
            t("Sent from this phone this month: ${formatCount(used)} of ${formatCount(AzureConfig.FREE_MONTHLY_CHARS)} free characters. " +
                "The free quota belongs to your Azure account, so other devices using the same key count too.",
                "এই মাসে এই ফোন থেকে পাঠানো: ${formatCount(used)} / ${formatCount(AzureConfig.FREE_MONTHLY_CHARS)} ফ্রি অক্ষর। " +
                    "ফ্রি কোটা আপনার Azure অ্যাকাউন্টের, তাই একই কী ব্যবহার করা অন্য ডিভাইসও গণনায় আসে।")
        ))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Ui.primaryButton(this, palette, t("Save", "সংরক্ষণ")) {
            AzureConfig.save(this, keyField.text.toString(), regionField.text.toString())
            toast(t("Saved", "সংরক্ষিত"))
            fillBody(VoiceEngines.AZURE)
        })
        buttons.addView(
            Ui.secondaryButton(
                this, palette,
                if (testingEngine == VoiceEngines.AZURE) t("Stop", "থামান") else t("Test voice", "কণ্ঠ শুনুন")
            ) {
                AzureConfig.save(this, keyField.text.toString(), regionField.text.toString())
                runTest(VoiceEngines.AZURE)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        if (AzureConfig.isConfigured(this)) {
            buttons.addView(
                Ui.secondaryButton(this, palette, t("Remove key", "কী মুছুন")) {
                    AzureConfig.clearKey(this)
                    fillBody(VoiceEngines.AZURE)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )
        }
        body.addView(buttons)
        body.addView(note(
            t("Your key stays on this phone and is sent only to Microsoft Azure to create speech.",
                "আপনার কী এই ফোনেই থাকে এবং শুধু কণ্ঠ তৈরির জন্য Microsoft Azure-এ পাঠানো হয়।")
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    // ---------------------------------------------------------------- MMS

    private fun buildMmsBody(body: LinearLayout) {
        mmsProgressBar = null
        mmsProgressText = null
        val state = MmsInstaller.state
        when {
            state is MmsInstaller.State.Downloading -> {
                val text = Ui.text(this, downloadText(state), 13f, palette.text)
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (state.doneBytes * 1000L / state.totalBytes.coerceAtLeast(1L)).toInt()
                    progressTintList = ColorStateList.valueOf(palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.accentSoft)
                }
                mmsProgressText = text
                mmsProgressBar = bar
                body.addView(text)
                body.addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
                ).apply { topMargin = dp(8); bottomMargin = dp(10) })
                body.addView(Ui.secondaryButton(this, palette, t("Cancel", "বাতিল")) { MmsInstaller.cancel() })
            }
            state is MmsInstaller.State.Installing -> {
                body.addView(Ui.text(this, t("Installing the voice…", "কণ্ঠ ইনস্টল হচ্ছে…"), 14f, palette.text))
            }
            MmsInstaller.isInstalled(this) -> {
                body.addView(Ui.text(
                    this,
                    t("Installed · ${formatMb(MmsInstaller.installedBytes(this))}",
                        "ইনস্টল করা আছে · ${formatMb(MmsInstaller.installedBytes(this))}"),
                    14f, palette.accent, bold = true
                ))
                body.addView(
                    switchRow(
                        t("Fix unwritten vowels (আশাকরি → আশাকোরি)", "অলিখিত স্বর ঠিক করুন (আশাকরি → আশাকোরি)"),
                        VoiceEngines.vowelFixEnabled(this)
                    ) { enabled ->
                        getSharedPreferences(Ui.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(VoiceEngines.KEY_MMS_VOWEL_FIX, enabled).apply()
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8); bottomMargin = dp(10) }
                )
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(Ui.primaryButton(
                    this, palette,
                    if (testingEngine == VoiceEngines.MMS) t("Stop", "থামান") else t("Test voice", "কণ্ঠ শুনুন")
                ) { runTest(VoiceEngines.MMS) })
                buttons.addView(
                    Ui.secondaryButton(this, palette, t("Delete", "মুছুন")) {
                        MmsInstaller.delete(this)
                        fillBody(VoiceEngines.MMS)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                )
                body.addView(buttons)
                MmsInstaller.packHash(this)?.let { hash ->
                    body.addView(note("SHA-256: $hash").apply { setTextIsSelectable(true) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(10) })
                }
            }
            else -> {
                if (state is MmsInstaller.State.Failed) {
                    body.addView(
                        Ui.text(this, state.message, 13f, palette.warning),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    )
                }
                body.addView(note(
                    t("A one-time download of about ${formatMb(MmsInstaller.APPROX_PACK_BYTES)}. Wi-Fi is recommended. " +
                        "About 330 MB of free space is needed while installing.",
                        "একবারের ডাউনলোড, প্রায় ${formatMb(MmsInstaller.APPROX_PACK_BYTES)}। Wi-Fi ভালো। " +
                            "ইনস্টলের সময় প্রায় ৩৩০ MB খালি জায়গা লাগে।")
                ))
                body.addView(
                    Ui.primaryButton(
                        this, palette,
                        if (state is MmsInstaller.State.Failed) t("Try again", "আবার চেষ্টা") else t("Download voice", "কণ্ঠ ডাউনলোড")
                    ) { MmsInstaller.start(this) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                )
            }
        }
        body.addView(note(
            t("Voice: Meta MMS-TTS (Bengali), licensed CC BY-NC 4.0 for non-commercial use. Runs on your phone with sherpa-onnx.",
                "কণ্ঠ: Meta MMS-TTS (বাংলা), অবাণিজ্যিক ব্যবহারের জন্য CC BY-NC 4.0 লাইসেন্স। sherpa-onnx দিয়ে ফোনেই চলে।")
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    // ---------------------------------------------------------------- System

    private fun buildSystemBody(body: LinearLayout) {
        body.addView(note(
            t("Uses the voice installed on this phone, so it works offline. Quality depends on the speech engine you have. " +
                "You can install a better Bangla voice or switch engine in Android settings.",
                "এই ফোনে ইনস্টল করা কণ্ঠ ব্যবহার করে, তাই অফলাইনে চলে। মান নির্ভর করে আপনার স্পিচ ইঞ্জিনের ওপর। " +
                    "Android সেটিংসে ভালো বাংলা কণ্ঠ ইনস্টল বা ইঞ্জিন বদলাতে পারেন।")
        ))
        body.addView(
            Ui.secondaryButton(this, palette, t("Android voice settings", "Android কণ্ঠ সেটিংস")) {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                        toast(t("Open Android Settings and search for Text-to-speech output.",
                            "Android Settings-এ Text-to-speech output খুঁজুন।"))
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
    }


    // ---------------------------------------------------------------- pronunciation corrections

    private fun addCorrectionsCard() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.shape(palette.surface, dp(18).toFloat())
        }
        outer.addView(Ui.text(this, t("Pronunciation corrections", "উচ্চারণ সংশোধন"), 17f, palette.text, bold = true))
        outer.addView(
            Ui.text(
                this,
                t("Type a word as the book writes it, then spell how it should sound. Press Hear it to test, Save to keep it. Works with the offline and Azure voices.",
                    "বইয়ে শব্দটি যেভাবে লেখা তা লিখুন, তারপর কেমন শোনাবে তা বানান করে লিখুন। শুনতে 'শুনুন', রাখতে 'সংরক্ষণ' চাপুন। অফলাইন ও Azure কণ্ঠে কাজ করে।"),
                13f, palette.textMuted
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
        )
        corrWord = field(t("Word as written (e.g. একটি)", "যেভাবে লেখা (যেমন একটি)"), "", false)
        corrSpoken = field(t("How it should sound (e.g. এক টি)", "যেভাবে শোনাবে (যেমন এক টি)"), "", false)
        outer.addView(corrWord, fieldParams())
        outer.addView(corrSpoken, fieldParams())

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            Ui.secondaryButton(this, palette, t("Hear it", "শুনুন")) { hearCorrection() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        )
        buttons.addView(
            Ui.primaryButton(this, palette, t("Save", "সংরক্ষণ")) { saveCorrection() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        outer.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        corrList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(corrList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        outer.addView(
            Ui.secondaryButton(this, palette, t("Share my list", "আমার তালিকা পাঠান")) { shareCorrections() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        refreshCorrections()
    }

    private fun refreshCorrections() {
        corrList.removeAllViews()
        val entries = PronunciationCorrections.load(this)
        if (entries.isEmpty()) {
            corrList.addView(note(t("No corrections saved yet.", "এখনও কোনো সংশোধন সংরক্ষিত নেই।")))
            return
        }
        for ((word, spoken) in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = Ui.text(this, "$word  →  $spoken", 14f, palette.text).apply {
                isClickable = true
                setOnClickListener {
                    corrWord.setText(word)
                    corrSpoken.setText(spoken)
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this, "▶", 16f, palette.accent).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { hearWord(word, mapOf(word to spoken)) }
            })
            row.addView(Ui.text(this, "✕", 16f, palette.textMuted).apply {
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    PronunciationCorrections.remove(this@VoiceSettingsActivity, word)
                    refreshCorrections()
                }
            })
            corrList.addView(row)
        }
    }

    private fun saveCorrection() {
        when (PronunciationCorrections.add(this, corrWord.text.toString(), corrSpoken.text.toString())) {
            PronunciationCorrections.Result.SAVED -> {
                corrWord.setText("")
                corrSpoken.setText("")
                refreshCorrections()
                toast(t("Saved.", "সংরক্ষিত হয়েছে।"))
            }
            PronunciationCorrections.Result.BAD_WORD ->
                toast(t("Write one Bangla word in the first box.", "প্রথম ঘরে একটি বাংলা শব্দ লিখুন।"))
            PronunciationCorrections.Result.BAD_SPOKEN ->
                toast(t("Write a different spelling in the second box.", "দ্বিতীয় ঘরে অন্যরকম বানান লিখুন।"))
            PronunciationCorrections.Result.FULL ->
                toast(t("The list is full (500 words).", "তালিকা পূর্ণ (৫০০ শব্দ)।"))
        }
    }

    private fun hearCorrection() {
        val word = CorrectionList.cleanWord(corrWord.text.toString())
        val pending = PronunciationCorrections.withPending(this, corrWord.text.toString(), corrSpoken.text.toString())
        if (pending == null) {
            toast(t("Fill both boxes first.", "আগে দুটি ঘরই পূরণ করুন।"))
            return
        }
        hearWord(word, pending)
    }

    /** Speaks the word through the chosen voice with [table] applied, just like real reading. */
    private fun hearWord(word: String, table: Map<String, String>) {
        val engine = VoiceEngines.selected(this)
        if (engine == VoiceEngines.SYSTEM) {
            toast(t("Choose the offline or Azure voice above to test corrections.",
                "সংশোধন পরীক্ষা করতে উপরে অফলাইন বা Azure কণ্ঠ বেছে নিন।"))
            return
        }
        VoiceRuntime.stopAll()
        testingEngine = null
        val speaker = VoiceRuntime.speaker(this, engine) ?: return
        // speaker() reloads the saved list, so the pending list goes in afterwards.
        BanglaTextPrep.userCorrections = LinkedHashMap(table)
        val rate = getSharedPreferences(Ui.PREFS, MODE_PRIVATE).getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f)
        corrHearing = true
        val done = {
            corrHearing = false
            PronunciationCorrections.install(this)
        }
        speaker.speak(listOf("$word, $word।"), 0, rate, object : SegmentSpeaker.Callback {
            override fun onSegmentStarted(index: Int) {}
            override fun onFinished() = done()
            override fun onError(message: String) {
                done()
                toast(message)
            }
            override fun onInterrupted() = done()
        })
    }

    private fun shareCorrections() {
        val entries = PronunciationCorrections.load(this)
        if (entries.isEmpty()) {
            toast(t("Nothing to share yet.", "পাঠানোর মতো কিছু নেই।"))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, CorrectionList.shareText(entries))
        }
        startActivity(Intent.createChooser(send, t("Share my list", "আমার তালিকা পাঠান")))
    }

    // ---------------------------------------------------------------- test voice

    private fun runTest(engine: String) {
        if (testingEngine == engine) {
            VoiceRuntime.stopAll()
            testingEngine = null
            fillBody(engine)
            return
        }
        VoiceRuntime.stopAll()
        val speaker = VoiceRuntime.speaker(this, engine) ?: return
        val sample = "আশাকরি আপনি ভালো আছেন। এটি কণ্ঠের একটি পরীক্ষামূলক পাঠ, যেখানে ড়, ঢ় আর য় আছে: বড়, আষাঢ়, ময়ূর।"
        val rate = getSharedPreferences(Ui.PREFS, MODE_PRIVATE).getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f)
        testingEngine = engine
        fillBody(engine)
        speaker.speak(listOf(sample), 0, rate, object : SegmentSpeaker.Callback {
            override fun onSegmentStarted(index: Int) {}

            override fun onFinished() {
                testingEngine = null
                fillBody(engine)
            }

            override fun onError(message: String) {
                testingEngine = null
                fillBody(engine)
                toast(message)
            }

            override fun onInterrupted() {
                testingEngine = null
                fillBody(engine)
            }
        })
    }

    // ---------------------------------------------------------------- small helpers

    private fun note(text: String): TextView = Ui.text(this, text, 12f, palette.textMuted).apply {
        setLineSpacing(0f, 1.15f)
    }.also { it.setPadding(0, 0, 0, dp(10)) }

    private fun field(hint: String, value: String, secret: Boolean): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 14f
        setTextColor(palette.text)
        setHintTextColor(palette.textMuted)
        setSingleLine(true)
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        background = Ui.shape(palette.surfaceAlt, dp(12).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun fieldParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun switchRow(text: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val toggle = Switch(this@VoiceSettingsActivity).apply {
                isChecked = checked
                thumbTintList = ColorStateList.valueOf(palette.accent)
                trackTintList = ColorStateList.valueOf(palette.accentSoft)
                setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
            }
            addView(Ui.text(this@VoiceSettingsActivity, text, 13f, palette.text),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }

    private fun downloadText(state: MmsInstaller.State.Downloading): String =
        t("Downloading… ${formatMb(state.doneBytes)} of ${formatMb(state.totalBytes)}",
            "ডাউনলোড হচ্ছে… ${formatMb(state.doneBytes)} / ${formatMb(state.totalBytes)}")

    private fun formatMb(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun t(en: String, bn: String): String = VoiceEngines.t(this, en, bn)

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
