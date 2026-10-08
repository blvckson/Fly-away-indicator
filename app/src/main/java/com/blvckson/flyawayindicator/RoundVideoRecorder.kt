package com.blvckson.flyawayindicator

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoundVideoRecorder(private val projection: MediaProjection, private val context: android.content.Context) {
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputFile: File? = null
    private var width = 0
    private var height = 0
    private var density = 0

    fun start(width: Int, height: Int, density: Int): File? {
        if (recorder != null) return outputFile
        this.width = width
        this.height = height
        this.density = density

        val base = context.getExternalFilesDir("movies") ?: context.filesDir
        val dir = File(base, "FlyAwayIndicator")
        if (!dir.exists() && !dir.mkdirs()) return null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "round_$stamp.mp4")

        val r = MediaRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(6_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(width, height)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            display = projection.createVirtualDisplay(
                "FlyAwayRoundRecording",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            r.start()
            recorder = r
            outputFile = file
            return file
        } catch (_: Throwable) {
            try { r.reset() } catch (_: Throwable) {}
            try { r.release() } catch (_: Throwable) {}
            display?.release()
            display = null
            file.delete()
            return null
        }
    }

    fun stop(): File? {
        val r = recorder ?: return outputFile
        val file = outputFile
        try { r.stop() } catch (_: Throwable) {
            file?.delete()
        }
        try { r.reset() } catch (_: Throwable) {}
        try { r.release() } catch (_: Throwable) {}
        display?.release()
        display = null
        recorder = null
        outputFile = null
        return file?.takeIf { it.exists() && it.length() > 1024L }
    }

    fun release() {
        stop()
    }
}
