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
import com.taptranslator.app.R
import kotlin.math.abs

class FloatingButtonView(
    context: Context,
    private val onTranslateClick: () -> Unit,
    private val onLongClickClose: () -> Unit
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val layoutParams: WindowManager.LayoutParams

    private val buttonSize = (56 * resources.displayMetrics.density).toInt()
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

        layoutParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        // Circular background with elevated shadow
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E88E5")) // Vibrant Material Blue
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        background = bgDrawable
        elevation = 12 * resources.displayMetrics.density

        // Icon inside button
        iconView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_search) // Default icon, can be styled
            setColorFilter(Color.WHITE)
            val padding = (14 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(iconView)

        // Loading spinner inside button
        progressBar = ProgressBar(context).apply {
            val pSize = (32 * resources.displayMetrics.density).toInt()
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
                    initialX = layoutParams.x
                    initialY = layoutParams.y
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
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(this, layoutParams)
                        } catch (e: Exception) {
                            // Ignored if view is detached
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click event
                        onTranslateClick()
                    } else {
                        // Snap to nearest edge (left or right)
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

        val currentX = layoutParams.x
        val targetX = if (currentX + buttonSize / 2 < screenWidth / 2) {
            16 // Snap to left with margin
        } else {
            screenWidth - buttonSize - 16 // Snap to right with margin
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(this@FloatingButtonView, layoutParams)
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }
        animator.start()
    }
}
