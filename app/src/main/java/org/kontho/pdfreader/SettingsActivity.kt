package org.kontho.pdfreader

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kontho_library", MODE_PRIVATE) }
    private var firstRun = false

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
        render()
        if (firstRun) {
            AlertDialog.Builder(this)
                .setTitle(label("Welcome to Kontho", "কণ্ঠে স্বাগতম"))
                .setMessage(label("Set up your preferred language, appearance, library layout, sorting and storage. You can change everything later in Settings.", "আপনার পছন্দের ভাষা, থিম, বই দেখানোর ধরন, সাজানো ও স্টোরেজ সেট করুন। পরে সেটিংস থেকে পরিবর্তন করতে পারবেন।"))
                .setPositiveButton(label("Start setup", "সেটআপ শুরু")) { _, _ -> }
                .show()
        }
    }

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
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
            else -> if (descending) label("Name · descending", "নাম · নিম্নক্রম") else label("Name · ascending", "নাম · ঊর্ধ্বক্রম")
        }
        addRow(label("Sort books", "বই সাজান"), sortSummary) {
            val items = arrayOf(label("Name · ascending", "নাম · ঊর্ধ্বক্রম"), label("Name · descending", "নাম · নিম্নক্রম"), label("Date · newest first", "তারিখ · নতুন আগে"), label("Date · oldest first", "তারিখ · পুরোনো আগে"))
            val selected = when (sort) { "date" -> if (descending) 2 else 3; else -> if (descending) 1 else 0 }
            AlertDialog.Builder(this).setTitle(label("Sort books", "বই সাজান")).setSingleChoiceItems(items, selected) { dialog, which ->
                prefs.edit().putString("sort_field", if (which < 2) "name" else "date").putBoolean("sort_descending", which == 1 || which == 2).apply()
                dialog.dismiss(); render()
            }.setNegativeButton(label("Cancel", "বাতিল"), null).show()
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
    }
}
