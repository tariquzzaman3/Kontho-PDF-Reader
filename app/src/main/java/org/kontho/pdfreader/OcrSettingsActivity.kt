package org.kontho.pdfreader

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale

/**
 * "OCR" screen under Settings → Reading: pick how scanned pages are read. Best first:
 *   1. Kontho OCR     (Bangla, English, Arabic; small one-time download, then offline)
 *   2. Phone default  (text already inside the PDF; works now, offline)
 * The text-cache limit lives here too.
 */
class OcrSettingsActivity : ComponentActivity() {
    private lateinit var palette: Palette
    private lateinit var column: LinearLayout
    private lateinit var cacheBody: LinearLayout

    private class EngineCard(
        val outer: LinearLayout,
        val indicator: TextView,
        val body: LinearLayout
    )

    private val cards = LinkedHashMap<String, EngineCard>()
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    private val installerListener: (OcrModels.State) -> Unit = { state ->
        val bar = progressBar
        val text = progressText
        if (state is OcrModels.State.Downloading && bar != null && text != null) {
            bar.progress = state.percent.coerceIn(0, 100) * 10
            text.text = downloadText(state)
        } else {
            fillBody(OcrEngines.KONTHO)
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
            Ui.text(this, "OCR", 22f, palette.text, bold = true),
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
                t("Choose how scanned pages are read aloud. Best quality first.",
                    "স্ক্যান করা পৃষ্ঠা কীভাবে পড়ে শোনানো হবে বেছে নিন। সেরা মান আগে।"),
                14f, palette.textMuted
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        )

        // Better on top.
        addCard(
            OcrEngines.KONTHO,
            t("Kontho OCR", "কণ্ঠ OCR"),
            t("Best", "সেরা"),
            t("Reads pictures of pages in Bangla, English and Arabic. Small one-time download.",
                "ছবির পৃষ্ঠা বাংলা, English ও আরবিতে পড়ে। একবারের ছোট ডাউনলোড।")
        )
        addCard(
            OcrEngines.DEVICE,
            t("Phone default", "ফোনের ডিফল্ট"),
            t("Basic", "সাধারণ"),
            t("Reads the text already inside the PDF. Works right now, offline.",
                "PDF-এর ভেতরের লেখা পড়ে। এখনই চলে, অফলাইনে।")
        )
        addCacheCard()

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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        OcrModels.addListener(installerListener)
        fillBody(OcrEngines.KONTHO)
        fillCache()
    }

    override fun onStop() {
        OcrModels.removeListener(installerListener)
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
            OcrEngines.select(this, id)
            updateSelection(animate = true)
        }
        Ui.pressable(head, 0.985f)

        cards[id] = EngineCard(outer, indicator, body)
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        fillBody(id)
    }

    private fun updateSelection(animate: Boolean) {
        val selected = OcrEngines.selected(this)
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
        if (id == OcrEngines.KONTHO) buildKonthoBody(card.body) else buildDeviceBody(card.body)
    }

    // ---------------------------------------------------------------- Kontho OCR

    private fun buildKonthoBody(body: LinearLayout) {
        progressBar = null
        progressText = null
        val busy = OcrModels.state is OcrModels.State.Downloading
        val selected = OcrEngines.languages(this)

        body.addView(note(t("Languages to recognise. Pick the ones your books use.",
            "যে ভাষাগুলো চেনাতে চান বেছে নিন।")))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (code in OcrModels.ORDER) {
            val on = code in selected
            val installed = OcrModels.isLanguageInstalled(this, code)
            val name = OcrEngines.languageName(this, code) + if (installed) " ✓" else ""
            val chip = Ui.text(this, name, 13f, if (on) palette.onAccent else palette.text, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = Ui.rippleShape(
                    if (on) palette.accent else palette.surfaceAlt, dp(20).toFloat(), palette.ripple
                )
                isClickable = true
                isFocusable = true
                alpha = if (busy) 0.6f else 1f
                setOnClickListener {
                    if (OcrModels.isBusy()) return@setOnClickListener
                    val next = if (on) selected - code else selected + code
                    if (next.isEmpty()) {
                        toast(t("Keep at least one language.", "অন্তত একটি ভাষা রাখুন।"))
                    } else {
                        OcrEngines.saveLanguages(this@OcrSettingsActivity, next)
                        fillBody(OcrEngines.KONTHO)
                    }
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (code != OcrModels.ORDER.first()) marginStart = dp(8) })
        }
        body.addView(chips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val state = OcrModels.state
        val installed = OcrModels.isInstalled(this, selected)
        val sizeText = formatMb(OcrModels.downloadBytes(selected))
        when {
            state is OcrModels.State.Downloading -> {
                val text = Ui.text(this, downloadText(state), 13f, palette.text)
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = state.percent.coerceIn(0, 100) * 10
                    progressTintList = ColorStateList.valueOf(palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.accentSoft)
                }
                progressText = text
                progressBar = bar
                body.addView(text)
                body.addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
                ).apply { topMargin = dp(8); bottomMargin = dp(10) })
                body.addView(Ui.secondaryButton(this, palette, t("Cancel", "বাতিল")) { OcrModels.cancel() })
            }
            installed -> {
                body.addView(Ui.text(
                    this,
                    t("Installed · works offline", "ইনস্টল করা আছে · অফলাইনে চলে"),
                    14f, palette.accent, bold = true
                ))
                body.addView(note(
                    t("Models on this phone: ${formatMb(OcrModels.installedBytes(this))}.",
                        "এই ফোনে মডেল: ${formatMb(OcrModels.installedBytes(this))}।")
                ), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
                body.addView(Ui.secondaryButton(this, palette, t("Delete downloaded models", "ডাউনলোড করা মডেল মুছুন")) {
                    OcrModels.delete(this)
                    fillBody(OcrEngines.KONTHO)
                })
            }
            else -> {
                if (state is OcrModels.State.Failed) {
                    body.addView(
                        Ui.text(this, state.message, 13f, palette.warning),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    )
                }
                val online = OcrEngines.isOnline(this)
                body.addView(note(
                    t("A one-time download of about $sizeText for the selected languages. Wi-Fi is recommended.",
                        "নির্বাচিত ভাষার জন্য একবারের ডাউনলোড, প্রায় $sizeText। Wi-Fi ভালো।")
                ))
                if (!online) {
                    body.addView(
                        Ui.text(
                            this,
                            t("You are offline, so the phone default is used until you download this.",
                                "আপনি অফলাইনে, তাই এটি ডাউনলোড না করা পর্যন্ত ফোনের ডিফল্ট ব্যবহার হবে।"),
                            13f, palette.warning
                        ),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    )
                }
                body.addView(
                    Ui.primaryButton(
                        this, palette,
                        if (state is OcrModels.State.Failed) t("Try again", "আবার চেষ্টা") else t("Download OCR", "OCR ডাউনলোড")
                    ) {
                        if (!OcrEngines.isOnline(this)) {
                            toast(t("No internet connection. Connect and try again.",
                                "ইন্টারনেট সংযোগ নেই। সংযোগ দিয়ে আবার চেষ্টা করুন।"))
                        } else {
                            OcrEngines.setStopAsking(this, false)
                            OcrModels.start(this, OcrEngines.languages(this))
                            fillBody(OcrEngines.KONTHO)
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        body.addView(note(
            t("Engine: Tesseract (Apache 2.0). Pages are read on your phone; nothing is uploaded.",
                "ইঞ্জিন: Tesseract (Apache 2.0)। পৃষ্ঠা আপনার ফোনেই পড়া হয়; কিছু আপলোড হয় না।")
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    // ---------------------------------------------------------------- Phone default

    private fun buildDeviceBody(body: LinearLayout) {
        body.addView(note(
            t("Fast and offline, with nothing to download. It cannot read pages that are only pictures. " +
                "If you open a scanned page, Kontho uses Kontho OCR when it is downloaded, or offers it when you are online.",
                "দ্রুত ও অফলাইনে চলে, কিছু ডাউনলোড করতে হয় না। শুধু ছবি থাকা পৃষ্ঠা পড়তে পারে না। " +
                    "স্ক্যান করা পৃষ্ঠা খুললে কণ্ঠ OCR ডাউনলোড করা থাকলে সেটি ব্যবহার করে, অনলাইনে থাকলে ডাউনলোডের প্রস্তাব দেয়।")
        ))
    }

    // ---------------------------------------------------------------- text cache

    private fun addCacheCard() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.shape(palette.surface, dp(18).toFloat(), palette.outline, dp(1))
        }
        outer.addView(Ui.text(this, t("OCR text cache limit", "OCR লেখার ক্যাশ সীমা"), 17f, palette.text, bold = true))
        outer.addView(
            Ui.text(
                this,
                t("Recognised text is saved so scanned pages are not read twice. Older pages are removed first when the limit is reached. Downloaded models use separate storage.",
                    "চেনা লেখা সংরক্ষণ হয়, তাই স্ক্যান করা পৃষ্ঠা দুবার পড়তে হয় না। সীমা পূর্ণ হলে পুরোনো পৃষ্ঠা আগে মুছে যায়। ডাউনলোড করা মডেল আলাদা স্টোরেজে থাকে।"),
                13f, palette.textMuted
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
        )
        cacheBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(cacheBody)
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        fillCache()
    }

    private val limitChoices = intArrayOf(10, 25, 50, 100, 250, 500, 0)

    private fun fillCache() {
        if (!::cacheBody.isInitialized) return
        cacheBody.removeAllViews()
        val prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        val limit = prefs.getInt(OcrEngines.KEY_CACHE_LIMIT_MB, 50)
        val used = cacheUsageBytes()
        val usedText = if (used < 1024L * 1024L) "${(used / 1024L).coerceAtLeast(0)} KB" else "${used / (1024L * 1024L)} MB"
        val summary = if (limit <= 0) {
            t("$usedText used · No limit", "$usedText ব্যবহৃত · সীমাহীন")
        } else {
            t("$usedText used of $limit MB", "$limit MB-এর মধ্যে $usedText ব্যবহৃত")
        }
        cacheBody.addView(Ui.text(this, summary, 14f, palette.accent, bold = true))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Ui.primaryButton(this, palette, t("Change limit", "সীমা বদলান")) { chooseLimit() })
        buttons.addView(
            Ui.secondaryButton(this, palette, t("Clear cache", "ক্যাশ মুছুন")) { clearCache() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        cacheBody.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    private fun chooseLimit() {
        val prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        val labels = arrayOf("10 MB", "25 MB", "50 MB", "100 MB", "250 MB", "500 MB", t("No limit", "সীমাহীন"))
        val saved = prefs.getInt(OcrEngines.KEY_CACHE_LIMIT_MB, 50)
        val selected = limitChoices.indexOf(saved).let { if (it >= 0) it else 2 }
        AlertDialog.Builder(this)
            .setTitle(t("Maximum OCR text cache", "OCR লেখার ক্যাশের সর্বোচ্চ আকার"))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val limitMb = limitChoices[which]
                prefs.edit().putInt(OcrEngines.KEY_CACHE_LIMIT_MB, limitMb).apply()
                trimCache(limitMb)
                dialog.dismiss()
                toast(t("OCR cache limit saved", "OCR ক্যাশের সীমা সংরক্ষিত"))
                fillCache()
            }
            .setNegativeButton(t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun clearCache() {
        File(filesDir, "ocr-cache").deleteRecursively()
        File(cacheDir, "ocr").deleteRecursively()
        // The reader keeps recent pages in memory; this tells it to drop them.
        getSharedPreferences(Ui.PREFS, MODE_PRIVATE).edit()
            .putLong(OcrEngines.KEY_CACHE_CLEARED_AT, System.currentTimeMillis()).apply()
        toast(t("OCR cache cleared.", "OCR ক্যাশ মুছে ফেলা হয়েছে।"))
        fillCache()
    }

    private fun cacheUsageBytes(): Long {
        val directory = File(filesDir, "ocr-cache")
        if (!directory.isDirectory) return 0L
        return directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.sumOf { it.length() }
    }

    private fun trimCache(limitMb: Int) {
        if (limitMb <= 0) return
        val directory = File(filesDir, "ocr-cache")
        if (!directory.isDirectory) return
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        val limitBytes = limitMb.toLong() * 1024L * 1024L
        for (file in files) {
            if (total <= limitBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun note(text: String): TextView = Ui.text(this, text, 12f, palette.textMuted).apply {
        setLineSpacing(0f, 1.15f)
    }.also { it.setPadding(0, 0, 0, dp(10)) }

    private fun downloadText(state: OcrModels.State.Downloading): String {
        val code = OcrModels.ORDER.firstOrNull { state.label.contains(it) }
        val name = if (code != null) " · " + OcrEngines.languageName(this, code) else ""
        return t("Downloading… ${state.percent}%$name", "ডাউনলোড হচ্ছে… ${state.percent}%$name")
    }

    private fun formatMb(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    private fun t(en: String, bn: String): String = VoiceEngines.t(this, en, bn)

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
