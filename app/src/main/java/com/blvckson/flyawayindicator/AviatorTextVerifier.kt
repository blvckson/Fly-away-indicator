package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

/**
 * Independent OCR confirmation for the Aviator game screen.
 * A red object or a changing screen is not enough to start recording.
 */
class AviatorTextVerifier {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile private var verifiedUntil = 0L
    @Volatile private var consecutiveMatches = 0
    @Volatile private var lastResultAt = 0L
    @Volatile private var lastResult = "Waiting for Aviator text confirmation"

    fun inspect(frame: Bitmap, now: Long = System.currentTimeMillis()) {
        if (busy || now - lastResultAt < 280L) return
        busy = true
        lastResultAt = now

        val left = (frame.width * 0.10f).toInt().coerceIn(0, frame.width - 1)
        val top = (frame.height * 0.08f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width * 0.90f).toInt().coerceIn(left + 1, frame.width)
        val bottom = (frame.height * 0.78f).toInt().coerceIn(top + 1, frame.height)
        var crop: Bitmap? = null
        var enlarged: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val scale = minOf(2.0, 900.0 / maxOf(crop.width, crop.height).toDouble())
            enlarged = Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt().coerceAtLeast(2),
                (crop.height * scale).toInt().coerceAtLeast(2),
                true
            )
            recognizer.process(InputImage.fromBitmap(enlarged, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.lowercase(Locale.US)
                        .replace(',', '.')
                        .replace('×', 'x')
                    val multiplier = Pattern.compile(
                        "\\b\\d{1,5}(?:\\.\\d{1,3})?\\s*x\\b",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(text).find()
                    val aviatorWord = text.contains("aviator") || text.contains("flew away")
                    val betControl = text.contains("bet") &&
                        (text.contains("auto") || text.contains("cash out") ||
                         text.contains("cashout"))
                    val match = multiplier && (aviatorWord || betControl)
                    if (match) {
                        consecutiveMatches = (consecutiveMatches + 1).coerceAtMost(4)
                        if (consecutiveMatches >= 2) {
                            verifiedUntil = System.currentTimeMillis() + 1800L
                            lastResult = "Aviator text confirmed"
                        } else {
                            lastResult = "Confirming Aviator screen (" + consecutiveMatches + "/2)"
                        }
                    } else {
                        consecutiveMatches = 0
                        verifiedUntil = 0L
                        lastResult = "Waiting for multiplier + game controls"
                    }
                }
                .addOnFailureListener {
                    consecutiveMatches = 0
                    verifiedUntil = 0L
                    lastResult = "Aviator text check failed; recording remains gated"
                }
                .addOnCompleteListener {
                    try { enlarged?.recycle() } catch (_: Throwable) {}
                    try { crop?.recycle() } catch (_: Throwable) {}
                    busy = false
                }
        } catch (_: Throwable) {
            try { enlarged?.recycle() } catch (_: Throwable) {}
            try { crop?.recycle() } catch (_: Throwable) {}
            busy = false
            consecutiveMatches = 0
            verifiedUntil = 0L
            lastResult = "Aviator text check unavailable"
        }
    }

    fun isVerified(now: Long = System.currentTimeMillis()): Boolean =
        consecutiveMatches >= 2 && now <= verifiedUntil

    fun status(): String = lastResult

    fun close() {
        try { recognizer.close() } catch (_: Throwable) {}
    }
}
