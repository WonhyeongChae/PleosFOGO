package com.ssafy.fogo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView

/**
 * Opening shown on every launch: pixel fog clears, the neon FOGO logo lights up, the mascot hops across it
 * and the map fills in (res/raw/fogo_intro.mp4, 1680×1208 like the Pleos app area, silent, 11.5 s).
 * When it ends — or on any tap — a pixel-neon "시작하기" button rises from the bottom.
 * Only built while parked: MainActivity shows the driving screen instead, so no video plays on the move.
 */
class IntroSplash(host: MainActivity, private val onStart: () -> Unit) : FrameLayout(host) {
    private val video = VideoView(host)
    private val button = NeonStartButton(host, host.ui)
    private val reveal = Runnable { showStart() }
    private var shown = false

    init {
        setBackgroundColor(BACKGROUND)
        addView(video, LayoutParams(-1, -1, Gravity.CENTER))
        button.visibility = View.INVISIBLE
        button.setOnClickListener { if (shown) onStart() }
        addView(button, LayoutParams(host.ui.fx(620), host.ui.fx(156), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = host.ui.fx(40) })
        // A tap anywhere skips the wait for the button (the video keeps playing behind it).
        setOnClickListener { showStart() }
        // Silent clip: never take audio focus from the car's radio or navigation prompts.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        video.setOnCompletionListener { showStart() }
        video.setOnErrorListener { _, _, _ ->
            fallbackLogo(host)
            showStart()
            true
        }
        video.setVideoURI(Uri.parse("android.resource://${host.packageName}/${R.raw.fogo_intro}"))
        video.start()
        // Safety net in case the completion callback never comes (decoder hiccup on the emulator).
        postDelayed(reveal, REVEAL_AFTER_MS)
    }

    private fun fallbackLogo(host: MainActivity) {
        video.visibility = View.GONE
        addView(host.ui.label("FOGO", 60f, NeonStartButton.LIME, pixel = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(host.ui.dp(10).toFloat(), 0f, 0f, NeonStartButton.LIME)
        }, LayoutParams(-1, -1, Gravity.CENTER))
        button.bringToFront()
    }

    private fun showStart() {
        if (shown) return
        shown = true
        removeCallbacks(reveal)
        button.apply {
            alpha = 0f
            translationY = height.coerceAtLeast(1) * .6f
            scaleX = .85f
            scaleY = .85f
            visibility = View.VISIBLE
            animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(reveal)
        video.stopPlayback()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Same navy as the first and last video frame, so the clip has no visible edge. */
        const val BACKGROUND = 0xff0a0f1e.toInt()
        private const val REVEAL_AFTER_MS = 11_000L
    }
}

/**
 * Square pixel-neon button: notched corners, lime tube with a breathing glow, a hard blue drop block
 * like the logo, and blinking ▶ ◀ marks. Pressing sinks the face onto the block and fills it lime.
 */
class NeonStartButton(context: Context, ui: AtlasUi) : TextView(context) {
    private val px = ui.dp(4).toFloat().coerceAtLeast(4f)
    private val margin = px * 3
    private val paint = Paint()
    private val tube = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = px * 2
        strokeJoin = Paint.Join.MITER
    }
    private var glow = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 850
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { glow = it.animatedValue as Float; invalidate() }
    }

    init {
        text = "시작하기"
        contentDescription = "시작하기"
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create(context.resources.getFont(R.font.galmuri), 700, false)
        textSize = ui.scale * 30f
        letterSpacing = .12f
        setTextColor(LIME)
        setShadowLayer(px * 3, 0f, 0f, LIME)
        isClickable = true
        isFocusable = true
        // Shadow layers (the glow) only draw in software.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        setTextColor(if (isPressed) DARK else LIME)
        setShadowLayer(if (isPressed) 0f else px * 3, 0f, 0f, LIME)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = isPressed
        val sink = if (pressed) px * 2 else 0f
        val left = margin
        val top = margin
        val right = width - margin - px * 2
        val bottom = height - margin - px * 2
        // Hard drop block (logo shadow blue), hidden under the face while pressed.
        paint.color = BLUE
        paint.clearShadowLayer()
        canvas.drawPath(notched(left + px * 2, top + px * 2, right + px * 2, bottom + px * 2), paint)

        canvas.save()
        canvas.translate(sink, sink)
        paint.color = if (pressed) LIME else BACKGROUND
        canvas.drawPath(notched(left, top, right, bottom), paint)
        // Neon tube: stroke drawn inside the face so the notches stay crisp; the glow breathes.
        tube.color = if (pressed) HIGHLIGHT else LIME
        if (pressed) tube.clearShadowLayer()
        else tube.setShadowLayer(px * (3f + 5f * glow), 0f, 0f, (0x99 + (0x66 * glow).toInt() shl 24) or (LIME and 0xffffff))
        canvas.drawPath(notched(left + px, top + px, right - px, bottom - px), tube)
        // Inner top highlight, one pixel.
        paint.color = if (pressed) HIGHLIGHT else 0x55ecffa6
        canvas.drawRect(left + px * 4, top + px * 3, right - px * 4, top + px * 4, paint)
        // Blinking start marks.
        if (glow > .45f || pressed) {
            paint.color = if (pressed) DARK else LIME
            val mid = (top + bottom) / 2f
            arrow(canvas, left + px * 6, mid, 1)
            arrow(canvas, right - px * 6, mid, -1)
        }
        super.onDraw(canvas)
        canvas.restore()
    }

    /** ▶ (dir 1) or ◀ (dir -1), three pixel columns tall 5·3·1, starting at [x]. */
    private fun arrow(canvas: Canvas, x: Float, mid: Float, dir: Int) {
        listOf(5, 3, 1).forEachIndexed { column, rows ->
            val cx = x + dir * column * px - if (dir < 0) px else 0f
            canvas.drawRect(cx, mid - rows * px / 2f, cx + px, mid + rows * px / 2f, paint)
        }
    }

    /** Rectangle with one-pixel steps cut out of each corner. */
    private fun notched(l: Float, t: Float, r: Float, b: Float) = Path().apply {
        val c = px * 2
        moveTo(l + c, t); lineTo(r - c, t); lineTo(r - c, t + px); lineTo(r - px, t + px); lineTo(r - px, t + c)
        lineTo(r, t + c); lineTo(r, b - c); lineTo(r - px, b - c); lineTo(r - px, b - px); lineTo(r - c, b - px)
        lineTo(r - c, b); lineTo(l + c, b); lineTo(l + c, b - px); lineTo(l + px, b - px); lineTo(l + px, b - c)
        lineTo(l, b - c); lineTo(l, t + c); lineTo(l + px, t + c); lineTo(l + px, t + px); lineTo(l + c, t + px)
        close()
    }

    companion object {
        const val LIME = 0xffb8f53c.toInt()
        private const val HIGHLIGHT = 0xffecffa6.toInt()
        private const val BLUE = 0xff2448d8.toInt()
        private const val DARK = 0xff0a0f1e.toInt()
        private const val BACKGROUND = 0xff0a0f1e.toInt()
    }
}
