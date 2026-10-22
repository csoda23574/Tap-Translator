package com.taptranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.taptranslator.app.data.model.RecognizedBlock
import com.taptranslator.app.data.model.ScriptType
import com.taptranslator.app.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrEngine {

    companion object {
        private const val TAG = "OcrEngine"
    }

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private fun getRecognizer(scriptType: ScriptType): TextRecognizer {
        return when (scriptType) {
            ScriptType.JAPANESE -> japaneseRecognizer
            ScriptType.KOREAN -> koreanRecognizer
            ScriptType.CHINESE -> chineseRecognizer
            ScriptType.LATIN -> latinRecognizer
        }
    }

    suspend fun recognizeText(
        bitmap: Bitmap,
        preferredScript: ScriptType
    ): List<RecognizedBlock> {
        AppLogger.i(TAG, "recognizeText called with preferredScript=$preferredScript, bitmap=${bitmap.width}x${bitmap.height}")

        // 1. Try preferred script first
        val primaryBlocks = tryRecognize(bitmap, preferredScript)
        if (primaryBlocks.isNotEmpty()) {
            AppLogger.i(TAG, "Preferred script $preferredScript found ${primaryBlocks.size} blocks!")
            return primaryBlocks
        }

        AppLogger.w(TAG, "Preferred script $preferredScript found 0 blocks. Attempting fallback scripts...")

        // 2. Fallback to other language recognizers
        val fallbackScripts = listOf(ScriptType.KOREAN, ScriptType.LATIN, ScriptType.JAPANESE, ScriptType.CHINESE)
            .filter { it != preferredScript }

        for (script in fallbackScripts) {
            val fallbackBlocks = tryRecognize(bitmap, script)
            if (fallbackBlocks.isNotEmpty()) {
                AppLogger.i(TAG, "Fallback recognizer ($script) succeeded! Found ${fallbackBlocks.size} blocks")
                return fallbackBlocks
            }
        }

        AppLogger.w(TAG, "All recognizers returned 0 text blocks from bitmap.")
        return emptyList()
    }

    private suspend fun tryRecognize(
        bitmap: Bitmap,
        scriptType: ScriptType
    ): List<RecognizedBlock> = suspendCancellableCoroutine { continuation ->
        try {
            val recognizer = getRecognizer(scriptType)
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = mutableListOf<RecognizedBlock>()
                    var idCounter = 0

                    for (block in visionText.textBlocks) {
                        val rawText = block.text.trim()
                        val boundingBox = block.boundingBox ?: Rect(0, 0, 0, 0)

                        if (rawText.isNotEmpty() && boundingBox.width() > 10 && boundingBox.height() > 10) {
                            blocks.add(
                                RecognizedBlock(
                                    id = idCounter++,
                                    originalText = rawText,
                                    rect = boundingBox
                                )
                            )
                        }
                    }
                    val sample = blocks.take(3).joinToString("; ") { it.originalText }
                    AppLogger.d(TAG, "Script $scriptType extracted ${blocks.size} blocks (samples: $sample)")
                    continuation.resume(blocks)
                }
                .addOnFailureListener { exception ->
                    AppLogger.e(TAG, "OCR failed for script $scriptType: ${exception.message}", exception)
                    continuation.resume(emptyList())
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "OCR exception for script $scriptType: ${e.message}", e)
            continuation.resume(emptyList())
        }
    }

    fun close() {
        try {
            latinRecognizer.close()
            japaneseRecognizer.close()
            koreanRecognizer.close()
            chineseRecognizer.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error closing recognizers", e)
        }
    }
}
