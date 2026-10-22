package com.taptranslator.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ExitConfirmDialogView(
    context: Context,
    private val onConfirmExit: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    val windowLayoutParams: WindowManager.LayoutParams

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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val density = resources.displayMetrics.density

        // 1. Semi-transparent dark dimmed background
        setBackgroundColor(Color.argb((255 * 0.55f).toInt(), 0, 0, 0))

        // Clicking outside the card dismisses the dialog
        setOnClickListener {
            onDismiss()
        }

        // 2. Dialog Card Container
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val cardPadding = (24 * density).toInt()
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#1E212A"))
                setStroke((1 * density).toInt(), Color.parseColor("#374151"))
            }
            elevation = 16 * density

            // Prevent clicks on the card from dismissing the dialog
            setOnClickListener { /* no-op */ }
        }

        val cardWidth = (300 * density).toInt()
        val cardParams = LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(card, cardParams)

        // 3. Header Row: Red Power/Exit Icon + Title
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_lock_power_off)
            setColorFilter(Color.parseColor("#EF5350"))
            val iconSize = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        headerRow.addView(iconView)

        val titleView = TextView(context).apply {
            text = "번역기 종료"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(titleView)
        card.addView(headerRow)

        // 4. Description Message
        val messageView = TextView(context).apply {
            text = "화면 번역 플로팅 버튼과 백그라운드 서비스를 모두 종료하시겠습니까?"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val topMargin = (14 * density).toInt()
            val bottomMargin = (22 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, bottomMargin)
            }
            setLineSpacing(0f, 1.25f)
        }
        card.addView(messageView)

        // 5. Button Row: Cancel and Exit Buttons
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnHeight = (42 * density).toInt()

        // Cancel Button
        val cancelBtn = TextView(context).apply {
            text = "취소"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(Color.parseColor("#334155"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onDismiss()
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        buttonRow.addView(cancelBtn)

        // Confirm Exit Button
        val exitBtn = TextView(context).apply {
            text = "종료하기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(Color.parseColor("#DC2626"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onConfirmExit()
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f)
        }
        buttonRow.addView(exitBtn)

        card.addView(buttonRow)
    }
}
