package org.kontho.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * A deliberately small PDF reader: open a PDF, view its pages, and move
 * backward/forward. Rendering is done off the UI thread with PdfRenderer.
 */
class MainActivity : ComponentActivity() {
    private val pdfWorker = Executors.newSingleThreadExecutor()
    private val documentGeneration = AtomicInteger(0)
    private val renderRequest = AtomicInteger(0)

    // These PDF objects are accessed only on pdfWorker.
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    // These fields are used on the main thread.
    private var currentPage = 0
    private var pageCount = 0
    private var currentFileName = "No PDF opened"

    private lateinit var fileNameLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pageImage: ImageView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) openPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 76, 66)
        window.navigationBarColor = Color.rgb(18, 76, 66)
        buildScreen()
        updatePageControls()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setBackgroundColor(Color.rgb(246, 248, 247))
        }

        val heading = TextView(this).apply {
            text = "Kontho PDF Reader"
            textSize = 23f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(18, 76, 66))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        root.addView(heading, matchWrap())

        val openButton = Button(this).apply {
            text = "Open PDF"
            isAllCaps = false
            setOnClickListener {
                openDocument.launch(arrayOf("application/pdf"))
            }
        }
        root.addView(openButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))

        fileNameLabel = TextView(this).apply {
            text = currentFileName
            textSize = 14f
            setTextColor(Color.rgb(45, 57, 53))
            maxLines = 2
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
        root.addView(fileNameLabel, matchWrap())

        statusLabel = TextView(this).apply {
            text = "Open a PDF to start reading."
            textSize = 13f
            setTextColor(Color.rgb(90, 103, 98))
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        root.addView(statusLabel, matchWrap())

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        previousButton = Button(this).apply {
            text = "← Previous"
            isAllCaps = false
            setOnClickListener { showPage(currentPage - 1) }
        }
        nextButton = Button(this).apply {
            text = "Next →"
            isAllCaps = false
            setOnClickListener { showPage(currentPage + 1) }
        }
        pageLabel = TextView(this).apply {
            text = "0 / 0"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 76, 66))
        }

        navigation.addView(previousButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        navigation.addView(pageLabel, LinearLayout.LayoutParams(0, dp(46), 0.8f))
        navigation.addView(nextButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(navigation, matchWrap())

        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = "PDF page"
        }

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(6))
            addView(pageImage, ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(pageScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun openPdf(uri: Uri) {
        val generation = documentGeneration.incrementAndGet()
        renderRequest.incrementAndGet()

        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // This document provider may grant access only for this session.
        }

        currentPage = 0
        pageCount = 0
        pageImage.setImageDrawable(null)
        currentFileName = getDisplayName(uri)
        fileNameLabel.text = currentFileName
        statusLabel.text = "Opening PDF…"
        updatePageControls()

        pdfWorker.execute {
            closeCurrentDocument()
            var openedDescriptor: ParcelFileDescriptor? = null
            var openedRenderer: PdfRenderer? = null
            try {
                openedDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Android could not open this PDF.")
                openedRenderer = PdfRenderer(openedDescriptor)
                val count = openedRenderer.pageCount
                if (count < 1) throw IOException("This PDF has no pages.")

                if (generation != documentGeneration.get()) {
                    openedRenderer.close()
                    openedDescriptor.close()
                    return@execute
                }

                fileDescriptor = openedDescriptor
                pdfRenderer = openedRenderer
                openedDescriptor = null
                openedRenderer = null

                runOnUiThread {
                    if (generation != documentGeneration.get()) return@runOnUiThread
                    pageCount = count
                    currentPage = 0
                    statusLabel.text = "PDF ready."
                    updatePageControls()
                    renderPage(0, generation)
                }
            } catch (error: Exception) {
                try { openedRenderer?.close() } catch (_: Exception) { }
                try { openedDescriptor?.close() } catch (_: Exception) { }
                runOnUiThread {
                    if (generation == documentGeneration.get()) {
                        statusLabel.text = "Could not open PDF: ${error.localizedMessage ?: "unknown error"}"
                        Toast.makeText(this, statusLabel.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        currentPage = index
        updatePageControls()
        statusLabel.text = "Loading page ${index + 1}…"
        renderPage(index, documentGeneration.get())
    }

    private fun renderPage(index: Int, generation: Int) {
        val request = renderRequest.incrementAndGet()
        pdfWorker.execute {
            if (request != renderRequest.get() || generation != documentGeneration.get()) {
                return@execute
            }

            var bitmap: Bitmap? = null
            try {
                val renderer = pdfRenderer ?: throw IOException("No PDF is open.")
                val page = renderer.openPage(index)
                try {
                    // Keep the bitmap large enough to read while limiting memory use.
                    val scale = minOf(
                        1200f / page.width.toFloat(),
                        1800f / page.height.toFloat()
                    )
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = pageBitmap
                    pageBitmap.eraseColor(Color.WHITE)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }

                val result = bitmap
                runOnUiThread {
                    if (generation == documentGeneration.get() &&
                        request == renderRequest.get() &&
                        currentPage == index
                    ) {
                        pageImage.setImageBitmap(result)
                        statusLabel.text = "Page ${index + 1} of $pageCount"
                    } else {
                        result?.recycle()
                    }
                }
            } catch (error: Exception) {
                bitmap?.recycle()
                runOnUiThread {
                    if (generation == documentGeneration.get() &&
                        request == renderRequest.get()
                    ) {
                        statusLabel.text = "Could not render page: ${error.localizedMessage ?: "unknown error"}"
                    }
                }
            }
        }
    }

    private fun updatePageControls() {
        if (!::previousButton.isInitialized) return
        pageLabel.text = if (pageCount > 0) "${currentPage + 1} / $pageCount" else "0 / 0"
        previousButton.isEnabled = pageCount > 0 && currentPage > 0
        nextButton.isEnabled = pageCount > 0 && currentPage < pageCount - 1
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
        }
    }

    private fun closeCurrentDocument() {
        try { pdfRenderer?.close() } catch (_: Exception) { }
        pdfRenderer = null
        try { fileDescriptor?.close() } catch (_: Exception) { }
        fileDescriptor = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    override fun onDestroy() {
        documentGeneration.incrementAndGet()
        renderRequest.incrementAndGet()
        pdfWorker.execute { closeCurrentDocument() }
        pdfWorker.shutdown()
        super.onDestroy()
    }
}
