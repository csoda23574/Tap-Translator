package com.taptranslator.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    private val bitmapLock = Any()
    private var lastFrameBitmap: Bitmap? = null

    @Volatile
    private var isReleased = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system")
            release()
        }
    }

    init {
        try {
            mediaProjection.registerCallback(projectionCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering MediaProjection callback", e)
        }
        updateScreenDimensions()
        initVirtualDisplay()
    }

    fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = context.resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        if (screenWidth <= 0) screenWidth = 1080
        if (screenHeight <= 0) screenHeight = 2400
        if (screenDensity <= 0) screenDensity = 420
    }

    private fun initVirtualDisplay() {
        if (isReleased) return
        try {
            updateScreenDimensions()

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                3
            )

            // Continuously drain and cache the latest screen frame
            // This prevents ImageReader buffer queue exhaustion and guarantees instant capture
            reader.setOnImageAvailableListener({ ir ->
                var img: Image? = null
                try {
                    img = ir.acquireLatestImage()
                    if (img != null) {
                        val bmp = imageToBitmap(img, screenWidth, screenHeight)
                        synchronized(bitmapLock) {
                            lastFrameBitmap?.recycle()
                            lastFrameBitmap = bmp
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame in listener", e)
                } finally {
                    img?.close()
                }
            }, handler)

            imageReader = reader

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TapTranslator_Continuous_Capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            Log.d(TAG, "Continuous VirtualDisplay initialized ($screenWidth x $screenHeight, density=$screenDensity)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VirtualDisplay", e)
        }
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        if (isReleased) return@withContext null

        // If a frame is already cached, return a fresh copy immediately
        synchronized(bitmapLock) {
            lastFrameBitmap?.let { cached ->
                if (!cached.isRecycled) {
                    return@withContext cached.copy(Bitmap.Config.ARGB_8888, true)
                }
            }
        }

        // If not cached yet (e.g. at immediate startup), wait up to 500ms for a frame
        var attempts = 0
        while (attempts < 10) {
            delay(50)
            synchronized(bitmapLock) {
                lastFrameBitmap?.let { cached ->
                    if (!cached.isRecycled) {
                        return@withContext cached.copy(Bitmap.Config.ARGB_8888, true)
                    }
                }
            }
            attempts++
        }

        Log.w(TAG, "Screen capture timed out waiting for initial frame")
        return@withContext null
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind() // Always rewind buffer before copying

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

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

    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            synchronized(bitmapLock) {
                lastFrameBitmap?.recycle()
                lastFrameBitmap = null
            }
            mediaProjection.unregisterCallback(projectionCallback)
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCaptureHelper", e)
        }
    }
}
