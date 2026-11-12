package com.ssafy.fogo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class AtlasColors(val night: Boolean) {
    val background = rgb(if (night) 0x111317 else 0xf3f4f6)
    val paper = rgb(if (night) 0x1d1f24 else 0xffffff)
    val fog = rgb(if (night) 0x30343c else 0xe4e6ea)
    val border = rgb(if (night) 0x66717e else 0x0b1426)
    val ink = rgb(if (night) 0xf2f4f7 else 0x0b1426)
    val muted = rgb(if (night) 0xbbc2cb else 0x5b6270)
    val navy = rgb(if (night) 0x1d1f24 else 0x002c5f)
    val yellow = rgb(if (night) 0xe8be2a else 0xffd43b)
    val lime = rgb(if (night) 0x9bd62a else 0xb8f53c)
    val dark = rgb(0x0b1426)
    val white = Color.WHITE

    private fun rgb(value: Int) = value or 0xff000000.toInt()
}

/**
 * [scale] enlarges every dp and text size together so screens follow the Figma proportions
 * (1700×1184 / 840×1184 frames) on wide Pleos displays instead of rendering at phone size.
 */
class AtlasUi(private val context: Context, val colors: AtlasColors, val wide: Boolean, val scale: Float = 1f) {
    private val textFont by lazy { context.resources.getFont(R.font.asta_sans) }
    private val displayFont by lazy { context.resources.getFont(R.font.press_start) }

    fun dp(n: Int) = (n * scale * context.resources.displayMetrics.density + .5f).toInt()
    /** Unscaled dp, for sizes already derived from the screen width. */
    fun rawDp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun box(fill: Int, outline: Int = colors.border, shadow: Boolean = false): android.graphics.drawable.Drawable {
        val face = GradientDrawable().apply { setColor(fill); setStroke(dp(3), outline) }
        if (!shadow) return face
        return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(this@AtlasUi.colors.dark) }, face)).apply {
            setLayerInset(0, dp(4), dp(4), 0, 0)
            setLayerInset(1, 0, 0, dp(4), dp(4))
        }
    }

    fun label(value: String, size: Float = 18f, color: Int = colors.ink, bold: Boolean = false, pixel: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size * scale
            setTextColor(color)
            typeface = if (pixel) displayFont else Typeface.create(textFont, if (bold) 750 else 500, false)
        }

    fun text(parent: LinearLayout, value: String, size: Float = 18f, color: Int = colors.ink,
             bold: Boolean = false, pixel: Boolean = false) =
        label(value, size, color, bold, pixel).also {
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    fun card(parent: LinearLayout, fill: Int = colors.paper, shadow: Boolean = false): LinearLayout = column().apply {
        background = box(fill, shadow = shadow)
        setPadding(dp(16), dp(13), dp(16), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    fun action(value: String, selected: Boolean = false, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) =
        Button(context).apply {
            text = value
            isAllCaps = false
            textSize = 17f * scale
            typeface = Typeface.create(textFont, 750, false)
            minHeight = dp(52)
            minimumHeight = dp(52)
            val fill = when {
                primary -> colors.lime
                selected -> if (colors.night) 0xff324963.toInt() else colors.navy
                else -> colors.paper
            }
            setTextColor(if (primary) colors.dark else if (selected) colors.white else colors.ink)
            background = RippleDrawable(ColorStateList.valueOf(0x335b6270), box(fill, shadow = primary), null)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            isEnabled = enabled
            alpha = if (enabled) 1f else .58f
            setOnClickListener { click() }
        }

    fun button(parent: LinearLayout, value: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
        parent.addView(action(value, primary = primary, enabled = enabled, click = click),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    fun strip(parent: LinearLayout, choices: List<Pair<String, () -> Unit>>, selected: String? = null) {
        val container = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val line = row()
        container.addView(line)
        choices.forEach { (name, click) ->
            line.addView(action(name, name == selected, click = click),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        parent.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    fun split(parent: LinearLayout, first: (LinearLayout) -> Unit, second: (LinearLayout) -> Unit) {
        if (!wide) {
            first(parent)
            second(parent)
            return
        }
        val line = row().apply { gravity = Gravity.TOP }
        val left = column()
        val right = column()
        line.addView(left, LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginEnd = dp(16) })
        line.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        first(left)
        second(right)
    }

    fun progress(parent: LinearLayout, ratio: Float) {
        val bar = row().apply { background = box(colors.fog); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        repeat(16) { index ->
            bar.addView(View(context).apply { setBackgroundColor(if (index < ratio * 16) colors.lime else colors.fog) },
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(2) })
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(19)).apply { bottomMargin = dp(8) })
    }
}
