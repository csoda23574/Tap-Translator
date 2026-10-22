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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
            // Android 14+ STRICT requirement: Callback MUST be registered before creating VirtualDisplay
            mediaProjection.registerCallback(projectionCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering MediaProjection callback", e)
        }
        updateScreenDimensions()
        initVirtualDisplay()
    }

    fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
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
            imageReader = reader

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TapTranslator_Persistent_Capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            Log.d(TAG, "VirtualDisplay initialized ($screenWidth x $screenHeight)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VirtualDisplay", e)
        }
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        if (isReleased) return@withContext null

        val reader = imageReader ?: run {
            initVirtualDisplay()
            imageReader
        } ?: return@withContext null

        // Try to acquire the latest available image immediately
        var latestImage: Image? = null
        try {
            latestImage = reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed acquiring latest image", e)
        }

        if (latestImage != null) {
            return@withContext try {
                imageToBitmap(latestImage, screenWidth, screenHeight)
            } finally {
                latestImage.close()
            }
        }

        // If no image immediately ready, wait for the next frame via listener
        suspendCancellableCoroutine { continuation ->
            var isHandled = false

            reader.setOnImageAvailableListener({ ir ->
                if (!isHandled) {
                    var img: Image? = null
                    try {
                        img = ir.acquireLatestImage()
                        if (img != null) {
                            isHandled = true
                            reader.setOnImageAvailableListener(null, null)
                            val bitmap = imageToBitmap(img, screenWidth, screenHeight)
                            continuation.resume(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onImageAvailable", e)
                        if (!isHandled) {
                            isHandled = true
                            reader.setOnImageAvailableListener(null, null)
                            continuation.resume(null)
                        }
                    } finally {
                        img?.close()
                    }
                }
            }, handler)

            // Timeout after 1.5 seconds if no frame arrives
            handler.postDelayed({
                if (!isHandled) {
                    isHandled = true
                    reader.setOnImageAvailableListener(null, null)
                    continuation.resume(null)
                }
            }, 1500)
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
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
            mediaProjection.unregisterCallback(projectionCallback)
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCaptureHelper", e)
        }
    }
}
