package com.taptranslator.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenCaptureHelper(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {

    companion object {
        private const val TAG = "ScreenCaptureHelper"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    init {
        updateScreenDimensions()
    }

    fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        updateScreenDimensions()

        // Clean up any previous instance
        releaseVirtualDisplay()

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        var isResumed = false

        reader.setOnImageAvailableListener({ ir ->
            var image: Image? = null
            try {
                image = ir.acquireLatestImage()
                if (image != null && !isResumed) {
                    val bitmap = imageToBitmap(image, screenWidth, screenHeight)
                    isResumed = true
                    releaseVirtualDisplay()
                    continuation.resume(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed capturing image from ImageReader", e)
                if (!isResumed) {
                    isResumed = true
                    releaseVirtualDisplay()
                    continuation.resume(null)
                }
            } finally {
                image?.close()
            }
        }, handler)

        // Timeout fallback after 3 seconds
        handler.postDelayed({
            if (!isResumed) {
                isResumed = true
                releaseVirtualDisplay()
                continuation.resume(null)
            }
        }, 3000)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "TapTranslator_Capture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        // Correctly handle row padding alignment in Android GPU memory
        val tempBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        tempBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            tempBitmap
        } else {
            val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            tempBitmap.recycle()
            cropped
        }
    }

    private fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
    }

    fun release() {
        releaseVirtualDisplay()
        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaProjection", e)
        }
    }
}
