package com.taptranslator.app.util

import android.graphics.Rect
import com.taptranslator.app.data.model.RecognizedBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        if (trimmed.length <= 1 && !trimmed.first().isLetter()) return true
        if (NOISE_ONLY_REGEX.matches(trimmed)) return true
        return false
    }

    /**
     * Checks if a block of text contains valid characters for the selected source language.
     * Prevents translating unrelated languages (e.g. English subtitles/descriptions when Chinese is selected).
     */
    fun matchesLanguage(text: String, sourceLang: String): Boolean {
        if (isNoiseText(text)) return false

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
                // Auto mode: accepts any text as long as it's not pure noise
                true
            }
            else -> {
                // Default: if unrecognized code, allow if not pure noise
                true
            }
        }
    }

    /**
     * Filters blocks by selected source language and intelligently merges vertically adjacent lines
     * of the same paragraph or multi-line title/dialogue to avoid fragmented translations and overlapping boxes.
     */
    fun filterAndMergeBlocks(blocks: List<RecognizedBlock>, sourceLang: String): List<RecognizedBlock> {
        // 1. Language & noise filtering
        val filtered = blocks.filter { block ->
            val match = matchesLanguage(block.originalText, sourceLang)
            if (!match) {
                AppLogger.d(TAG, "Filtered out non-matching block: \"${block.originalText.take(40)}\" (sourceLang=$sourceLang)")
            }
            match
        }

        AppLogger.i(TAG, "Language filter ($sourceLang): ${blocks.size} input blocks -> ${filtered.size} matching blocks")

        if (filtered.size <= 1) return filtered

        // 2. Sort from top to bottom, then left to right
        val sorted = filtered.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))

        // 3. Merge vertically adjacent lines that belong to the same block/title
        val merged = mutableListOf<RecognizedBlock>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (shouldMerge(current, next)) {
                AppLogger.d(TAG, "Merging adjacent lines: \"${current.originalText.take(20)}\" + \"${next.originalText.take(20)}\"")
                val mergedText = "${current.originalText} ${next.originalText}".trim()
                val mergedRect = Rect(
                    min(current.rect.left, next.rect.left),
                    min(current.rect.top, next.rect.top),
                    max(current.rect.right, next.rect.right),
                    max(current.rect.bottom, next.rect.bottom)
                )
                current = current.copy(originalText = mergedText, rect = mergedRect)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged.mapIndexed { index, block ->
            block.copy(id = index)
        }
    }

    private fun shouldMerge(b1: RecognizedBlock, b2: RecognizedBlock): Boolean {
        val r1 = b1.rect
        val r2 = b2.rect

        // Vertical distance from bottom of b1 to top of b2
        val verticalGap = r2.top - r1.bottom
        val avgHeight = (r1.height() + r2.height()) / 2.0

        // Must be directly below with a reasonable line gap
        if (verticalGap < -(r1.height() * 0.4) || verticalGap > avgHeight * 1.3) {
            return false
        }

        // Horizontal alignment check: overlapping horizontally or left-aligned
        val horizontalOverlap = min(r1.right, r2.right) - max(r1.left, r2.left)
        val minWidth = min(r1.width(), r2.width())
        val isHorizontallyAligned = horizontalOverlap > minWidth * 0.35 || abs(r1.left - r2.left) < 80

        return isHorizontallyAligned
    }
}
