package org.kontho.pdfreader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val worker = Executors.newSingleThreadExecutor()
    private var pdfUri: Uri? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageCount = 0
    private var pageText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pageImage: ImageView
    private lateinit var extractedText: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var readButton: Button
    private lateinit var stopButton: Button
    private lateinit var progress: ProgressBar

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openDocument(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        tts = TextToSpeech(this, this)
        buildScreen()
        status.text = "Open a PDF to begin. Text-based Bangla PDFs work best."
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 250, 249))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val title = TextView(this).apply {
            text = "Kontho PDF Reader"
            textSize = 24f
            setTextColor(Color.rgb(18, 76, 66))
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Read Bangla books aloud"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(subtitle, matchWrap())

        root.addView(actionButton("Open PDF") {
            openPdf.launch(arrayOf("application/pdf"))
        }, matchWrap())

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status, matchWrap())

        progress = ProgressBar(this).apply { visibility = android.view.View.GONE }
        root.addView(progress, matchWrap())

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previousButton = actionButton("Previous") { showPage(currentPage - 1) }
        nextButton = actionButton("Next") { showPage(currentPage + 1) }
        navigation.addView(previousButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(6)
        })
        navigation.addView(nextButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(navigation, matchWrap())

        pageLabel = TextView(this).apply {
            text = "No PDF opened"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(pageLabel, matchWrap())

        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = "PDF page preview"
        }
        root.addView(pageImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        val speechActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        readButton = actionButton("Read page") { readCurrentPage() }
        stopButton = actionButton("Stop") { tts?.stop() }
        speechActions.addView(readButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(6)
        })
        speechActions.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(speechActions, matchWrap())

        extractedText = TextView(this).apply {
            text = "Extracted page text will appear here."
            textSize = 16f
            setTextColor(Color.rgb(35, 42, 40))
            setPadding(dp(8), dp(12), dp(8), dp(12))
            textIsSelectable = true
        }
        val textScroll = ScrollView(this).apply {
            addView(extractedText)
        }
        root.addView(textScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
        ))

        setContentView(root)
        updateNavigation()
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun openDocument(uri: Uri) {
        status.text = "Opening PDF…"
        progress.visibility = android.view.View.VISIBLE
        tts?.stop()
        pdfUri = uri

        try {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        } catch (_: IllegalArgumentException) {
            // Continue with the temporary read permission if necessary.
        }

        worker.execute {
            try {
                pdfRenderer?.close()
                fileDescriptor?.close()
                fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Could not open this PDF.")
                pdfRenderer = PdfRenderer(fileDescriptor!!)
                pageCount = pdfRenderer!!.pageCount
                currentPage = 0
                runOnUiThread {
                    status.text = "PDF opened. Preparing page 1…"
                    updateNavigation()
                }
                renderAndExtractPage(0)
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    status.text = "Could not open PDF: ${e.localizedMessage ?: "unknown error"}"
                    toast("Try a different PDF file.")
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        currentPage = index
        tts?.stop()
        progress.visibility = android.view.View.VISIBLE
        pageLabel.text = "Loading page ${index + 1} of $pageCount…"
        updateNavigation()
        worker.execute { renderAndExtractPage(index) }
    }

    private fun renderAndExtractPage(index: Int) {
        try {
            val renderer = pdfRenderer ?: throw IOException("No PDF is open.")
            val page = renderer.openPage(index)
            val width = 1000
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val text = extractText(index + 1)
            runOnUiThread {
                if (index == currentPage) {
                    pageImage.setImageBitmap(bitmap)
                    pageText = text.trim()
                    extractedText.text = if (pageText.isBlank()) {
                        "No selectable text found on this page. It may be a scanned image."
                    } else pageText
                    pageLabel.text = "Page ${index + 1} of $pageCount"
                    status.text = if (pageText.isBlank()) {
                        "Page displayed. No text found for speech."
                    } else {
                        "Ready to read this page aloud."
                    }
                    progress.visibility = android.view.View.GONE
                    updateNavigation()
                } else {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                progress.visibility = android.view.View.GONE
                status.text = "Could not read page: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }

    private fun extractText(pageNumber: Int): String {
        val uri = pdfUri ?: return ""
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

    private fun readCurrentPage() {
        if (pageText.isBlank()) {
            toast("No text to read on this page. Scanned PDFs need OCR.")
            return
        }
        if (!ttsReady) {
            toast("Speech engine is still starting. Please try again.")
            return
        }
        val language = tts?.setLanguage(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (language == TextToSpeech.LANG_NOT_SUPPORTED || language == TextToSpeech.LANG_MISSING_DATA) {
            status.text = "Your installed speech engine does not support Bangla. Install a Bangla-capable TTS engine to test reading."
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
        previousButton.isEnabled = currentPage > 0
        nextButton.isEnabled = pageCount > 0 && currentPage < pageCount - 1
        readButton.isEnabled = pageCount > 0
        pageLabel.text = if (pageCount > 0) "Page ${currentPage + 1} of $pageCount" else "No PDF opened"
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setLanguage(Locale("bn", "BD"))
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
        worker.execute {
            try {
                pdfRenderer?.close()
                fileDescriptor?.close()
            } catch (_: Exception) {
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
