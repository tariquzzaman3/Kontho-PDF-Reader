package org.kontho.pdfreader

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * One calm green-and-paper palette for the whole app, in a light and a dark flavour.
 * Everything that draws UI asks for the palette here instead of hard-coding colours.
 */
class Palette(val dark: Boolean) {
    val background: Int = if (dark) 0xFF0F1614.toInt() else 0xFFF5F8F6.toInt()
    val surface: Int = if (dark) 0xFF18211E.toInt() else 0xFFFFFFFF.toInt()
    val surfaceAlt: Int = if (dark) 0xFF222D29.toInt() else 0xFFE9F0EC.toInt()
    val outline: Int = if (dark) 0xFF2C3A35.toInt() else 0xFFE0E8E4.toInt()
    val text: Int = if (dark) 0xFFE9F2EE.toInt() else 0xFF15231F.toInt()
    val textMuted: Int = if (dark) 0xFF9FB2A9.toInt() else 0xFF5E7068.toInt()
    val accent: Int = if (dark) 0xFF5CCBA6.toInt() else 0xFF126854.toInt()
    val onAccent: Int = if (dark) 0xFF06241A.toInt() else 0xFFFFFFFF.toInt()
    val accentSoft: Int = if (dark) 0xFF1E3B31.toInt() else 0xFFDCEEE7.toInt()
    val warning: Int = if (dark) 0xFFFFB86B.toInt() else 0xFF9A5B00.toInt()
    val ripple: Int = if (dark) 0x33FFFFFF else 0x22126854
}

object Ui {
    const val PREFS = "kontho_library"

    fun isDark(context: Context): Boolean {
        val theme = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app_theme", "system")
        return when (theme) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun palette(context: Context): Palette = Palette(isDark(context))

    fun isBangla(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app_language", "en") == "bn"

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(
        color: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokePx: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** A rounded surface with a touch ripple that respects the corner radius. */
    fun rippleShape(
        color: Int,
        radiusPx: Float,
        rippleColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokePx: Int = 0
    ): Drawable {
        val content = shape(color, radiusPx, strokeColor, strokePx)
        val mask = shape(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    /** Clip a view (for example a book cover) to rounded corners. */
    fun roundCorners(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    /** Gentle press feedback: the view shrinks slightly while it is held down. */
    fun pressable(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }
            false
        }
    }

    /** Fade and slide a view in. Used for the staggered entrance of cards. */
    fun revealIn(view: View, delayMs: Long, distancePx: Float) {
        view.alpha = 0f
        view.translationY = distancePx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(300L)
            .start()
    }

    fun applySystemBars(window: Window, palette: Palette, navigationColor: Int = palette.background) {
        window.statusBarColor = palette.background
        window.navigationBarColor = navigationColor
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
    }

    fun edgeToEdge(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun icon(context: Context, resId: Int, tint: Int, sizeDp: Int = 24): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Round icon button used in headers (settings, back). */
    fun iconButton(
        context: Context,
        palette: Palette,
        resId: Int,
        description: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        background = rippleShape(palette.surfaceAlt, dp(context, 22).toFloat(), palette.ripple)
        isClickable = true
        isFocusable = true
        addView(
            icon(context, resId, palette.text, 22),
            LinearLayout.LayoutParams(dp(context, 22), dp(context, 22))
        )
        setOnClickListener { onClick() }
        pressable(this, 0.92f)
    }

    /** Solid accent button with rounded corners. */
    fun primaryButton(context: Context, palette: Palette, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent)
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            background = rippleShape(palette.accent, dp(context, 14).toFloat(), 0x33FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressable(this)
        }

    /** Quiet outlined button for secondary actions. */
    fun secondaryButton(context: Context, palette: Palette, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            background = rippleShape(
                palette.surface, dp(context, 14).toFloat(), palette.ripple,
                palette.outline, dp(context, 1)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressable(this)
        }
}
