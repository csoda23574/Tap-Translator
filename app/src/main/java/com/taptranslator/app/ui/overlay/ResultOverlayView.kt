package com.taptranslator.app.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.taptranslator.app.data.model.OverlayDisplayMode
import com.taptranslator.app.data.model.RecognizedBlock
import kotlin.math.max
import kotlin.math.min

class ResultOverlayView(
    context: Context,
    private val blocks: List<RecognizedBlock>,
    private val displayMode: OverlayDisplayMode,
    private val bgAlpha: Float = 0.9f,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    val windowLayoutParams: WindowManager.LayoutParams
    private val density = resources.displayMetrics.density
    private val screenWidth = resources.displayMetrics.widthPixels
    private val screenHeight = resources.displayMetrics.heightPixels

    private var activeComparisonCard: View? = null
    private var highlightedBoxView: TextView? = null

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val isNaturalInplace = (displayMode == OverlayDisplayMode.NATURAL_INPLACE || displayMode == OverlayDisplayMode.BOTH)

        // In Google Lens natural in-place mode, background is 100% transparent so screen/app is crystal clear
        val dimAlpha = if (isNaturalInplace) 0.0f else 0.38f
        setBackgroundColor(Color.argb((255 * dimAlpha).toInt(), 0, 0, 0))

        if (displayMode == OverlayDisplayMode.NATURAL_INPLACE ||
            displayMode == OverlayDisplayMode.OVERLAY_BOXES ||
            displayMode == OverlayDisplayMode.BOTH) {
            setupOverlayBoxes(isNaturalInplace)
        }

        if (displayMode == OverlayDisplayMode.POPUP_CARD || displayMode == OverlayDisplayMode.BOTH) {
            setupBottomCard()
        }

        // Tap outside to dismiss active comparison card or entire overlay
        setOnClickListener {
            if (activeComparisonCard != null) {
                dismissComparisonCard()
            } else {
                onDismiss()
            }
        }
    }

    private fun setupOverlayBoxes(isNaturalInplace: Boolean) {
        val placedBoxes = mutableListOf<Rect>()
        val sortedBlocks = blocks.sortedBy { it.rect.top }

        for (block in sortedBlocks) {
            val text = block.translatedText.ifBlank { block.originalText }.trim()
            if (text.isBlank()) continue

            val r = block.rect
            if (r.width() <= 0 || r.height() <= 0) continue

            if (isNaturalInplace) {
                // === GOOGLE LENS NATURAL IN-PLACE REPLACEMENT ===
                // Strictly in-place placement: no downward cascade shift!
                val padH = (3 * density).toInt()
                val padV = (1 * density).toInt()

                val isSingleLine = !text.contains('\n') && r.height() < (38 * density).toInt()
                val extraW = if (isSingleLine && text.length > block.originalText.length) (8 * density).toInt() else 0
                val boxWidth = (r.width() + padH * 2 + extraW).coerceIn((20 * density).toInt(), screenWidth - (6 * density).toInt())
                val boxHeight = (r.height() + padV * 2).coerceAtLeast((14 * density).toInt())

                var leftPos = r.left - padH - (extraW / 2)
                leftPos = leftPos.coerceIn((2 * density).toInt(), screenWidth - boxWidth - (2 * density).toInt())

                var topPos = r.top - padV
                topPos = topPos.coerceIn((2 * density).toInt(), screenHeight - boxHeight - (2 * density).toInt())

                val boxView = TextView(context).apply {
                    this.text = text
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(block.textColor)
                    gravity = Gravity.CENTER
                    setPadding(padH, padV, padH, padV)

                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 2.5f * density
                        setColor(block.bgColor)
                    }
                    elevation = 0f

                    if (isSingleLine) {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    } else {
                        maxLines = 4
                        ellipsize = TextUtils.TruncateAt.END
                    }

                    // Auto-size text down to fit snugly within the line box without vertical tower explosion
                    val approxSp = (r.height() / density * 0.72f).coerceIn(8f, 16f).toInt()
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this,
                        7,
                        approxSp.coerceAtLeast(10),
                        1,
                        TypedValue.COMPLEX_UNIT_SP
                    )

                    setupTouchListener(this, block, true)
                }

                val boxParams = LayoutParams(boxWidth, boxHeight).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.leftMargin = leftPos
                    this.topMargin = topPos
                }

                addView(boxView, boxParams)

            } else {
                // === CLASSIC FLOATING CARD MODE (with badge & collision avoidance) ===
                val minWidth = (48 * density).toInt()
                val maxWidth = (screenWidth - 24 * density).toInt()
                val boxWidth = r.width().coerceIn(minWidth, maxWidth)

                val approxLineHeightDp = (r.height() / density).coerceAtLeast(14f)
                val fontSizeSp = (approxLineHeightDp * 0.70f).coerceIn(10.5f, 16f)

                val badgePrefix = if (blocks.size > 1) "${getNumberBadge(block.id + 1)} " else ""
                val displayText = "$badgePrefix$text"

                val boxView = TextView(context).apply {
                    this.text = displayText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    val padH = (6 * density).toInt()
                    val padV = (3 * density).toInt()
                    setPadding(padH, padV, padH, padV)

                    background = createBoxBackground(Color.parseColor("#38BDF8"))
                    elevation = 6 * density

                    setupTouchListener(this, block, false)
                }

                var leftPos = r.left.coerceIn((8 * density).toInt(), screenWidth - boxWidth - (8 * density).toInt())
                var topPos = r.top.coerceIn((20 * density).toInt(), screenHeight - (44 * density).toInt())

                // Shift down only in classic floating box mode
                for (placed in placedBoxes) {
                    val horizOverlap = (min(leftPos + boxWidth, placed.right) - max(leftPos, placed.left)) > 0
                    if (horizOverlap && topPos < placed.bottom + (3 * density).toInt() && topPos >= placed.top - (14 * density).toInt()) {
                        topPos = placed.bottom + (3 * density).toInt()
                    }
                }

                val estimatedHeight = r.height().coerceAtLeast((24 * density).toInt())
                placedBoxes.add(Rect(leftPos, topPos, leftPos + boxWidth, topPos + estimatedHeight))

                val boxParams = LayoutParams(boxWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.leftMargin = leftPos
                    this.topMargin = topPos
                }

                addView(boxView, boxParams)
            }
        }
    }

    private fun setupTouchListener(boxView: TextView, block: RecognizedBlock, isNaturalInplace: Boolean) {
        var downTime = 0L
        var isPeeking = false
        val touchHandler = Handler(Looper.getMainLooper())
        val peekRunnable = Runnable {
            isPeeking = true
            boxView.alpha = 0f // Reveal original foreign text underneath
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        boxView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    isPeeking = false
                    touchHandler.postDelayed(peekRunnable, 300)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touchHandler.removeCallbacks(peekRunnable)
                    if (isPeeking) {
                        boxView.alpha = 1f
                        isPeeking = false
                    } else if (System.currentTimeMillis() - downTime < 300) {
                        showComparisonCard(block, boxView, isNaturalInplace)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(peekRunnable)
                    boxView.alpha = 1f
                    isPeeking = false
                    true
                }
                else -> false
            }
        }
    }

    private fun createBoxBackground(borderColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 7 * density
            val alphaInt = (255 * bgAlpha).toInt()
            setColor(Color.argb(alphaInt, 15, 23, 42))
            setStroke((1.5f * density).toInt(), borderColor)
        }
    }

    /**
     * Shows a polished 1:1 comparison popup card when a translated text is tapped.
     * Clearly presents original text vs translation, with copy actions and peek mode.
     */
    private fun showComparisonCard(block: RecognizedBlock, targetBoxView: TextView, isNaturalInplace: Boolean) {
        dismissComparisonCard()

        highlightedBoxView = targetBoxView

        // Highlight active target box with accent border
        if (isNaturalInplace) {
            targetBoxView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3 * density
                setColor(block.bgColor)
                setStroke((2 * density).toInt(), Color.parseColor("#38BDF8"))
            }
        } else {
            targetBoxView.background = createBoxBackground(Color.parseColor("#F59E0B"))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.parseColor("#1E212A"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38BDF8"))
            }
            elevation = 18 * density
            setOnClickListener { /* Consume clicks inside */ }
        }

        // Header: Title + Close
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            val num = block.id + 1
            text = "🌐 원문 ➔ 번역 대조 [문장 #$num]"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener {
                dismissComparisonCard()
            }
        }
        header.addView(closeBtn)
        card.addView(header)

        // 1. Original text container
        val origLabel = TextView(context).apply {
            text = "원문 (Original):"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
            layoutParams = lp
        }
        card.addView(origLabel)

        val origBox = TextView(context).apply {
            text = block.originalText
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#0F172A"))
                setStroke((1 * density).toInt(), Color.parseColor("#334155"))
            }
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
            layoutParams = lp
        }
        card.addView(origBox)

        // 2. Translation text container
        val transLabel = TextView(context).apply {
            text = "번역 (Translation):"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
            layoutParams = lp
        }
        card.addView(transLabel)

        val transBox = TextView(context).apply {
            text = block.translatedText.ifBlank { block.originalText }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#0F172A"))
                setStroke((1 * density).toInt(), Color.parseColor("#1E3A8A"))
            }
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
            layoutParams = lp
        }
        card.addView(transBox)

        // 3. Action Buttons Row: Copy Translation + Peek Original
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (36 * density).toInt()).apply {
                topMargin = (14 * density).toInt()
            }
            layoutParams = lp
        }

        // Copy button
        val copyBtn = TextView(context).apply {
            text = "📋 번역 복사"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#334155"))
            }
            setOnClickListener {
                copyToClipboard("번역문", block.translatedText)
                Toast.makeText(context, "번역문이 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        btnRow.addView(copyBtn)

        // Peek Original button
        val peekBtn = TextView(context).apply {
            text = "👁️ 원문 엿보기 (누르고 있기)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#1E293B"))
                setStroke((1 * density).toInt(), Color.parseColor("#38BDF8"))
            }

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        targetBoxView.alpha = 0f
                        card.alpha = 0.25f
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        targetBoxView.alpha = 1f
                        card.alpha = 1f
                        true
                    }
                    else -> false
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f)
        }
        btnRow.addView(peekBtn)

        card.addView(btnRow)

        // Position card centered horizontally, positioned either above or below the target box
        val cardWidth = (320 * density).toInt().coerceAtMost(screenWidth - (32 * density).toInt())
        val cardParams = LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val boxY = targetBoxView.y.toInt()
            if (boxY > screenHeight / 2) {
                topMargin = (boxY - (220 * density).toInt()).coerceAtLeast((48 * density).toInt())
            } else {
                topMargin = (boxY + targetBoxView.height + (16 * density).toInt()).coerceAtMost(screenHeight - (280 * density).toInt())
            }
        }

        addView(card, cardParams)
        activeComparisonCard = card
    }

    private fun dismissComparisonCard() {
        activeComparisonCard?.let {
            removeView(it)
            activeComparisonCard = null
        }
        highlightedBoxView?.let { boxView ->
            val isNaturalInplace = (displayMode == OverlayDisplayMode.NATURAL_INPLACE || displayMode == OverlayDisplayMode.BOTH)
            if (isNaturalInplace) {
                val block = blocks.firstOrNull { it.translatedText == boxView.text || it.originalText == boxView.text }
                boxView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2.5f * density
                    setColor(block?.bgColor ?: Color.WHITE)
                }
            } else {
                boxView.background = createBoxBackground(Color.parseColor("#38BDF8"))
            }
            highlightedBoxView = null
        }
    }

    private fun setupBottomCard() {
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    20 * density, 20 * density,
                    20 * density, 20 * density,
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#1E1E24"))
                setStroke((1 * density).toInt(), Color.parseColor("#37474F"))
            }
            background = bg
            elevation = 16 * density
            setOnClickListener { /* Consume click */ }
        }

        // Header: Title + Copy All + Close
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "⚡ 번역 결과 (${blocks.size}개 문장)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)

        val copyAllBtn = Button(context).apply {
            text = "전체 복사"
            setTextColor(Color.parseColor("#90CAF9"))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                val fullText = blocks.joinToString("\n\n") { b ->
                    val trans = b.translatedText.ifBlank { b.originalText }
                    "${b.originalText}\n↳ $trans"
                }
                copyToClipboard("전체 번역문", fullText)
                Toast.makeText(context, "전체 번역문이 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(copyAllBtn)

        val closeBtn = Button(context).apply {
            text = "✕ 닫기"
            setTextColor(Color.parseColor("#FF8A80"))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                onDismiss()
            }
        }
        header.addView(closeBtn)

        cardLayout.addView(header)

        // Scrollable content of items
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (240 * density).toInt()
            )
        }

        val contentList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((index, block) in blocks.withIndex()) {
            val itemContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val itemPadding = (10 * density).toInt()
                setPadding(itemPadding, itemPadding, itemPadding, itemPadding)

                val itemBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#2A2A32"))
                }
                background = itemBg
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp

                setOnClickListener {
                    val t = block.translatedText.ifBlank { block.originalText }
                    copyToClipboard("번역문", t)
                    Toast.makeText(context, "복사되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            val origText = TextView(context).apply {
                val numBadge = getNumberBadge(index + 1)
                text = "$numBadge 원문: ${block.originalText}"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            itemContainer.addView(origText)

            val transText = TextView(context).apply {
                text = "↳ 번역: ${block.translatedText.ifBlank { "(번역 결과 없음)" }}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (3 * density).toInt()
                }
                layoutParams = lp
            }
            itemContainer.addView(transText)

            contentList.addView(itemContainer)
        }

        scrollView.addView(contentList)
        cardLayout.addView(scrollView)

        val cardParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        addView(cardLayout, cardParams)
    }

    private fun getNumberBadge(num: Int): String {
        return when (num) {
            1 -> "①"
            2 -> "②"
            3 -> "③"
            4 -> "④"
            5 -> "⑤"
            6 -> "⑥"
            7 -> "⑦"
            8 -> "⑧"
            9 -> "⑨"
            10 -> "⑩"
            else -> "[$num]"
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
