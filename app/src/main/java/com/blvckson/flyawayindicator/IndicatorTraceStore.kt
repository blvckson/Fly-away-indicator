package com.blvckson.flyawayindicator

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One sampled row of observable live-round behaviour.
 *
 * This intentionally stores numeric features only, not screenshots, audio,
 * account details, bets, balances, or other screen content.
 */
internal data class IndicatorTraceSample(
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val frameDeltaMs: Long,
    val globalChange: Double,
    val centralChange: Double,
    val planeScore: Double,
    val planeXNorm: Double?,
    val planeYNorm: Double?,
    val planeWidthNorm: Double?,
    val planeHeightNorm: Double?,
    val planePixelCount: Int,
    val planeAspectRatio: Double?,
    val planeFillRatio: Double,
    val geometryChange: Double,
    val shapeChange: Double,
    val movementScore: Double,
    val detectorMovementScore: Double,
    val trend800Ms: Double?,
    val trend250Ms: Double?,
    val candidateScore: Double,
    val baselineScore: Double,
    val preEvidence: Boolean,
    val red: Boolean,
    val ocrVerified: Boolean,
    val visualLive: Boolean,
    val endConfirmed: Boolean
)

/**
 * Writes a compact CSV trace for each confirmed live round.
 *
 * Files are stored under Android/data/<package>/files/Documents/
 * FlyAwayIndicatorTraces, with app-private storage as a fallback.
 * A bounded number of files is retained to avoid unbounded storage growth.
 */
internal class IndicatorTraceStore(private val context: Context) {
    companion object {
        private const val MAX_TRACE_FILES = 40
        private const val FLUSH_EVERY_ROWS = 30

        private const val HEADER =
            "record_type,wall_time_ms,elapsed_ms,frame_delta_ms,global_change,central_change," +
            "plane_score,plane_x_norm,plane_y_norm,plane_width_norm,plane_height_norm," +
            "plane_pixel_count,plane_aspect_ratio,plane_fill_ratio,geometry_change,shape_change," +
            "movement_score,detector_movement_score,trend_800ms,trend_250ms,candidate_score,baseline_score," +
            "pre_evidence,red,ocr_verified,visual_live,end_confirmed,end_reason"
    }

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var pendingRows = 0

    @Synchronized
    fun beginRound(roundNumber: Int, wallTimeMs: Long): String? {
        finishRoundInternal("interrupted_by_new_round", 0L)
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(base, "FlyAwayIndicatorTraces")
        if (!directory.exists() && !directory.mkdirs()) return null

        pruneOldFiles(directory)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(wallTimeMs))
        val file = File(directory, "round-$roundNumber-$stamp.csv")

        return try {
            val output = BufferedWriter(FileWriter(file, false), 64 * 1024)
            output.write(HEADER)
            output.newLine()
            output.write(eventRow("START", wallTimeMs, 0L))
            output.newLine()
            output.flush()
            writer = output
            currentFile = file
            pendingRows = 0
            file.absolutePath
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
            writer = null
            currentFile = null
            null
        }
    }

    @Synchronized
    fun append(sample: IndicatorTraceSample) {
        val output = writer ?: return
        try {
            output.write(
                listOf(
                    "FRAME",
                    sample.wallTimeMs.toString(),
                    sample.elapsedMs.toString(),
                    sample.frameDeltaMs.toString(),
                    number(sample.globalChange),
                    number(sample.centralChange),
                    number(sample.planeScore),
                    optional(sample.planeXNorm),
                    optional(sample.planeYNorm),
                    optional(sample.planeWidthNorm),
                    optional(sample.planeHeightNorm),
                    sample.planePixelCount.toString(),
                    optional(sample.planeAspectRatio),
                    number(sample.planeFillRatio),
                    number(sample.geometryChange),
                    number(sample.shapeChange),
                    number(sample.movementScore),
                    number(sample.detectorMovementScore),
                    optional(sample.trend800Ms),
                    optional(sample.trend250Ms),
                    number(sample.candidateScore),
                    number(sample.baselineScore),
                    sample.preEvidence.toString(),
                    sample.red.toString(),
                    sample.ocrVerified.toString(),
                    sample.visualLive.toString(),
                    sample.endConfirmed.toString(),
                    ""
                ).joinToString(",")
            )
            output.newLine()
            pendingRows++
            if (pendingRows >= FLUSH_EVERY_ROWS) {
                output.flush()
                pendingRows = 0
            }
        } catch (_: Throwable) {
            // A diagnostic-write failure must never stop the live detector.
            closeWriter()
        }
    }

    @Synchronized
    fun finishRound(reason: String, elapsedMs: Long): String? =
        finishRoundInternal(reason, elapsedMs)

    @Synchronized
    fun directoryPath(): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "FlyAwayIndicatorTraces").absolutePath
    }

    private fun finishRoundInternal(reason: String, elapsedMs: Long): String? {
        val file = currentFile
        val output = writer
        if (output != null) {
            try {
                output.write(eventRow("END", System.currentTimeMillis(), elapsedMs, reason))
                output.newLine()
                output.flush()
            } catch (_: Throwable) {
            } finally {
                try { output.close() } catch (_: Throwable) {}
            }
        }
        writer = null
        currentFile = null
        pendingRows = 0
        return file?.absolutePath
    }

    private fun eventRow(type: String, wallTimeMs: Long, elapsedMs: Long, reason: String = ""): String {
        val fields = MutableList(HEADER.split(",").size) { "" }
        fields[0] = type
        fields[1] = wallTimeMs.toString()
        fields[2] = elapsedMs.toString()
        if (reason.isNotBlank()) {
            fields[fields.lastIndex] = "\"${reason.replace("\"", "\"\"")}\""
        }
        return fields.joinToString(",")
    }

    private fun closeWriter() {
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
        currentFile = null
        pendingRows = 0
    }

    private fun pruneOldFiles(directory: File) {
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".csv", ignoreCase = true)
        }?.sortedBy { it.lastModified() } ?: return

        val excess = files.size - (MAX_TRACE_FILES - 1)
        if (excess > 0) {
            files.take(excess).forEach { file ->
                try { file.delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun number(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.6f", value) else ""

    private fun optional(value: Double?): String =
        if (value == null || !value.isFinite()) "" else number(value)
}
