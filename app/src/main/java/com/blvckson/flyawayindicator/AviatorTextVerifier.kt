package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

/**
 * OCR evidence for Aviator screen identity. The multiplier's numeric value is
 * deliberately not exposed; only its presence and visual bounds are retained.
 */
internal object AviatorMultiplierTextPattern {
    // Seven integer digits cover the user's very-long-round examples (e.g. 1000004.09x).
    val pattern: Pattern = Pattern.compile(
        "(?<![\\d.])\\b\\d{1,7}(?:\\.\\d{1,3})?\\s*x\\b",
        Pattern.CASE_INSENSITIVE
    )
}

class AviatorTextVerifier {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile private var verifiedUntil = 0L
    @Volatile private var consecutiveMatches = 0
    @Volatile private var lastResultAt = 0L
    @Volatile private var lastResult = "Waiting for Aviator text confirmation"
    @Volatile private var endedUntil = 0L
    @Volatile private var screenPresenceUntil = 0L
    @Volatile private var latestEvidence = AviatorTextEvidence()

    fun inspect(frame: Bitmap, now: Long = System.currentTimeMillis()) {
        if (busy || now - lastResultAt < 280L) return
        busy = true
        lastResultAt = now

        val cropLeft = (frame.width * 0.10f).toInt().coerceIn(0, frame.width - 1)
        val cropTop = (frame.height * 0.08f).toInt().coerceIn(0, frame.height - 1)
        val cropRight = (frame.width * 0.90f).toInt().coerceIn(cropLeft + 1, frame.width)
        val cropBottom = (frame.height * 0.78f).toInt().coerceIn(cropTop + 1, frame.height)
        var crop: Bitmap? = null
        var enlarged: Bitmap? = null
        var scale = 1.0
        try {
            crop = Bitmap.createBitmap(
                frame, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop
            )
            scale = minOf(2.0, 900.0 / maxOf(crop.width, crop.height).toDouble())
            enlarged = Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt().coerceAtLeast(2),
                (crop.height * scale).toInt().coerceAtLeast(2),
                true
            )
            val capturedWidth = frame.width
            val capturedHeight = frame.height
            recognizer.process(InputImage.fromBitmap(enlarged, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.lowercase(Locale.US)
                        .replace(',', '.')
                        .replace('×', 'x')
                    val multiplierPattern = AviatorMultiplierTextPattern.pattern
                    val multiplierLine = result.textBlocks
                        .asSequence()
                        .flatMap { it.lines.asSequence() }
                        .firstOrNull { line ->
                            multiplierPattern.matcher(
                                line.text.lowercase(Locale.US)
                                    .replace(',', '.')
                                    .replace('×', 'x')
                            ).find()
                        }
                    val multiplierPresent = multiplierPattern.matcher(text).find()
                    val multiplierBounds = multiplierLine?.boundingBox?.let { box ->
                        val originalLeft = cropLeft + box.left / scale
                        val originalTop = cropTop + box.top / scale
                        val originalRight = cropLeft + box.right / scale
                        val originalBottom = cropTop + box.bottom / scale
                        MultiplierTextBounds(
                            left = (originalLeft / capturedWidth).coerceIn(0.0, 1.0),
                            top = (originalTop / capturedHeight).coerceIn(0.0, 1.0),
                            right = (originalRight / capturedWidth).coerceIn(0.0, 1.0),
                            bottom = (originalBottom / capturedHeight).coerceIn(0.0, 1.0)
                        ).takeIf { it.isValid() }
                    }
                    if (text.contains("flew away") || text.contains("flewaway")) {
                        endedUntil = System.currentTimeMillis() + 1600L
                        lastResult = "Aviator round end confirmed: FLEW AWAY"
                    } else {
                        // Clear stale end confirmation as soon as OCR sees the new
                        // live screen, preventing a previous result from suppressing
                        // a newly detected round.
                        endedUntil = 0L
                    }

                    val aviatorWord = text.contains("aviator") || text.contains("flew away")
                    val betButtonPresent = Pattern.compile("\\bbet\\b").matcher(text).find()
                    val cashOutPresent = text.contains("cash out") || text.contains("cashout")
                    val betControl = text.contains("bet") &&
                        (text.contains("auto") || cashOutPresent)
                    // Do not let this app's own waiting text (or unrelated text that
                    // merely mentions Aviator) start a GREEN screen session. Require
                    // the game marker/control to be paired with an on-screen multiplier.
                    if (multiplierPresent && (aviatorWord || betControl)) {
                        screenPresenceUntil = System.currentTimeMillis() + 5_000L
                    }
                    val match = multiplierPresent && (aviatorWord || betControl)
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
                    latestEvidence = AviatorTextEvidence(
                        verified = isVerified(),
                        multiplierPresent = multiplierPresent && multiplierBounds != null,
                        aviatorLabelPresent = aviatorWord,
                        gameControlsPresent = betControl,
                        endConfirmed = isEndConfirmed(),
                        betButtonPresent = betButtonPresent,
                        cashOutPresent = cashOutPresent,
                        multiplierBounds = multiplierBounds,
                        observedAtMillis = System.currentTimeMillis()
                    )
                }
                .addOnFailureListener {
                    consecutiveMatches = 0
                    verifiedUntil = 0L
                    latestEvidence = AviatorTextEvidence(observedAtMillis = System.currentTimeMillis())
                    lastResult = "Aviator text check failed; screen identity remains unconfirmed"
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
            latestEvidence = AviatorTextEvidence(observedAtMillis = System.currentTimeMillis())
            lastResult = "Aviator text check unavailable"
        }
    }

    fun isVerified(now: Long = System.currentTimeMillis()): Boolean =
        consecutiveMatches >= 2 && now <= verifiedUntil

    fun isEndConfirmed(now: Long = System.currentTimeMillis()): Boolean = now <= endedUntil

    fun isAviatorScreenVisible(now: Long = System.currentTimeMillis()): Boolean =
        now <= screenPresenceUntil

    internal fun evidence(now: Long = System.currentTimeMillis()): AviatorTextEvidence =
        latestEvidence.copy(verified = isVerified(now), endConfirmed = isEndConfirmed(now))

    fun status(): String = lastResult

    fun close() {
        try { recognizer.close() } catch (_: Throwable) {}
    }
}
