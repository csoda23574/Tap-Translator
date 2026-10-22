package com.taptranslator.app.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import kotlin.math.abs

class FloatingButtonView(
    context: Context,
    private val onTranslateClick: () -> Unit,
    private val onLongClickClose: () -> Unit
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowLayoutParams: WindowManager.LayoutParams

    // Reduced by 15% from 56dp to 48dp
    private val buttonSize = (48 * resources.displayMetrics.density).toInt()
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val iconView: ImageView
    private val progressBar: ProgressBar

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowLayoutParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 350
        }

        // Circular background with elevated shadow
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E88E5"))
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        background = bgDrawable
        elevation = 10 * resources.displayMetrics.density

        // Icon inside button
        iconView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.WHITE)
            val padding = (11 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(iconView)

        // Loading spinner inside button
        progressBar = ProgressBar(context).apply {
            val pSize = (24 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(pSize, pSize).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
            indeterminateDrawable?.setTint(Color.WHITE)
        }
        addView(progressBar)

        setupTouchListener()
    }

    fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            iconView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
        }
    }

    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowLayoutParams.x
                    initialY = windowLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        windowLayoutParams.x = initialX + dx
                        windowLayoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(this, windowLayoutParams)
                        } catch (e: Exception) {
                            // Ignored if view is detached
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onTranslateClick()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels

        val currentX = windowLayoutParams.x
        val targetX = if (currentX + buttonSize / 2 < screenWidth / 2) {
            12 // Snap to left with margin
        } else {
            screenWidth - buttonSize - 12 // Snap to right with margin
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                windowLayoutParams.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(this@FloatingButtonView, windowLayoutParams)
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }
        animator.start()
    }
}
