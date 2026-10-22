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
import android.view.WindowManager
import com.taptranslator.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class ScreenCaptureHelper(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {

    companion object {
        private const val TAG = "ScreenCapture"
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
            AppLogger.w(TAG, "MediaProjection.Callback onStop() invoked by Android system")
            isProjectionAlive = false
            release()
        }
    }

    init {
        try {
            mediaProjection.registerCallback(projectionCallback, handler)
            AppLogger.i(TAG, "MediaProjection callback successfully registered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed registering MediaProjection callback", e)
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
        AppLogger.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density=$screenDensity")
    }

    private fun initVirtualDisplay() {
        if (!isProjectionAlive) {
            AppLogger.w(TAG, "initVirtualDisplay: projection is not alive")
            return
        }
        try {
            updateScreenDimensions()

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

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
                    AppLogger.e(TAG, "Error in onImageAvailable: ${e.message}", e)
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
            AppLogger.i(TAG, "VirtualDisplay created: $screenWidth x $screenHeight, density=$screenDensity, readerSurface=${reader.surface}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed creating VirtualDisplay", e)
        }
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        AppLogger.i(TAG, "captureScreen() called. isProjectionAlive=$isProjectionAlive")
        if (!isProjectionAlive) {
            AppLogger.e(TAG, "Capture failed: MediaProjection is dead/stopped by system")
            return@withContext null
        }

        // 1. Return cached frame if available
        synchronized(bitmapLock) {
            lastFrameBitmap?.let { cached ->
                if (!cached.isRecycled) {
                    AppLogger.d(TAG, "Returning cached frame bitmap: ${cached.width}x${cached.height}")
                    return@withContext cached.copy(Bitmap.Config.ARGB_8888, true)
                }
            }
        }

        // 2. Direct acquire attempt
        try {
            val reader = imageReader
            val directImage = reader?.acquireLatestImage()
            if (directImage != null) {
                try {
                    AppLogger.d(TAG, "Acquired direct image from reader: ${directImage.width}x${directImage.height}")
                    val bmp = imageToBitmap(directImage, screenWidth, screenHeight)
                    if (bmp != null) {
                        return@withContext bmp
                    }
                } finally {
                    directImage.close()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Direct image acquire attempt failed: ${e.message}")
        }

        // 3. Wait up to 1000ms for a frame to arrive
        AppLogger.d(TAG, "Waiting for screen frame...")
        var attempts = 0
        while (attempts < 20) {
            delay(50)
            synchronized(bitmapLock) {
                lastFrameBitmap?.let { cached ->
                    if (!cached.isRecycled) {
                        AppLogger.i(TAG, "Frame arrived at attempt $attempts: ${cached.width}x${cached.height}")
                        return@withContext cached.copy(Bitmap.Config.ARGB_8888, true)
                    }
                }
            }
            attempts++
        }

        AppLogger.e(TAG, "Screen capture TIMED OUT after 1000ms! No frame received.")
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
            AppLogger.e(TAG, "imageToBitmap failed: ${e.message}", e)
            null
        }
    }

    fun release() {
        AppLogger.i(TAG, "Releasing ScreenCaptureHelper resources")
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
            AppLogger.e(TAG, "Error releasing ScreenCaptureHelper", e)
        }
    }
}
