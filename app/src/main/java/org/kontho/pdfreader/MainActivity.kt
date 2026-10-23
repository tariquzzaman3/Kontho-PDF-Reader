package org.kontho.pdfreader

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

private data class Book(val uri: Uri, val name: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnailWorker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("kontho_library", MODE_PRIVATE) }

    private var books: List<Book> = emptyList()
    private var currentBook: Book? = null
    private var pdfUri: Uri? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageCount = 0
    private var pageText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var showingLibrary = true
    private var settingsSnapshot = ""

    private lateinit var libraryStatus: TextView
    private lateinit var libraryGrid: GridLayout
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pageImage: ImageView
    private lateinit var extractedText: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var readButton: Button
    private lateinit var stopButton: Button
    private lateinit var coverButton: Button
    private lateinit var progress: ProgressBar

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keepReadPermission(uri)
            val book = Book(uri, displayName(uri))
            rememberRecentBook(book)
            openBook(book)
        }
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
        showLibrary()
        refreshLibrary()
        settingsSnapshot = currentSettingsSnapshot()
        if (!getSharedPreferences("kontho_library", MODE_PRIVATE).getBoolean("setup_done", false)) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("first_run", true))
        }
    }

    private fun currentSettingsSnapshot(): String {
        val p = getSharedPreferences("kontho_library", MODE_PRIVATE)
        return listOf("app_theme", "app_language", "library_view", "grid_columns", "sort_field", "sort_descending")
            .joinToString("|") { key -> p.all[key]?.toString() ?: "" }
    }

    override fun onResume() {
        super.onResume()
        val snapshot = currentSettingsSnapshot()
        if (settingsSnapshot.isNotEmpty() && snapshot != settingsSnapshot) {
            recreate()
            return
        }
        if (snapshot.isNotEmpty()) settingsSnapshot = snapshot
        if (::libraryGrid.isInitialized && showingLibrary) refreshLibrary()
    }

    private fun showLibrary() {
        showingLibrary = true
        currentBook = null
        tts?.stop()
        worker.execute { closeCurrentRenderer() }

        val prefs = getSharedPreferences("kontho_library", MODE_PRIVATE)
        val dark = when (prefs.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val appLanguage = prefs.getString("app_language", "en") == "bn"
        fun label(en: String, bn: String) = if (appLanguage) bn else en
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) Color.rgb(25, 31, 29) else Color.rgb(248, 250, 249))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val title = TextView(this).apply {
            text = "Kontho PDF Reader"
            textSize = 24f
            setTextColor(if (dark) Color.rgb(175, 225, 210) else Color.rgb(18, 76, 66))
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = label("Your books · Read Bangla books aloud", "আপনার বই · বাংলা বই শুনুন")
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(subtitle, matchWrap())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val folderButton = actionButton(label("Scan books folder", "বইয়ের ফোল্ডার স্ক্যান")) {
            chooseBookFolder.launch(null)
        }
        val singlePdfButton = actionButton(label("Open one PDF", "একটি PDF খুলুন")) {
            openPdf.launch(arrayOf("application/pdf"))
        }
        actions.addView(folderButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(5)
        })
        actions.addView(singlePdfButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
        })
        root.addView(actions, matchWrap())
        root.addView(actionButton(label("Settings ⚙", "সেটিংস ⚙")) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, matchWrap())

        libraryStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(libraryStatus, matchWrap())

        libraryGrid = GridLayout(this).apply {
            columnCount = if (prefs.getString("library_view", "grid") == "list") 1 else prefs.getInt("grid_columns", 2).coerceIn(2, 4)
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(libraryGrid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        renderBookGrid()
    }

    private fun refreshLibrary() {
        if (!::libraryStatus.isInitialized || !showingLibrary) return
        libraryStatus.text = "Finding PDF books…"
        worker.execute {
            val found = LinkedHashMap<String, Book>()

            // Add recently opened PDFs too, so individually opened books remain easy to find.
            val recentUris = preferences.getStringSet(KEY_RECENT_BOOKS, emptySet())?.toList().orEmpty()
            recentUris.forEach { value ->
                try {
                    val uri = Uri.parse(value)
                    val name = preferences.getString(recentNameKey(uri), null) ?: displayName(uri)
                    found[value] = Book(uri, name)
                } catch (_: Exception) {
                    // Ignore old or inaccessible recent entries.
                }
            }

            val folderValue = preferences.getString(KEY_BOOK_FOLDER, null)
            if (!folderValue.isNullOrBlank()) {
                try {
                    val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderValue))
                    if (folder != null && folder.canRead()) {
                        collectPdfBooks(folder, found, 0)
                    }
                } catch (_: Exception) {
                    // The user can select the folder again if its access was revoked.
                }
            }

            val prefs = getSharedPreferences("kontho_library", MODE_PRIVATE)
            val sortField = prefs.getString("sort_field", "name") ?: "name"
            val descending = prefs.getBoolean("sort_descending", false)
            val comparator = if (sortField == "date") compareBy<Book> { modifiedFor(it.uri) }
                else compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            val sorted = if (descending) found.values.sortedWith(comparator.reversed())
                else found.values.sortedWith(comparator)
            runOnUiThread {
                if (!showingLibrary) return@runOnUiThread
                books = sorted
                renderBookGrid()
                libraryStatus.text = when {
                    books.isEmpty() && folderValue.isNullOrBlank() ->
                        "Choose the folder containing your books. You only need to grant access once."
                    books.isEmpty() ->
                        "No PDFs found in that folder. Choose a folder that contains your books, or open one PDF."
                    else -> "${books.size} PDF book${if (books.size == 1) "" else "s"} found"
                }
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
            val empty = TextView(this).apply {
                text = "Your books will appear here. Choose a folder to find PDFs automatically."
                textSize = 15f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(32), dp(24), dp(32))
            }
            libraryGrid.addView(empty, GridLayout.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 2)
            })
            return
        }

        books.forEach { book ->
            val card = LinearLayout(this).apply {
                orientation = if (getSharedPreferences("kontho_library", MODE_PRIVATE).getString("library_view", "grid") == "list") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    val darkCard = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_theme", "system") == "dark"
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

            val cover = ImageView(this).apply {
                tag = book.uri.toString()
                setBackgroundColor(Color.rgb(238, 242, 240))
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                contentDescription = "Cover of ${book.name}"
            }
            val listMode = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("library_view", "grid") == "list"
            card.addView(cover, if (listMode) LinearLayout.LayoutParams(dp(64), dp(88)).apply { marginEnd = dp(10) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))

            val title = TextView(this).apply {
                text = book.name.removeSuffixIgnoreCase(".pdf")
                textSize = 14f
                setTextColor(Color.rgb(28, 43, 39))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(8), dp(2), 0)
            }
            card.addView(title, matchWrap())

            val coverPageLabel = TextView(this).apply {
                text = "Cover: page ${coverPageFor(book.uri) + 1}"
                textSize = 12f
                setTextColor(Color.rgb(89, 108, 101))
                setPadding(dp(2), dp(3), dp(2), dp(2))
            }
            card.addView(coverPageLabel, matchWrap())

            if (listMode) {
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title, matchWrap())
                    addView(coverPageLabel, matchWrap())
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
                card.addView(coverPageLabel, matchWrap())
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(8))
                }
                libraryGrid.addView(card, params)
            }
            loadCoverThumbnail(book, cover, coverPageLabel)
        }
    }

    private fun loadCoverThumbnail(book: Book, image: ImageView, label: TextView) {
        thumbnailWorker.execute {
            var bitmap: Bitmap? = null
            try {
                contentResolver.openFileDescriptor(book.uri, "r")?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount > 0) {
                            val requestedPage = coverPageFor(book.uri).coerceIn(0, renderer.pageCount - 1)
                            renderer.openPage(requestedPage).use { page ->
                                val width = 360
                                val height = (width.toFloat() * page.height / page.width)
                                    .toInt().coerceAtLeast(1).coerceAtMost(520)
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
                if (result != null && showingLibrary && image.tag == book.uri.toString()) {
                    image.setImageBitmap(result)
                    label.text = "Cover: page ${coverPageFor(book.uri) + 1}"
                } else if (result != null) {
                    result.recycle()
                }
            }
        }
    }

    private fun openBook(book: Book) {
        showingLibrary = false
        currentBook = book
        pdfUri = book.uri
        currentPage = 0
        pageCount = 0
        pageText = ""
        tts?.stop()
        buildReaderScreen()
        status.text = "Opening ${book.name}…"
        progress.visibility = View.VISIBLE

        worker.execute {
            try {
                closeCurrentRenderer()
                fileDescriptor = contentResolver.openFileDescriptor(book.uri, "r")
                    ?: throw IOException("Could not open this PDF.")
                pdfRenderer = PdfRenderer(fileDescriptor!!)
                pageCount = pdfRenderer!!.pageCount
                currentPage = 0
                runOnUiThread {
                    if (currentBook?.uri == book.uri && !showingLibrary) {
                        status.text = "Preparing page 1…"
                        updateNavigation()
                    }
                }
                if (pageCount > 0) renderAndExtractPage(0, book.uri)
                else throw IOException("This PDF has no pages.")
            } catch (e: Exception) {
                runOnUiThread {
                    if (currentBook?.uri == book.uri && !showingLibrary) {
                        progress.visibility = View.GONE
                        status.text = "Could not open PDF: ${e.localizedMessage ?: "unknown error"}"
                        toast("Try another PDF or check file access.")
                    }
                }
            }
        }
    }

    private fun buildReaderScreen() {
        val appLanguageReader = getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_language", "en") == "bn"
        fun readerLabel(en: String, bn: String) = if (appLanguageReader) bn else en
        val darkReader = when (getSharedPreferences("kontho_library", MODE_PRIVATE).getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (darkReader) Color.rgb(25, 31, 29) else Color.rgb(248, 250, 249))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = actionButton(readerLabel("← Library", "← লাইব্রেরি")) { showLibrary(); refreshLibrary() }
        topRow.addView(backButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)
        ))
        val readerTitle = TextView(this).apply {
            text = currentBook?.name ?: readerLabel("PDF Reader", "PDF রিডার")
            textSize = 16f
            setTextColor(if (darkReader) Color.rgb(175, 225, 210) else Color.rgb(18, 76, 66))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(readerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topRow, matchWrap())

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(if (darkReader) Color.LTGRAY else Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(4))
        }
        root.addView(status, matchWrap())

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, matchWrap())

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previousButton = actionButton(readerLabel("Previous page", "আগের পৃষ্ঠা")) { showPage(currentPage - 1) }
        nextButton = actionButton(readerLabel("Next page", "পরের পৃষ্ঠা")) { showPage(currentPage + 1) }
        navigation.addView(previousButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(4)
        })
        navigation.addView(nextButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(4)
        })
        root.addView(navigation, matchWrap())

        pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(if (darkReader) Color.LTGRAY else Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(4))
        }
        pageLabel.setOnClickListener { showGoToPageDialog() }
        pageLabel.contentDescription = "Tap to go to a page"
        root.addView(pageLabel, matchWrap())

        val coverActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        coverButton = actionButton(readerLabel("Use this page as cover", "এই পৃষ্ঠাটি কভার করুন")) { saveCurrentPageAsCover() }
        val chooseCoverButton = actionButton(readerLabel("Choose page…", "পৃষ্ঠা বাছুন…")) { showCoverPageDialog() }
        coverActions.addView(coverButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(4)
        })
        coverActions.addView(chooseCoverButton, LinearLayout.LayoutParams(0, dp(44), 0.72f).apply {
            marginStart = dp(4)
        })
        root.addView(coverActions, matchWrap())

        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(if (darkReader) Color.rgb(39, 47, 44) else Color.WHITE)
            contentDescription = "PDF page preview"
        }
        root.addView(pageImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        val speechActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        readButton = actionButton(readerLabel("Read page aloud", "জোরে পড়ুন")) { readCurrentPage() }
        stopButton = actionButton(readerLabel("Stop", "থামুন")) { tts?.stop() }
        speechActions.addView(readButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(4)
        })
        speechActions.addView(stopButton, LinearLayout.LayoutParams(0, dp(44), 0.65f).apply {
            marginStart = dp(4)
        })
        root.addView(speechActions, matchWrap())

        extractedText = TextView(this).apply {
            text = readerLabel("Page text will appear here.", "এখানে পৃষ্ঠার লেখা দেখা যাবে।")
            textSize = 16f
            setTextColor(if (darkReader) Color.WHITE else Color.rgb(35, 42, 40))
            setBackgroundColor(if (darkReader) Color.rgb(25, 31, 29) else Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isTextSelectable = true
        }
        val textScroll = ScrollView(this).apply { addView(extractedText) }
        root.addView(textScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))

        setContentView(root)
        updateNavigation()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        currentPage = index
        tts?.stop()
        progress.visibility = View.VISIBLE
        pageLabel.text = "Loading page ${index + 1} of $pageCount…"
        updateNavigation()
        val uri = currentBook?.uri ?: return
        worker.execute { renderAndExtractPage(index, uri) }
    }

    private fun renderAndExtractPage(index: Int, uri: Uri) {
        var bitmap: Bitmap? = null
        try {
            val renderer = pdfRenderer ?: throw IOException("No PDF is open.")
            val page = renderer.openPage(index)
            try {
                val width = 900
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = pageBitmap
                pageBitmap.eraseColor(Color.WHITE)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }

            val text = extractText(uri, index + 1)
            val renderedBitmap = bitmap
            runOnUiThread {
                if (currentBook?.uri == uri && currentPage == index && !showingLibrary) {
                    pageImage.setImageBitmap(renderedBitmap)
                    pageText = text.trim()
                    extractedText.text = if (pageText.isBlank()) {
                        "No selectable text found. This may be a scanned PDF; OCR is not included yet."
                    } else pageText
                    pageLabel.text = "Page ${index + 1} of $pageCount  ·  Tap here to jump"
                    status.text = if (pageText.isBlank()) {
                        "Page displayed, but no selectable text was found."
                    } else {
                        "Ready to read this page aloud."
                    }
                    progress.visibility = View.GONE
                    updateNavigation()
                } else {
                    renderedBitmap?.recycle()
                }
            }
        } catch (e: Exception) {
            bitmap?.recycle()
            runOnUiThread {
                if (currentBook?.uri == uri && !showingLibrary) {
                    progress.visibility = View.GONE
                    status.text = "Could not read page: ${e.localizedMessage ?: "unknown error"}"
                }
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
        status.text = "Saved page ${currentPage + 1} as this book's cover. Return to Library to see it."
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
                    status.text = "Saved PDF page $page as the book cover."
                    toast("Book cover updated to page $page.")
                }
            }
            .show()
    }

    private fun showGoToPageDialog() {
        if (pageCount <= 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((currentPage + 1).toString())
            selectAll()
            hint = "Page number"
        }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setMessage("Enter a page number from 1 to $pageCount.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go") { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..pageCount) {
                    toast("Enter a page from 1 to $pageCount.")
                } else {
                    showPage(page - 1)
                }
            }
            .show()
    }

    private fun readCurrentPage() {
        if (pageText.isBlank()) {
            toast("No selectable text on this page. Scanned PDFs need OCR.")
            return
        }
        if (!ttsReady) {
            toast("Speech engine is still starting. Please try again.")
            return
        }
        val language = tts?.setLanguage(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (language == TextToSpeech.LANG_NOT_SUPPORTED || language == TextToSpeech.LANG_MISSING_DATA) {
            status.text = "The installed speech engine does not support Bangla. Install a Bangla-capable TTS engine to test reading."
            toast("Bangla speech is not available in the current TTS engine.")
            return
        }
        status.text = "Reading page aloud…"
        val result = tts?.speak(pageText, TextToSpeech.QUEUE_FLUSH, null, "kontho-page-${currentPage + 1}")
        if (result == TextToSpeech.ERROR) {
            status.text = "Speech could not start. Check your TTS engine settings."
        }
    }

    private fun updateNavigation() {
        if (!::previousButton.isInitialized) return
        previousButton.isEnabled = currentPage > 0
        nextButton.isEnabled = pageCount > 0 && currentPage < pageCount - 1
        readButton.isEnabled = pageCount > 0 && ttsReady
        coverButton.isEnabled = pageCount > 0
        pageLabel.text = if (pageCount > 0) "Page ${currentPage + 1} of $pageCount  ·  Tap here to jump" else "Loading PDF…"
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setLanguage(Locale("bn", "BD"))
            runOnUiThread {
                if (!showingLibrary && ::readButton.isInitialized) updateNavigation()
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
        tts?.stop()
        tts?.shutdown()
        worker.execute { closeCurrentRenderer() }
        worker.shutdown()
        thumbnailWorker.shutdown()
        super.onDestroy()
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
    }

    companion object {
        private const val KEY_BOOK_FOLDER = "book_folder_uri"
        private const val KEY_RECENT_BOOKS = "recent_books"
    }
}
