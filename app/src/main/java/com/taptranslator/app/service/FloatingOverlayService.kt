package com.taptranslator.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.taptranslator.app.MainActivity
import com.taptranslator.app.TapTranslatorApp
import com.taptranslator.app.capture.ScreenCaptureHelper
import com.taptranslator.app.data.model.LanguageOption
import com.taptranslator.app.data.model.RecognizedBlock
import com.taptranslator.app.data.model.ScriptType
import com.taptranslator.app.data.preference.PreferencesManager
import com.taptranslator.app.ocr.OcrEngine
import com.taptranslator.app.translation.GeminiTranslator
import com.taptranslator.app.ui.overlay.FloatingButtonView
import com.taptranslator.app.ui.overlay.ResultOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.taptranslator.app.action.START"
        const val ACTION_STOP = "com.taptranslator.app.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager
    private lateinit var ocrEngine: OcrEngine
    private lateinit var geminiTranslator: GeminiTranslator

    private var mediaProjection: MediaProjection? = null
    private var screenCaptureHelper: ScreenCaptureHelper? = null

    private var floatingButtonView: FloatingButtonView? = null
    private var currentResultOverlay: ResultOverlayView? = null

    @Volatile
    private var isProcessing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager.getInstance(this)
        ocrEngine = OcrEngine()
        geminiTranslator = GeminiTranslator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startAsForegroundService()
                    setupMediaProjection(resultCode, resultData)
                    setupFloatingButton()
                    isRunning = true
                    prefs.isServiceRunning = true
                } else {
                    Log.e(TAG, "Missing MediaProjection permission data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
        }

        return START_NOT_STICKY
    }

    private fun startAsForegroundService() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, TapTranslatorApp.CHANNEL_ID)
            .setContentTitle("Tap Translator 실행 중")
            .setContentText("화면의 플로팅 버튼을 눌러 즉석 번역을 실행하세요.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "번역기 종료", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.let { projection ->
            screenCaptureHelper = ScreenCaptureHelper(this, projection)
        }
    }

    private fun setupFloatingButton() {
        if (floatingButtonView != null) return

        floatingButtonView = FloatingButtonView(
            context = this,
            onTranslateClick = {
                handleTranslateClick()
            },
            onLongClickClose = {
                stopForegroundService()
            }
        )

        try {
            windowManager.addView(floatingButtonView, floatingButtonView?.windowLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating button view", e)
        }
    }

    private fun handleTranslateClick() {
        if (isProcessing) return
        isProcessing = true

        dismissResultOverlay()

        // 1. Immediately switch button to loading spinner
        floatingButtonView?.setLoading(true)

        serviceScope.launch {
            try {
                // Briefly hide button for screenshot frame
                floatingButtonView?.alpha = 0f
                delay(30)

                val bitmap: Bitmap? = try {
                    screenCaptureHelper?.captureScreen()
                } finally {
                    floatingButtonView?.alpha = 1f
                }

                if (bitmap == null) {
                    Toast.makeText(this@FloatingOverlayService, "화면 캡처를 준비 중입니다. 잠시 후 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. OCR Text extraction with multi-language fallback
                val sourceLang = prefs.sourceLanguage
                val preferredScript = LanguageOption.SUPPORTED_SOURCE.firstOrNull { it.code == sourceLang }?.scriptType
                    ?: ScriptType.LATIN

                val recognizedBlocks = withContext(Dispatchers.Default) {
                    ocrEngine.recognizeText(bitmap, preferredScript)
                }

                if (recognizedBlocks.isEmpty()) {
                    Toast.makeText(this@FloatingOverlayService, "화면에서 인식된 텍스트가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 3. Gemini Translation
                val apiKey = prefs.apiKey
                if (apiKey.isBlank()) {
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "Gemini API 키가 설정되지 않았습니다. 앱 설정에서 키를 등록해주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val targetLang = prefs.targetLanguage
                val tone = prefs.translationTone
                val model = prefs.selectedModel

                val translatedBlocks = geminiTranslator.translateBlocks(
                    blocks = recognizedBlocks,
                    apiKey = apiKey,
                    model = model,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    tone = tone
                )

                // 4. Display Result Overlay
                showResultOverlay(translatedBlocks)

            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                Toast.makeText(
                    this@FloatingOverlayService,
                    "번역 오류: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                floatingButtonView?.alpha = 1f
                floatingButtonView?.visibility = View.VISIBLE
                floatingButtonView?.setLoading(false)
                isProcessing = false
            }
        }
    }

    private fun showResultOverlay(blocks: List<RecognizedBlock>) {
        dismissResultOverlay()

        val displayMode = prefs.overlayDisplayMode
        val alpha = prefs.overlayBackgroundAlpha

        val overlay = ResultOverlayView(
            context = this,
            blocks = blocks,
            displayMode = displayMode,
            bgAlpha = alpha,
            onDismiss = {
                dismissResultOverlay()
            }
        )

        try {
            windowManager.addView(overlay, overlay.windowLayoutParams)
            currentResultOverlay = overlay
            Toast.makeText(this, "${blocks.size}개 문장 번역 완료!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding ResultOverlayView", e)
        }
    }

    private fun dismissResultOverlay() {
        currentResultOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing ResultOverlayView", e)
            }
            currentResultOverlay = null
        }
    }

    private fun stopForegroundService() {
        dismissResultOverlay()

        floatingButtonView?.let { button ->
            try {
                windowManager.removeView(button)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing floating button", e)
            }
            floatingButtonView = null
        }

        screenCaptureHelper?.release()
        screenCaptureHelper = null
        mediaProjection?.stop()
        mediaProjection = null

        ocrEngine.close()

        isRunning = false
        prefs.isServiceRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopForegroundService()
    }
}
