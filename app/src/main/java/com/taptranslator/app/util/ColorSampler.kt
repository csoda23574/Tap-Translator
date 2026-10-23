package com.taptranslator.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ColorSampler {

    data class BlockColors(
        val bgColor: Int,
        val textColor: Int
    )

    /**
     * Accurately samples background color from bounding box perimeter and text color from
     * contrasting interior strokes. Guarantees clean inpainting and maximum readability.
     */
    fun sampleBlockColors(bitmap: Bitmap, rect: Rect): BlockColors {
        val width = bitmap.width
        val height = bitmap.height

        val left = rect.left.coerceIn(0, width - 1)
        val right = rect.right.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val bottom = rect.bottom.coerceIn(0, height - 1)

        val rectW = right - left
        val rectH = bottom - top

        if (rectW <= 2 || rectH <= 2) {
            return BlockColors(Color.WHITE, Color.BLACK)
        }

        // 1. Sample perimeter pixels (background candidates)
        val perimeterPixels = mutableListOf<Int>()
        val stepX = max(1, rectW / 24)
        val stepY = max(1, rectH / 12)

        // Top & bottom perimeter
        for (x in left..right step stepX) {
            perimeterPixels.add(bitmap.getPixel(x, top))
            perimeterPixels.add(bitmap.getPixel(x, bottom))
        }
        // Left & right perimeter
        for (y in top..bottom step stepY) {
            perimeterPixels.add(bitmap.getPixel(left, y))
            perimeterPixels.add(bitmap.getPixel(right, y))
        }

        val dominantBg = getDominantColor(perimeterPixels, fallback = Color.WHITE)
        val bgLuminance = calculateLuminance(dominantBg)

        // 2. Sample interior pixels for text color (pixels that contrast with dominantBg)
        val textCandidates = mutableListOf<Int>()
        val innerMarginX = max(1, (rectW * 0.08).toInt())
        val innerMarginY = max(1, (rectH * 0.08).toInt())

        val innerLeft = left + innerMarginX
        val innerRight = right - innerMarginX
        val innerTop = top + innerMarginY
        val innerBottom = bottom - innerMarginY

        if (innerRight > innerLeft && innerBottom > innerTop) {
            val innerStepX = max(1, (innerRight - innerLeft) / 20)
            val innerStepY = max(1, (innerBottom - innerTop) / 10)

            for (x in innerLeft..innerRight step innerStepX) {
                for (y in innerTop..innerBottom step innerStepY) {
                    val p = bitmap.getPixel(x, y)
                    val pLum = calculateLuminance(p)
                    // Must have meaningful luminance difference from background
                    if (abs(pLum - bgLuminance) >= 0.22) {
                        textCandidates.add(p)
                    }
                }
            }
        }

        var dominantText = if (textCandidates.isNotEmpty()) {
            getDominantColor(textCandidates, fallback = if (bgLuminance > 0.5) Color.BLACK else Color.WHITE)
        } else {
            if (bgLuminance > 0.5) Color.parseColor("#111827") else Color.WHITE
        }

        // 3. Contrast Safeguard (ensure text is always readable against the background)
        val textLuminance = calculateLuminance(dominantText)
        if (abs(bgLuminance - textLuminance) < 0.28) {
            dominantText = if (bgLuminance > 0.5) {
                Color.parseColor("#0F172A") // Deep slate black
            } else {
                Color.WHITE
            }
        }

        return BlockColors(dominantBg, dominantText)
    }

    /**
     * Bins RGB colors into 5-bit color buckets to find the most dominant cluster.
     */
    private fun getDominantColor(pixels: List<Int>, fallback: Int): Int {
        if (pixels.isEmpty()) return fallback

        val bins = mutableMapOf<Int, Int>()
        val rawColorSum = mutableMapOf<Int, Triple<Long, Long, Long>>()

        for (p in pixels) {
            // Quantize to 5-bit (step of 8)
            val rBucket = (Color.red(p) / 10) * 10
            val gBucket = (Color.green(p) / 10) * 10
            val bBucket = (Color.blue(p) / 10) * 10
            val bucketKey = Color.rgb(rBucket, gBucket, bBucket)

            bins[bucketKey] = (bins[bucketKey] ?: 0) + 1

            val currentSum = rawColorSum[bucketKey] ?: Triple(0L, 0L, 0L)
            rawColorSum[bucketKey] = Triple(
                currentSum.first + Color.red(p),
                currentSum.second + Color.green(p),
                currentSum.third + Color.blue(p)
            )
        }

        val topBucket = bins.maxByOrNull { it.value }?.key ?: return fallback
        val count = bins[topBucket] ?: 1
        val sum = rawColorSum[topBucket] ?: return topBucket

        // Return the average exact color within the winning bucket
        val avgR = (sum.first / count).toInt().coerceIn(0, 255)
        val avgG = (sum.second / count).toInt().coerceIn(0, 255)
        val avgB = (sum.third / count).toInt().coerceIn(0, 255)

        return Color.rgb(avgR, avgG, avgB)
    }

    /**
     * Relative luminance (ITU-R BT.709)
     */
    private fun calculateLuminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
