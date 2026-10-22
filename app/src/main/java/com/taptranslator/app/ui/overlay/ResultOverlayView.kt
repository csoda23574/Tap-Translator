package com.taptranslator.app.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
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
import com.taptranslator.app.data.model.OverlayDisplayMode
import com.taptranslator.app.data.model.RecognizedBlock

class ResultOverlayView(
    context: Context,
    private val blocks: List<RecognizedBlock>,
    private val displayMode: OverlayDisplayMode,
    private val bgAlpha: Float = 0.9f,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    val layoutParams: WindowManager.LayoutParams

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
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

        // Semi-transparent dim background (tap to dismiss)
        setBackgroundColor(Color.argb((255 * 0.35f).toInt(), 0, 0, 0))

        if (displayMode == OverlayDisplayMode.OVERLAY_BOXES || displayMode == OverlayDisplayMode.BOTH) {
            setupOverlayBoxes()
        }

        if (displayMode == OverlayDisplayMode.POPUP_CARD || displayMode == OverlayDisplayMode.BOTH) {
            setupBottomCard()
        }

        // Tap outside to dismiss
        setOnClickListener {
            onDismiss()
        }
    }

    private fun setupOverlayBoxes() {
        val density = resources.displayMetrics.density

        for (block in blocks) {
            val text = block.translatedText.ifBlank { block.originalText }
            if (text.isBlank()) continue

            val boxView = TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER

                val padding = (6 * density).toInt()
                setPadding(padding, padding / 2, padding, padding / 2)

                // Background with rounded corners
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * density
                    val alphaInt = (255 * bgAlpha).toInt()
                    setColor(Color.argb(alphaInt, 20, 24, 33))
                    setStroke((1 * density).toInt(), Color.parseColor("#42A5F5"))
                }
                background = bg
                elevation = 6 * density

                // Handle touch on box to copy individual translation
                setOnClickListener {
                    copyToClipboard("번역 텍스트", text)
                    Toast.makeText(context, "텍스트가 복사되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            val r = block.rect
            // Ensure minimum dimensions
            val boxWidth = (r.width().coerceAtLeast((70 * density).toInt()))
            val boxHeight = (r.height().coerceAtLeast((28 * density).toInt()))

            val boxParams = LayoutParams(boxWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = r.left
                topMargin = r.top
            }

            addView(boxView, boxParams)
        }
    }

    private fun setupBottomCard() {
        val density = resources.displayMetrics.density

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    20 * density, 20 * density, // Top-left
                    20 * density, 20 * density, // Top-right
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#1E1E24"))
                setStroke((1 * density).toInt(), Color.parseColor("#37474F"))
            }
            background = bg
            elevation = 16 * density

            // Prevent card touches from closing the overlay
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

        for (block in blocks) {
            val itemContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val itemPadding = (8 * density).toInt()
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
                text = block.originalText
                setTextColor(Color.parseColor("#B0BEC5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            itemContainer.addView(origText)

            val transText = TextView(context).apply {
                text = block.translatedText.ifBlank { "(번역 결과 없음)" }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
