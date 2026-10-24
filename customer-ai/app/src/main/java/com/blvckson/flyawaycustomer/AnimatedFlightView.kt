package com.blvckson.flyawaycustomer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** Lightweight Aviator-inspired motion banner: smoky sky, red trails, sketch plane and falling notes. */
class AnimatedFlightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startedAt = System.currentTimeMillis()
    private var active = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        active = false
        removeCallbacks(null)
        super.onDetachedFromWindow()
    }

    private fun color(value: Int) { paint.color = value; paint.style = Paint.Style.FILL; paint.strokeWidth = 1f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sx = w / 360f
        val sy = h / 142f
        canvas.save()
        canvas.scale(sx, sy)
        color(Color.rgb(38, 40, 44)); canvas.drawRect(0f, 0f, 360f, 142f, paint)
        val t = (System.currentTimeMillis() - startedAt) / 1000f
        // Soft smoke clouds
        for (i in 0..5) {
            val x = ((i * 73f - t * 10f) % 410f + 410f) % 410f - 25f
            color(if (i % 2 == 0) Color.rgb(79, 82, 87) else Color.rgb(58, 61, 66))
            canvas.drawCircle(x, 110f + sin(i.toDouble()).toFloat() * 9f, 22f + (i % 3) * 4f, paint)
            canvas.drawCircle(x + 17f, 105f, 18f, paint)
        }
        // Red speed trails
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f; paint.color = Color.rgb(238, 35, 47)
        for (i in 0..3) {
            val y = 22f + i * 22f
            canvas.drawLine(0f, y + 12f, 110f + (i * 9), y - 8f, paint)
        }
        paint.style = Paint.Style.FILL
        // Falling money notes loop down the sky
        for (i in 0..5) {
            val x = 40f + i * 53f + sin((t + i).toDouble()).toFloat() * 9f
            val y = ((t * (25f + i * 2f) + i * 29f) % 170f) - 20f
            canvas.save()
            canvas.rotate(sin((t + i).toDouble()).toFloat() * 18f, x, y)
            color(Color.rgb(238, 238, 235)); canvas.drawRoundRect(RectF(x - 9f, y - 5f, x + 9f, y + 5f), 2f, 2f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(220, 35, 47)
            canvas.drawRect(x - 7f, y - 3f, x + 7f, y + 3f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 8f; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.color = Color.rgb(55, 57, 61); canvas.drawText("$", x - 2.5f, y + 3f, paint)
            canvas.restore()
        }
        // Hand-drawn style plane: charcoal fuselage, white body, red wing accents
        val px = 165f + sin(t.toDouble() * 0.8).toFloat() * 10f
        val py = 43f + sin(t.toDouble() * 1.3).toFloat() * 4f
        canvas.save()
        canvas.rotate(-8f, px, py)
        val wing = Path().apply { moveTo(px - 65f, py + 7f); lineTo(px + 6f, py - 4f); lineTo(px + 44f, py + 11f); lineTo(px - 14f, py + 14f); close() }
        color(Color.BLACK); canvas.drawPath(wing, paint)
        val wingRed = Path().apply { moveTo(px - 52f, py + 7f); lineTo(px + 5f, py + 1f); lineTo(px + 31f, py + 9f); lineTo(px - 13f, py + 11f); close() }
        color(Color.rgb(231, 35, 48)); canvas.drawPath(wingRed, paint)
        val body = Path().apply { moveTo(px - 48f, py + 1f); lineTo(px + 41f, py - 8f); lineTo(px + 58f, py - 2f); lineTo(px + 39f, py + 4f); lineTo(px - 48f, py + 5f); close() }
        color(Color.rgb(235, 236, 235)); canvas.drawPath(body, paint)
        color(Color.rgb(230, 35, 47)); canvas.drawRect(px - 20f, py - 2f, px + 32f, py, paint)
        val tail = Path().apply { moveTo(px - 43f, py + 2f); lineTo(px - 57f, py - 12f); lineTo(px - 52f, py + 4f); close() }
        color(Color.rgb(232, 35, 48)); canvas.drawPath(tail, paint)
        color(Color.rgb(30, 31, 34)); canvas.drawOval(RectF(px - 7f, py - 7f, px + 12f, py - 2f), paint)
        // propeller and red hub
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Color.WHITE
        canvas.drawLine(px + 57f, py - 9f, px + 57f, py + 7f, paint)
        paint.style = Paint.Style.FILL; color(Color.rgb(236, 34, 48)); canvas.drawCircle(px + 57f, py - 1f, 3.5f, paint)
        canvas.restore()
        // Brush-like white/red frame strokes
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(239, 239, 235)
        canvas.drawArc(RectF(5f, 5f, 355f, 137f), 195f, 125f, false, paint)
        paint.strokeWidth = 3f; paint.color = Color.rgb(238, 35, 48)
        canvas.drawArc(RectF(5f, 5f, 355f, 137f), 15f, 75f, false, paint)
        canvas.restore()
        if (active) postInvalidateDelayed(40L)
    }
}