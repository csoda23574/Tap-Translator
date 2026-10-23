package com.taptranslator.app.util

import android.graphics.Rect
import com.taptranslator.app.data.model.RecognizedBlock

object LanguageFilter {

    private const val TAG = "LanguageFilter"

    // Hanzi (Chinese characters): CJK Unified Ideographs & Extension A
    private val HAN_REGEX = Regex("[\\p{IsHan}]")

    // Japanese: Hiragana, Katakana, Half-width Katakana, and Kanji
    private val JAPANESE_HIRAGANA_KATAKANA = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\uFF65-\\uFF9F]")
    private val JAPANESE_ALL = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\uFF65-\\uFF9F\\p{IsHan}]")

    // Korean: Hangul Syllables & Jamo
    private val KOREAN_REGEX = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")

    // Pure numbers, dates, timestamps, battery/status symbols, and punctuation
    private val NOISE_ONLY_REGEX = Regex("^[\\d\\s:.,/\\-+*#!?~()\\[\\]{}'\"%<>@|_=;^&`$\\\\]+$")

    fun isNoiseText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (NOISE_ONLY_REGEX.matches(trimmed)) return true

        val letterCount = trimmed.count { it.isLetter() || HAN_REGEX.matches(it.toString()) }
        if (letterCount == 0) return true

        // Filter out single punctuation/symbol characters
        if (trimmed.length <= 1 && !HAN_REGEX.matches(trimmed) && !KOREAN_REGEX.matches(trimmed) && !JAPANESE_ALL.matches(trimmed)) {
            return true
        }
        return false
    }

    /**
     * Checks if a block of text contains valid characters for the selected source language.
     * Prevents translating unrelated languages or already-target language content.
     */
    fun matchesLanguage(text: String, sourceLang: String, targetLang: String = "ko"): Boolean {
        if (isNoiseText(text)) return false

        // If target is Korean and text is already Korean without foreign script, skip it!
        if (targetLang == "ko") {
            val hasKorean = KOREAN_REGEX.containsMatchIn(text)
            val hasForeign = HAN_REGEX.containsMatchIn(text) || JAPANESE_HIRAGANA_KATAKANA.containsMatchIn(text)
            if (hasKorean && !hasForeign) {
                return false
            }
        }

        return when (sourceLang) {
            "zh" -> {
                // Chinese MUST contain at least one Chinese character (Hanzi)
                HAN_REGEX.containsMatchIn(text)
            }
            "ja" -> {
                // Japanese MUST contain Hiragana, Katakana, or Kanji
                JAPANESE_ALL.containsMatchIn(text)
            }
            "ko" -> {
                // Korean MUST contain Hangul
                KOREAN_REGEX.containsMatchIn(text)
            }
            "en" -> {
                // English must contain Latin letters and NOT be Asian script
                val hasLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
                val hasAsian = HAN_REGEX.containsMatchIn(text) ||
                        KOREAN_REGEX.containsMatchIn(text) ||
                        JAPANESE_HIRAGANA_KATAKANA.containsMatchIn(text)
                hasLatin && !hasAsian
            }
            "auto" -> {
                // Auto mode: accepts any text as long as it's not noise and not already target language
                true
            }
            else -> true
        }
    }

    /**
     * Filters blocks by system UI boundaries, noise, and selected source language.
     * Keeps each line strictly in-place at its original bounding box for pixel-perfect Google Lens replacement.
     */
    fun filterBlocks(
        blocks: List<RecognizedBlock>,
        sourceLang: String,
        targetLang: String = "ko",
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        statusBarHeight: Int = 80,
        navBarHeight: Int = 100
    ): List<RecognizedBlock> {
        val filtered = blocks.filter { block ->
            val r = block.rect
            // 1. Exclude status bar region (clock, battery, telecom indicators)
            if (statusBarHeight > 0 && r.bottom <= statusBarHeight + 8) {
                AppLogger.d(TAG, "Filtered out status bar block: \"${block.originalText}\" [rect=$r]")
                return@filter false
            }
            // 2. Exclude navigation bar region (home/back gestures)
            if (navBarHeight > 0 && r.top >= screenHeight - navBarHeight - 8) {
                AppLogger.d(TAG, "Filtered out navigation bar block: \"${block.originalText}\" [rect=$r]")
                return@filter false
            }
            // 3. Language & noise matching
            val match = matchesLanguage(block.originalText, sourceLang, targetLang)
            if (!match) {
                AppLogger.d(TAG, "Filtered out non-matching block: \"${block.originalText.take(40)}\" (sourceLang=$sourceLang, targetLang=$targetLang)")
            }
            match
        }

        AppLogger.i(TAG, "Language filter ($sourceLang -> $targetLang): ${blocks.size} input blocks -> ${filtered.size} matching blocks")

        // Sort from top to bottom, then left to right, and reassign sequential IDs
        val sorted = filtered.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        return sorted.mapIndexed { index, block ->
            block.copy(id = index)
        }
    }

    // Retain legacy method for backward compatibility if referenced elsewhere
    fun filterAndMergeBlocks(blocks: List<RecognizedBlock>, sourceLang: String): List<RecognizedBlock> {
        return filterBlocks(blocks, sourceLang)
    }
}
