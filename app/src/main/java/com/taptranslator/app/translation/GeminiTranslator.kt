package com.taptranslator.app.translation

import android.util.Log
import com.taptranslator.app.data.model.RecognizedBlock
import com.taptranslator.app.data.model.TranslationTone
import kotlinx.coroutines.Dispatchers
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
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun testApiKey(apiKey: String, model: String = "gemini-1.5-flash"): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API 키가 비어있습니다."))
            }

            val url = "$BASE_URL/$model:generateContent?key=$apiKey"
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Hello, please reply with 'OK' if you can read this.")
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
                return@withContext Result.failure(Exception(errorMsg))
            }

            Result.success("연결 성공! Gemini API 정상 작동 중")
        } catch (e: Exception) {
            Log.e(TAG, "Test API key failed", e)
            Result.failure(e)
        }
    }

    suspend fun translateBlocks(
        blocks: List<RecognizedBlock>,
        apiKey: String,
        model: String,
        sourceLang: String,
        targetLang: String,
        tone: TranslationTone
    ): List<RecognizedBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()
        if (apiKey.isBlank()) throw IllegalArgumentException("Gemini API 키를 설정해주세요.")

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
            throw Exception("Gemini 번역 실패: $errorMsg")
        }

        parseTranslationResponse(body, blocks)
    }

    private fun parseTranslationResponse(responseBody: String, originalBlocks: List<RecognizedBlock>): List<RecognizedBlock> {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: return originalBlocks
        if (candidates.length() == 0) return originalBlocks

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content") ?: return originalBlocks
        val parts = content.optJSONArray("parts") ?: return originalBlocks
        if (parts.length() == 0) return originalBlocks

        var jsonText = parts.getJSONObject(0).optString("text", "").trim()

        // Strip markdown backticks if returned (e.g. ```json ... ```)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse translation JSON: $jsonText", e)
        }

        return originalBlocks.map { block ->
            block.copy(translatedText = translationMap[block.id] ?: block.originalText)
        }
    }
}
