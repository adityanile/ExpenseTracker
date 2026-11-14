package com.adityanile.expensetracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.getSystemService
import java.io.File

object ScreenCaptureManager {

    @Volatile
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var displayDensityDpi: Int = 0

    @Synchronized
    fun initialize(context: Context, resultCode: Int, resultData: android.content.Intent) {
        release()
        val manager = context.getSystemService<MediaProjectionManager>()
            ?: error("MediaProjectionManager unavailable")

        mediaProjection = manager.getMediaProjection(resultCode, resultData)

        val metrics = context.resources.displayMetrics
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensityDpi = metrics.densityDpi

        captureThread = HandlerThread("screenshot-capture").apply { start() }

        imageReader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            3,
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "expense-tracker-capture",
            displayWidth,
            displayHeight,
            displayDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(captureThread!!.looper),
        )
    }

    fun isReady(): Boolean {
        return mediaProjection != null && imageReader != null && virtualDisplay != null
    }

    @Synchronized
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
    }

    @Synchronized
    fun capture(context: Context): Result<File> {
        return captureBitmap()
            .mapCatching { bitmap ->
                val file = ScreenshotStore.save(context, bitmap)
                bitmap.recycle()
                file
            }
    }

    @Synchronized
    fun captureBitmap(): Result<Bitmap> {
        if (mediaProjection == null) {
            return Result.failure(IllegalStateException("Screen capture not initialized"))
        }
        val reader = imageReader
            ?: return Result.failure(IllegalStateException("Screen capture not initialized"))

        return try {
            val image = waitForImage(reader)
                ?: return Result.failure(IllegalStateException("Failed to capture screenshot"))

            image.use {
                val bitmap = imageToBitmap(image, displayWidth, displayHeight)
                Result.success(bitmap)
            }
        } catch (security: SecurityException) {
            release()
            Result.failure(
                IllegalStateException(
                    "Screen capture permission expired. Grant permission again.",
                    security,
                ),
            )
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun waitForImage(reader: ImageReader): Image? {
        val timeoutMs = 1800L
        val started = System.currentTimeMillis()
        var image: Image? = null

        while (image == null && (System.currentTimeMillis() - started) < timeoutMs) {
            image = reader.acquireLatestImage()
            if (image == null) {
                Thread.sleep(40)
            }
        }

        return image
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("No image plane")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return croppedBitmap
    }
}
