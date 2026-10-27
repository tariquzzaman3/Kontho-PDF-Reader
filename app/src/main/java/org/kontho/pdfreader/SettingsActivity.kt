package org.kontho.pdfreader

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.net.Uri
import java.io.File

class SettingsActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kontho_library", MODE_PRIVATE) }
    private var firstRun = false
    private var setupStep = 0

    private val chooseBooks = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            prefs.edit().putString("book_folder_uri", uri.toString()).apply()
            Toast.makeText(this, label("Books folder saved", "বইয়ের ফোল্ডার সংরক্ষিত"), Toast.LENGTH_LONG).show()
            render()
        }
    }

    private val chooseCache = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            prefs.edit().putString("cache_folder_uri", uri.toString()).apply()
            Toast.makeText(this, label("Cache folder saved", "ক্যাশ ফোল্ডার সংরক্ষিত"), Toast.LENGTH_LONG).show()
            render()
        }
    }

    private val chooseSetupBooks = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            prefs.edit()
                .putString("book_folder_uri", uri.toString())
                .putBoolean("setup_done", true)
                .apply()
            finish()
        } else {
            renderSetupStep()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        firstRun = intent.getBooleanExtra("first_run", false)
        val theme = prefs.getString("app_theme", "system")
        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
        if (firstRun && !prefs.getBoolean("setup_done", false)) {
            renderSetupStep()
        } else {
            render()
        }
    }

    /**
     * Four short onboarding screens: language, appearance, library layout,
     * and PDF folder. The regular Settings screen remains available afterward.
     */
    private fun renderSetupStep() {
        val language = prefs.getString("app_language", "en") == "bn"
        val dark = when (prefs.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val screenBackground = if (dark) Color.rgb(22, 29, 27) else Color.rgb(248, 250, 249)
        val foreground = if (dark) Color.WHITE else Color.rgb(27, 48, 42)
        val muted = if (dark) Color.rgb(187, 201, 195) else Color.rgb(99, 117, 109)
        val accent = Color.rgb(18, 104, 84)

        window.statusBarColor = if (dark) Color.rgb(20, 28, 26) else Color.rgb(18, 76, 66)
        window.navigationBarColor = window.statusBarColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(14))
            setBackgroundColor(screenBackground)
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.kontho_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "কণ্ঠ · Kontho PDF Reader logo"
        }
        content.addView(logo, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
            bottomMargin = dp(10)
        })

        val brand = TextView(this).apply {
            text = if (language) "বাংলা বই, আপনার কণ্ঠে" else "Bangla books, brought to life"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }
        content.addView(brand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) })

        val stepTitle = TextView(this).apply {
            text = when (setupStep) {
                0 -> if (language) "ভাষা নির্বাচন করুন" else "Choose your language"
                1 -> if (language) "আপনার থিম বেছে নিন" else "Choose your theme"
                2 -> if (language) "বই কীভাবে দেখাব?" else "Choose your library layout"
                else -> if (language) "PDF বইয়ের ফোল্ডার" else "Choose your PDF folder"
            }
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(foreground)
            setPadding(0, 0, 0, dp(6))
        }
        content.addView(stepTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val helper = TextView(this).apply {
            text = when (setupStep) {
                0 -> if (language) "শুরু করার জন্য ভাষা বেছে নিন" else "Pick the language you want to use"
                1 -> if (language) "পরে সেটিংস থেকে বদলাতে পারবেন" else "You can change this later in Settings"
                2 -> if (language) "বইয়ের তালিকা বা কভার গ্রিড" else "A simple list or a book-cover grid"
                else -> if (language) "শুধু নির্বাচিত ফোল্ডারেই বই খোঁজা হবে" else "Kontho will scan this folder and its subfolders"
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(4), 0, dp(4), dp(18))
        }
        content.addView(helper, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fun cardBackground(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) {
                if (dark) Color.rgb(32, 67, 57) else Color.rgb(224, 242, 235)
            } else {
                if (dark) Color.rgb(37, 45, 42) else Color.WHITE
            })
            setStroke(dp(if (selected) 2 else 1), if (selected) accent else if (dark) Color.rgb(70, 83, 77) else Color.rgb(222, 230, 226))
        }

        fun choiceCard(symbol: String, title: String, selected: Boolean, action: () -> Unit): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
                background = cardBackground(selected)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val icon = TextView(this).apply {
                text = symbol
                textSize = 36f
                gravity = Gravity.CENTER
                setTextColor(foreground)
            }
            card.addView(icon, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ))
            val labelView = TextView(this).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(foreground)
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(labelView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return card
        }

        fun addChoiceRow(cards: List<View>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            cards.forEachIndexed { index, view ->
                row.addView(view, LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                    if (index > 0) marginStart = dp(8)
                    if (index < cards.lastIndex) marginEnd = dp(8)
                })
            }
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        when (setupStep) {
            0 -> {
                val languageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val banglaCard = choiceCard("🇧🇩", "বাংলা", language) {
                    prefs.edit().putString("app_language", "bn").apply()
                    setupStep = 1
                    renderSetupStep()
                }
                val englishCard = choiceCard("🇬🇧", "English", !language) {
                    prefs.edit().putString("app_language", "en").apply()
                    setupStep = 1
                    renderSetupStep()
                }
                languageRow.addView(banglaCard, LinearLayout.LayoutParams(0, dp(150), 1f).apply { marginEnd = dp(6) })
                languageRow.addView(englishCard, LinearLayout.LayoutParams(0, dp(150), 1f).apply { marginStart = dp(6) })
                content.addView(languageRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                val caption = TextView(this).apply {
                    text = if (language) "ভাষা" else "Language"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, dp(18))
                }
                content.addView(caption, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            1 -> {
                val theme = prefs.getString("app_theme", "system") ?: "system"
                addChoiceRow(listOf(
                    choiceCard("◐", label("System", "সিস্টেম"), theme == "system") {
                        prefs.edit().putString("app_theme", "system").apply()
                        setupStep = 2
                        renderSetupStep()
                    },
                    choiceCard("☀", label("Light", "লাইট"), theme == "light") {
                        prefs.edit().putString("app_theme", "light").apply()
                        setupStep = 2
                        renderSetupStep()
                    },
                    choiceCard("☾", label("Dark", "ডার্ক"), theme == "dark") {
                        prefs.edit().putString("app_theme", "dark").apply()
                        setupStep = 2
                        renderSetupStep()
                    }
                ))
                val caption = TextView(this).apply {
                    text = label("Theme", "থিম")
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                }
                content.addView(caption, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            2 -> {
                val viewMode = prefs.getString("library_view", "grid") ?: "grid"
                addChoiceRow(listOf(
                    choiceCard("☷", label("List", "তালিকা"), viewMode == "list") {
                        prefs.edit().putString("library_view", "list").apply()
                        setupStep = 3
                        renderSetupStep()
                    },
                    choiceCard("▦", label("Grid", "গ্রিড"), viewMode == "grid") {
                        prefs.edit().putString("library_view", "grid").apply()
                        setupStep = 3
                        renderSetupStep()
                    }
                ))
                val caption = TextView(this).apply {
                    text = label("Library layout", "বই দেখানোর ধরন")
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                }
                content.addView(caption, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            else -> {
                val selectedFolder = prefs.getString("book_folder_uri", null)
                val folderCard = choiceCard(
                    if (selectedFolder.isNullOrBlank()) "📁" else "✓",
                    if (selectedFolder.isNullOrBlank()) label("Choose PDF folder", "PDF ফোল্ডার বাছুন") else label("Folder selected", "ফোল্ডার নির্বাচিত"),
                    !selectedFolder.isNullOrBlank()
                ) {
                    chooseSetupBooks.launch(null)
                }
                content.addView(folderCard, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(130)
                ).apply { bottomMargin = dp(10) })
                val caption = TextView(this).apply {
                    text = label("PDF folder", "PDF ফোল্ডার")
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                    setPadding(0, dp(6), 0, dp(12))
                }
                content.addView(caption, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val currentStepText = if (language) {
            listOf("০", "১", "২", "৩", "৪")[setupStep + 1]
        } else {
            (setupStep + 1).toString()
        }
        val totalStepText = if (language) "৪" else "4"
        val progressText = TextView(this).apply {
            text = if (language) "ধাপ $currentStepText / $totalStepText"
                else "Step $currentStepText of $totalStepText"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(progressText, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        installSafeInsets(root, dp(20), dp(12), dp(20), dp(14))
    }

    private fun installSafeInsets(root: View, left: Int, top: Int, right: Int, bottom: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(en: String, bn: String): String = if (prefs.getString("app_language", "en") == "bn") bn else en

    private fun render() {
        val lang = prefs.getString("app_language", "en") ?: "en"
        val dark = when (prefs.getString("app_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        window.statusBarColor = if (dark) Color.rgb(20, 28, 26) else Color.rgb(18, 76, 66)
        window.navigationBarColor = window.statusBarColor
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(if (dark) Color.rgb(25, 31, 29) else Color.rgb(248, 250, 249))
        }
        val title = TextView(this).apply {
            text = label("Settings", "সেটিংস")
            textSize = 26f
            setTextColor(if (dark) Color.WHITE else Color.rgb(18, 76, 66))
            setPadding(0, 0, 0, 12)
        }
        root.addView(title)
        val scroll = android.widget.ScrollView(this)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(options)
        val foreground = if (dark) Color.rgb(244, 248, 246) else Color.rgb(27, 48, 42)
        val muted = if (dark) Color.rgb(174, 190, 182) else Color.rgb(101, 119, 111)
        val cardSurface = if (dark) Color.rgb(35, 44, 40) else Color.WHITE
        val cardStroke = if (dark) Color.rgb(58, 72, 65) else Color.rgb(225, 233, 229)
        val accent = Color.rgb(18, 104, 84)

        fun cardBackground() = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(cardSurface)
            setStroke(dp(1), cardStroke)
        }

        fun addSection(sectionTitle: String) {
            val heading = TextView(this).apply {
                text = sectionTitle
                textSize = 12f
                letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (dark) Color.rgb(142, 198, 177) else accent)
                setPadding(dp(2), dp(15), 0, dp(8))
            }
            options.addView(heading, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun addRow(titleText: String, summary: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(13), dp(13))
                background = cardBackground()
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val heading = TextView(this).apply {
                text = titleText
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(foreground)
            }
            val detail = TextView(this).apply {
                text = summary
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(4), 0, 0)
            }
            textColumn.addView(heading, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            textColumn.addView(detail, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val arrow = TextView(this).apply {
                text = "›"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(8), 0, 0, 0)
            }
            card.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(arrow, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            options.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        fun addToggleRow(titleText: String, summary: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(12), dp(13))
                background = cardBackground()
            }
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val heading = TextView(this).apply {
                text = titleText
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(foreground)
            }
            val detail = TextView(this).apply {
                text = summary
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(4), 0, 0)
            }
            textColumn.addView(heading)
            textColumn.addView(detail)
            val toggle = android.widget.Switch(this).apply {
                isChecked = checked
                contentDescription = titleText
                setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
            }
            card.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(toggle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
            card.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            options.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        addSection(label("GENERAL", "সাধারণ"))
        val language = prefs.getString("app_language", "en") ?: "en"
        addRow(label("App language", "অ্যাপের ভাষা"), if (language == "bn") "বাংলা" else "English") {
            AlertDialog.Builder(this).setTitle(label("App language", "অ্যাপের ভাষা"))
                .setSingleChoiceItems(arrayOf("English", "বাংলা"), if (language == "bn") 1 else 0) { dialog, which ->
                    prefs.edit().putString("app_language", if (which == 1) "bn" else "en").apply()
                    dialog.dismiss(); render()
                }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
        }

        val theme = prefs.getString("app_theme", "system") ?: "system"
        addRow(label("Appearance", "থিম"), when (theme) { "light" -> label("Light", "লাইট"); "dark" -> label("Dark", "ডার্ক"); else -> label("System default", "সিস্টেম ডিফল্ট") }) {
            AlertDialog.Builder(this).setTitle(label("Appearance", "থিম"))
                .setSingleChoiceItems(arrayOf(label("System default", "সিস্টেম ডিফল্ট"), label("Light", "লাইট"), label("Dark", "ডার্ক")), when (theme) { "light" -> 1; "dark" -> 2; else -> 0 }) { dialog, which ->
                    prefs.edit().putString("app_theme", arrayOf("system", "light", "dark")[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
        }

        val view = prefs.getString("library_view", "grid") ?: "grid"
        addRow(label("Library layout", "বই দেখানোর ধরন"), if (view == "list") label("List", "লিস্ট") else label("Grid", "গ্রিড")) {
            AlertDialog.Builder(this).setTitle(label("Library layout", "বই দেখানোর ধরন"))
                .setSingleChoiceItems(arrayOf(label("Grid", "গ্রিড"), label("List", "লিস্ট")), if (view == "list") 1 else 0) { dialog, which ->
                    prefs.edit().putString("library_view", if (which == 1) "list" else "grid").apply()
                    dialog.dismiss(); render()
                }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
        }

        val columns = prefs.getInt("grid_columns", 2).coerceIn(2, 4)
        addRow(label("Grid columns", "গ্রিড কলাম"), "$columns") {
            AlertDialog.Builder(this).setTitle(label("Grid columns", "গ্রিড কলাম"))
                .setSingleChoiceItems(arrayOf("2", "3", "4"), columns - 2) { dialog, which ->
                    prefs.edit().putInt("grid_columns", which + 2).apply()
                    dialog.dismiss(); render()
                }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
        }

        val sort = prefs.getString("sort_field", "name") ?: "name"
        val descending = prefs.getBoolean("sort_descending", false)
        val sortSummary = when (sort) {
            "date" -> if (descending) label("Date · newest first", "তারিখ · নতুন আগে") else label("Date · oldest first", "তারিখ · পুরোনো আগে")
            else -> if (descending) label("Name · descending", "নাম: শেষ থেকে শুরু") else label("Name · ascending", "নাম: শুরু থেকে শেষ")
        }
        addRow(label("Sort books", "বই সাজান"), sortSummary) {
            val items = arrayOf(label("Name · ascending", "নাম: শুরু থেকে শেষ"), label("Name · descending", "নাম: শেষ থেকে শুরু"), label("Date · newest first", "তারিখ · নতুন আগে"), label("Date · oldest first", "তারিখ · পুরোনো আগে"))
            val selected = when (sort) { "date" -> if (descending) 2 else 3; else -> if (descending) 1 else 0 }
            AlertDialog.Builder(this).setTitle(label("Sort books", "বই সাজান")).setSingleChoiceItems(items, selected) { dialog, which ->
                prefs.edit().putString("sort_field", if (which < 2) "name" else "date").putBoolean("sort_descending", which == 1 || which == 2).apply()
                dialog.dismiss(); render()
            }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
        }

        addSection(label("READING", "পাঠ"))
        val narrationRates = floatArrayOf(0.75f, 0.85f, 0.95f, 1.05f)
        val narrationRateLabels = arrayOf(
            label("Slow storyteller (0.75x)", "ধীর গল্পপাঠ (০.৭৫×)"),
            label("Storyteller (0.85x)", "গল্পপাঠ (০.৮৫×)"),
            label("Natural (0.95x)", "স্বাভাবিক (০.৯৫×)"),
            label("Fast (1.05x)", "দ্রুত (১.০৫×)")
        )
        val savedNarrationRate = prefs.getFloat("narration_rate", 0.85f)
        val selectedNarrationRate = narrationRates.indices.minByOrNull {
            kotlin.math.abs(narrationRates[it] - savedNarrationRate)
        } ?: 1
        addRow(label("Narration pace", "পাঠের গতি"), narrationRateLabels[selectedNarrationRate]) {
            AlertDialog.Builder(this).setTitle(label("Narration pace", "পাঠের গতি"))
                .setSingleChoiceItems(narrationRateLabels, selectedNarrationRate) { dialog, which ->
                    prefs.edit().putFloat("narration_rate", narrationRates[which]).apply()
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton(label("Cancel", "বাতিল"), null)
                .show()
        }

        addRow(
            label("Bangla speech voice & engine", "বাংলা কণ্ঠ ও TTS ইঞ্জিন"),
            label(
                "Install Bangla voice data or change the Android speech engine",
                "বাংলা ভয়েস ডেটা ইনস্টল করুন বা Android-এর TTS ইঞ্জিন বদলান"
            )
        ) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        label("Open Android Settings and search for Text-to-speech output.", "Android Settings-এ Text-to-speech output খুঁজুন।"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val cacheLimitChoices = intArrayOf(10, 25, 50, 100, 250, 500, 0)
        val cacheLimitLabels = arrayOf(
            "10 MB", "25 MB", "50 MB", "100 MB", "250 MB", "500 MB",
            label("No limit", "সীমাহীন")
        )
        val savedCacheLimit = prefs.getInt("ocr_cache_limit_mb", 50)
        val selectedCacheLimit = cacheLimitChoices.indexOf(savedCacheLimit).let { if (it >= 0) it else 2 }
        val cachedOcrBytes = ocrCacheUsageBytes()
        val cacheUsageLabel = if (cachedOcrBytes < 1024L * 1024L) {
            "${(cachedOcrBytes / 1024L).coerceAtLeast(0)} KB"
        } else {
            "${cachedOcrBytes / (1024L * 1024L)} MB"
        }
        val cacheLimitSummary = if (savedCacheLimit <= 0) {
            label("$cacheUsageLabel used · No limit", "$cacheUsageLabel ব্যবহৃত · সীমাহীন")
        } else {
            label("$cacheUsageLabel used of $savedCacheLimit MB", "$savedCacheLimit MB-এর মধ্যে $cacheUsageLabel ব্যবহৃত")
        }
        addRow(label("OCR text cache limit", "OCR লেখার ক্যাশ সীমা"), cacheLimitSummary) {
            AlertDialog.Builder(this)
                .setTitle(label("Maximum OCR text cache", "OCR লেখার ক্যাশের সর্বোচ্চ আকার"))
                .setSingleChoiceItems(cacheLimitLabels, selectedCacheLimit) { dialog, which ->
                    val limitMb = cacheLimitChoices[which]
                    prefs.edit().putInt("ocr_cache_limit_mb", limitMb).apply()
                    trimOcrCache(limitMb)
                    dialog.dismiss()
                    Toast.makeText(this, label("OCR cache limit saved", "OCR ক্যাশের সীমা সংরক্ষিত"), Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton(label("Cancel", "বাতিল"), null)
                .show()
        }

        addSection(label("STORAGE", "স্টোরেজ"))
        val booksFolder = prefs.getString("book_folder_uri", null)
        addRow(label("Books folder", "বইয়ের ফোল্ডার"), if (booksFolder == null) label("Not selected", "নির্বাচন করা হয়নি") else label("Selected · scan on app launch", "নির্বাচিত · অ্যাপ খোলার সময় স্ক্যান")) {
            chooseBooks.launch(null)
        }

        addRow(label("About storage access", "স্টোরেজ সম্পর্কে"), label("Books are scanned only in your selected folder. OCR text cache stays in app storage and follows the limit above.", "শুধু আপনার বেছে নেওয়া ফোল্ডারে বই খোঁজা হয়। OCR লেখার ক্যাশ অ্যাপের নিজস্ব স্টোরেজে থাকে এবং উপরের সীমা মেনে চলে।")) {
            AlertDialog.Builder(this).setMessage(label("Kontho scans only the folder you grant access to. Android does not allow unrestricted scanning of every location by default. PDF files remain where you store them, while OCR text cache is managed separately in app storage. Speech is generated by Android text-to-speech; this version does not save generated audio files.", "কণ্ঠ শুধু আপনার অনুমতি দেওয়া ফোল্ডার স্ক্যান করে। Android ডিফল্টভাবে সব জায়গা অবাধে স্ক্যান করতে দেয় না। PDF আপনার নির্বাচিত জায়গাতেই থাকে, আর OCR লেখার ক্যাশ অ্যাপের নিজস্ব স্টোরেজে আলাদাভাবে থাকে। Android-এর টেক্সট-টু-স্পিচ কণ্ঠ তৈরি করে; এই সংস্করণে তৈরি অডিও ফাইল হিসেবে সংরক্ষণ করা হয় না।"))
                .setPositiveButton(label("OK", "ঠিক আছে"), null).show()
        }

        addSection(label("UPDATES & ABOUT", "আপডেট ও পরিচিতি"))
        addRow(label("About Kontho", "কণ্ঠ পরিচিতি"), label("Version 0.6.0 · Bangla PDF reader", "সংস্করণ ০.৬.০ · বাংলা PDF রিডার")) {
            AlertDialog.Builder(this)
                .setTitle("কণ্ঠ · Kontho PDF Reader")
                .setMessage(label("Version 0.6.0\nRead PDFs, use Bengali/English OCR, and listen through Android text-to-speech.", "সংস্করণ ০.৬.০\nPDF পড়ুন, বাংলা/ইংরেজি OCR ব্যবহার করুন এবং Android-এর কণ্ঠে শুনুন।"))
                .setPositiveButton(label("OK", "ঠিক আছে"), null).show()
        }

        addRow(
            label("Support Kontho", "কণ্ঠকে সহায়তা করুন"),
            label("Buy a coffee or support the project", "কফি কিনে বা অনুদান দিয়ে প্রকল্পটিকে সহায়তা করুন")
        ) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.supportkori.com/tariquzzaman")))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    label("Could not open the support page.", "সহায়তার পৃষ্ঠাটি খোলা যায়নি।"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        addRow(label("Check for update now", "এখনই আপডেট দেখুন"), label("Check GitHub for a newer release", "নতুন রিলিজের জন্য GitHub পরীক্ষা করুন")) {
            checkForUpdates(manual = true)
        }

        val automaticChecks = prefs.getBoolean("auto_check_updates", true)
        addToggleRow(
            label("Automatic update checks", "স্বয়ংক্রিয় আপডেট পরীক্ষা"),
            label("Check at most once a day while using Kontho", "কণ্ঠ ব্যবহার করার সময় দিনে সর্বোচ্চ একবার পরীক্ষা"),
            automaticChecks
        ) { enabled ->
            prefs.edit().putBoolean("auto_check_updates", enabled).apply()
            Toast.makeText(
                this,
                if (enabled) label("Automatic update checks enabled", "স্বয়ংক্রিয় আপডেট পরীক্ষা চালু")
                else label("Automatic update checks disabled", "স্বয়ংক্রিয় আপডেট পরীক্ষা বন্ধ"),
                Toast.LENGTH_SHORT
            ).show()
        }

        addRow(label("Privacy policy", "গোপনীয়তা নীতি"), label("How Kontho handles PDFs, OCR and voice data", "PDF, OCR ও ভয়েস ডেটা কীভাবে ব্যবহৃত হয়")) {
            showPrivacyPolicy()
        }

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val done = Button(this).apply {
            text = label("Done", "সম্পন্ন")
            setOnClickListener {
                if (firstRun) prefs.edit().putBoolean("setup_done", true).apply()
                finish()
            }
        }
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        installSafeInsets(root, dp(24), dp(20), dp(24), dp(20))
    }

    private fun ocrCacheUsageBytes(): Long {
        val directory = File(filesDir, "ocr-cache")
        if (!directory.isDirectory) return 0L
        return directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
    }

    private fun trimOcrCache(limitMb: Int) {
        if (limitMb <= 0) return
        val directory = File(filesDir, "ocr-cache")
        if (!directory.isDirectory) return
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.lastModified() }.toList()
        var totalBytes = files.sumOf { it.length() }
        val limitBytes = limitMb.toLong() * 1024L * 1024L
        for (file in files) {
            if (totalBytes <= limitBytes) break
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, label("Checking GitHub for updates…", "GitHub-এ আপডেট খোঁজা হচ্ছে…"), Toast.LENGTH_SHORT).show()
        }
        GithubReleaseChecker.check(this) { result ->
            if (isFinishing || isDestroyed) return@check
            if (result.updateAvailable) {
                AlertDialog.Builder(this)
                    .setTitle(label("Kontho update available", "কণ্ঠের আপডেট পাওয়া গেছে"))
                    .setMessage(label(
                        "Installed version: ${result.installedVersion}\nLatest version: ${result.latestVersion}\n\nOpen the official release page to review and download it.",
                        "ইনস্টল করা সংস্করণ: ${result.installedVersion}\nসর্বশেষ সংস্করণ: ${result.latestVersion}\n\nপর্যালোচনা ও ডাউনলোড করতে অফিসিয়াল রিলিজ পৃষ্ঠা খুলুন।"
                    ))
                    .setPositiveButton(label("Open release", "রিলিজ খুলুন")) { _, _ -> openReleasePage(result.releaseUrl) }
                    .setNegativeButton(label("Later", "পরে"), null)
                    .show()
            } else if (manual) {
                val message = if (result.latestVersion == null) {
                    label(
                        "Could not find a published GitHub release yet. You can still open the releases page to check available downloads. Details: ${result.errorMessage ?: "unknown error"}",
                        "এখনো প্রকাশিত GitHub রিলিজ পাওয়া যায়নি। ডাউনলোড দেখতে রিলিজ পৃষ্ঠা খুলুন। বিবরণ: ${result.errorMessage ?: "অজানা ত্রুটি"}"
                    )
                } else {
                    label(
                        "You are using version ${result.installedVersion}. The latest published GitHub release is ${result.latestVersion}.",
                        "আপনি ${result.installedVersion} সংস্করণ ব্যবহার করছেন। GitHub-এ সর্বশেষ প্রকাশিত সংস্করণ ${result.latestVersion}।"
                    )
                }
                AlertDialog.Builder(this)
                    .setTitle(label("Update check", "আপডেট পরীক্ষা"))
                    .setMessage(message)
                    .setPositiveButton(label("Open releases", "রিলিজ খুলুন")) { _, _ -> openReleasePage(result.releaseUrl) }
                    .setNegativeButton(label("Close", "বন্ধ"), null)
                    .show()
            }
        }
    }

    private fun openReleasePage(url: String?) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url ?: GithubReleaseChecker.RELEASES_URL)))
        } catch (_: Exception) {
            Toast.makeText(this, label("Could not open updates", "আপডেট খোলা যায়নি"), Toast.LENGTH_LONG).show()
        }
    }

    private fun showPrivacyPolicy() {
        val bn = prefs.getString("app_language", "en") == "bn"
        val title = if (bn) "গোপনীয়তা নীতি" else "Privacy policy"
        val message = if (bn) """কণ্ঠ PDF Reader — গোপনীয়তা নীতি

আপনার বেছে নেওয়া PDF ও অনুমতিপ্রাপ্ত ফোল্ডার ব্যবহার করা হয়। বইয়ের তালিকা, সেটিংস, কভার পৃষ্ঠা, OCR লেখা এবং OCR মডেল ডিভাইসের অ্যাপ-স্টোরেজে থাকে।

অ্যাপে অ্যাকাউন্ট, বিজ্ঞাপন, অ্যানালিটিক্স বা নিজস্ব সার্ভারে PDF/OCR লেখা আপলোড করার ব্যবস্থা নেই। প্রথম OCR ব্যবহারে GitHub থেকে Tesseract মডেল ডাউনলোড হয়। স্বয়ংক্রিয় আপডেট পরীক্ষা চালু থাকলে দিনে সর্বোচ্চ একবার, অথবা সেটিংস থেকে হাতে পরীক্ষা করলে, GitHub-এর প্রকাশিত রিলিজের তথ্যও দেখা হয়। এই অনুরোধে আপনার PDF বা OCR লেখা পাঠানো হয় না; GitHub IP ঠিকানার মতো সংযোগ-তথ্য লগ করতে পারে।

Read aloud Android-এর TTS ইঞ্জিন ব্যবহার করে। ইঞ্জিনের সেটিংস অনুযায়ী কণ্ঠ ডিভাইসে তৈরি হতে পারে অথবা ইঞ্জিন সরবরাহকারীর মাধ্যমে প্রক্রিয়াকৃত হতে পারে। OCR ক্যাশ OCR সেটিংস থেকে মুছতে পারেন। যোগাযোগ: GitHub repository issues.""" else """Kontho PDF Reader — Privacy Policy

Kontho uses PDFs and folders you choose through Android's document picker. Book references, preferences, cover-page choices, OCR text cache and downloaded OCR models are kept in app storage.

The app has no accounts, ads, analytics, or app-operated server that receives PDFs or recognized OCR text. On first OCR use it downloads public Tesseract language models from GitHub. When automatic update checks are enabled, it also requests public GitHub release metadata at most once a day, or when you manually check in Settings. These requests never include your PDFs or OCR text; GitHub may log ordinary connection metadata such as your IP address.

Read aloud uses the Android TTS engine. Depending on that engine and its settings, speech may be processed locally or by the engine provider. You can clear OCR cache in OCR settings. Contact: GitHub repository issues."""
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(if (bn) "অনলাইনে সম্পূর্ণ নীতি খুলুন" else "Open full policy online") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tariquzzaman3/Kontho-PDF-Reader/blob/main/docs/privacy-policy.md"))) }
                catch (_: Exception) { Toast.makeText(this, if (bn) "নীতির লিংক খোলা যায়নি" else "Could not open policy link", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(if (bn) "বন্ধ" else "Close", null).show()
    }
}
