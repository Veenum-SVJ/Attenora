package dev.opensource.attenora.enforcement

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.opensource.attenora.data.SettingsRepository

/**
 * The live reel count: one number with "reels today" under it, drawn straight
 * onto the video.
 *
 * There is no background, scrim, blur or filter of any kind. The count used to
 * be mocked up behind a grey wash that made the reel unwatchable; the only
 * thing on screen here is the glyphs themselves, and every pixel of video
 * behind them is left alone.
 *
 * Legibility without a layer comes from a two-pass outline: a wide, translucent
 * black stroke offset slightly downward (reads as a soft drop shadow), then a
 * tighter black edge, then the white fill. `Paint.setShadowLayer` would be the
 * obvious call, but it is ignored on a hardware-accelerated canvas below API
 * 28 and this app supports 26, so the outline is drawn explicitly and behaves
 * identically on every device.
 */
class ReelCounterView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val gap = 6f * density
    private val offsetY = 1.5f * density
    private val softWidth = 7f * density
    private val tightWidth = 3f * density

    private val countFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 44f * density
        color = Color.WHITE
    }

    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        color = Color.WHITE
    }

    private val countSoft = stroked(countFill, softWidth, SOFT)
    private val countTight = stroked(countFill, tightWidth, TIGHT)
    private val labelSoft = stroked(labelFill, softWidth, SOFT)
    private val labelTight = stroked(labelFill, tightWidth, TIGHT)

    private fun stroked(base: Paint, width: Float, argb: Int): Paint =
        Paint(base).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeJoin = Paint.Join.ROUND
            setColor(argb)
        }

    var count: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * How solid the glyphs are, 0.15..1. Independent of the view's own [alpha],
     * which is what the fade in/out animates — so a translucent counter can
     * still fade in and out cleanly instead of starting at the wrong value.
     */
    var opacity: Float = 1f
        set(value) {
            val v = value.coerceIn(SettingsRepository.MIN_REEL_OPACITY, 1f)
            if (field == v) return
            field = v
            applyOpacity()
            invalidate()
        }

    private fun applyOpacity() {
        countFill.alpha = (255 * opacity).toInt()
        labelFill.alpha = (255 * opacity).toInt()
        countSoft.alpha = (SOFT_ALPHA * opacity).toInt()
        labelSoft.alpha = (SOFT_ALPHA * opacity).toInt()
        countTight.alpha = (TIGHT_ALPHA * opacity).toInt()
        labelTight.alpha = (TIGHT_ALPHA * opacity).toInt()
    }

    init {
        // Purely decorative, and the window is NOT_TOUCHABLE — a tap here must
        // reach the reel underneath, never this view.
        isFocusable = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cf = countFill.fontMetrics
        val lf = labelFill.fontMetrics
        val textWidth = maxOf(
            countFill.measureText(count.toString()),
            labelFill.measureText(LABEL)
        )
        val width = textWidth + paddingLeft + paddingRight + softWidth * 2
        val height = (cf.descent - cf.ascent) + gap + (lf.descent - lf.ascent) +
            paddingTop + paddingBottom + softWidth + offsetY
        setMeasuredDimension(
            width.toInt().coerceAtLeast(1),
            height.toInt().coerceAtLeast(1)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f + (paddingLeft - paddingRight) / 2f
        val cf = countFill.fontMetrics
        val lf = labelFill.fontMetrics
        val countBaseline = paddingTop + softWidth / 2f - cf.ascent
        val labelBaseline = countBaseline + cf.descent + gap - lf.ascent
        val digits = count.toString()

        canvas.save()
        canvas.translate(0f, offsetY)
        canvas.drawText(digits, cx, countBaseline, countSoft)
        canvas.drawText(LABEL, cx, labelBaseline, labelSoft)
        canvas.restore()

        canvas.drawText(digits, cx, countBaseline, countTight)
        canvas.drawText(digits, cx, countBaseline, countFill)
        canvas.drawText(LABEL, cx, labelBaseline, labelTight)
        canvas.drawText(LABEL, cx, labelBaseline, labelFill)
    }

    private companion object {
        const val LABEL = "reels today"

        /** Wide, faint pass — reads as a soft shadow rather than an outline. */
        const val SOFT = 0x66000000
        const val SOFT_ALPHA = 0x66

        /** Tight edge, just enough to hold the white against a bright clip. */
        const val TIGHT = 0xB3000000.toInt()
        const val TIGHT_ALPHA = 0xB3
    }
}
