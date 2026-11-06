package com.backdropcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the text layer that goes on top of the video: name title (lower third), intro card
 * and live captions. Everything is laid out relative to the visible picture, so it looks the
 * same in portrait, landscape and square.
 */
class OverlayComposer {

    class State(
        val name: String = "",
        val role: String = "",
        val brandColor: Int = DEFAULT_COLOR,
        val showNameTitle: Boolean = false,
        val introTitle: String = "",
        val introSubtitle: String = "",
        /** 0 = hidden, 1 = fully shown (fades in and out). */
        val introAlpha: Float = 0f,
        val caption: String = "",
    ) {
        val isEmpty: Boolean
            get() = !(showNameTitle && name.isNotBlank()) && introAlpha <= 0f && caption.isBlank()
    }

    /** Returns the overlay for a picture of visW x visH, or null when there is nothing to draw. */
    fun render(visW: Float, visH: Float, s: State): Bitmap? {
        if (s.isEmpty || visW <= 0f || visH <= 0f) return null
        val scale = LONG_SIDE / max(visW, visH)
        val w = (visW * scale).roundToInt().coerceAtLeast(2)
        val h = (visH * scale).roundToInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val unit = min(w, h) / 100f // 1 % of the short side

        // With captions showing, the name title moves up so the two never overlap.
        if (s.showNameTitle && s.name.isNotBlank()) drawNameTitle(c, w, h, unit, s, if (s.caption.isNotBlank()) unit * 14f else 0f)
        if (s.caption.isNotBlank()) drawCaption(c, w, h, unit, s.caption)
        if (s.introAlpha > 0f) drawIntro(c, w, h, unit, s)
        return bmp
    }

    /** Lower third: a coloured bar with name and role, bottom left, above any captions. */
    private fun drawNameTitle(c: Canvas, w: Int, h: Int, unit: Float, s: State, lift: Float) {
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 5.2f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rolePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textSize = unit * 3.6f
        }
        val pad = unit * 2.4f
        val textW = max(namePaint.measureText(s.name), if (s.role.isBlank()) 0f else rolePaint.measureText(s.role))
        val boxH = pad * 2 + namePaint.textSize + if (s.role.isBlank()) 0f else rolePaint.textSize * 1.35f
        val left = unit * 5f
        val bottom = h - unit * 18f - lift
        val top = bottom - boxH
        val bar = unit * 1.4f
        val box = RectF(left, top, left + bar + pad * 2 + textW, bottom)
        c.drawRoundRect(box, unit * 1.2f, unit * 1.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC111114.toInt() })
        c.drawRect(left, top, left + bar, bottom, Paint().apply { color = s.brandColor })
        val tx = left + bar + pad
        c.drawText(s.name, tx, top + pad + namePaint.textSize * 0.82f, namePaint)
        if (s.role.isNotBlank()) {
            c.drawText(s.role, tx, top + pad + namePaint.textSize + rolePaint.textSize * 1.1f, rolePaint)
        }
    }

    /** Captions: up to two lines, centred near the bottom, white on a dark rounded box. */
    private fun drawCaption(c: Canvas, w: Int, h: Int, unit: Float, text: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxW = (w * 0.86f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.START)
            .build()
        var lineW = 0f
        for (i in 0 until layout.lineCount) lineW = max(lineW, layout.getLineWidth(i))
        val pad = unit * 1.8f
        val top = h - unit * 14f - layout.height
        val box = RectF((w - lineW) / 2f - pad, top - pad, (w + lineW) / 2f + pad, top + layout.height + pad)
        c.drawRoundRect(box, unit * 1.5f, unit * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        c.save()
        c.translate((w - maxW) / 2f, top)
        layout.draw(c)
        c.restore()
    }

    /** Intro card: darkened picture with a big title and a subtitle, fading in and out. */
    private fun drawIntro(c: Canvas, w: Int, h: Int, unit: Float, s: State) {
        val a = s.introAlpha.coerceIn(0f, 1f)
        c.drawColor(Color.argb((a * 170).toInt(), 0, 0, 0))
        val title = s.introTitle.ifBlank { s.name }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((a * 255).toInt(), 255, 255, 255)
            textSize = unit * 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, (w * 0.84f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (s.brandColor and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        }
        val top = h / 2f - layout.height / 2f - unit * 3f
        c.save()
        c.translate(w * 0.08f, top)
        layout.draw(c)
        c.restore()
        val lineY = top + layout.height + unit * 2.5f
        c.drawRoundRect(RectF(w / 2f - unit * 8f, lineY, w / 2f + unit * 8f, lineY + unit * 1.1f), unit, unit, accent)
        val sub = s.introSubtitle.ifBlank { s.role }
        if (sub.isNotBlank()) {
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((a * 230).toInt(), 255, 255, 255)
                textSize = unit * 4.6f
                textAlign = Paint.Align.CENTER
            }
            c.drawText(sub, w / 2f, lineY + unit * 8f, subPaint)
        }
    }

    companion object {
        /** Overlay resolution along the long side (sharp at 1080p, fine at 4K). */
        private const val LONG_SIDE = 1920f
        const val DEFAULT_COLOR = 0xFFFFC107.toInt()
        val BRAND_COLORS = intArrayOf(
            0xFFFFC107.toInt(), 0xFF3D5AFE.toInt(), 0xFF7C4DFF.toInt(), 0xFFE91E63.toInt(),
            0xFF00BFA5.toInt(), 0xFFFF5722.toInt(), 0xFF212121.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
