package org.kontho.pdfreader

import androidx.activity.ComponentActivity
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private data class Book(val uri: Uri, val name: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnailWorker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("kontho_library", MODE_PRIVATE) }

    private var books: List<Book> = emptyList()
    private var currentBook: Book? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    @Volatile private var currentPage = 0
    private var pageCount = 0
    private var pageText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isNarrating = false
    private var narrationPaused = false
    private var narrationCurrentSegmentIndex = 0
    private var narrationSegments: List<String> = emptyList()
    private var narrationRunId = 0
    private var lastNarrationUtteranceId: String? = null
    private var lastNarrationMode = "device"
    private var showingLibrary = true
    private var settingsSnapshot = ""
    private var lastBookFolderUri: String? = null
    private var pendingFolderScan = false
    @Volatile private var readerGeneration = 0

    private lateinit var libraryGrid: GridLayout
    private lateinit var librarySwipe: SwipeRefreshLayout
    private lateinit var readerScroll: ScrollView
    private lateinit var horizontalPageScroll: HorizontalScrollView
    private lateinit var pagesContainer: LinearLayout
    private lateinit var readerMenuButton: TextView
    private var readerOptionsPopup: PopupWindow? = null
    private lateinit var readerPlaybackPanel: LinearLayout
    private lateinit var readerPauseButton: TextView
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar
    private var progressAnimator: ValueAnimator? = null
    private val pageImages = mutableListOf<ImageView>()
    private var pageDimensions: List<Pair<Int, Int>> = emptyList()
    private val pageBitmaps = mutableMapOf<Int, Bitmap>()
    private val pageTextCache = mutableMapOf<Int, String>()
    private val ocrTextCache = ConcurrentHashMap<String, String>()
    private val queuedPageRenders = mutableSetOf<Int>()
    private var zoomScale = 1.0f
    private var basePageWidthPx = 0
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var zoomGestureOccurred = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideReaderOverlaysRunnable = Runnable { hideReaderOverlays() }
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDidMove = false

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keepReadPermission(uri)
            val book = Book(uri, displayName(uri))
            rememberRecentBook(book)
            openBook(book)
        }
    }

    private val createPdfCopy = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        if (destination != null) copyCurrentBookTo(destination)
    }

    private val chooseBookFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not support persistable permissions.
            } catch (_: IllegalArgumentException) {
                // Keep the current-session URI if the provider rejects persistence.
            }
            preferences.edit().putString(KEY_BOOK_FOLDER, uri.toString()).apply()
            refreshLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_theme", "system")
        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = if (dark) Color.rgb(20, 28, 26) else Color.rgb(18, 76, 66)
        window.navigationBarColor = window.statusBarColor
        PDFBoxResourceLoader.init(applicationContext)
        tts = TextToSpeech(this, this)
        lastBookFolderUri = preferences.getString(KEY_BOOK_FOLDER, null)
        showLibrary()
        refreshLibrary()
        settingsSnapshot = currentSettingsSnapshot()
        if (!getSharedPreferences("kontho_library", MODE_PRIVATE).getBoolean("setup_done", false)) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("first_run", true))
        }
    }

    private fun maybeCheckForUpdatesAutomatically() {
        if (!preferences.getBoolean("setup_done", false)) return
        if (!preferences.getBoolean("auto_check_updates", true)) return
        val now = System.currentTimeMillis()
        val previousCheck = preferences.getLong("last_update_check_ms", 0L)
        if (now - previousCheck < 24L * 60L * 60L * 1000L) return
        preferences.edit().putLong("last_update_check_ms", now).apply()

        GithubReleaseChecker.check(this) { result ->
            if (isFinishing || isDestroyed) return@check
            if (!preferences.getBoolean("auto_check_updates", true) || !result.updateAvailable) return@check
            AlertDialog.Builder(this)
                .setTitle("কণ্ঠ · Kontho PDF Reader update")
                .setMessage(
                    "A newer version (${result.latestVersion}) is available. " +
                        "You have ${result.installedVersion}. Open the official release page to review it."
                )
                .setPositiveButton("View update") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl ?: GithubReleaseChecker.RELEASES_URL)))
                    } catch (_: Exception) {
                        toast("Could not open the update page.")
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun currentSettingsSnapshot(): String {
        val p = getSharedPreferences("kontho_library", MODE_PRIVATE)
        return listOf("app_theme", "app_language", "library_view", "grid_columns", "sort_field", "sort_descending", "book_folder_uri")
            .joinToString("|") { key -> p.all[key]?.toString() ?: "" }
    }

    override fun onResume() {
        super.onResume()
        maybeCheckForUpdatesAutomatically()
        val snapshot = currentSettingsSnapshot()
        val folderUri = preferences.getString(KEY_BOOK_FOLDER, null)
        val folderChanged = folderUri != lastBookFolderUri
        if (settingsSnapshot.isNotEmpty() && snapshot != settingsSnapshot) {
            settingsSnapshot = snapshot
            lastBookFolderUri = folderUri
            if (folderChanged) pendingFolderScan = !folderUri.isNullOrBlank()
            if (showingLibrary) {
                val cachedBooks = books
                showLibrary()
                books = cachedBooks
                renderBookGrid()
                if (folderChanged && !folderUri.isNullOrBlank()) {
                    pendingFolderScan = false
                    refreshLibrary()
                }
            } else if (currentBook != null && pageDimensions.isNotEmpty()) {
                val selectedPage = currentPage
                val generation = ++readerGeneration
                buildReaderScreen()
                buildContinuousPages(generation, selectedPage)
            }
            return
        }
        if (snapshot.isNotEmpty()) settingsSnapshot = snapshot
        lastBookFolderUri = folderUri
        // Do not scan on return or on rotation; pull down to refresh explicitly.
        if (::libraryGrid.isInitialized && showingLibrary) renderBookGrid()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.statusBarColor = if (preferences.getString("app_theme", "system") == "dark") {
            Color.rgb(20, 28, 26)
        } else {
            Color.rgb(18, 76, 66)
        }
        window.navigationBarColor = window.statusBarColor
        if (showingLibrary) {
            val cachedBooks = books
            showLibrary()
            books = cachedBooks
            renderBookGrid()
            // Reflow the views after rotation without rescanning the PDF folder.
        } else if (currentBook != null && pageDimensions.isNotEmpty()) {
            val selectedPage = currentPage
            val generation = ++readerGeneration
            buildReaderScreen()
            buildContinuousPages(generation, selectedPage)
        }
        settingsSnapshot = currentSettingsSnapshot()
        lastBookFolderUri = preferences.getString(KEY_BOOK_FOLDER, null)
    }

    private fun showLibrary() {
        readerGeneration++
        mainHandler.removeCallbacks(hideReaderOverlaysRunnable)
        stopReaderNarration()
        releaseReaderBitmaps()
        showingLibrary = true
        currentBook = null
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        val prefs = preferences
        val dark = when (prefs.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val appLanguage = prefs.getString("app_language", "en") == "bn"
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        fun label(en: String, bn: String) = if (appLanguage) bn else en
        window.statusBarColor = if (dark) Color.rgb(20, 28, 26) else Color.rgb(18, 76, 66)
        window.navigationBarColor = window.statusBarColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) Color.rgb(25, 31, 29) else Color.rgb(248, 250, 249))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.kontho_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "কণ্ঠ · Kontho PDF Reader logo"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(8)
        })

        val title = TextView(this).apply {
            text = "কণ্ঠ · Kontho PDF Reader"
            textSize = 22f
            setTextColor(if (dark) Color.rgb(175, 225, 210) else Color.rgb(18, 76, 66))
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))

        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(if (dark) Color.WHITE else Color.rgb(18, 76, 66))
            contentDescription = label("Settings", "সেটিংস")
            setPadding(dp(10), 0, dp(2), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(52)))
        root.addView(header, matchWrap())

        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dividerLine = View(this).apply {
            setBackgroundColor(if (dark) Color.rgb(76, 91, 84) else Color.rgb(210, 220, 215))
        }
        sortRow.addView(dividerLine, LinearLayout.LayoutParams(0, dp(1), 1f))
        val sortButton = TextView(this).apply {
            text = label("Sort ▾", "সাজান ▾")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (dark) Color.rgb(213, 231, 222) else Color.rgb(32, 79, 66))
            setPadding(dp(12), dp(7), dp(2), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSortMenu(this) }
        }
        sortRow.addView(sortButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
        ))
        root.addView(sortRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        val columns = if (prefs.getString("library_view", "grid") == "list") {
            1
        } else {
            prefs.getInt("grid_columns", 2).coerceIn(2, 4)
        }
        libraryGrid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(libraryGrid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        librarySwipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.rgb(18, 104, 84))
            setOnRefreshListener {
                isRefreshing = false
                refreshLibrary()
            }
            addView(listScroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        root.addView(librarySwipe, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(createProgressPanel(dark), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        installContentInsets(root, dp(16), dp(8), dp(16), dp(8))
        renderBookGrid()
        worker.execute { closeCurrentRenderer() }
    }

    private fun installContentInsets(root: View, left: Int, top: Int, right: Int, bottom: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val prefs = preferences
        val field = prefs.getString("sort_field", "name") ?: "name"
        val descending = prefs.getBoolean("sort_descending", false)
        val bn = prefs.getString("app_language", "en") == "bn"
        val names = if (bn) arrayOf("নাম: শুরু থেকে শেষ", "নাম: শেষ থেকে শুরু", "তারিখ · নতুন আগে", "তারিখ · পুরোনো আগে")
            else arrayOf("Name · A–Z", "Name · Z–A", "Date · newest first", "Date · oldest first")
        popup.menu.add(0, 0, 0, names[0] + if (field == "name" && !descending) " ✓" else "")
        popup.menu.add(0, 1, 1, names[1] + if (field == "name" && descending) " ✓" else "")
        popup.menu.add(0, 2, 2, names[2] + if (field == "date" && descending) " ✓" else "")
        popup.menu.add(0, 3, 3, names[3] + if (field == "date" && !descending) " ✓" else "")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> prefs.edit().putString("sort_field", "name").putBoolean("sort_descending", false).apply()
                1 -> prefs.edit().putString("sort_field", "name").putBoolean("sort_descending", true).apply()
                2 -> prefs.edit().putString("sort_field", "date").putBoolean("sort_descending", true).apply()
                3 -> prefs.edit().putString("sort_field", "date").putBoolean("sort_descending", false).apply()
                else -> return@setOnMenuItemClickListener false
            }
            sortBooksInMemory()
            renderBookGrid()
            settingsSnapshot = currentSettingsSnapshot()
            true
        }
        popup.show()
    }

    private fun sortBooksInMemory() {
        val field = preferences.getString("sort_field", "name") ?: "name"
        val descending = preferences.getBoolean("sort_descending", false)
        val comparator = if (field == "date") compareBy<Book> { modifiedFor(it.uri) }
            else compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        books = if (descending) books.sortedWith(comparator.reversed()) else books.sortedWith(comparator)
    }

    private fun refreshLibrary() {
        if (!::libraryGrid.isInitialized || !showingLibrary) return
        if (::librarySwipe.isInitialized) librarySwipe.isRefreshing = false
        setBusy(if (preferences.getString(KEY_BOOK_FOLDER, null).isNullOrBlank()) "Finding recent books…" else "Scanning PDF folder…")
        worker.execute {
            val found = LinkedHashMap<String, Book>()
            val recentUris = preferences.getStringSet(KEY_RECENT_BOOKS, emptySet())?.toList().orEmpty()
            recentUris.forEach { value ->
                try {
                    val uri = Uri.parse(value)
                    val name = preferences.getString(recentNameKey(uri), null) ?: displayName(uri)
                    found[value] = Book(uri, name)
                } catch (_: Exception) {
                    // Ignore inaccessible old entries.
                }
            }

            val folderValue = preferences.getString(KEY_BOOK_FOLDER, null)
            if (!folderValue.isNullOrBlank()) {
                setBusy("Finding books in selected folder…")
                try {
                    val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderValue))
                    if (folder != null && folder.canRead()) collectPdfBooks(folder, found, 0)
                } catch (_: Exception) {
                    // The user may have to grant the folder again in Settings.
                }
            }

            val field = preferences.getString("sort_field", "name") ?: "name"
            val descending = preferences.getBoolean("sort_descending", false)
            val comparator = if (field == "date") compareBy<Book> { modifiedFor(it.uri) }
                else compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            val sorted = if (descending) found.values.sortedWith(comparator.reversed())
                else found.values.sortedWith(comparator)

            runOnUiThread {
                if (!showingLibrary) {
                    if (::librarySwipe.isInitialized) librarySwipe.isRefreshing = false
                    clearBusy()
                    return@runOnUiThread
                }
                books = sorted
                renderBookGrid()
                if (::librarySwipe.isInitialized) librarySwipe.isRefreshing = false
                clearBusy()
            }
        }
    }

    private fun collectPdfBooks(folder: DocumentFile, found: LinkedHashMap<String, Book>, depth: Int) {
        if (depth > 10) return
        for (file in folder.listFiles()) {
            try {
                if (file.isDirectory) {
                    collectPdfBooks(file, found, depth + 1)
                } else if (file.isFile && isPdf(file)) {
                    val uri = file.uri
                    found[uri.toString()] = Book(uri, file.name ?: "Untitled book.pdf")
                }
            } catch (_: SecurityException) {
                // Skip folders the selected document provider does not permit us to read.
            }
        }
    }

    private fun isPdf(file: DocumentFile): Boolean {
        return file.name?.endsWith(".pdf", ignoreCase = true) == true ||
            file.type.equals("application/pdf", ignoreCase = true)
    }

    private fun renderBookGrid() {
        if (!::libraryGrid.isInitialized || !showingLibrary) return
        libraryGrid.removeAllViews()

        if (books.isEmpty()) {
            val dark = preferences.getString("app_theme", "system") == "dark"
            val bn = preferences.getString("app_language", "en") == "bn"
            val empty = TextView(this).apply {
                text = if (bn) "এখনো কোনো PDF পাওয়া যায়নি। সেটিংস থেকে PDF ফোল্ডার বেছে নিন, অথবা তালিকা রিফ্রেশ করতে নিচে টানুন।"
                    else "No PDFs found. Choose a PDF folder in Settings, or pull down to refresh."
                textSize = 15f
                setTextColor(if (dark) Color.LTGRAY else Color.rgb(90, 103, 98))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(40))
            }
            libraryGrid.addView(empty, GridLayout.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, libraryGrid.columnCount)
            })
            return
        }

        books.forEach { book ->
            val darkCard = when (preferences.getString("app_theme", "system")) {
                "dark" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            val card = LinearLayout(this).apply {
                orientation = if (getSharedPreferences("kontho_library", MODE_PRIVATE).getString("library_view", "grid") == "list") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable().apply {
                    setColor(if (darkCard) Color.rgb(39, 47, 44) else Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), if (darkCard) Color.rgb(70, 84, 78) else Color.rgb(225, 232, 229))
                }
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { openBook(book) }
                setOnLongClickListener { showBookActions(book); true }
                contentDescription = "Open ${book.name}. Long press for more actions."
            }

            val listMode = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("library_view", "grid") == "list"
            val cover = ImageView(this).apply {
                tag = book.uri.toString()
                setBackgroundColor(Color.rgb(238, 242, 240))
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = !listMode
                contentDescription = "Cover of ${book.name}"
            }
            card.addView(cover, if (listMode) LinearLayout.LayoutParams(dp(64), dp(88)).apply { marginEnd = dp(10) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val title = TextView(this).apply {
                text = book.name.removeSuffixIgnoreCase(".pdf")
                textSize = 14f
                setTextColor(if (darkCard) Color.rgb(235, 242, 238) else Color.rgb(28, 43, 39))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(8), dp(2), 0)
            }
            if (listMode) {
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title, matchWrap())
                }
                card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                libraryGrid.addView(card, GridLayout.LayoutParams().apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 1)
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
            } else {
                card.addView(title, matchWrap())
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(8))
                }
                libraryGrid.addView(card, params)
            }
            loadCoverThumbnail(book, cover)
        }
    }

    private fun loadCoverThumbnail(book: Book, image: ImageView) {
        thumbnailWorker.execute {
            var bitmap: Bitmap? = null
            try {
                contentResolver.openFileDescriptor(book.uri, "r")?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount > 0) {
                            val requestedPage = coverPageFor(book.uri).coerceIn(0, renderer.pageCount - 1)
                            renderer.openPage(requestedPage).use { page ->
                                // Bound thumbnail memory while preserving the PDF page's actual ratio.
                                val scale = minOf(360f / page.width, 520f / page.height)
                                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                                val thumbnail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap = thumbnail
                                thumbnail.eraseColor(Color.WHITE)
                                page.render(thumbnail, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep the placeholder when a PDF is encrypted or cannot be rendered.
            }

            val result = bitmap
            runOnUiThread {
                if (result != null && !isFinishing && !isDestroyed && showingLibrary && image.tag == book.uri.toString()) {
                    if (image.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        val gridWidth = libraryGrid.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(32))
                        val columns = libraryGrid.columnCount.coerceAtLeast(1)
                        val coverWidth = image.width.takeIf { it > 0 } ?: (gridWidth / columns - dp(24)).coerceAtLeast(dp(48))
                        image.layoutParams = image.layoutParams.apply {
                            height = (coverWidth.toFloat() * result.height / result.width).roundToInt().coerceAtLeast(dp(72))
                        }
                    }
                    image.setImageBitmap(result)
                } else if (result != null) {
                    result.recycle()
                }
            }
        }
    }

    private fun openBook(book: Book) {
        showingLibrary = false
        currentBook = book
        currentPage = 0
        pageCount = 0
        pageText = ""
        pageTextCache.clear()
        pageDimensions = emptyList()
        queuedPageRenders.clear()
        releaseReaderBitmaps()
        stopReaderNarration()
        val generation = ++readerGeneration
        buildReaderScreen()

        worker.execute {
            try {
                closeCurrentRenderer()
                val descriptor = contentResolver.openFileDescriptor(book.uri, "r")
                    ?: throw IOException("Could not open this PDF.")
                val renderer = PdfRenderer(descriptor)
                fileDescriptor = descriptor
                pdfRenderer = renderer
                val sizes = ArrayList<Pair<Int, Int>>(renderer.pageCount)
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page -> sizes += page.width to page.height }
                }
                if (sizes.isEmpty()) throw IOException("This PDF has no pages.")

                runOnUiThread {
                    if (generation != readerGeneration || currentBook?.uri != book.uri || showingLibrary) return@runOnUiThread
                    pageDimensions = sizes
                    pageCount = sizes.size
                    buildContinuousPages(generation)
                }
            } catch (error: Exception) {
                closeCurrentRenderer()
                runOnUiThread {
                    if (generation == readerGeneration && currentBook?.uri == book.uri && !showingLibrary) {
                        Toast.makeText(this, "Could not open PDF: ${error.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun buildReaderScreen() {
        val dark = when (preferences.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark

        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Observe all multi-touch events before ScrollView/HorizontalScrollView
                // can intercept them, so pinch zoom is reliable across the full page.
                scaleGestureDetector?.onTouchEvent(event)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(if (dark) Color.rgb(34, 38, 36) else Color.rgb(221, 225, 222))
        }
        horizontalPageScroll = HorizontalScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(if (dark) Color.rgb(34, 38, 36) else Color.rgb(221, 225, 222))
        }
        readerScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(16))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        pagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(3), 0)
        }
        val width = contentPageWidth()
        readerScroll.addView(pagesContainer, ViewGroup.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        horizontalPageScroll.addView(readerScroll, ViewGroup.LayoutParams(
            width, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(horizontalPageScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        readerMenuButton = TextView(this).apply {
            text = "⋮"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = glassBackground()
            elevation = dp(6).toFloat()
            contentDescription = if (preferences.getString("app_language", "en") == "bn") "আরও বিকল্প" else "More options"
            setOnClickListener {
                if (tag == "menu_open") {
                    readerOptionsPopup?.dismiss()
                    tag = null
                    text = "⋮"
                    showReaderOverlays()
                } else {
                    tag = "menu_open"
                    text = "×"
                    mainHandler.removeCallbacks(hideReaderOverlaysRunnable)
                    showReaderMenu(this)
                }
            }
        }
        root.addView(readerMenuButton, FrameLayout.LayoutParams(dp(52), dp(52),
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12)
            marginEnd = dp(12)
        })

        readerPlaybackPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glassBackground()
            setPadding(dp(5), dp(3), dp(5), dp(3))
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        readerPauseButton = playbackButton("▶", "Play or pause reading") {
            if (narrationPaused) resumeReaderNarration()
            else if (isNarrating) pauseReaderNarration()
            else if (lastNarrationMode == "ocr") startOcrReadAloud() else startDeviceDefaultReadAloud()
        }
        playbackButton("■", "Stop reading") {
            stopReaderNarration()
            showReaderOverlays()
        }
        playbackButton("↻", "Restart current page") {
            restartReaderNarration()
            showReaderOverlays()
        }
        playbackButton("×", "Close playback controls") {
            stopReaderNarration()
            readerPlaybackPanel.visibility = View.GONE
            mainHandler.removeCallbacks(hideReaderOverlaysRunnable)
            mainHandler.postDelayed(hideReaderOverlaysRunnable, 2_000L)
        }
        root.addView(createProgressPanel(dark), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(76)
        })
        root.addView(readerPlaybackPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(16)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (zoomScale * detector.scaleFactor).coerceIn(0.6f, 3.0f)
                    if (kotlin.math.abs(next - zoomScale) >= 0.005f) {
                        zoomGestureOccurred = true
                        applyZoom(next, rerender = false)
                    }
                    return true
                }
            }
        )

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        readerScroll.setOnTouchListener { _, event ->
            val detector = scaleGestureDetector
            val scaling = detector?.isInProgress == true || event.pointerCount > 1

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDidMove = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - touchDownX) > touchSlop ||
                        kotlin.math.abs(event.y - touchDownY) > touchSlop || scaling
                    ) touchDidMove = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> touchDidMove = true
                MotionEvent.ACTION_POINTER_UP -> touchDidMove = true
                MotionEvent.ACTION_UP -> {
                    if (zoomGestureOccurred) {
                        applyZoom(zoomScale, rerender = true)
                        zoomGestureOccurred = false
                        touchDidMove = true
                    }
                    if (!touchDidMove) showReaderOverlays()
                }
                MotionEvent.ACTION_CANCEL -> touchDidMove = true
            }
            scaling
        }
        readerScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateCurrentPageFromScroll()
            updateVisiblePages()
        }

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        showReaderOverlays()
    }

    private fun playbackButton(symbol: String, description: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
            minWidth = dp(48)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            readerPlaybackPanel.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun createProgressPanel(dark: Boolean): LinearLayout {
        progressAnimator?.cancel()
        progressAnimator = null
        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (dark) Color.rgb(20, 28, 26) else Color.WHITE)
                setStroke(dp(1), if (dark) Color.rgb(76, 100, 88) else Color.rgb(198, 216, 204))
            }
            visibility = View.GONE
        }
        progressLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(if (dark) Color.rgb(225, 239, 231) else Color.rgb(48, 75, 61))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val track = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(if (dark) Color.rgb(61, 79, 69) else Color.rgb(220, 231, 224))
        }
        val fill = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(if (dark) Color.rgb(62, 224, 144) else Color.rgb(18, 135, 87))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressDrawable = LayerDrawable(arrayOf(
                track,
                ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        progressPanel.addView(progressLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(9)
        ))
        return progressPanel
    }

    private fun setBusy(message: String, percent: Int? = null) {
        mainHandler.post {
            if (!::progressPanel.isInitialized || !::progressLabel.isInitialized || !::progressBar.isInitialized) return@post
            progressPanel.visibility = View.VISIBLE
            progressLabel.text = message
            if (percent == null) {
                if (progressAnimator?.isRunning != true) {
                    progressAnimator?.cancel()
                    progressAnimator = ValueAnimator.ofInt(18, 76, 18).apply {
                        duration = 1_100L
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener { animation ->
                            if (::progressBar.isInitialized && progressPanel.visibility == View.VISIBLE) {
                                progressBar.progress = animation.animatedValue as Int
                            }
                        }
                        start()
                    }
                }
            } else {
                progressAnimator?.cancel()
                progressAnimator = null
                progressBar.progress = percent.coerceIn(0, 100)
            }
        }
    }

    private fun clearBusy() {
        mainHandler.post {
            progressAnimator?.cancel()
            progressAnimator = null
            if (::progressPanel.isInitialized) progressPanel.visibility = View.GONE
            if (::progressBar.isInitialized) {
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
        }
    }

    private fun glassBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(Color.argb(218, 25, 32, 29))
        setStroke(dp(1), Color.argb(90, 255, 255, 255))
    }

    private fun contentPageWidth(): Int {
        basePageWidthPx = (resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(dp(200))
        return (basePageWidthPx * zoomScale).roundToInt().coerceAtLeast(dp(200))
    }

    private fun applyZoom(newScale: Float, rerender: Boolean) {
        zoomScale = newScale.coerceIn(0.6f, 3.0f)
        val width = contentPageWidth()
        val oldScrollX = if (::horizontalPageScroll.isInitialized) horizontalPageScroll.scrollX else 0
        if (::pagesContainer.isInitialized) {
            pagesContainer.layoutParams = pagesContainer.layoutParams.apply { this.width = width }
        }
        if (::readerScroll.isInitialized) {
            readerScroll.layoutParams = readerScroll.layoutParams.apply { this.width = width }
        }
        if (::horizontalPageScroll.isInitialized) {
            horizontalPageScroll.post {
                val maximum = (width - horizontalPageScroll.width).coerceAtLeast(0)
                horizontalPageScroll.scrollTo(oldScrollX.coerceIn(0, maximum), 0)
            }
        }
        pageImages.forEachIndexed { index, image ->
            val size = pageDimensions.getOrNull(index) ?: return@forEachIndexed
            val imageWidth = (width - dp(14)).coerceAtLeast(dp(120))
            val height = (imageWidth.toFloat() * size.second / size.first).roundToInt().coerceAtLeast(dp(160))
            image.layoutParams = image.layoutParams.apply {
                this.width = imageWidth
                this.height = height
            }
        }
        if (rerender && !showingLibrary && pageDimensions.isNotEmpty()) {
            val pageToRestore = currentPage
            val generation = ++readerGeneration
            buildContinuousPages(generation, pageToRestore)
        }
    }

    private fun rotateReaderScreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun buildContinuousPages(generation: Int, restorePage: Int = currentPage) {
        pagesContainer.removeAllViews()
        pageImages.forEach { it.setImageDrawable(null) }
        pageImages.clear()
        pageBitmaps.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        pageBitmaps.clear()
        queuedPageRenders.clear()

        val contentWidth = contentPageWidth()
        pagesContainer.layoutParams = pagesContainer.layoutParams.apply { width = contentWidth }
        readerScroll.layoutParams = readerScroll.layoutParams.apply { width = contentWidth }
        pageDimensions.forEachIndexed { index, size ->
            val imageWidth = (contentWidth - dp(14)).coerceAtLeast(dp(120))
            val pageHeight = (imageWidth.toFloat() * size.second / size.first)
                .roundToInt().coerceAtLeast(dp(160))
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(185, 191, 188))
                cornerRadius = dp(2).toFloat()
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                setBackground(border)
                setPadding(dp(1), dp(1), dp(1), dp(1))
                contentDescription = "PDF page ${index + 1} of ${pageDimensions.size}"
            }
            pagesContainer.addView(image, LinearLayout.LayoutParams(imageWidth, pageHeight).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
                bottomMargin = dp(5)
            })
            pageImages.add(image)
        }
        currentPage = restorePage.coerceIn(0, (pageDimensions.size - 1).coerceAtLeast(0))
        pageText = pageTextCache[currentPage].orEmpty()
        if (pageDimensions.isNotEmpty()) setBusy("Loading PDF page ${currentPage + 1}…")
        readerScroll.post {
            if (generation == readerGeneration && !showingLibrary && pageImages.isNotEmpty()) {
                readerScroll.scrollTo(0, pageImages[currentPage].top)
                horizontalPageScroll.scrollTo(
                    ((contentWidth - horizontalPageScroll.width) / 2).coerceAtLeast(0), 0
                )
                updateVisiblePages()
            } else if (pageDimensions.isEmpty()) {
                clearBusy()
            }
        }
    }

    private fun updateCurrentPageFromScroll() {
        if (!::readerScroll.isInitialized || pageImages.isEmpty()) return
        val targetY = readerScroll.scrollY + readerScroll.height / 2
        val found = pageImages.indexOfFirst { targetY >= it.top && targetY <= it.bottom }
        val visibleIndex = (if (found >= 0) found else pageImages.indexOfLast { it.top <= targetY })
            .coerceIn(0, pageImages.lastIndex)
        if (visibleIndex != currentPage) {
            currentPage = visibleIndex
            pageText = pageTextCache[currentPage].orEmpty()
            trimReaderBitmaps()
        }
    }

    private fun updateVisiblePages() {
        if (!::readerScroll.isInitialized || pageImages.isEmpty()) return
        val viewportTop = readerScroll.scrollY
        val viewportBottom = viewportTop + readerScroll.height
        val margin = readerScroll.height.coerceAtLeast(dp(500))
        pageImages.indices.filter { index ->
            pageImages[index].bottom >= viewportTop - margin &&
                pageImages[index].top <= viewportBottom + margin
        }.forEach { renderPageIntoView(it, readerGeneration) }
        trimReaderBitmaps()
    }

    private fun renderPageIntoView(index: Int, generation: Int) {
        if (index !in pageImages.indices || generation != readerGeneration) return
        if (pageBitmaps[index]?.isRecycled == false || !queuedPageRenders.add(index)) return
        val dimensions = pageDimensions.getOrNull(index) ?: return
        val targetWidth = minOf(1500, maxOf(850, (basePageWidthPx * zoomScale.coerceAtMost(1.5f)).roundToInt()))
        val scale = minOf(
            targetWidth.toFloat() / dimensions.first,
            2500f / dimensions.second
        )
        val width = (dimensions.first * scale).roundToInt().coerceAtLeast(1)
        val height = (dimensions.second * scale).roundToInt().coerceAtLeast(1)
        val uri = currentBook?.uri ?: return
        if (index == currentPage) setBusy("Loading page ${index + 1}…")

        worker.execute {
            if (generation != readerGeneration) return@execute
            var bitmap: Bitmap? = null
            try {
                val renderer = pdfRenderer ?: throw IOException("No PDF is open.")
                renderer.openPage(index).use { page ->
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = rendered
                    rendered.eraseColor(Color.WHITE)
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                val result = bitmap
                mainHandler.post {
                    if (generation == readerGeneration && currentBook?.uri == uri) queuedPageRenders.remove(index)
                    if (result != null && generation == readerGeneration &&
                        currentBook?.uri == uri && index in pageImages.indices && !showingLibrary
                    ) {
                        pageBitmaps.remove(index)?.let { old ->
                            if (old !== result && !old.isRecycled) old.recycle()
                        }
                        pageBitmaps[index] = result
                        pageImages[index].setImageBitmap(result)
                        trimReaderBitmaps()
                        if (index == currentPage) clearBusy()
                    } else {
                        result?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
            } catch (_: Exception) {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
                mainHandler.post {
                    if (generation == readerGeneration && currentBook?.uri == uri) queuedPageRenders.remove(index)
                    if (generation == readerGeneration && index == currentPage && !showingLibrary) {
                        clearBusy()
                        Toast.makeText(this, "Could not display page ${index + 1}.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun trimReaderBitmaps() {
        if (!::readerScroll.isInitialized) return
        val iterator = pageBitmaps.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (kotlin.math.abs(entry.key - currentPage) > 2) {
                if (entry.key in pageImages.indices) pageImages[entry.key].setImageDrawable(null)
                if (!entry.value.isRecycled) entry.value.recycle()
                iterator.remove()
            }
        }
    }

    private fun releaseReaderBitmaps() {
        pageImages.forEach { image -> image.setImageDrawable(null) }
        pageBitmaps.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        pageBitmaps.clear()
        queuedPageRenders.clear()
        pageTextCache.clear()
        pageDimensions = emptyList()
        pageImages.clear()
    }

    private fun showReaderOverlays() {
        if (!::readerMenuButton.isInitialized || showingLibrary) return
        mainHandler.removeCallbacks(hideReaderOverlaysRunnable)
        readerMenuButton.visibility = View.VISIBLE
        readerPlaybackPanel.visibility = if (isNarrating || narrationPaused) View.VISIBLE else View.GONE
        readerPauseButton.text = if (isNarrating && !narrationPaused) "Ⅱ" else "▶"
        mainHandler.postDelayed(hideReaderOverlaysRunnable, 2_000L)
    }

    private fun hideReaderOverlays() {
        if (!::readerMenuButton.isInitialized) return
        readerMenuButton.visibility = View.GONE
        readerPlaybackPanel.visibility = View.GONE
    }

    private fun showReaderMenu(anchor: View) {
        val bn = preferences.getString("app_language", "en") == "bn"
        val dark = when (preferences.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val primaryTextColor = if (dark) Color.rgb(238, 242, 239) else Color.rgb(30, 55, 46)
        val secondaryTextColor = if (dark) Color.rgb(174, 218, 202) else Color.rgb(20, 105, 80)
        val dividerColor = if (dark) Color.rgb(71, 82, 77) else Color.rgb(225, 230, 227)
        fun label(en: String, bnText: String) = if (bn) bnText else en
        mainHandler.removeCallbacks(hideReaderOverlaysRunnable)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun addAction(title: String, action: () -> Unit, settingsAction: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val mainAction = TextView(this).apply {
                text = title
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(primaryTextColor)
                setPadding(dp(10), dp(9), dp(8), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    readerOptionsPopup?.dismiss()
                    action()
                }
            }
            row.addView(mainAction, LinearLayout.LayoutParams(0, dp(46), 1f))
            if (settingsAction != null) {
                val gear = TextView(this).apply {
                    text = "⚙"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(secondaryTextColor)
                    contentDescription = "$title settings"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        readerOptionsPopup?.dismiss()
                        settingsAction()
                    }
                }
                row.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)))
            }
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val divider = View(this).apply { setBackgroundColor(dividerColor) }
            content.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ))
        }

        addAction(label("Read aloud", "পড়ে শোনান"),
            { startDeviceDefaultReadAloud() }, { showSpeechSettings() })
        addAction(label("Read aloud (OCR)", "OCR দিয়ে পড়ে শোনান"),
            { startOcrReadAloud() }, { showOcrSettings() })
        val pageJumpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageJumpLabel = TextView(this).apply {
            text = label("Go to page", "পৃষ্ঠা নম্বরে যান")
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(primaryTextColor)
            setPadding(dp(10), dp(9), dp(8), dp(9))
        }
        pageJumpRow.addView(pageJumpLabel, LinearLayout.LayoutParams(0, dp(40), 1f))
        val pageJumpInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            setText((currentPage + 1).toString())
            setSelectAllOnFocus(true)
            hint = "1-" + pageCount
            contentDescription = label("Page number", "পৃষ্ঠা নম্বর")
        }
        pageJumpRow.addView(pageJumpInput, LinearLayout.LayoutParams(dp(64), dp(36)))
        fun jumpToEnteredPage() {
            val pageNumber = pageJumpInput.text.toString().trim().toIntOrNull()
            if (pageNumber == null || pageNumber !in 1..pageCount) {
                pageJumpInput.error = label("Enter a page from 1 to " + pageCount, "১ থেকে " + pageCount + " এর মধ্যে পৃষ্ঠা লিখুন")
                return
            }
            readerOptionsPopup?.dismiss()
            showPage(pageNumber - 1)
        }
        pageJumpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                jumpToEnteredPage()
                true
            } else false
        }
        val pageJumpButton = Button(this).apply {
            text = label("Go", "যান")
            isAllCaps = false
            textSize = 12f
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { jumpToEnteredPage() }
        }
        pageJumpRow.addView(pageJumpButton, LinearLayout.LayoutParams(dp(52), dp(36)).apply {
            leftMargin = dp(4)
            rightMargin = dp(6)
        })
        content.addView(pageJumpRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val pageJumpDivider = View(this).apply { setBackgroundColor(dividerColor) }
        content.addView(pageJumpDivider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val zoomTitle = TextView(this).apply {
            text = label("Zoom", "জুম")
            textSize = 15f
            setTextColor(primaryTextColor)
        }
        zoomRow.addView(zoomTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        val zoomPercent = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            setTextColor(primaryTextColor)
            setText((zoomScale * 100).roundToInt().toString())
            setSelectAllOnFocus(true)
            contentDescription = label("Zoom percentage", "জুমের শতাংশ")
        }
        val zoomUnit = TextView(this).apply {
            text = "%"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor)
        }
        fun commitZoomInput(): Boolean {
            val entered = zoomPercent.text.toString().trim().toIntOrNull()
            if (entered == null) {
                zoomPercent.error = label("Enter a zoom from 60 to 300", "৬০ থেকে ৩০০ এর মধ্যে জুম লিখুন")
                zoomPercent.setText((zoomScale * 100).roundToInt().toString())
                return false
            }
            val normalized = entered.coerceIn(60, 300)
            zoomPercent.error = null
            if (kotlin.math.abs(zoomScale - normalized / 100f) >= 0.005f) {
                applyZoom(normalized / 100f, rerender = true)
            }
            zoomPercent.setText(normalized.toString())
            return true
        }
        zoomPercent.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                commitZoomInput()
                true
            } else false
        }
        zoomPercent.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitZoomInput()
        }
        val zoomOut = Button(this).apply {
            text = "−"
            textSize = 13f
            isAllCaps = false
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                commitZoomInput()
                applyZoom(zoomScale - 0.1f, rerender = true)
                zoomPercent.setText((zoomScale * 100).roundToInt().toString())
            }
        }
        val zoomIn = Button(this).apply {
            text = "+"
            textSize = 13f
            isAllCaps = false
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                commitZoomInput()
                applyZoom(zoomScale + 0.1f, rerender = true)
                zoomPercent.setText((zoomScale * 100).roundToInt().toString())
            }
        }
        zoomRow.addView(zoomOut, LinearLayout.LayoutParams(dp(38), dp(36)))
        zoomRow.addView(zoomPercent, LinearLayout.LayoutParams(dp(48), dp(36)))
        zoomRow.addView(zoomUnit, LinearLayout.LayoutParams(dp(16), dp(36)))
        zoomRow.addView(zoomIn, LinearLayout.LayoutParams(dp(38), dp(36)))
        content.addView(zoomRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addAction(label("Share PDF", "PDF শেয়ার করুন"), { shareCurrentBook() })
        addAction(label("Print PDF", "PDF প্রিন্ট করুন"), { printCurrentBook() })
        addAction(label("Download opened book…", "খোলা বই ডাউনলোড…"), {
            val name = currentBook?.name ?: "book.pdf"
            createPdfCopy.launch(if (name.endsWith(".pdf", true)) name else "$name.pdf")
        })
        addAction(label("Set current page as cover", "বর্তমান পৃষ্ঠাটি কভার করুন"), { saveCurrentPageAsCover() })
        addAction(label("Choose cover page…", "কভার পৃষ্ঠা বাছুন…"), { showCoverPageDialog() })
        addAction(label("Rotate screen", "স্ক্রিন ঘোরান"), { rotateReaderScreen() })
        addAction(label("Settings", "সেটিংস"), {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(if (dark) Color.rgb(28, 35, 32) else Color.WHITE)
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val popupWidth = minOf(dp(360), resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
        val popupHeight = minOf(dp(620), resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(dp(280))
        val menuPopup = PopupWindow(scroll, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (dark) Color.rgb(28, 35, 32) else Color.WHITE)
                setStroke(dp(1), dividerColor)
            })
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            setOnDismissListener {
                readerOptionsPopup = null
                if (::readerMenuButton.isInitialized) {
                    readerMenuButton.tag = null
                    readerMenuButton.text = "⋮"
                }
                showReaderOverlays()
            }
        }
        readerOptionsPopup = menuPopup
        menuPopup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }

    private fun showSpeechSettings() {
        val language = preferences.getString("app_language", "en") == "bn"
        val rateValue = preferences.getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f)
        val pitchValue = preferences.getFloat("narration_pitch", 1.0f).coerceIn(0.5f, 1.5f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val rateLabel = TextView(this).apply { text = "Speed: ${"%.2f".format(Locale.US, rateValue)}×" }
        val rate = SeekBar(this).apply {
            max = 100
            progress = ((rateValue - 0.5f) * 100).roundToInt().coerceIn(0, 100)
        }
        rate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rateLabel.text = "Speed: ${"%.2f".format(Locale.US, 0.5f + progress / 100f)}×"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val pitchLabel = TextView(this).apply { text = "Pitch: ${"%.2f".format(Locale.US, pitchValue)}×" }
        val pitch = SeekBar(this).apply {
            max = 100
            progress = ((pitchValue - 0.5f) * 100).roundToInt().coerceIn(0, 100)
        }
        pitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchLabel.text = "Pitch: ${"%.2f".format(Locale.US, 0.5f + progress / 100f)}×"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(rateLabel)
        root.addView(rate)
        root.addView(pitchLabel)
        root.addView(pitch)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (language) "ডিভাইসের ভয়েস সেটিংস" else "Device voice settings")
            .setView(root)
            .setNeutralButton(if (language) "ডিভাইসের TTS সেটিংস" else "Device TTS settings") { _, _ -> openDeviceTtsSettings() }
            .setNegativeButton(if (language) "বাতিল" else "Cancel", null)
            .setPositiveButton(if (language) "সংরক্ষণ" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rateSetting = 0.5f + rate.progress / 100f
                val pitchSetting = 0.5f + pitch.progress / 100f
                preferences.edit()
                    .putFloat("narration_rate", rateSetting)
                    .putFloat("narration_pitch", pitchSetting)
                    .apply()
                tts?.setSpeechRate(rateSetting)
                tts?.setPitch(pitchSetting)
                readerOptionsPopup?.dismiss()
            }
        }
        dialog.show()
    }

    private fun openDeviceTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            catch (_: Exception) { toast("Open Android Settings and search for Text-to-speech output.") }
        }
    }

    private fun showOcrSettings() {
        val language = preferences.getString("app_language", "en") == "bn"
        val languages = arrayOf("ben", "eng", "ben+eng")
        val labels = if (language) arrayOf("বাংলা", "English", "বাংলা + English")
            else arrayOf("Bengali", "English", "Bengali + English")
        val current = preferences.getString("ocr_language", "ben") ?: "ben"
        val selected = languages.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(if (language) "OCR ভাষা" else "OCR language")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.edit().putString("ocr_language", languages[which]).apply()
                readerOptionsPopup?.dismiss()
                Toast.makeText(this, labels[which], Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (language) "OCR ক্যাশ মুছুন" else "Clear OCR cache") { _, _ ->
                clearOcrCache()
            }
            .setNegativeButton(if (language) "বন্ধ" else "Close", null)
            .show()
    }

    private fun clearOcrCache() {
        setBusy("Refreshing OCR cache…")
        worker.execute {
            val folder = File(cacheDir, "ocr")
            folder.deleteRecursively()
            ocrTextCache.clear()
            mainHandler.post {
                clearBusy()
                Toast.makeText(this, "OCR cache refreshed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount || index !in pageImages.indices) return
        currentPage = index
        pageText = pageTextCache[index].orEmpty()
        readerScroll.post {
            if (index in pageImages.indices) {
                readerScroll.smoothScrollTo(0, pageImages[index].top)
                renderPageIntoView(index, readerGeneration)
            }
        }
    }

    private fun extractText(uri: Uri, pageNumber: Int): String {
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) return ""
            PDDocument.load(input).use { document ->
                val stripper = PDFTextStripper().apply {
                    startPage = pageNumber
                    endPage = pageNumber
                    sortByPosition = true
                }
                return stripper.getText(document)
            }
        }
    }

    private fun saveCurrentPageAsCover() {
        val book = currentBook ?: return
        if (pageCount <= 0) return
        preferences.edit().putInt(coverPageKey(book.uri), currentPage).apply()
        toast("Book cover updated to page ${currentPage + 1}.")
    }

    private fun showCoverPageDialog() {
        if (pageCount <= 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((currentPage + 1).toString())
            selectAll()
            hint = "Page number"
        }
        AlertDialog.Builder(this)
            .setTitle("Choose book cover page")
            .setMessage("Enter the PDF page number to use as the thumbnail in your library (1–$pageCount).")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set cover") { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..pageCount) {
                    toast("Enter a page from 1 to $pageCount.")
                } else {
                    val book = currentBook ?: return@setPositiveButton
                    preferences.edit().putInt(coverPageKey(book.uri), page - 1).apply()
                    toast("Book cover updated to page $page.")
                }
            }
            .show()
    }

    private fun readCurrentPage() {
        startDeviceDefaultReadAloud()
    }

    private fun startDeviceDefaultReadAloud() {
        if (pageCount <= 0 || currentBook == null) {
            toast("Open a PDF first.")
            return
        }
        stopReaderNarration()
        lastNarrationMode = "device"
        val requestedPage = currentPage
        val generation = readerGeneration
        val cached = pageTextCache[requestedPage]
        if (cached != null) {
            speakTextOnCurrentPage(cached, requestedPage, "device")
            return
        }
        val uri = currentBook?.uri ?: return
        setBusy("Preparing the voice…")
        worker.execute {
            try {
                val extracted = extractText(uri, requestedPage + 1).trim()
                mainHandler.post {
                    if (generation != readerGeneration || currentBook?.uri != uri || showingLibrary) return@post
                    pageTextCache[requestedPage] = extracted
                    if (currentPage == requestedPage) pageText = extracted
                    speakTextOnCurrentPage(extracted, requestedPage, "device")
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (generation == readerGeneration && currentBook?.uri == uri) {
                        clearBusy()
                        Toast.makeText(this, "Could not extract selectable text: ${error.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startOcrReadAloud() {
        if (pageCount <= 0 || currentBook == null) {
            toast("Open a PDF first.")
            return
        }
        stopReaderNarration()
        lastNarrationMode = "ocr"
        val requestedPage = currentPage
        val generation = readerGeneration
        val uri = currentBook?.uri ?: return
        val language = preferences.getString("ocr_language", "ben") ?: "ben"
        val cacheKey = "${uri}#${requestedPage + 1}#${language}"
        val inMemory = ocrTextCache[cacheKey]
        if (inMemory != null) {
            speakTextOnCurrentPage(inMemory, requestedPage, "ocr")
            return
        }
        setBusy("Scanning OCR page ${requestedPage + 1}…")
        worker.execute {
            try {
                val cacheFile = ocrCacheFile(uri, requestedPage, language)
                val cachedText = if (cacheFile.isFile) cacheFile.readText(Charsets.UTF_8) else null
                val recognizedText = cachedText?.takeIf { it.isNotBlank() }
                    ?: recognizePdfPage(uri, requestedPage, language)
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(recognizedText, Charsets.UTF_8)
                ocrTextCache[cacheKey] = recognizedText
                mainHandler.post {
                    if (generation != readerGeneration || currentBook?.uri != uri || showingLibrary) return@post
                    speakTextOnCurrentPage(recognizedText, requestedPage, "ocr")
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (generation == readerGeneration && currentBook?.uri == uri) {
                        clearBusy()
                        Toast.makeText(
                            this,
                            "OCR failed: ${error.localizedMessage ?: "check your connection and OCR language model"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun ocrCacheFile(uri: Uri, pageIndex: Int, language: String): File =
        File(File(cacheDir, "ocr"), "${uri.toString().hashCode()}_page_${pageIndex + 1}_${language.replace('+', '_')}.txt")

    /**
     * Bengali/English OCR models download once on first OCR use. The page scan
     * itself runs locally with Tesseract and is cached for subsequent reading.
     */
    private fun recognizePdfPage(uri: Uri, pageIndex: Int, language: String): String {
        ensureOcrModels(language)
        val dimensions = pageDimensions.getOrNull(pageIndex) ?: throw IOException("Page dimensions are not ready.")
        val maxWidth = 1600
        val maxHeight = 2500
        val scale = minOf(maxWidth.toFloat() / dimensions.first, maxHeight.toFloat() / dimensions.second)
        val width = (dimensions.first * scale).roundToInt().coerceAtLeast(1)
        val height = (dimensions.second * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val renderer = pdfRenderer ?: throw IOException("No PDF is open.")
            renderer.openPage(pageIndex).use { page ->
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            setBusy("Scanning OCR page ${pageIndex + 1}…")
            val tess = TessBaseAPI()
            try {
                val languageCode = language
                if (!tess.init(File(filesDir, "tesseract").absolutePath, languageCode)) {
                    throw IOException("Tesseract could not initialize the selected OCR language.")
                }
                tess.setImage(bitmap)
                return tess.getUTF8Text().orEmpty().trim()
            } finally {
                tess.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun ensureOcrModels(language: String) {
        val dataRoot = File(filesDir, "tesseract")
        val tessdata = File(dataRoot, "tessdata")
        if (!tessdata.exists() && !tessdata.mkdirs()) throw IOException("Cannot create the OCR model folder.")
        val languages = when (language) {
            "eng" -> listOf("eng")
            "ben+eng" -> listOf("ben", "eng")
            else -> listOf("ben")
        }
        for (code in languages) {
            val model = File(tessdata, "$code.traineddata")
            if (model.isFile && model.length() > 1_000_000L) continue
            val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$code.traineddata")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 45_000
                requestMethod = "GET"
            }
            try {
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("OCR model download returned HTTP ${connection.responseCode}.")
                }
                val total = connection.contentLengthLong
                val partial = File(tessdata, "$code.traineddata.part")
                setBusy("Downloading OCR model: $code…")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastUpdate = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (total > 0 && copied - lastUpdate >= 256 * 1024) {
                                lastUpdate = copied
                                setBusy(
                                    "Downloading OCR model: $code…",
                                    (copied * 100L / total).toInt()
                                )
                            }
                        }
                    }
                }
                if (model.exists()) model.delete()
                if (!partial.renameTo(model)) throw IOException("Could not save OCR model.")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun speakTextOnCurrentPage(text: String, pageIndex: Int, mode: String) {
        if (text.isBlank()) {
            clearBusy()
            toast(if (mode == "ocr") "OCR found no readable text on this page." else "No selectable text on this page. Try Read aloud (OCR).")
            showReaderOverlays()
            return
        }
        if (!ttsReady) {
            clearBusy()
            toast("Speech engine is still starting. Please try again.")
            return
        }
        val speech = tts ?: return
        val defaultLocale = Locale.getDefault()
        val preferredLocale = if (mode == "ocr") {
            when (preferences.getString("ocr_language", "ben") ?: "ben") {
                "ben" -> Locale("bn", "BD")
                "eng" -> Locale.ENGLISH
                else -> defaultLocale
            }
        } else defaultLocale
        var localeResult = speech.setLanguage(preferredLocale)
        var usingDefaultVoice = false
        if (localeResult < TextToSpeech.LANG_AVAILABLE && preferredLocale != defaultLocale) {
            localeResult = speech.setLanguage(defaultLocale)
            usingDefaultVoice = localeResult >= TextToSpeech.LANG_AVAILABLE
        }
        if (localeResult < TextToSpeech.LANG_AVAILABLE) {
            clearBusy()
            toast("The selected speech voice is not available. Open Android text-to-speech settings to install a voice.")
            return
        }
        if (usingDefaultVoice && mode == "ocr" && preferredLocale.language == "bn") {
            toast("A Bangla voice is not installed; using the device default voice. Install one in Android TTS settings when available.")
        }
        speech.setSpeechRate(preferences.getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f))
        speech.setPitch(preferences.getFloat("narration_pitch", 1.0f).coerceIn(0.5f, 1.5f))
        val segments = splitNarrationText(text)
        if (segments.isEmpty()) {
            clearBusy()
            toast("No readable text was found on this page.")
            return
        }

        lastNarrationMode = mode
        narrationSegments = segments
        narrationCurrentSegmentIndex = 0
        narrationPaused = false
        isNarrating = true
        queueNarrationSegments(0)
        clearBusy()
        showReaderOverlays()
    }

    private fun queueNarrationSegments(startIndex: Int) {
        val speech = tts ?: return
        if (narrationSegments.isEmpty() || startIndex !in narrationSegments.indices) {
            isNarrating = false
            narrationPaused = false
            showReaderOverlays()
            return
        }
        val run = ++narrationRunId
        lastNarrationUtteranceId = "kontho-run-$run-segment-${narrationSegments.lastIndex}"
        for (index in startIndex until narrationSegments.size) {
            val queueMode = if (index == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = speech.speak(
                narrationSegments[index],
                queueMode,
                null,
                "kontho-run-$run-segment-$index"
            )
            if (result == TextToSpeech.ERROR) {
                stopReaderNarration()
                toast("Speech could not start. Check Android text-to-speech settings.")
                return
            }
            if (index < narrationSegments.lastIndex) {
                speech.playSilentUtterance(
                    450L,
                    TextToSpeech.QUEUE_ADD,
                    "kontho-run-$run-pause-$index"
                )
            }
        }
    }

    private fun pauseReaderNarration() {
        if (!isNarrating) return
        narrationPaused = true
        isNarrating = false
        // Android TTS has no universal pause API; resume repeats the current paragraph.
        tts?.stop()
        showReaderOverlays()
    }

    private fun resumeReaderNarration() {
        if (!narrationPaused) {
            if (!isNarrating) restartReaderNarration()
            return
        }
        narrationPaused = false
        isNarrating = true
        queueNarrationSegments(narrationCurrentSegmentIndex.coerceIn(0, narrationSegments.lastIndex))
        showReaderOverlays()
    }

    private fun restartReaderNarration() {
        stopReaderNarration()
        if (lastNarrationMode == "ocr") startOcrReadAloud() else startDeviceDefaultReadAloud()
    }

    private fun stopReaderNarration() {
        tts?.stop()
        isNarrating = false
        narrationPaused = false
        narrationSegments = emptyList()
        narrationCurrentSegmentIndex = 0
        lastNarrationUtteranceId = null
        if (::readerPauseButton.isInitialized) readerPauseButton.text = "▶"
        if (::readerPlaybackPanel.isInitialized) readerPlaybackPanel.visibility = View.GONE
    }

    private fun splitNarrationText(text: String, maxLength: Int = 2500): List<String> {
        val paragraphs = text.trim()
            .split(Regex("\\n\\s*\\n+"))
            .map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }
            .filter { it.isNotBlank() }
        val segments = mutableListOf<String>()
        for (paragraph in paragraphs) {
            var remaining = paragraph
            while (remaining.length > maxLength) {
                var cut = remaining.lastIndexOf(' ', maxLength)
                if (cut < maxLength / 2) cut = maxLength
                segments += remaining.substring(0, cut).trim()
                remaining = remaining.substring(cut).trimStart()
            }
            if (remaining.isNotBlank()) segments += remaining
        }
        return segments
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setSpeechRate(preferences.getFloat("narration_rate", 0.85f).coerceIn(0.5f, 1.5f))
            tts?.setPitch(preferences.getFloat("narration_pitch", 1.0f).coerceIn(0.5f, 1.5f))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val segment = utteranceId?.substringAfterLast("-segment-")?.toIntOrNull()
                    mainHandler.post {
                        if (!isFinishing && !showingLibrary) {
                            if (segment != null) narrationCurrentSegmentIndex = segment
                            if (!narrationPaused) isNarrating = true
                            if (::readerPauseButton.isInitialized) readerPauseButton.text = if (isNarrating && !narrationPaused) "Ⅱ" else "▶"
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && utteranceId == lastNarrationUtteranceId) {
                        mainHandler.post {
                            isNarrating = false
                            narrationPaused = false
                            lastNarrationUtteranceId = null
                            if (::readerPauseButton.isInitialized) readerPauseButton.text = "▶"
                            showReaderOverlays()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && utteranceId == lastNarrationUtteranceId) {
                        mainHandler.post {
                            if (!narrationPaused) {
                                isNarrating = false
                                narrationPaused = false
                                lastNarrationUtteranceId = null
                                if (::readerPauseButton.isInitialized) readerPauseButton.text = "▶"
                                showReaderOverlays()
                            }
                        }
                    }
                }
            })
        }
    }

    private fun shareCurrentBook() {
        val book = currentBook
        if (book == null) {
            toast("Open a PDF before sharing.")
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, book.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${book.name}"))
        } catch (error: Exception) {
            toast("Could not share this PDF: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    private fun printCurrentBook() {
        val book = currentBook
        if (book == null) {
            toast("Open a PDF before printing.")
            return
        }
        val printManager = getSystemService(PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            toast("Printing is not available on this device.")
            return
        }

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(book.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    val source = contentResolver.openInputStream(book.uri)
                        ?: throw IOException("Could not read the selected PDF.")
                    source.use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancellationSignal?.isCanceled == true) {
                                    throw java.io.InterruptedIOException("Print cancelled.")
                                }
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFailed(error.localizedMessage ?: "Could not prepare this PDF for printing.")
                    }
                }
            }
        }

        try {
            printManager.print(book.name, adapter, null)
        } catch (error: Exception) {
            toast("Could not start printing: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    private fun copyCurrentBookTo(destination: Uri) {
        val book = currentBook ?: return
        val source = book.uri
        setBusy("Saving opened book…")
        worker.execute {
            try {
                val total = try { DocumentFile.fromSingleUri(this, source)?.length() ?: -1L } catch (_: Exception) { -1L }
                contentResolver.openInputStream(source).use { input ->
                    if (input == null) throw IOException("Could not read the open PDF.")
                    contentResolver.openOutputStream(destination, "w").use { output ->
                        if (output == null) throw IOException("Could not create the selected PDF file.")
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastUpdate = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (total > 0 && copied - lastUpdate >= 512 * 1024) {
                                lastUpdate = copied
                                setBusy("Saving opened book…", (copied * 100L / total).toInt())
                            }
                        }
                        output.flush()
                    }
                }
                mainHandler.post {
                    clearBusy()
                    Toast.makeText(this, "Book copy saved.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    clearBusy()
                    Toast.makeText(this, "Could not save book: ${error.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun keepReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permission.
        } catch (_: IllegalArgumentException) {
            // Use access granted for the current session when persistence is unavailable.
        }
    }

    private fun modifiedFor(uri: Uri): Long {
        return try { DocumentFile.fromSingleUri(this, uri)?.lastModified() ?: 0L } catch (_: Exception) { 0L }
    }

    private fun showBookActions(book: Book) {
        val bn = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_language", "en") == "bn"
        val options = if (bn) arrayOf("খুলুন", "নাম পরিবর্তন", "মুছুন") else arrayOf("Open", "Rename", "Delete")
        AlertDialog.Builder(this).setTitle(book.name).setItems(options) { _, which ->
            when (which) {
                0 -> openBook(book)
                1 -> renameBook(book)
                2 -> AlertDialog.Builder(this)
                    .setTitle(if (bn) "PDF মুছবেন?" else "Delete PDF?")
                    .setMessage(if (bn) "এতে আসল ফাইলটি মুছে যাবে।" else "This deletes the actual PDF file and cannot be undone.")
                    .setNegativeButton(if (bn) "বাতিল" else "Cancel", null)
                    .setPositiveButton(if (bn) "মুছুন" else "Delete") { _, _ ->
                        val success = try { DocumentFile.fromSingleUri(this, book.uri)?.delete() == true } catch (_: Exception) { false }
                        if (success) { forgetRecentBook(book.uri); refreshLibrary() } else toast(if (bn) "ফাইলটি মুছতে অনুমতি নেই।" else "Android does not allow deleting this file. Check folder permissions.")
                    }.show()
            }
        }.show()
    }

    private fun renameBook(book: Book) {
        val bn = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_language", "en") == "bn"
        val input = EditText(this).apply { setText(book.name); selectAll() }
        AlertDialog.Builder(this).setTitle(if (bn) "নাম পরিবর্তন" else "Rename PDF")
            .setView(input)
            .setNegativeButton(if (bn) "বাতিল" else "Cancel", null)
            .setPositiveButton(if (bn) "সংরক্ষণ" else "Save") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                if (!name.endsWith(".pdf", true)) name += ".pdf"
                val success = try { DocumentFile.fromSingleUri(this, book.uri)?.renameTo(name) == true } catch (_: Exception) { false }
                if (success) { forgetRecentBook(book.uri); refreshLibrary() } else toast(if (bn) "ফাইলের নাম বদলাতে অনুমতি নেই।" else "Android does not allow renaming this file. Check folder permissions.")
            }.show()
    }

    private fun forgetRecentBook(uri: Uri) {
        val recent = getSharedPreferences("kontho_library", MODE_PRIVATE)
            .getStringSet("recent_books", emptySet())?.toMutableSet() ?: mutableSetOf()
        recent.remove(uri.toString())
        getSharedPreferences("kontho_library", MODE_PRIVATE).edit().putStringSet("recent_books", recent).apply()
    }

    private fun rememberRecentBook(book: Book) {
        val key = book.uri.toString()
        val recent = preferences.getStringSet(KEY_RECENT_BOOKS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        recent.add(key)
        preferences.edit()
            .putStringSet(KEY_RECENT_BOOKS, recent)
            .putString(recentNameKey(book.uri), book.name)
            .apply()
    }

    private fun displayName(uri: Uri): String {
        var result: String? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) result = cursor.getString(column)
            }
        } catch (_: Exception) {
            // Fall back to the URI's final segment below.
        } finally {
            cursor?.close()
        }
        return result ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled book.pdf"
    }

    private fun coverPageFor(uri: Uri): Int = preferences.getInt(coverPageKey(uri), 0).coerceAtLeast(0)

    private fun coverPageKey(uri: Uri) = "cover_page_${uri.toString().hashCode()}"
    private fun recentNameKey(uri: Uri) = "recent_name_${uri.toString().hashCode()}"

    private fun closeCurrentRenderer() {
        try {
            pdfRenderer?.close()
        } catch (_: Exception) {
        }
        pdfRenderer = null
        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
        }
        fileDescriptor = null
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { action() }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    override fun onDestroy() {
        readerGeneration++
        mainHandler.removeCallbacks(hideReaderOverlaysRunnable)
        progressAnimator?.cancel()
        progressAnimator = null
        tts?.stop()
        tts?.shutdown()
        releaseReaderBitmaps()
        worker.execute { closeCurrentRenderer() }
        worker.shutdown()
        thumbnailWorker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!showingLibrary) {
            showLibrary()
            if (pendingFolderScan) {
                pendingFolderScan = false
                refreshLibrary()
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
    }

    companion object {
        private const val KEY_BOOK_FOLDER = "book_folder_uri"
        private const val KEY_RECENT_BOOKS = "recent_books"
    }
}
