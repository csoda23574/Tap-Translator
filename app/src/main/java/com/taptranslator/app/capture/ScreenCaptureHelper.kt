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
import java.nio.ByteBuffer

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
    @Volatile
    private var lastFrameBitmap: Bitmap? = null

    @Volatile
    var isProjectionAlive = true
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system")
            isProjectionAlive = false
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
        if (!isProjectionAlive) return
        try {
            updateScreenDimensions()

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            // Continuously drain and cache the latest screen frame
            reader.setOnImageAvailableListener({ ir ->
                var img: Image? = null
                try {
                    img = ir.acquireLatestImage()
                    if (img != null) {
                        val bmp = imageToBitmap(img, screenWidth, screenHeight)
                        if (bmp != null) {
                            synchronized(bitmapLock) {
                                lastFrameBitmap?.recycle()
                                lastFrameBitmap = bmp
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onImageAvailable: ${e.message}", e)
                } finally {
                    img?.close()
                }
            }, handler)

            imageReader = reader

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
            Log.d(TAG, "VirtualDisplay created: $screenWidth x $screenHeight, density=$screenDensity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating VirtualDisplay", e)
        }
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        if (!isProjectionAlive) {
            Log.w(TAG, "Capture called but projection is not alive")
            return@withContext null
        }

        // 1. Check if cached frame is ready
        synchronized(bitmapLock) {
            lastFrameBitmap?.let { cached ->
                if (!cached.isRecycled) {
                    return@withContext cached.copy(Bitmap.Config.ARGB_8888, true)
                }
            }
        }

        // 2. Try direct acquire from ImageReader
        try {
            val reader = imageReader
            val directImage = reader?.acquireLatestImage()
            if (directImage != null) {
                try {
                    val bmp = imageToBitmap(directImage, screenWidth, screenHeight)
                    if (bmp != null) {
                        return@withContext bmp
                    }
                } finally {
                    directImage.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct image acquire attempt: ${e.message}")
        }

        // 3. Wait up to 800ms for a frame
        var attempts = 0
        while (attempts < 16) {
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

        Log.w(TAG, "Screen capture timed out after 800ms")
        return@withContext null
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmapWidth = width + rowPadding / pixelStride
            val requiredBytes = bitmapWidth * height * 4

            // Fix the notorious "Buffer not large enough for pixels" Android GPU padding bug
            val safeBuffer: ByteBuffer = if (buffer.remaining() < requiredBytes) {
                val newBuffer = ByteBuffer.allocateDirect(requiredBytes)
                buffer.rewind()
                newBuffer.put(buffer)
                newBuffer.rewind()
                newBuffer
            } else {
                buffer
            }

            val tempBitmap = Bitmap.createBitmap(
                bitmapWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(safeBuffer)

            if (rowPadding == 0) {
                tempBitmap
            } else {
                val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                tempBitmap.recycle()
                cropped
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in imageToBitmap: ${e.message}", e)
            null
        }
    }

    fun release() {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCaptureHelper", e)
        }
    }
}
