// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder

/** A WebView that lets us read how far down the page is scrolled. */
private class ReaderWebView(context: android.content.Context) : WebView(context) {
    fun scrollRange(): Int = computeVerticalScrollRange()
}

/**
 * Reads EPUB books. Chapters are shown in a locked-down WebView (no scripts, no network, no file access):
 * every file the chapter asks for is served straight out of the book.
 */
class EpubReaderActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(Ui.PREFS, MODE_PRIVATE) }
    private val palette by lazy { Ui.palette(this) }

    private var book: EpubBook? = null
    private var bookKey = ""
    private var bookName = ""
    private var chapter = 0
    private var pendingRatio: Float? = null
    private var pendingFragment: String? = null
    private var textZoom = 110
    private var readerTheme = THEME_AUTO

    private lateinit var root: FrameLayout
    private lateinit var web: ReaderWebView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var loadingLabel: TextView
    private var insetTop = 0
    private var insetBottom = 0
    private val hideBars = Runnable { setBarsVisible(false) }

    private fun tr(en: String, bn: String): String = if (Ui.isBangla(this)) bn else en
    private fun dp(value: Int): Int = Ui.dp(this, value)

    private class Colors(val background: Int, val text: Int, val link: Int, val dark: Boolean)

    private fun colors(): Colors {
        val effective = if (readerTheme == THEME_AUTO) (if (Ui.isDark(this)) THEME_DARK else THEME_LIGHT) else readerTheme
        return when (effective) {
            THEME_DARK -> Colors(0xFF121212.toInt(), 0xFFD8D8D8.toInt(), 0xFF7FD6B8.toInt(), true)
            THEME_SEPIA -> Colors(0xFFF4ECD8.toInt(), 0xFF433422.toInt(), 0xFF8A4B14.toInt(), false)
            else -> Colors(0xFFFFFFFF.toInt(), 0xFF1B1B1B.toInt(), 0xFF126854.toInt(), false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.data ?: intent.getStringExtra(BookFormats.EXTRA_URI)?.let { Uri.parse(it) }
        bookName = intent.getStringExtra(BookFormats.EXTRA_NAME) ?: uri?.lastPathSegment ?: "Book"
        if (uri == null) {
            finish()
            return
        }
        bookKey = BookFormats.key(uri)
        textZoom = prefs.getInt("epub_zoom", 110)
        readerTheme = prefs.getString("epub_theme", THEME_AUTO) ?: THEME_AUTO

        root = FrameLayout(this)
        setContentView(root)
        applyThemeColors()
        loadingLabel = Ui.text(this, tr("Opening…", "খোলা হচ্ছে…"), 16f, colors().text).apply { gravity = Gravity.CENTER }
        root.addView(loadingLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            applyInsets()
            insets
        }
        ViewCompat.requestApplyInsets(root)

        Thread({
            try {
                val file: File = ArchiveCache.fileFor(this, uri, "epub") { percent ->
                    runOnUiThread { loadingLabel.text = tr("Opening… $percent%", "খোলা হচ্ছে… $percent%") }
                }
                val opened = EpubBook.open(file)
                runOnUiThread {
                    if (isFinishing || isDestroyed) opened.close() else onBookReady(opened)
                }
            } catch (error: Throwable) {
                runOnUiThread { fail(error.message ?: tr("Could not open this book.", "এই বইটি খোলা যায়নি।")) }
            }
        }, "kontho-epub-open").apply { isDaemon = true }.start()
    }

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
        KDialog(this)
            .setTitle(tr("Could not open book", "বই খোলা যায়নি"))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(tr("Close", "বন্ধ")) { _, _ -> finish() }
            .show()
    }

    private fun applyThemeColors() {
        val c = colors()
        root.setBackgroundColor(c.background)
        val p = Palette(c.dark)
        Ui.applySystemBars(window, p, c.background)
        window.statusBarColor = c.background
        if (::web.isInitialized) web.setBackgroundColor(c.background)
    }

    // ---------------------------------------------------------------- book

    @SuppressLint("SetJavaScriptEnabled")
    private fun onBookReady(opened: EpubBook) {
        book = opened
        root.removeView(loadingLabel)

        web = ReaderWebView(this).apply {
            setBackgroundColor(colors().background)
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                textZoom = this@EpubReaderActivity.textZoom
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            isVerticalScrollBarEnabled = false
            webViewClient = readerClient()
        }
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleBars()
                return false
            }
        })
        web.setOnTouchListener { _, event ->
            tap.onTouchEvent(event)
            false
        }
        root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        buildBars()
        applyInsets()

        val saved = prefs.getString("epub_pos_$bookKey", null)
        var startChapter = 0
        var ratio: Float? = null
        saved?.split(':')?.let { parts ->
            startChapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
            ratio = parts.getOrNull(1)?.toFloatOrNull()
        }
        openChapter(startChapter.coerceIn(0, opened.spine.size - 1), ratio, null)
        setBarsVisible(true)
    }

    private fun urlFor(path: String): String =
        "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

    private fun openChapter(index: Int, ratio: Float?, fragment: String?) {
        val opened = book ?: return
        chapter = index.coerceIn(0, opened.spine.size - 1)
        pendingRatio = ratio
        pendingFragment = fragment
        web.loadUrl(urlFor(opened.spine[chapter]))
        updateChapterLabel()
    }

    private fun readerClient() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return blocked()
            val path = try {
                URLDecoder.decode((url.encodedPath ?: "").removePrefix("/").replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                return blocked()
            }
            val opened = book ?: return blocked()
            val bytes = opened.read(path) ?: return WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0))
            )
            val mime = opened.mimeOf(path)
            return if (mime.contains("html")) {
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(styled(bytes)))
            } else {
                WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == HOST) {
                val path = URLDecoder.decode((url.encodedPath ?: "").removePrefix("/").replace("+", "%2B"), "UTF-8")
                val index = book?.spine?.indexOf(path) ?: -1
                if (index >= 0) {
                    chapter = index
                    pendingRatio = null
                    pendingFragment = url.fragment
                    updateChapterLabel()
                }
                return false
            }
            if (url.scheme == "http" || url.scheme == "https") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                }
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val ratio = pendingRatio
            val fragment = pendingFragment
            pendingRatio = null
            pendingFragment = null
            if (ratio != null) {
                view.postDelayed({
                    val range = (web.scrollRange() - web.height).coerceAtLeast(0)
                    web.scrollTo(0, (range * ratio).toInt())
                }, 150)
            } else if (fragment != null && url != null && !url.contains('#')) {
                view.loadUrl("$url#$fragment")
            }
        }
    }

    private fun blocked() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    /** Puts the reader's colours into a chapter by adding one style block to it. */
    private fun styled(bytes: ByteArray): ByteArray {
        val c = colors()
        fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
        val css = "<style>html,body{background:${hex(c.background)}!important;color:${hex(c.text)}!important;}" +
            "body{margin:0!important;padding:14px 20px 28px!important;line-height:1.6;-webkit-text-size-adjust:100%;" +
            "overflow-wrap:anywhere;}" +
            "body *{color:inherit!important;background-color:transparent!important;}" +
            "a,a *{color:${hex(c.link)}!important;}img{max-width:100%!important;height:auto!important;}</style>"
        val html = String(bytes, Charsets.UTF_8)
        val at = html.indexOf("</head>", ignoreCase = true)
        val out = if (at >= 0) html.substring(0, at) + css + html.substring(at) else css + html
        return out.toByteArray(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- bars

    private fun glass(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(palette.surface)
        setStroke(dp(1), palette.outline)
    }

    private fun buildBars() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            elevation = dp(4).toFloat()
        }
        topBar.addView(
            Ui.iconButton(this, palette, R.drawable.ic_back, tr("Back", "পেছনে")) { finish() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        titleLabel = Ui.text(this, book?.title ?: BookFormats.title(bookName), 14f, palette.text, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        topBar.addView(
            Ui.iconButton(this, palette, R.drawable.ic_book, tr("Contents", "সূচিপত্র")) { showContents() },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) }
        )
        topBar.addView(
            Ui.iconButton(this, palette, R.drawable.ic_tune, tr("Text and theme", "লেখা ও থিম")) { showTextDialog() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { marginStart = dp(12); marginEnd = dp(12) })

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = dp(4).toFloat()
        }
        bottomBar.addView(
            Ui.secondaryButton(this, palette, "‹ " + tr("Prev", "আগে")) { step(-1) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        chapterLabel = Ui.text(this, "", 13f, palette.textMuted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottomBar.addView(chapterLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        bottomBar.addView(
            Ui.secondaryButton(this, palette, tr("Next", "পরে") + " ›") { step(1) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { marginStart = dp(12); marginEnd = dp(12) })
        updateChapterLabel()
    }

    private fun applyInsets() {
        if (!::root.isInitialized) return
        if (::web.isInitialized) {
            (web.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = insetTop
                it.bottomMargin = insetBottom
                web.layoutParams = it
            }
        }
        if (::topBar.isInitialized) {
            (topBar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = insetTop + dp(8)
                topBar.layoutParams = it
            }
            (bottomBar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = insetBottom + dp(12)
                bottomBar.layoutParams = it
            }
        }
    }

    private fun setBarsVisible(visible: Boolean) {
        if (!::topBar.isInitialized) return
        val state = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = state
        bottomBar.visibility = state
        handler.removeCallbacks(hideBars)
        if (visible) handler.postDelayed(hideBars, 4_000L)
    }

    private fun toggleBars() = setBarsVisible(topBar.visibility != View.VISIBLE)

    private fun updateChapterLabel() {
        val opened = book ?: return
        if (!::chapterLabel.isInitialized) return
        val title = opened.chapterTitle(opened.spine[chapter])
        val count = "${chapter + 1} / ${opened.spine.size}"
        chapterLabel.text = if (title.isNullOrBlank()) count else "$title · $count"
    }

    private fun step(delta: Int) {
        val opened = book ?: return
        val target = chapter + delta
        if (target < 0 || target >= opened.spine.size) return
        openChapter(target, null, null)
        setBarsVisible(true)
    }

    // ---------------------------------------------------------------- dialogs

    private fun showContents() {
        val opened = book ?: return
        val entries = opened.toc
        if (entries.isEmpty()) {
            val titles = opened.spine.mapIndexed { i, _ -> tr("Section ${i + 1}", "অংশ ${i + 1}") }.toTypedArray()
            KDialog(this)
                .setTitle(tr("Contents", "সূচিপত্র"))
                .setSingleChoiceItems(titles, chapter) { _, which -> openChapter(which, null, null) }
                .setNegativeButton(tr("Close", "বন্ধ"), null)
                .show()
            return
        }
        val current = entries.indexOfLast { opened.spine.indexOf(it.path) in 0..chapter }.coerceAtLeast(0)
        KDialog(this)
            .setTitle(tr("Contents", "সূচিপত্র"))
            .setSingleChoiceItems(entries.map { it.title }.toTypedArray(), current) { _, which ->
                val entry = entries[which]
                val index = opened.spine.indexOf(entry.path)
                if (index >= 0) openChapter(index, null, entry.fragment)
            }
            .setNegativeButton(tr("Close", "বন্ধ"), null)
            .show()
    }

    private fun showTextDialog() {
        val sizeLabel = Ui.text(this, tr("Text size: $textZoom%", "লেখার আকার: $textZoom%"), 14.5f, palette.text)
        val slider = SeekBar(this).apply {
            max = 14
            progress = ((textZoom - 80) / 10).coerceIn(0, 14)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    textZoom = 80 + value * 10
                    sizeLabel.text = tr("Text size: $textZoom%", "লেখার আকার: $textZoom%")
                    if (fromUser) web.settings.textZoom = textZoom
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putInt("epub_zoom", textZoom).apply()
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(sizeLabel)
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val themes = arrayOf(THEME_AUTO, THEME_LIGHT, THEME_SEPIA, THEME_DARK)
        val labels = arrayOf(
            tr("Match app theme", "অ্যাপের থিম অনুযায়ী"),
            tr("Light", "হালকা"),
            tr("Sepia", "সেপিয়া"),
            tr("Dark", "গাঢ়")
        )
        KDialog(this)
            .setTitle(tr("Text and theme", "লেখা ও থিম"))
            .setView(box)
            .setSingleChoiceItems(labels, themes.indexOf(readerTheme).coerceAtLeast(0)) { _, which ->
                readerTheme = themes[which]
                prefs.edit().putString("epub_theme", readerTheme).apply()
                applyThemeColors()
                saveReloadPosition()
            }
            .setNegativeButton(tr("Done", "শেষ"), null)
            .show()
    }

    /** Reloads the current chapter (so new colours apply) and returns to the same spot. */
    private fun saveReloadPosition() {
        val opened = book ?: return
        val range = (web.scrollRange() - web.height).coerceAtLeast(1)
        openChapter(chapter, web.scrollY.toFloat() / range, null)
        if (chapter in opened.spine.indices) updateChapterLabel()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        if (book != null && ::web.isInitialized) {
            val range = (web.scrollRange() - web.height).coerceAtLeast(1)
            val ratio = (web.scrollY.toFloat() / range).coerceIn(0f, 1f)
            prefs.edit().putString("epub_pos_$bookKey", "$chapter:$ratio").apply()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            root.removeView(web)
            web.destroy()
        }
        try {
            book?.close()
        } catch (_: Exception) {
        }
        book = null
        super.onDestroy()
    }

    companion object {
        private const val HOST = "kontho-epub.local"
        private const val THEME_AUTO = "auto"
        private const val THEME_LIGHT = "light"
        private const val THEME_SEPIA = "sepia"
        private const val THEME_DARK = "dark"
    }
}
