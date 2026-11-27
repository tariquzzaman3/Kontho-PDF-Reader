package org.kontho.pdfreader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.SeekBar
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

        addSpeedCard()
        addOptionsCard()
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

    override fun onDestroy() {
        deviceTts?.stop()
        deviceTts?.shutdown()
        deviceTts = null
        super.onDestroy()
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

    private var azureGuideOpen: Boolean? = null

    private fun buildAzureBody(body: LinearLayout) {
        body.addView(note(
            t("Azure gives about 500,000 characters of speech per month for free. You need a free Speech key. " +
                "Follow the steps below once; it takes about 10 minutes.",
                "Azure প্রতি মাসে প্রায় ৫,০০,০০০ অক্ষরের কণ্ঠ ফ্রি দেয়। এজন্য একটি ফ্রি Speech কী লাগবে। " +
                    "নিচের ধাপগুলো একবার অনুসরণ করুন; প্রায় ১০ মিনিট লাগে।")
        ))
        addAzureGuide(body)
        val keyField = field(t("Azure Speech key", "Azure Speech কী"), AzureConfig.apiKey(this), secret = true)
        val regionField = field(t("Region, e.g. southeastasia", "অঞ্চল, যেমন southeastasia"), AzureConfig.region(this), secret = false)
        body.addView(keyField, fieldParams())
        body.addView(regionField, fieldParams())
        val pasteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pasteRow.addView(smallChip(t("Paste key", "কী পেস্ট করুন")) { pasteInto(keyField, trim = true) })
        pasteRow.addView(smallChip(t("Paste region", "অঞ্চল পেস্ট করুন")) { pasteInto(regionField, trim = true, lower = true) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
        body.addView(pasteRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

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


    // ---- Azure: step-by-step key guide

    private fun addAzureGuide(body: LinearLayout) {
        val open = azureGuideOpen ?: !AzureConfig.isConfigured(this)
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rippleShape(palette.accentSoft, dp(14).toFloat(), palette.ripple)
            isClickable = true
            isFocusable = true
        }
        toggle.addView(
            Ui.text(this, t("How to get your free Speech key", "ফ্রি Speech কী কীভাবে পাবেন"), 14f, palette.accent, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        toggle.addView(Ui.text(this, if (open) "▲" else "▼", 13f, palette.accent))
        toggle.setOnClickListener {
            azureGuideOpen = !open
            fillBody(VoiceEngines.AZURE)
        }
        body.addView(toggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        if (!open) return

        val steps = listOf(
            t("Sign in to Azure", "Azure-এ সাইন ইন করুন") to
                t("Tap the button below to open portal.azure.com in your browser. Sign in with a Microsoft account, or create a free one. " +
                    "Azure may ask for a phone number and a card to prove you are a real person. Choose the Free (F0) Speech plan in step 3 and you will not be charged.",
                    "নিচের বোতামে চাপ দিয়ে ব্রাউজারে portal.azure.com খুলুন। Microsoft অ্যাকাউন্ট দিয়ে সাইন ইন করুন, না থাকলে ফ্রি একটি বানান। " +
                        "আপনি সত্যিকারের মানুষ কি না যাচাই করতে Azure ফোন নম্বর ও কার্ড চাইতে পারে। ধাপ ৩-এ Free (F0) প্ল্যান বেছে নিলে কোনো টাকা কাটবে না।"),
            t("Find Speech services", "Speech services খুঁজুন") to
                t("At the top of the page, tap the search box and type  Speech . Choose \"Speech services\", then tap Create.",
                    "পেজের ওপরের সার্চ বক্সে  Speech  লিখুন। \"Speech services\" বেছে নিয়ে Create চাপুন।"),
            t("Fill in the form", "ফর্মটি পূরণ করুন") to
                t("Subscription: leave as it is.\nResource group: tap Create new and type any name, for example kontho.\n" +
                    "Region: pick one close to you, for example Southeast Asia.\nName: any short unique name, for example kontho-voice-yourname.\n" +
                    "Pricing tier: choose Free (F0).\nThen tap Review + create, and Create.",
                    "Subscription: যেমন আছে থাক।\nResource group: Create new চেপে যেকোনো নাম দিন, যেমন kontho।\n" +
                        "Region: আপনার কাছের একটি বেছে নিন, যেমন Southeast Asia।\nName: ছোট ও আলাদা একটি নাম, যেমন kontho-voice-আপনার-নাম।\n" +
                        "Pricing tier: Free (F0) বেছে নিন।\nতারপর Review + create এবং Create চাপুন।"),
            t("Open your keys", "আপনার কী খুলুন") to
                t("When it says the deployment is complete, tap Go to resource. In the menu on the left, tap \"Keys and Endpoint\".",
                    "ডিপ্লয়মেন্ট শেষ হলে Go to resource চাপুন। বাঁদিকের মেনুতে \"Keys and Endpoint\" চাপুন।"),
            t("Copy the key and the region", "কী ও অঞ্চল কপি করুন") to
                t("Copy KEY 1 (a long line of letters and numbers) by tapping the copy icon next to it. " +
                    "Below it you will also see Location/Region, for example southeastasia. You will paste both here.",
                    "KEY 1 (অক্ষর ও সংখ্যার লম্বা লাইন) এর পাশের কপি আইকনে চেপে কপি করুন। " +
                        "এর নিচে Location/Region দেখবেন, যেমন southeastasia। দুটোই এখানে পেস্ট করবেন।"),
            t("Paste them here", "এখানে পেস্ট করুন") to
                t("Come back to Kontho. Tap Paste key to fill the key box, then copy the region and tap Paste region. " +
                    "Write the region in small letters with no spaces: \"Southeast Asia\" becomes southeastasia. Tap Save, then Test voice.",
                    "কণ্ঠ-তে ফিরে আসুন। কী পেস্ট করুন চাপলে কী-র ঘর ভরবে, তারপর অঞ্চল কপি করে অঞ্চল পেস্ট করুন চাপুন। " +
                        "অঞ্চল ছোট হাতের অক্ষরে, ফাঁকা ছাড়া লিখুন: \"Southeast Asia\" হবে southeastasia। সংরক্ষণ চাপুন, তারপর কণ্ঠ শুনুন।")
        )
        steps.forEachIndexed { index, (title, detail) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                Ui.text(this, (index + 1).toString(), 13f, palette.onAccent, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.shape(palette.accent, dp(12).toFloat())
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(1); marginEnd = dp(12) }
            )
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this, title, 14f, palette.text, bold = true))
            texts.addView(
                Ui.text(this, detail, 13f, palette.textMuted).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
        body.addView(
            Ui.primaryButton(this, palette, t("Open Azure portal", "Azure পোর্টাল খুলুন")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portal.azure.com/")))
                } catch (_: Exception) {
                    toast(t("Could not open the browser. Go to portal.azure.com yourself.",
                        "ব্রাউজার খোলা যায়নি। নিজে portal.azure.com-এ যান।"))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
        body.addView(note(
            t("Azure screens change from time to time, so a button may be named a little differently. " +
                "Keep your key private: anyone who has it can use your free allowance.",
                "Azure-এর স্ক্রিন মাঝে মাঝে বদলায়, তাই কোনো বোতামের নাম একটু আলাদা হতে পারে। " +
                    "কী গোপন রাখুন: যার কাছে এটি থাকবে সে আপনার ফ্রি সীমা ব্যবহার করতে পারবে।")
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    private fun smallChip(text: String, onClick: () -> Unit): TextView =
        Ui.text(this, text, 12f, palette.accent, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = Ui.rippleShape(palette.accentSoft, dp(16).toFloat(), palette.ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun pasteInto(target: EditText, trim: Boolean, lower: Boolean = false) {
        val clip = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
        var value = clip.orEmpty()
        if (trim) value = value.trim()
        if (lower) value = value.lowercase(Locale.ROOT).replace(" ", "")
        if (value.isEmpty()) {
            toast(t("Nothing copied yet. Copy it in Azure first.", "এখনো কিছু কপি করা হয়নি। আগে Azure থেকে কপি করুন।"))
        } else {
            target.setText(value)
            target.setSelection(value.length)
        }
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
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Ui.primaryButton(this, palette, t("Choose Bangla voice", "বাংলা কণ্ঠ বেছে নিন")) {
            showDeviceBanglaVoices()
        })
        buttons.addView(
            Ui.secondaryButton(this, palette, t("Android voice settings", "Android কণ্ঠ সেটিংস")) { openDeviceTtsSettings() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        body.addView(HorizontalScrollViewWrap(buttons), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    @Suppress("FunctionName")
    private fun HorizontalScrollViewWrap(child: View): View =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(child)
        }

    // ---------------------------------------------------------------- speed, pitch and reading options

    private val prefs by lazy { getSharedPreferences(Ui.PREFS, MODE_PRIVATE) }

    private fun plainCard(title: String, hint: String?): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.shape(palette.surface, dp(18).toFloat())
        }
        outer.addView(Ui.text(this, title, 17f, palette.text, bold = true))
        if (hint != null) {
            outer.addView(note(hint).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        Ui.revealIn(outer, 240L, dp(14).toFloat())
        return outer
    }

    private fun sliderRow(parent: LinearLayout, title: String, key: String, default: Float) {
        val value = prefs.getFloat(key, default).coerceIn(0.5f, 1.5f)
        val valueLabel = Ui.text(this, String.format(Locale.US, "%.2f×", value), 13f, palette.accent, bold = true)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, title, 14f, palette.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueLabel)
        parent.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val bar = SeekBar(this).apply {
            max = 100
            progress = Math.round((value - 0.5f) * 100f).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.accentSoft)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = String.format(Locale.US, "%.2f×", 0.5f + progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putFloat(key, 0.5f + (seekBar?.progress ?: 0) / 100f).apply()
            }
        })
        parent.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addSpeedCard() {
        val outer = plainCard(
            t("Speed and pitch", "গতি ও স্বর"),
            t("Reading speed applies to every voice. Pitch applies to the device voice only.",
                "পড়ার গতি সব কণ্ঠে প্রযোজ্য। স্বর শুধু ডিভাইসের কণ্ঠে প্রযোজ্য।")
        )
        sliderRow(outer, t("Reading speed", "পড়ার গতি"), "narration_rate", 0.85f)
        sliderRow(outer, t("Voice pitch", "কণ্ঠের স্বর"), "narration_pitch", 1.0f)
    }

    private fun addOptionsCard() {
        val outer = plainCard(t("While reading aloud", "পড়ে শোনানোর সময়"), null)
        fun option(title: String, key: String, default: Boolean, hint: String? = null) {
            outer.addView(switchRow(title, prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
            if (hint != null) outer.addView(note(hint).apply { setPadding(0, dp(2), 0, 0) })
        }
        option(t("Continue automatically to the next page", "পরের পৃষ্ঠায় স্বয়ংক্রিয়ভাবে পড়া চালান"), "ocr_continue_next_page", true)
        option(
            t("Keep reading when the app is in the background", "অ্যাপ পেছনে গেলেও পড়া চালান"), "read_aloud_background", false,
            t("Android may stop playback if the app is force-closed.", "অ্যাপ জোর করে বন্ধ করলে Android পড়া থামাতে পারে।")
        )
        option(t("Show reading position", "পড়ার অবস্থান দেখান"), "show_reading_position", true)
        option(
            t("Skip page numbers", "পৃষ্ঠা নম্বর বাদ দিন"), "read_aloud_skip_page_numbers", true,
            t("Tries to leave out a lone page number at the top or bottom of a page.", "পাতার শুরু বা শেষের একক পৃষ্ঠা নম্বর বাদ দেওয়ার চেষ্টা করবে।")
        )
        option(
            t("Skip the book title repeated on each page", "প্রতি পাতায় বারবার আসা বইয়ের নাম বাদ দিন"), "read_aloud_skip_book_title", true,
            t("Tries to leave out a line that matches the book title at the top or bottom of a page.", "পাতার শুরু বা শেষে বইয়ের নামের মতো লাইন বাদ দেওয়ার চেষ্টা করবে।")
        )
    }

    // ---------------------------------------------------------------- device Bangla voice chooser

    private var deviceTts: TextToSpeech? = null
    private var deviceTtsReady = false
    private var deviceTtsPending: ((TextToSpeech) -> Unit)? = null

    private fun withDeviceTts(block: (TextToSpeech) -> Unit) {
        val existing = deviceTts
        if (existing != null && deviceTtsReady) {
            block(existing)
            return
        }
        deviceTtsPending = block
        if (existing == null) {
            deviceTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    deviceTtsReady = true
                    deviceTts?.let { engine -> deviceTtsPending?.invoke(engine) }
                } else {
                    toast(t("The speech engine could not start.", "স্পিচ ইঞ্জিন চালু হয়নি।"))
                }
                deviceTtsPending = null
            }
        }
    }

    private fun openDeviceTtsSettings() {
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
    }

    private fun showDeviceBanglaVoices() = withDeviceTts { speech ->
        if (isFinishing || isDestroyed) return@withDeviceTts
        val voices = speech.voices.orEmpty()
            .filter { it.locale.language.equals("bn", ignoreCase = true) }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.latency }
            )
        if (voices.isEmpty()) {
            KDialog(this)
                .setTitle(t("No Bangla voices found", "বাংলা কণ্ঠ পাওয়া যায়নি"))
                .setMessage(t(
                    "Your current speech engine does not list a Bangla voice. Open Android's text-to-speech settings, try Google Speech Services or another engine, and use Install voice data to add Bangla.",
                    "আপনার বর্তমান স্পিচ ইঞ্জিনে বাংলা কণ্ঠ নেই। Android-এর টেক্সট-টু-স্পিচ সেটিংস খুলে Google Speech Services বা অন্য ইঞ্জিন বেছে নিন এবং Install voice data থেকে বাংলা যোগ করুন।"
                ))
                .setPositiveButton(t("Open speech settings", "স্পিচ সেটিংস খুলুন")) { _, _ -> openDeviceTtsSettings() }
                .setNegativeButton(t("Close", "বন্ধ"), null)
                .show()
            return@withDeviceTts
        }
        val labels = voices.mapIndexed { index, voice ->
            val place = when (voice.locale.country.uppercase(Locale.ROOT)) {
                "BD" -> t("Bangladeshi Bangla", "বাংলাদেশি বাংলা")
                "IN" -> t("Indian Bangla", "ভারতীয় বাংলা")
                else -> voice.locale.getDisplayName(Locale.ENGLISH)
            }
            val quality = when {
                voice.quality >= Voice.QUALITY_HIGH -> t("High quality", "উচ্চ মান")
                voice.quality >= Voice.QUALITY_NORMAL -> t("Standard quality", "সাধারণ মান")
                else -> t("Basic quality", "সাধারণ নিম্ন মান")
            }
            val link = if (voice.isNetworkConnectionRequired) t("online", "অনলাইন") else t("offline", "অফলাইন")
            "${index + 1}. $place · $quality · $link"
        }
        val options = (listOf(t("Automatic (phone's choice)", "স্বয়ংক্রিয় (ফোনের পছন্দ)")) + labels).toTypedArray()
        val saved = prefs.getString("bangla_tts_voice_name", null)
        val selected = if (saved == null) 0 else voices.indexOfFirst { it.name == saved }.let { if (it < 0) 0 else it + 1 }
        KDialog(this)
            .setTitle(t("Device Bangla voice", "ডিভাইসের বাংলা কণ্ঠ"))
            .setMessage(t("Choose a voice to save it and hear a short sample. Online voices need internet.",
                "কণ্ঠ বেছে নিলে সংরক্ষণ হবে এবং নমুনা শোনাবে। অনলাইন কণ্ঠে ইন্টারনেট লাগে।"))
            .setSingleChoiceItems(options, selected) { _, which ->
                val chosen = voices.getOrNull(which - 1)
                prefs.edit().apply {
                    if (chosen == null) remove("bangla_tts_voice_name") else putString("bangla_tts_voice_name", chosen.name)
                }.apply()
                speech.stop()
                if (speech.setLanguage(Locale("bn", "BD")) < TextToSpeech.LANG_AVAILABLE) {
                    toast(t("This engine cannot speak Bangla. Install a Bangla voice in Android settings.",
                        "এই ইঞ্জিন বাংলা বলতে পারে না। Android সেটিংসে বাংলা কণ্ঠ ইনস্টল করুন।"))
                    return@setSingleChoiceItems
                }
                if (chosen != null) speech.setVoice(chosen)
                speech.setSpeechRate(prefs.getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f))
                speech.setPitch(prefs.getFloat("narration_pitch", 1.0f).coerceIn(0.5f, 1.5f))
                speech.speak("স্বাগতম। কণ্ঠ PDF Reader আপনাকে বাংলা বই পড়ে শোনাতে সাহায্য করে।", TextToSpeech.QUEUE_FLUSH, null, "bangla-voice-preview")
            }
            .setNeutralButton(t("Speech settings", "স্পিচ সেটিংস")) { _, _ -> openDeviceTtsSettings() }
            .setNegativeButton(t("Close", "বন্ধ"), null)
            .show()
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
