package com.adityanile.expensetracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

class PaymentOverlayAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var edgeState = EdgeState.RIGHT

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        Log.d(TAG, "Accessibility service connected")
        scheduleOverlayRetry()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (overlayView == null) {
            scheduleOverlayRetry()
        }
    }

    override fun onInterrupt() {
        hideOverlaySafely()
    }

    override fun onDestroy() {
        hideOverlaySafely()
        super.onDestroy()
    }

    private fun scheduleOverlayRetry() {
        showOverlaySafely()
        mainHandler.postDelayed({ showOverlaySafely() }, 300)
        mainHandler.postDelayed({ showOverlaySafely() }, 1000)
    }

    private fun showOverlaySafely() {
        if (overlayView != null) return

        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bubbleSize = dp(46)
            val visiblePeek = dp(14)
            val dragThreshold = dp(6)
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val bubble = FrameLayout(this).apply {
                minimumWidth = bubbleSize
                minimumHeight = bubbleSize
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(232, 255, 255, 255))
                    setStroke(dp(1), Color.argb(160, 35, 35, 35))
                }
                alpha = ACTIVE_ALPHA
                elevation = dp(10).toFloat()
                isClickable = true
                isFocusable = false
            }

            val leftHiddenX = -(bubbleSize - visiblePeek)
            val rightHiddenX = screenWidth - visiblePeek
            val minY = dp(80)
            val maxY = screenHeight - bubbleSize - dp(80)

            val layoutParams = WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = rightHiddenX
                y = screenHeight / 2
            }

            val idleRunnable = Runnable {
                snapToEdge(windowManager, bubble, layoutParams, leftHiddenX, rightHiddenX)
                bubble.alpha = IDLE_ALPHA
            }

            bubble.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var dragged = false

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            mainHandler.removeCallbacks(idleRunnable)
                            bubble.alpha = ACTIVE_ALPHA
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            dragged = false
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (!dragged && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                                dragged = true
                            }

                            if (dragged) {
                                layoutParams.x = (initialX + dx).coerceIn(leftHiddenX, rightHiddenX)
                                layoutParams.y = (initialY + dy).coerceIn(minY, maxY)
                                windowManager.updateViewLayout(bubble, layoutParams)
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (!dragged) {
                                view.performClick()
                                triggerCapture()
                            }
                            snapToEdge(windowManager, bubble, layoutParams, leftHiddenX, rightHiddenX)
                            mainHandler.postDelayed(idleRunnable, IDLE_DELAY_MS)
                            return true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            snapToEdge(windowManager, bubble, layoutParams, leftHiddenX, rightHiddenX)
                            mainHandler.postDelayed(idleRunnable, IDLE_DELAY_MS)
                            return true
                        }

                        else -> return false
                    }
                }
            })

            windowManager.addView(bubble, layoutParams)
            overlayView = bubble
            overlayLayoutParams = layoutParams
            edgeState = EdgeState.RIGHT
            mainHandler.postDelayed(idleRunnable, IDLE_DELAY_MS)
            Log.d(TAG, "Overlay bubble attached")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to add overlay", error)
        }
    }

    private fun snapToEdge(
        windowManager: WindowManager,
        bubble: View,
        layoutParams: WindowManager.LayoutParams,
        leftHiddenX: Int,
        rightHiddenX: Int,
    ) {
        val middle = resources.displayMetrics.widthPixels / 2
        edgeState = if (layoutParams.x < middle) EdgeState.LEFT else EdgeState.RIGHT
        layoutParams.x = if (edgeState == EdgeState.LEFT) leftHiddenX else rightHiddenX
        windowManager.updateViewLayout(bubble, layoutParams)
    }

    private fun triggerCapture() {
        thread {
            val result = ScreenCaptureManager.capture(applicationContext)
            Handler(Looper.getMainLooper()).post {
                if (result.isSuccess) {
                    Toast.makeText(
                        applicationContext,
                        "Saved: ${result.getOrThrow().name}",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Capture failed. Open app and grant permission first.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun hideOverlaySafely() {
        val currentView = overlayView ?: return
        try {
            mainHandler.removeCallbacksAndMessages(null)
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(currentView)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to remove overlay", error)
        } finally {
            overlayView = null
            overlayLayoutParams = null
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val TAG = "PaymentOverlayService"
        private const val IDLE_DELAY_MS = 2000L
        private const val ACTIVE_ALPHA = 0.98f
        private const val IDLE_ALPHA = 0.45f
    }

    private enum class EdgeState {
        LEFT,
        RIGHT,
    }
}
