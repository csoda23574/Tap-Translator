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
import android.provider.Settings
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
import com.taptranslator.app.ui.overlay.ExitConfirmDialogView
import com.taptranslator.app.ui.overlay.FloatingButtonView
import com.taptranslator.app.ui.overlay.ResultOverlayView
import com.taptranslator.app.util.AppLogger
import com.taptranslator.app.util.LanguageFilter
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
    private var exitConfirmDialog: ExitConfirmDialogView? = null

    @Volatile
    private var isProcessing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "FloatingOverlayService onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager.getInstance(this)
        ocrEngine = OcrEngine()
        geminiTranslator = GeminiTranslator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        AppLogger.i(TAG, "onStartCommand action=${intent.action}")
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
                    AppLogger.i(TAG, "MediaProjection data valid. Starting service...")
                    startAsForegroundService()
                    setupMediaProjection(resultCode, resultData)
                    setupFloatingButton()
                    isRunning = true
                    prefs.isServiceRunning = true
                } else {
                    AppLogger.e(TAG, "Missing MediaProjection permission data (code=$resultCode, data=${resultData != null})")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                AppLogger.i(TAG, "Received ACTION_STOP")
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
        if (floatingButtonView != null) {
            AppLogger.d(TAG, "Floating button already exists")
            return
        }

        AppLogger.i(TAG, "Creating FloatingButtonView...")
        floatingButtonView = FloatingButtonView(
            context = this,
            onTranslateClick = {
                handleTranslateClick()
            },
            onLongClickClose = {
                AppLogger.i(TAG, "FloatingButtonView long-clicked -> showing exit confirmation dialog")
                showExitConfirmDialog()
            }
        )

        try {
            windowManager.addView(floatingButtonView, floatingButtonView?.windowLayoutParams)
            AppLogger.i(TAG, "FloatingButtonView added to WindowManager successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to add floating button view to WindowManager", e)
        }
    }

    private fun handleTranslateClick() {
        if (isProcessing) {
            AppLogger.w(TAG, "handleTranslateClick ignored: already processing")
            return
        }
        isProcessing = true
        AppLogger.i(TAG, "=== Translate button clicked ===")

        dismissExitConfirmDialog()
        dismissResultOverlay()

        // 1. Immediately switch button to loading spinner
        floatingButtonView?.setLoading(true)

        serviceScope.launch {
            try {
                // Briefly hide button for screenshot frame
                floatingButtonView?.alpha = 0f
                delay(40)

                val helper = screenCaptureHelper
                if (helper == null || !helper.isProjectionAlive) {
                    AppLogger.e(TAG, "Screen capture projection is dead or null (helper=$helper)")
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "화면 캡처 권한이 만료되었습니다. 앱에서 번역기를 다시 시작해주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                AppLogger.d(TAG, "Requesting screen capture bitmap...")
                val bitmap: Bitmap? = try {
                    helper.captureScreen()
                } finally {
                    floatingButtonView?.alpha = 1f
                }

                if (bitmap == null) {
                    AppLogger.w(TAG, "Screen capture returned null bitmap")
                    Toast.makeText(this@FloatingOverlayService, "화면 캡처 대기 중입니다. 잠시 후 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppLogger.i(TAG, "Screen capture succeeded: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")

                // 2. OCR Text extraction with multi-language fallback
                val sourceLang = prefs.sourceLanguage
                val preferredScript = LanguageOption.SUPPORTED_SOURCE.firstOrNull { it.code == sourceLang }?.scriptType
                    ?: ScriptType.LATIN

                AppLogger.i(TAG, "Starting OCR processing (preferredScript=$preferredScript, sourceLang=$sourceLang)...")
                val rawBlocks = withContext(Dispatchers.Default) {
                    ocrEngine.recognizeText(bitmap, preferredScript)
                }

                if (rawBlocks.isEmpty()) {
                    AppLogger.w(TAG, "OCR found 0 text blocks on the captured screen")
                    Toast.makeText(this@FloatingOverlayService, "화면에서 인식된 텍스트가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppLogger.i(TAG, "Raw OCR extracted ${rawBlocks.size} blocks")

                // 2-1. Filter strictly by selected source language and merge multi-line paragraphs
                val recognizedBlocks = LanguageFilter.filterAndMergeBlocks(rawBlocks, sourceLang)

                if (recognizedBlocks.isEmpty()) {
                    AppLogger.w(TAG, "No blocks matched selected source language ($sourceLang) out of ${rawBlocks.size} OCR blocks")
                    val langName = LanguageOption.SUPPORTED_SOURCE.firstOrNull { it.code == sourceLang }?.displayName?.split(" ")?.first() ?: sourceLang
                    Toast.makeText(this@FloatingOverlayService, "화면에서 $langName 텍스트를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppLogger.i(TAG, "Language filter kept ${recognizedBlocks.size} target blocks (merged & cleaned from ${rawBlocks.size} raw blocks)")

                // 3. Gemini Translation
                val apiKey = prefs.apiKey
                if (apiKey.isBlank()) {
                    AppLogger.e(TAG, "Gemini API key is blank in preferences")
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "Gemini API 키가 설정되지 않았습니다. 앱 설정에서 키를 먼저 등록해주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val targetLang = prefs.targetLanguage
                val tone = prefs.translationTone
                val model = prefs.selectedModel
                AppLogger.i(TAG, "Requesting Gemini translation: model=$model, $sourceLang -> $targetLang, tone=${tone.name}")

                val translatedBlocks = geminiTranslator.translateBlocks(
                    blocks = recognizedBlocks,
                    apiKey = apiKey,
                    model = model,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    tone = tone
                )
                AppLogger.i(TAG, "Gemini translation returned ${translatedBlocks.size} blocks")

                // 4. Display Result Overlay
                showResultOverlay(translatedBlocks)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Translation workflow error: ${e.message}", e)
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
                AppLogger.d(TAG, "=== Translate workflow completed (isProcessing reset) ===")
            }
        }
    }

    private fun showResultOverlay(blocks: List<RecognizedBlock>) {
        dismissResultOverlay()

        val canDraw = Settings.canDrawOverlays(this)
        AppLogger.d(TAG, "showResultOverlay: canDrawOverlays=$canDraw, blocks=${blocks.size}")

        if (!canDraw) {
            AppLogger.e(TAG, "Overlay permission not granted!")
            Toast.makeText(this, "다른 앱 위에 그리기 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            return
        }

        val displayMode = prefs.overlayDisplayMode
        val alpha = prefs.overlayBackgroundAlpha

        val overlay = ResultOverlayView(
            context = this,
            blocks = blocks,
            displayMode = displayMode,
            bgAlpha = alpha,
            onDismiss = {
                AppLogger.d(TAG, "ResultOverlayView dismiss callback invoked")
                dismissResultOverlay()
            }
        )

        try {
            windowManager.addView(overlay, overlay.windowLayoutParams)
            currentResultOverlay = overlay
            AppLogger.i(TAG, "ResultOverlayView added successfully to WindowManager (${blocks.size} blocks)")
            Toast.makeText(this, "⚡ ${blocks.size}개 문장 번역 완료!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed adding ResultOverlayView to WindowManager", e)
            Toast.makeText(this, "오버레이 표시 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissResultOverlay() {
        currentResultOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
                AppLogger.d(TAG, "ResultOverlayView removed")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed removing ResultOverlayView: ${e.message}")
            }
            currentResultOverlay = null
        }
    }

    private fun showExitConfirmDialog() {
        dismissExitConfirmDialog()

        if (!Settings.canDrawOverlays(this)) return

        val dialog = ExitConfirmDialogView(
            context = this,
            onConfirmExit = {
                dismissExitConfirmDialog()
                stopForegroundService()
                Toast.makeText(this, "번역기가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                dismissExitConfirmDialog()
            }
        )

        try {
            windowManager.addView(dialog, dialog.windowLayoutParams)
            exitConfirmDialog = dialog
            AppLogger.i(TAG, "ExitConfirmDialogView added to WindowManager")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed adding ExitConfirmDialogView to WindowManager", e)
        }
    }

    private fun dismissExitConfirmDialog() {
        exitConfirmDialog?.let { dialog ->
            try {
                windowManager.removeView(dialog)
                AppLogger.d(TAG, "ExitConfirmDialogView removed")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed removing ExitConfirmDialogView: ${e.message}")
            }
            exitConfirmDialog = null
        }
    }

    private fun stopForegroundService() {
        AppLogger.i(TAG, "stopForegroundService called")
        dismissExitConfirmDialog()
        dismissResultOverlay()

        floatingButtonView?.let { button ->
            try {
                windowManager.removeView(button)
                AppLogger.d(TAG, "FloatingButtonView removed")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed removing floating button: ${e.message}")
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
