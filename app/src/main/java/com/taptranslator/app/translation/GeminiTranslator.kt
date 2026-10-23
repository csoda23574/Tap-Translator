package com.taptranslator.app.translation

import android.util.Log
import com.taptranslator.app.data.model.RecognizedBlock
import com.taptranslator.app.data.model.TranslationTone
import com.taptranslator.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiTranslator {

    companion object {
        private const val TAG = "GeminiTranslator"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val FALLBACK_STABLE_MODEL = "gemini-flash-latest"
        val DEFAULT_FALLBACK_MODELS = listOf(
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest"
        )
    }

    @Volatile
    private var cachedCandidates: List<String> = emptyList()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Queries Google Generative AI API to automatically detect the latest available Flash model
     * (excluding lite/8b/exp/preview models).
     */
    suspend fun fetchLatestFlashModel(apiKey: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            AppLogger.d(TAG, "fetchLatestFlashModel: Querying available models...")
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.w(TAG, "fetchLatestFlashModel failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val modelsArray = root.optJSONArray("models") ?: return@withContext null

            val candidateModels = mutableListOf<String>()

            for (i in 0 until modelsArray.length()) {
                val item = modelsArray.getJSONObject(i)
                val modelName = item.optString("name", "").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")

                val canGenerate = methods?.let { arr ->
                    (0 until arr.length()).any { arr.optString(it) == "generateContent" }
                } ?: false

                if (!canGenerate) continue

                val lower = modelName.lowercase()
                // Must be Flash text model (exclude lite, 8b, image-only, audio/tts, experimental, preview)
                if (lower.contains("flash") &&
                    !lower.contains("lite") &&
                    !lower.contains("8b") &&
                    !lower.contains("exp") &&
                    !lower.contains("preview") &&
                    !lower.contains("deprecated") &&
                    !lower.contains("image") &&
                    !lower.contains("tts") &&
                    !lower.contains("audio") &&
                    !lower.contains("video") &&
                    !lower.contains("embedding") &&
                    !lower.contains("realtime")
                ) {
                    candidateModels.add(modelName)
                }
            }

            if (candidateModels.isEmpty()) {
                AppLogger.w(TAG, "fetchLatestFlashModel: No matching Flash model candidates found")
                return@withContext null
            }

            // Sort by version descending (3.8 > 3.7 > 3.6 > 3.5 > 2.5 > flash-latest)
            val sorted = candidateModels.sortedWith { m1, m2 ->
                val v1 = extractVersion(m1)
                val v2 = extractVersion(m2)
                if (v1 != v2) {
                    v2.compareTo(v1)
                } else {
                    m1.compareTo(m2)
                }
            }

            cachedCandidates = sorted
            val latest = sorted.firstOrNull()
            AppLogger.i(TAG, "Auto-detected latest Flash model: $latest (filtered candidates: $sorted)")
            return@withContext latest
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error fetching latest Flash model: ${e.message}", e)
            return@withContext null
        }
    }

    private fun extractVersion(modelName: String): Double {
        val regex = Regex("""gemini-(\d+(?:\.\d+)?)""")
        val match = regex.find(modelName)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun isRecoverableError(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return msg.contains("503") ||
                msg.contains("high demand") ||
                msg.contains("429") ||
                msg.contains("resource has been exhausted") ||
                msg.contains("quota") ||
                msg.contains("404") ||
                msg.contains("not found") ||
                msg.contains("500") ||
                msg.contains("overloaded")
    }

    private fun getFallbackCandidates(currentModel: String): List<String> {
        val pool = (cachedCandidates.ifEmpty { DEFAULT_FALLBACK_MODELS }).toMutableList()
        if (!pool.contains(FALLBACK_STABLE_MODEL)) {
            pool.add(FALLBACK_STABLE_MODEL)
        }
        return pool.filter { it != currentModel }
    }

    suspend fun testApiKey(apiKey: String, model: String = "gemini-3.8-flash"): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                AppLogger.w(TAG, "testApiKey failed: API key is blank")
                return@withContext Result.failure(IllegalArgumentException("API 키가 비어있습니다."))
            }

            AppLogger.i(TAG, "testApiKey starting for model=$model")
            val primaryResult = executeTestCall(apiKey, model)

            if (primaryResult.isSuccess) {
                AppLogger.i(TAG, "testApiKey success: ${primaryResult.getOrNull()}")
                return@withContext primaryResult
            }

            // If the model had high demand / temporary failure, test with fallback cascade
            val ex = primaryResult.exceptionOrNull()
            if (ex != null && isRecoverableError(ex)) {
                AppLogger.w(TAG, "testApiKey: $model temporary error (${ex.message}), checking fallback cascade...")
                val fallbacks = getFallbackCandidates(model)
                for (fb in fallbacks) {
                    AppLogger.d(TAG, "testApiKey: testing fallback candidate $fb")
                    val fbResult = executeTestCall(apiKey, fb)
                    if (fbResult.isSuccess) {
                        val msg = "연결 성공! ($model 부하 시 $fb 로 자동 전환)"
                        AppLogger.i(TAG, "testApiKey fallback success: $msg")
                        return@withContext Result.success(msg)
                    }
                }
            }

            AppLogger.e(TAG, "testApiKey failed: ${primaryResult.exceptionOrNull()?.message}")
            primaryResult
        } catch (e: Exception) {
            AppLogger.e(TAG, "Test API key unexpected failure: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun executeTestCall(apiKey: String, model: String): Result<String> {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Ping. Reply with 'OK'.")
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errorObj = JSONObject(body).getJSONObject("error")
                errorObj.getString("message")
            } catch (e: Exception) {
                "HTTP ${response.code}: $body"
            }
            AppLogger.w(TAG, "executeTestCall failed HTTP ${response.code}: $errorMsg")
            return Result.failure(Exception(errorMsg))
        }

        return Result.success("연결 성공! Gemini API 정상 작동 중 ($model)")
    }

    suspend fun translateBlocks(
        blocks: List<RecognizedBlock>,
        apiKey: String,
        model: String,
        sourceLang: String,
        targetLang: String,
        tone: TranslationTone
    ): List<RecognizedBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) {
            AppLogger.d(TAG, "translateBlocks: 0 blocks to translate")
            return@withContext emptyList()
        }
        if (apiKey.isBlank()) {
            AppLogger.e(TAG, "translateBlocks: API key is blank")
            throw IllegalArgumentException("Gemini API 키를 설정해주세요.")
        }

        AppLogger.i(TAG, "translateBlocks start: ${blocks.size} blocks, model=$model, $sourceLang -> $targetLang, tone=${tone.name}")
        val sampleText = blocks.firstOrNull()?.originalText?.take(60)?.replace("\n", " ")
        AppLogger.d(TAG, "Sample block 0 [${blocks.firstOrNull()?.rect}]: \"$sampleText\"")

        // 1. First attempt with requested model
        try {
            val res = executeTranslateCall(blocks, apiKey, model, sourceLang, targetLang, tone)
            AppLogger.i(TAG, "translateBlocks completed successfully with $model: ${res.size} blocks translated")
            return@withContext res
        } catch (e: Exception) {
            val isRecoverable = isRecoverableError(e)
            AppLogger.w(TAG, "Primary model $model failed (recoverable=$isRecoverable): ${e.message}")

            if (isRecoverable) {
                // Try quick retry on primary model
                try {
                    delay(800)
                    AppLogger.d(TAG, "Retrying once on primary model $model after delay...")
                    val res = executeTranslateCall(blocks, apiKey, model, sourceLang, targetLang, tone)
                    AppLogger.i(TAG, "translateBlocks retry on $model succeeded!")
                    return@withContext res
                } catch (retryEx: Exception) {
                    AppLogger.w(TAG, "Retry on $model failed: ${retryEx.message}")
                }
            }

            // Fallback cascade across available candidates
            val fallbackCandidates = getFallbackCandidates(model)
            AppLogger.i(TAG, "Starting fallback cascade through: $fallbackCandidates")

            var lastException: Exception = e
            for (fallbackModel in fallbackCandidates) {
                try {
                    AppLogger.i(TAG, "Attempting fallback model: $fallbackModel")
                    val res = executeTranslateCall(blocks, apiKey, fallbackModel, sourceLang, targetLang, tone)
                    AppLogger.i(TAG, "translateBlocks fallback to $fallbackModel SUCCEEDED! (${res.size} blocks translated)")
                    return@withContext res
                } catch (fbEx: Exception) {
                    AppLogger.w(TAG, "Fallback model $fallbackModel failed: ${fbEx.message}")
                    lastException = fbEx
                }
            }

            AppLogger.e(TAG, "All models in fallback cascade failed: ${lastException.message}", lastException)
            throw lastException
        }
    }

    private fun executeTranslateCall(
        blocks: List<RecognizedBlock>,
        apiKey: String,
        model: String,
        sourceLang: String,
        targetLang: String,
        tone: TranslationTone
    ): List<RecognizedBlock> {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"

        val inputList = JSONArray()
        for (block in blocks) {
            inputList.put(JSONObject().apply {
                put("id", block.id)
                put("text", block.originalText)
            })
        }

        val promptText = buildString {
            appendLine("You are an expert on-screen and game subtitle translator.")
            appendLine("Translate the following numbered texts from '$sourceLang' into '$targetLang'.")
            appendLine("Tone guideline: ${tone.instruction}")
            appendLine("Rules:")
            appendLine("1. Keep each item's 'id' exactly as given.")
            appendLine("2. Ensure translations sound natural in context of a mobile screen / dialogue.")
            appendLine("3. For short UI elements (buttons, tabs, menu items, tags, badges), translate into concise, punchy phrasing suitable for mobile screen limits (do not add verbose or explanatory words).")
            appendLine("4. Return ONLY a valid JSON array of objects with keys 'id' and 'translation'.")
            appendLine("Example output format: [{\"id\": 0, \"translation\": \"...\"}, {\"id\": 1, \"translation\": \"...\"}]")
            appendLine()
            appendLine("Input texts:")
            appendLine(inputList.toString())
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.3)
            })
        }

        AppLogger.d(TAG, "Sending Gemini request to model=$model (payload length: ${requestJson.toString().length})")
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        AppLogger.d(TAG, "Gemini HTTP response code: ${response.code}, body size: ${body.length}")

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errorObj = JSONObject(body).getJSONObject("error")
                errorObj.getString("message")
            } catch (e: Exception) {
                "HTTP ${response.code}: $body"
            }
            AppLogger.e(TAG, "Gemini API error (HTTP ${response.code}): $errorMsg")
            throw Exception(errorMsg)
        }

        return parseTranslationResponse(body, blocks)
    }

    private fun parseTranslationResponse(responseBody: String, originalBlocks: List<RecognizedBlock>): List<RecognizedBlock> {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: run {
            AppLogger.w(TAG, "parseTranslationResponse: No candidates in response")
            return originalBlocks
        }
        if (candidates.length() == 0) {
            AppLogger.w(TAG, "parseTranslationResponse: candidates array is empty")
            return originalBlocks
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content") ?: run {
            AppLogger.w(TAG, "parseTranslationResponse: No content in candidate[0]")
            return originalBlocks
        }
        val parts = content.optJSONArray("parts") ?: run {
            AppLogger.w(TAG, "parseTranslationResponse: No parts in candidate[0].content")
            return originalBlocks
        }
        if (parts.length() == 0) {
            AppLogger.w(TAG, "parseTranslationResponse: parts array is empty")
            return originalBlocks
        }

        var jsonText = parts.getJSONObject(0).optString("text", "").trim()

        if (jsonText.startsWith("```json")) {
            jsonText = jsonText.removePrefix("```json").trim()
        } else if (jsonText.startsWith("```")) {
            jsonText = jsonText.removePrefix("```").trim()
        }
        if (jsonText.endsWith("```")) {
            jsonText = jsonText.removeSuffix("```").trim()
        }

        val translationMap = mutableMapOf<Int, String>()
        try {
            val jsonArray = JSONArray(jsonText)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val id = item.optInt("id", -1)
                val translation = item.optString("translation", "")
                if (id >= 0 && translation.isNotBlank()) {
                    translationMap[id] = translation
                }
            }
            AppLogger.d(TAG, "Successfully parsed JSON with ${translationMap.size} translation entries")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse translation JSON: ${jsonText.take(200)}", e)
        }

        return originalBlocks.map { block ->
            block.copy(translatedText = translationMap[block.id] ?: block.originalText)
        }
    }
}
