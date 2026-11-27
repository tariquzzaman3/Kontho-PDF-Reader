package org.kontho.pdfreader

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Short branded welcome: logo, name and the slogan, then straight into the library.
 * PDFs opened from other apps go directly to MainActivity and never see this screen.
 */
class SplashActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = Ui.palette(this)
        Ui.edgeToEdge(window)
        Ui.applySystemBars(window, palette)

        val root = FrameLayout(this).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.kontho_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "কণ্ঠ · Kontho PDF Reader logo"
        }
        column.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112)).apply {
            bottomMargin = Ui.dp(this@SplashActivity, 18)
        })

        val name = TextView(this).apply {
            text = "কণ্ঠ"
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
        }
        column.addView(name, wrap())

        val product = TextView(this).apply {
            text = "KONTHO PDF READER"
            textSize = 12f
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            setTextColor(palette.textMuted)
        }
        column.addView(product, wrap().apply { topMargin = Ui.dp(this@SplashActivity, 2) })

        val rule = View(this).apply {
            background = Ui.shape(palette.accent, Ui.dp(this@SplashActivity, 2).toFloat())
        }
        column.addView(rule, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 3)).apply {
            topMargin = Ui.dp(this@SplashActivity, 22)
            bottomMargin = Ui.dp(this@SplashActivity, 22)
        })

        val sloganBn = TextView(this).apply {
            text = "কণ্ঠে প্রাণ পায় বাংলা বই"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
        }
        column.addView(sloganBn, wrap())

        val sloganEn = TextView(this).apply {
            text = "Bangla books, brought to life"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.textMuted)
        }
        column.addView(sloganEn, wrap().apply { topMargin = Ui.dp(this@SplashActivity, 6) })

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        setContentView(root)

        // Entrance: the logo pops in, then each line rises into place.
        logo.alpha = 0f
        logo.scaleX = 0.8f
        logo.scaleY = 0.8f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(520L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
        val rise = Ui.dp(this, 10).toFloat()
        fun reveal(view: View, delay: Long) {
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        reveal(name, 180L)
        reveal(product, 280L)
        reveal(rule, 380L)
        reveal(sloganBn, 460L)
        reveal(sloganEn, 560L)

        root.setOnClickListener { proceed() }
        handler.postDelayed({ proceed() }, 1900L)
    }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun proceed() {
        if (launched || isFinishing) return
        launched = true
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
