// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Reads CBZ and CBR comics and manga. Three ways to read: pages left to right, pages right to left
 * (manga), or one long vertical strip (webtoons). Tap the middle to show or hide the controls.
 */
class ComicReaderActivity : ComponentActivity() {
    private val palette = Palette(true)
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newFixedThreadPool(2)
    private val prefs by lazy { getSharedPreferences(Ui.PREFS, MODE_PRIVATE) }

    private var source: ComicSource? = null
    private var bookKey = ""
    private var bookName = ""
    private var mode = MODE_LTR
    private var currentPage = 0
    private var updatingSeek = false

    private lateinit var root: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var loadingLabel: TextView
    private val snap = PagerSnapHelper()
    private var insetTop = 0
    private var insetBottom = 0

    private val hideBars = Runnable { setBarsVisible(false) }
    private val bitmapCache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 6L).toInt().coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        Ui.applySystemBars(window, palette, Color.BLACK)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.data ?: intent.getStringExtra(BookFormats.EXTRA_URI)?.let { Uri.parse(it) }
        bookName = intent.getStringExtra(BookFormats.EXTRA_NAME) ?: uri?.lastPathSegment ?: "Comic"
        if (uri == null) {
            finish()
            return
        }
        bookKey = BookFormats.key(uri)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        loadingLabel = Ui.text(this, tr("Opening…", "খোলা হচ্ছে…"), 16f, Color.WHITE).apply { gravity = Gravity.CENTER }
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
                val opened = ComicArchive.open(this, uri, bookName) { percent ->
                    runOnUiThread { loadingLabel.text = tr("Opening… $percent%", "খোলা হচ্ছে… $percent%") }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        opened.close()
                    } else {
                        onSourceReady(opened)
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread { fail(error.message ?: tr("Could not open this comic.", "এই কমিক খোলা যায়নি।")) }
            }
        }, "kontho-comic-open").apply { isDaemon = true }.start()
    }

    private fun tr(en: String, bn: String): String = if (Ui.isBangla(this)) bn else en

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
        KDialog(this)
            .setTitle(tr("Could not open comic", "কমিক খোলা যায়নি"))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(tr("Close", "বন্ধ")) { _, _ -> finish() }
            .show()
    }

    // ---------------------------------------------------------------- reader

    private fun onSourceReady(opened: ComicSource) {
        if (opened.pageCount == 0) {
            opened.close()
            fail(tr("No pages were found in this file.", "এই ফাইলে কোনো পৃষ্ঠা পাওয়া যায়নি।"))
            return
        }
        source = opened
        root.removeView(loadingLabel)
        mode = prefs.getString("comic_mode_$bookKey", null)
            ?: prefs.getString("comic_mode", MODE_LTR) ?: MODE_LTR
        currentPage = prefs.getInt("comic_page_$bookKey", 0).coerceIn(0, opened.pageCount - 1)

        recycler = RecyclerView(this).apply {
            setBackgroundColor(Color.BLACK)
            itemAnimator = null
            setHasFixedSize(false)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode == MODE_WEBTOON) updateFromLayout()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && mode != MODE_WEBTOON) updateFromSnap()
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) setBarsVisible(false)
            }
        })

        buildBars(opened.pageCount)
        applyMode(mode, currentPage)
        setBarsVisible(true)
    }

    private fun glass(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(Color.argb(218, 25, 32, 29))
        setStroke(dp(1), Color.argb(90, 255, 255, 255))
    }

    private fun buildBars(count: Int) {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        topBar.addView(
            Ui.iconButton(this, palette, R.drawable.ic_back, tr("Back", "পেছনে")) { finish() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleLabel = Ui.text(this, BookFormats.title(bookName), 14f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pageLabel = Ui.text(this, "", 12f, Color.argb(200, 255, 255, 255))
        texts.addView(titleLabel)
        texts.addView(pageLabel)
        topBar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        topBar.addView(
            Ui.iconButton(this, palette, R.drawable.ic_tune, tr("Reading mode", "পড়ার ধরন")) { showModeDialog() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { marginStart = dp(12); marginEnd = dp(12) })

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        seek = SeekBar(this).apply {
            max = (count - 1).coerceAtLeast(0)
            progress = currentPage
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updatingSeek) goTo(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    handler.removeCallbacks(hideBars)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    scheduleHide()
                }
            })
        }
        bottomBar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { marginStart = dp(12); marginEnd = dp(12) })
        applyInsets()
        updatePageLabel()
    }

    private fun applyInsets() {
        if (!::topBar.isInitialized) return
        (topBar.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = insetTop + dp(8)
            topBar.layoutParams = it
        }
        (bottomBar.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = insetBottom + dp(12)
            bottomBar.layoutParams = it
        }
    }

    private fun setBarsVisible(visible: Boolean) {
        if (!::topBar.isInitialized) return
        val state = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = state
        bottomBar.visibility = state
        handler.removeCallbacks(hideBars)
        if (visible) scheduleHide()
    }

    private fun toggleBars() {
        setBarsVisible(topBar.visibility != View.VISIBLE)
    }

    /** Controls stay for 4 seconds, like the PDF reader's. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideBars)
        handler.postDelayed(hideBars, 4_000L)
    }

    // ---------------------------------------------------------------- modes and pages

    private fun applyMode(newMode: String, page: Int) {
        mode = newMode
        bitmapCache.evictAll()
        val rtl = mode == MODE_RTL
        if (mode == MODE_WEBTOON) {
            snap.attachToRecyclerView(null)
            recycler.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        } else {
            recycler.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, rtl)
            snap.attachToRecyclerView(recycler)
        }
        recycler.adapter = PageAdapter()
        (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, 0)
        seek.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        setPage(page)
    }

    private fun showModeDialog() {
        val labels = arrayOf(
            tr("Pages, left to right", "পৃষ্ঠা, বাম থেকে ডানে"),
            tr("Pages, right to left (manga)", "পৃষ্ঠা, ডান থেকে বামে (মাঙ্গা)"),
            tr("Vertical strip (webtoon)", "লম্বা স্ক্রল (ওয়েবটুন)")
        )
        val modes = arrayOf(MODE_LTR, MODE_RTL, MODE_WEBTOON)
        KDialog(this)
            .setTitle(tr("Reading mode", "পড়ার ধরন"))
            .setSingleChoiceItems(labels, modes.indexOf(mode).coerceAtLeast(0)) { _, which ->
                val chosen = modes[which]
                prefs.edit().putString("comic_mode_$bookKey", chosen).putString("comic_mode", chosen).apply()
                if (chosen != mode) applyMode(chosen, currentPage)
            }
            .setNegativeButton(tr("Close", "বন্ধ"), null)
            .show()
    }

    private fun goTo(page: Int) {
        val count = source?.pageCount ?: return
        val target = page.coerceIn(0, count - 1)
        (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
        setPage(target)
    }

    private fun step(delta: Int) {
        val count = source?.pageCount ?: return
        val target = (currentPage + delta).coerceIn(0, count - 1)
        if (target != currentPage) recycler.smoothScrollToPosition(target)
    }

    private fun onPageTap(x: Float, width: Int) {
        val rtl = mode == MODE_RTL
        when {
            x < width / 3f -> step(if (rtl) 1 else -1)
            x > width * 2f / 3f -> step(if (rtl) -1 else 1)
            else -> toggleBars()
        }
    }

    private fun updateFromSnap() {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val view = snap.findSnapView(manager) ?: return
        setPage(manager.getPosition(view))
    }

    private fun updateFromLayout() {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val count = source?.pageCount ?: return
        var first = manager.findFirstVisibleItemPosition()
        if (first < 0) return
        val view = manager.findViewByPosition(first)
        if (view != null && view.bottom < recycler.height / 3 && first + 1 < count) first++
        setPage(first)
    }

    private fun setPage(page: Int) {
        currentPage = page
        updatePageLabel()
        if (::seek.isInitialized && seek.progress != page) {
            updatingSeek = true
            seek.progress = page
            updatingSeek = false
        }
    }

    private fun updatePageLabel() {
        val count = source?.pageCount ?: return
        if (::pageLabel.isInitialized) pageLabel.text = "${currentPage + 1} / $count"
    }

    // ---------------------------------------------------------------- page images

    private fun targetWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        return if (mode == MODE_WEBTOON) screen else minOf(screen * 2, 2400)
    }

    private fun decodePage(index: Int, target: Int): Bitmap? {
        val pages = source ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            pages.open(index).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            // Keep one page under about 24 MB, even for very long strips.
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) * 4L > 24L * 1024 * 1024) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            pages.open(index).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun prefetch(index: Int) {
        val count = source?.pageCount ?: return
        if (index < 0 || index >= count || bitmapCache.get(index) != null) return
        val target = targetWidth()
        decoder.execute {
            if (bitmapCache.get(index) == null) decodePage(index, target)?.let { bitmapCache.put(index, it) }
        }
    }

    private class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var boundIndex = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = source?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view: ImageView = if (mode == MODE_WEBTOON) {
                ImageView(parent.context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener { toggleBars() }
                }
            } else {
                ZoomImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onTap = { x, width -> onPageTap(x, width) }
                }
            }
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.boundIndex = position
            val cached = bitmapCache.get(position)
            if (cached != null) {
                show(holder, cached)
            } else {
                holder.image.setImageDrawable(null)
                if (mode == MODE_WEBTOON) holder.image.minimumHeight = (resources.displayMetrics.widthPixels * 1.4f).toInt()
                val target = targetWidth()
                decoder.execute {
                    val bitmap = decodePage(position, target)
                    if (bitmap != null) bitmapCache.put(position, bitmap)
                    runOnUiThread {
                        if (!isDestroyed && holder.boundIndex == position && bitmap != null) show(holder, bitmap)
                    }
                }
            }
            prefetch(position + 1)
            if (mode != MODE_WEBTOON) prefetch(position - 1)
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.boundIndex = -1
            holder.image.setImageDrawable(null)
        }

        private fun show(holder: PageHolder, bitmap: Bitmap) {
            holder.image.minimumHeight = 0
            holder.image.setImageBitmap(bitmap)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (source != null && ::recycler.isInitialized) {
            val page = currentPage
            recycler.post { goTo(page) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (source != null) {
            prefs.edit().putInt("comic_page_$bookKey", currentPage).apply()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        try {
            source?.close()
        } catch (_: Exception) {
        }
        source = null
        super.onDestroy()
    }

    companion object {
        const val MODE_LTR = "ltr"
        const val MODE_RTL = "rtl"
        const val MODE_WEBTOON = "webtoon"
    }
}
