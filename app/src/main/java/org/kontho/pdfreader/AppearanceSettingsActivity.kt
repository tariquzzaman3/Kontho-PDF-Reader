package org.kontho.pdfreader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * "Appearance" screen under Settings → General: theme, the colour of the light theme, and how the
 * library looks (grid or list, and how many grid columns). Colour only shows while the light look
 * is in use; the column count only shows for the grid layout.
 */
class AppearanceSettingsActivity : ComponentActivity() {
    private lateinit var palette: Palette
    private lateinit var column: LinearLayout
    private val prefs by lazy { getSharedPreferences(Ui.PREFS, MODE_PRIVATE) }

    private lateinit var colorCard: LinearLayout
    private lateinit var columnsBox: LinearLayout

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
            Ui.text(this, t("Appearance", "থিম ও চেহারা"), 22f, palette.text, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        buildThemeCard()
        buildColorCard()
        buildLayoutCard()

        val scroll = ScrollView(this).apply {
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

        for (index in 0 until column.childCount) {
            val card = column.getChildAt(index)
            if (card.visibility == View.VISIBLE) Ui.revealIn(card, 60L * index, dp(14).toFloat())
        }
    }

    // ---------------------------------------------------------------- cards

    private fun card(title: String, hint: String?): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.shape(palette.surface, dp(18).toFloat(), palette.outline, dp(1))
        }
        outer.addView(Ui.text(this, title, 17f, palette.text, bold = true))
        if (hint != null) {
            outer.addView(
                Ui.text(this, hint, 13f, palette.textMuted),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
        }
        column.addView(outer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        return outer
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView =
        Ui.text(this, text, 14f, if (on) palette.onAccent else palette.text, bold = on).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Ui.rippleShape(
                if (on) palette.accent else palette.surfaceAlt, dp(20).toFloat(), palette.ripple
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun chipRow(vararg chips: TextView): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { index, chip ->
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) marginStart = dp(8) })
        }
        return row
    }

    // ---- theme

    private fun buildThemeCard() {
        val theme = prefs.getString("app_theme", "system") ?: "system"
        val outer = card(t("Theme", "থিম"), t("Light, dark, or follow the phone.", "লাইট, ডার্ক, অথবা ফোনের সাথে মিলিয়ে।"))
        fun pick(value: String) {
            if (value == theme) return
            prefs.edit().putString("app_theme", value).apply()
            recreate()
        }
        outer.addView(
            chipRow(
                chip(t("System default", "সিস্টেম ডিফল্ট"), theme == "system") { pick("system") },
                chip(t("Light", "লাইট"), theme == "light") { pick("light") },
                chip(t("Dark", "ডার্ক"), theme == "dark") { pick("dark") }
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
    }

    // ---- colour (light look only)

    private fun buildColorCard() {
        colorCard = card(
            t("Colour", "রং"),
            t("Pick the colour of the light theme.", "লাইট থিমের রং বেছে নিন।")
        )
        val selected = Ui.accentKey(this)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Palette.ACCENTS.forEachIndexed { index, family ->
            val on = family.key == (Palette.ACCENTS.firstOrNull { it.key == selected } ?: Palette.ACCENTS.first()).key
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = t(family.nameEn, family.nameBn)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener {
                    if (!on) {
                        prefs.edit().putString(Ui.KEY_ACCENT, family.key).apply()
                        recreate()
                    }
                }
            }
            val swatch = TextView(this).apply {
                gravity = Gravity.CENTER
                text = if (on) "✓" else ""
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = Ui.shape(
                    family.accent, dp(24).toFloat(),
                    if (on) palette.text else family.soft, if (on) dp(3) else dp(2)
                )
            }
            item.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)))
            item.addView(
                Ui.text(this, t(family.nameEn, family.nameBn), 11f, if (on) palette.text else palette.textMuted, bold = on),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            strip.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) marginStart = dp(4) })
        }
        colorCard.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
        // Colour only applies while the light look is showing.
        colorCard.visibility = if (palette.dark) View.GONE else View.VISIBLE
    }

    // ---- library layout and columns

    private fun buildLayoutCard() {
        val outer = card(
            t("Library layout", "বই দেখানোর ধরন"),
            t("How your books are shown on the home screen.", "হোম স্ক্রিনে বই কীভাবে দেখাবে।")
        )
        val layout = prefs.getString("library_view", "grid") ?: "grid"
        val modeRow = chipRow(
            chip(t("Grid", "গ্রিড"), layout != "list") { setLayout("grid") },
            chip(t("List", "লিস্ট"), layout == "list") { setLayout("list") }
        )
        outer.addView(modeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        columnsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val columns = prefs.getInt("grid_columns", 2).coerceIn(1, 10)
        columnsBox.addView(
            Ui.text(this, t("Grid columns", "গ্রিড কলাম"), 14f, palette.text, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16); bottomMargin = dp(8) }
        )
        val numbers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (count in 1..10) {
            val on = count == columns
            numbers.addView(
                Ui.text(this, count.toString(), 14f, if (on) palette.onAccent else palette.text, bold = on).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rippleShape(
                        if (on) palette.accent else palette.surfaceAlt, dp(20).toFloat(), palette.ripple
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        prefs.edit().putInt("grid_columns", count).apply()
                        rebuildColumns()
                    }
                },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (count > 1) marginStart = dp(6) }
            )
        }
        columnsBox.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(numbers)
        })
        columnsBox.visibility = if (layout == "list") View.GONE else View.VISIBLE
        outer.addView(columnsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun setLayout(mode: String) {
        if ((prefs.getString("library_view", "grid") ?: "grid") == mode) return
        prefs.edit().putString("library_view", mode).apply()
        // Rebuild the two layout chips and show or hide the column picker.
        column.removeAllViews()
        buildThemeCard()
        buildColorCard()
        TransitionManager.beginDelayedTransition(column, AutoTransition().setDuration(200L))
        buildLayoutCard()
    }

    private fun rebuildColumns() {
        column.removeAllViews()
        buildThemeCard()
        buildColorCard()
        buildLayoutCard()
    }

    private fun t(en: String, bn: String): String = VoiceEngines.t(this, en, bn)

    private fun dp(value: Int): Int = Ui.dp(this, value)
}
