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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.net.Uri

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
            prefs.edit().putString("book_folder_uri", uri.toString()).apply()
        }
        renderSetupStep()
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

        val logo = TextView(this).apply {
            text = "কণ্ঠ"
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            contentDescription = "Kontho logo"
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

        val progressText = TextView(this).apply {
            text = label("Step ${setupStep + 1} of 4", "ধাপ ${setupStep + 1}/৪")
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

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (setupStep > 0) {
            val back = Button(this).apply {
                text = label("Back", "পেছনে")
                isAllCaps = false
                setOnClickListener {
                    setupStep = (setupStep - 1).coerceAtLeast(0)
                    renderSetupStep()
                }
            }
            footer.addView(back, LinearLayout.LayoutParams(0, dp(48), 0.65f).apply {
                marginEnd = dp(8)
            })
        }
        if (setupStep == 3) {
            val finish = Button(this).apply {
                text = label("Finish setup", "সেটআপ শেষ করুন")
                isAllCaps = false
                setOnClickListener {
                    prefs.edit().putBoolean("setup_done", true).apply()
                    finish()
                }
            }
            footer.addView(finish, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (prefs.getString("book_folder_uri", null).isNullOrBlank()) {
                val skip = TextView(this).apply {
                    text = label("Set up later", "পরে করব")
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        prefs.edit().putBoolean("setup_done", true).apply()
                        finish()
                    }
                }
                root.addView(skip, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        if (footer.childCount > 0) {
            root.addView(footer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
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
        fun addRow(titleText: String, summary: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = "$titleText\n$summary"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { action() }
            }
            options.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8
            })
        }

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

        val booksFolder = prefs.getString("book_folder_uri", null)
        addRow(label("Books folder", "বইয়ের ফোল্ডার"), if (booksFolder == null) label("Not selected", "নির্বাচন করা হয়নি") else label("Selected · scan on app launch", "নির্বাচিত · অ্যাপ খোলার সময় স্ক্যান")) {
            chooseBooks.launch(null)
        }

        val cacheFolder = prefs.getString("cache_folder_uri", null)
        addRow(label("Audio/cache storage folder", "অডিও/ক্যাশ স্টোরেজ ফোল্ডার"), if (cacheFolder == null) label("Not selected", "নির্বাচন করা হয়নি") else label("Selected", "নির্বাচিত")) {
            chooseCache.launch(null)
        }
        addRow(label("About storage access", "স্টোরেজ সম্পর্কে"), label("Android asks you to grant access to a folder. Choose phone memory or an SD card in the folder picker.", "Android ফোল্ডারের অনুমতি চাইবে। ফোল্ডার বাছাইয়ের সময় ফোন মেমোরি বা SD কার্ড নির্বাচন করুন।")) {
            AlertDialog.Builder(this).setMessage(label("Kontho scans the folder you grant access to each time the app opens. Android does not allow unrestricted scanning of every location by default. The chosen cache folder is saved for future generated audio/cache files; this build does not yet generate audiobook cache files.", "অ্যাপ খোলার সময় অনুমতিপ্রাপ্ত ফোল্ডারটি স্ক্যান করা হয়। Android ডিফল্টভাবে সব জায়গা অবাধে স্ক্যান করতে দেয় না। ভবিষ্যতে তৈরি অডিও/ক্যাশ ফাইলের জন্য ফোল্ডারটি সংরক্ষণ করা হয়; এই সংস্করণে এখনো অডিও ক্যাশ তৈরি হয় না।"))
                .setPositiveButton(label("OK", "ঠিক আছে"), null).show()
        }

        addRow(label("About Kontho", "কণ্ঠ পরিচিতি"), label("Version 0.6.0 · Bangla PDF reader", "সংস্করণ ০.৬.০ · বাংলা PDF রিডার")) {
            AlertDialog.Builder(this)
                .setTitle("কণ্ঠ · Kontho PDF Reader")
                .setMessage(label("Version 0.6.0\\nRead PDFs, use Bengali/English OCR, and listen through Android text-to-speech.", "সংস্করণ ০.৬.০\\nPDF পড়ুন, বাংলা/ইংরেজি OCR ব্যবহার করুন এবং Android-এর কণ্ঠে শুনুন।"))
                .setPositiveButton(label("OK", "ঠিক আছে"), null).show()
        }

        addRow(label("Check for updates", "আপডেট দেখুন"), label("Open official GitHub releases", "অফিশিয়াল GitHub রিলিজ খুলুন")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases"))) }
            catch (_: Exception) { Toast.makeText(this, label("Could not open updates", "আপডেট খোলা যায়নি"), Toast.LENGTH_LONG).show() }
        }

        addRow(label("Automatic updates", "স্বয়ংক্রিয় আপডেট"), label("Managed by Google Play after installation from Play Store", "Play Store থেকে ইনস্টল করলে Google Play আপডেট পরিচালনা করবে")) {
            AlertDialog.Builder(this)
                .setTitle(label("Automatic updates", "স্বয়ংক্রিয় আপডেট"))
                .setMessage(label("Kontho does not silently install APKs. Google Play manages automatic updates for Play-installed apps. For GitHub builds, use Check for updates.", "কণ্ঠ নিজে থেকে APK ইনস্টল করে না। Play Store থেকে ইনস্টল করা অ্যাপের স্বয়ংক্রিয় আপডেট Google Play পরিচালনা করে। GitHub সংস্করণের জন্য আপডেট দেখুন।"))
                .setPositiveButton(label("OK", "ঠিক আছে"), null).show()
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

    private fun showPrivacyPolicy() {
        val bn = prefs.getString("app_language", "en") == "bn"
        val title = if (bn) "গোপনীয়তা নীতি" else "Privacy policy"
        val message = if (bn) """কণ্ঠ PDF Reader — গোপনীয়তা নীতি

আপনার বেছে নেওয়া PDF ও অনুমতিপ্রাপ্ত ফোল্ডার ব্যবহার করা হয়। বইয়ের তালিকা, সেটিংস, কভার পৃষ্ঠা, OCR লেখা এবং OCR মডেল ডিভাইসের অ্যাপ-স্টোরেজে থাকে।

অ্যাপে অ্যাকাউন্ট, বিজ্ঞাপন, অ্যানালিটিক্স বা নিজস্ব সার্ভারে PDF/OCR লেখা আপলোড করার ব্যবস্থা নেই। প্রথম OCR ব্যবহারে GitHub থেকে Tesseract মডেল ডাউনলোড হয়; GitHub IP ঠিকানার মতো সংযোগ-তথ্য লগ করতে পারে।

Read aloud Android-এর TTS ইঞ্জিন ব্যবহার করে। ইঞ্জিনের সেটিংস অনুযায়ী কণ্ঠ ডিভাইসে তৈরি হতে পারে অথবা ইঞ্জিন সরবরাহকারীর মাধ্যমে প্রক্রিয়াকৃত হতে পারে। OCR ক্যাশ OCR সেটিংস থেকে মুছতে পারেন। যোগাযোগ: GitHub repository issues.""" else """Kontho PDF Reader — Privacy Policy

Kontho uses PDFs and folders you choose through Android's document picker. Book references, preferences, cover-page choices, OCR text cache and downloaded OCR models are kept in app storage.

The app has no accounts, ads, analytics, or app-operated server that receives PDFs or recognized OCR text. On first OCR use it downloads public Tesseract language models from GitHub; GitHub may log ordinary connection metadata such as your IP address.

Read aloud uses the Android TTS engine. Depending on that engine and its settings, speech may be processed locally or by the engine provider. You can clear OCR cache in OCR settings. Contact: GitHub repository issues."""
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(if (bn) "অনলাইনে সম্পূর্ণ নীতি খুলুন" else "Open full policy online") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tariquzzaman3/Kontho-PDF-Reader/blob/main/docs/privacy-policy.md"))) }
                catch (_: Exception) { Toast.makeText(this, if (bn) "নীতির লিংক খোলা যায়নি" else "Could not open policy link", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(if (bn) "বন্ধ" else "Close", null).show()
    }
}
