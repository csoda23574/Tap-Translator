package com.taptranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.taptranslator.app.data.model.RecognizedBlock
import com.taptranslator.app.data.model.ScriptType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

                        // Filter out trivial noise or single symbols
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
                    Log.d(TAG, "Recognized ${blocks.size} text blocks using $scriptType")
                    continuation.resume(blocks)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "OCR recognition failed: ${exception.message}", exception)
                    continuation.resumeWithException(exception)
                }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    fun close() {
        try {
            latinRecognizer.close()
            japaneseRecognizer.close()
            koreanRecognizer.close()
            chineseRecognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing recognizers", e)
        }
    }
}
