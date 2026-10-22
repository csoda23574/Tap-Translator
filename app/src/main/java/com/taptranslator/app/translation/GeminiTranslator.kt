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
        const val FALLBACK_STABLE_MODEL = "gemini-1.5-flash"
    }

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
                // Must be Flash, and must NOT be Lite, 8b, experimental, or preview
                if (lower.contains("flash") &&
                    !lower.contains("lite") &&
                    !lower.contains("8b") &&
                    !lower.contains("exp") &&
                    !lower.contains("preview") &&
                    !lower.contains("deprecated")
                ) {
                    candidateModels.add(modelName)
                }
            }

            if (candidateModels.isEmpty()) {
                AppLogger.w(TAG, "fetchLatestFlashModel: No matching Flash model candidates found")
                return@withContext null
            }

            // Sort by version descending (e.g. 3.8 > 2.5 > 2.0 > 1.5)
            val sorted = candidateModels.sortedWith { m1, m2 ->
                val v1 = extractVersion(m1)
                val v2 = extractVersion(m2)
                v2.compareTo(v1)
            }

            val latest = sorted.firstOrNull()
            AppLogger.i(TAG, "Auto-detected latest Flash model: $latest (candidates: $candidateModels)")
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

    suspend fun testApiKey(apiKey: String, model: String = "gemini-3.8-flash"): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                AppLogger.w(TAG, "testApiKey failed: API key is blank")
                return@withContext Result.failure(IllegalArgumentException("API 키가 비어있습니다."))
            }

            AppLogger.i(TAG, "testApiKey starting for model=$model")
            var targetModel = model
            var result = executeTestCall(apiKey, targetModel)

            // If the model had high demand / temporary failure, test with fallback model
            if (result.isFailure && result.exceptionOrNull()?.message?.contains("high demand", ignoreCase = true) == true) {
                if (targetModel != FALLBACK_STABLE_MODEL) {
                    AppLogger.w(TAG, "testApiKey: $model high demand, falling back to $FALLBACK_STABLE_MODEL")
                    targetModel = FALLBACK_STABLE_MODEL
                    result = executeTestCall(apiKey, targetModel)
                    if (result.isSuccess) {
                        val msg = "연결 성공! ($model 부하 시 $FALLBACK_STABLE_MODEL 로 자동 전환)"
                        AppLogger.i(TAG, "testApiKey fallback success: $msg")
                        return@withContext Result.success(msg)
                    }
                }
            }

            if (result.isSuccess) {
                AppLogger.i(TAG, "testApiKey success: ${result.getOrNull()}")
            } else {
                AppLogger.e(TAG, "testApiKey failed: ${result.exceptionOrNull()?.message}")
            }

            result
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

        // First attempt with requested model
        try {
            val res = executeTranslateCall(blocks, apiKey, model, sourceLang, targetLang, tone)
            AppLogger.i(TAG, "translateBlocks completed successfully: ${res.size} blocks translated")
            return@withContext res
        } catch (e: Exception) {
            val isHighDemand = e.message?.contains("high demand", ignoreCase = true) == true ||
                    e.message?.contains("503", ignoreCase = true) == true

            if (isHighDemand) {
                AppLogger.w(TAG, "Model $model high demand, retrying once after delay...")
                delay(1000)
                try {
                    val res = executeTranslateCall(blocks, apiKey, model, sourceLang, targetLang, tone)
                    AppLogger.i(TAG, "translateBlocks retry succeeded with model $model")
                    return@withContext res
                } catch (retryEx: Exception) {
                    AppLogger.w(TAG, "Retry on $model failed, falling back to $FALLBACK_STABLE_MODEL: ${retryEx.message}")
                    if (model != FALLBACK_STABLE_MODEL) {
                        val fallbackRes = executeTranslateCall(blocks, apiKey, FALLBACK_STABLE_MODEL, sourceLang, targetLang, tone)
                        AppLogger.i(TAG, "translateBlocks fallback to $FALLBACK_STABLE_MODEL succeeded")
                        return@withContext fallbackRes
                    }
                    throw retryEx
                }
            }
            AppLogger.e(TAG, "translateBlocks error: ${e.message}", e)
            throw e
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
            appendLine("3. Return ONLY a valid JSON array of objects with keys 'id' and 'translation'.")
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
