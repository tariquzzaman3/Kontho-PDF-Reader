package org.kontho.pdfreader

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Kontho's own pop-up, drawn with the app palette (rounded card, accent buttons) instead of the
 * phone's default alert box. The builder methods mirror AlertDialog.Builder, so it is a drop-in.
 */
class KDialog(private val context: Context) {
    private val palette = Ui.palette(context)
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var choices: Array<String>? = null
    private var choiceIndex = -1
    private var choiceListener: ((DialogInterface, Int) -> Unit)? = null
    private var positive: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var negative: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neutral: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var cancelListener: (() -> Unit)? = null
    private var cancelable = true

    fun setTitle(value: CharSequence?) = apply { title = value }
    fun setMessage(value: CharSequence?) = apply { message = value }
    fun setView(value: View?) = apply { content = value }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener) = apply {
        cancelListener = { listener.onCancel(null) }
    }

    fun setSingleChoiceItems(items: Array<String>, checked: Int, listener: (DialogInterface, Int) -> Unit) = apply {
        choices = items
        choiceIndex = checked
        choiceListener = listener
    }

    fun setPositiveButton(label: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null) = apply {
        positive = label to listener
    }

    fun setNegativeButton(label: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null) = apply {
        negative = label to listener
    }

    fun setNeutralButton(label: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null) = apply {
        neutral = label to listener
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    fun create(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setOnCancelListener { cancelListener?.invoke() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(palette.surface, dp(24).toFloat(), palette.outline, dp(1))
            setPadding(dp(22), dp(20), dp(22), dp(12))
        }

        title?.let {
            card.addView(
                Ui.text(context, it, 19f, palette.text, bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            )
        }

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        message?.let {
            body.addView(
                Ui.text(context, it, 14.5f, palette.text).apply {
                    setLineSpacing(0f, 1.2f)
                    setTextIsSelectable(false)
                    movementMethod = LinkMovementMethod.getInstance()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            )
        }
        content?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            body.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        choices?.let { items ->
            items.forEachIndexed { index, text ->
                body.addView(choiceRow(dialog, text, index == choiceIndex, index), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }
        card.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(body, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        if (positive != null || negative != null || neutral != null) {
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            neutral?.let { (label, listener) ->
                buttons.addView(textButton(dialog, label, false, listener))
                buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            negative?.let { (label, listener) -> buttons.addView(textButton(dialog, label, false, listener)) }
            positive?.let { (label, listener) -> buttons.addView(textButton(dialog, label, true, listener)) }
            card.addView(buttons, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        } else {
            card.setPadding(dp(22), dp(20), dp(22), dp(16))
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = context.resources.displayMetrics
            val width = minOf((metrics.widthPixels * 0.9f).toInt(), dp(440))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes.apply { dimAmount = 0.5f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Never taller than the screen: the body scrolls inside the card.
            decorView.setPadding(0, dp(24), 0, dp(24))
        }
        return dialog
    }

    fun show(): Dialog = create().also { it.show() }

    private fun choiceRow(dialog: Dialog, text: String, selected: Boolean, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rippleShape(
                if (selected) palette.accentSoft else palette.surfaceAlt,
                dp(14).toFloat(), palette.ripple,
                if (selected) palette.accent else Color.TRANSPARENT,
                if (selected) dp(1) else 0
            )
            isClickable = true
            isFocusable = true
        }
        val label = Ui.text(context, text, 15f, palette.text, bold = selected).apply {
            setLineSpacing(0f, 1.1f)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dot = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            text = if (selected) "✓" else ""
            setTextColor(palette.onAccent)
            background = if (selected) {
                Ui.shape(palette.accent, dp(11).toFloat())
            } else {
                Ui.shape(Color.TRANSPARENT, dp(11).toFloat(), palette.outline, dp(2))
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) })
        row.setOnClickListener {
            choiceListener?.invoke(dialog, index)
            if (dialog.isShowing) dialog.dismiss()
        }
        return row
    }

    private fun textButton(
        dialog: Dialog,
        label: CharSequence,
        primary: Boolean,
        listener: ((DialogInterface, Int) -> Unit)?
    ): View = Ui.text(context, label, 14f, if (primary) palette.onAccent else palette.accent, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(44)
        background = if (primary) {
            Ui.rippleShape(palette.accent, dp(14).toFloat(), 0x33FFFFFF)
        } else {
            Ui.rippleShape(Color.TRANSPARENT, dp(14).toFloat(), palette.ripple)
        }
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
        setOnClickListener {
            listener?.invoke(dialog, 0)
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}
