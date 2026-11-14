package com.adityanile.expensetracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class PaymentOverlayAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var resultCardView: View? = null
    private var isProcessingCapture = false
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
            val bubbleSize = dp(44)
            val visiblePeek = dp(12)
            val dragThreshold = dp(6)
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val bubble = FrameLayout(this).apply {
                minimumWidth = bubbleSize
                minimumHeight = bubbleSize
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(242, 37, 40, 44))
                    setStroke(dp(1), Color.argb(130, 170, 170, 170))
                }
                alpha = ACTIVE_ALPHA
                elevation = dp(12).toFloat()
                isClickable = true
                isFocusable = false

                addView(
                    View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(250, 125, 211, 252))
                        }
                    },
                    FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER),
                )
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
                                triggerCaptureAndAnalyze()
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

    private fun triggerCaptureAndAnalyze() {
        if (isProcessingCapture) {
            Toast.makeText(applicationContext, "Processing in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = AzureAnalyzerSettingsProvider.fromBuildConfig()
        if (!settings.isConfigured()) {
            Toast.makeText(
                applicationContext,
                "Azure secrets missing. Add in ~/.gradle/gradle.properties, project .gradle/gradle.properties, or local.properties",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        isProcessingCapture = true
        Toast.makeText(applicationContext, "Processing screenshot…", Toast.LENGTH_SHORT).show()

        thread {
            val captureResult = ScreenCaptureManager.captureBitmap()
            if (captureResult.isFailure) {
                isProcessingCapture = false
                val errorMessage = captureResult.exceptionOrNull()?.message
                    ?: "Capture failed. Open app and grant permission first."
                mainHandler.post {
                    Toast.makeText(applicationContext, errorMessage, Toast.LENGTH_LONG).show()
                }
                return@thread
            }

            val bitmap = captureResult.getOrThrow()
            val imageBytes = bitmap.toPngBytes()
            bitmap.recycle()

            val analyzeResult = AzureContentUnderstandingClient.analyzeImageBytes(imageBytes, settings)
            isProcessingCapture = false

            mainHandler.post {
                if (analyzeResult.isSuccess) {
                    showEditableFieldsCard(analyzeResult.getOrNull().orEmpty())
                } else {
                    val message = analyzeResult.exceptionOrNull()?.message ?: "Azure analysis failed"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showEditableFieldsCard(fields: List<AzureExtractedField>) {
        val editableValues = linkedMapOf<String, EditText>()

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            addView(TextView(context).apply {
                text = "Expense Details"
                setTextColor(Color.argb(255, 237, 237, 237))
                textSize = 17f
            })

            if (fields.isEmpty()) {
                addView(TextView(context).apply {
                    text = "No fields extracted"
                    setTextColor(Color.argb(220, 186, 186, 186))
                    textSize = 14f
                    setPadding(0, dp(8), 0, dp(10))
                })
            } else {
                fields.forEach { field ->
                    val fieldContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.argb(255, 46, 50, 56))
                            setStroke(dp(1), Color.argb(95, 100, 100, 100))
                        }
                        setPadding(dp(10), dp(8), dp(10), dp(10))
                    }

                    fieldContainer.addView(TextView(context).apply {
                        text = field.name
                        setTextColor(Color.argb(255, 175, 214, 255))
                        textSize = 12f
                        setPadding(0, 0, 0, dp(4))
                    })

                    val input = EditText(context).apply {
                        setText(field.value)
                        setTextColor(Color.argb(255, 242, 242, 242))
                        setHintTextColor(Color.argb(180, 175, 175, 175))
                        hint = "Update ${field.name}"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(10).toFloat()
                            setColor(Color.argb(255, 33, 35, 39))
                            setStroke(dp(1), Color.argb(80, 95, 95, 95))
                        }
                        setPadding(dp(10), dp(9), dp(10), dp(9))
                    }
                    editableValues[field.name] = input
                    fieldContainer.addView(input)

                    addView(
                        fieldContainer,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(8) },
                    )
                }
            }

            val buttonsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(12), 0, 0)
            }

            buttonsRow.addView(createSecondaryButton("Close").apply {
                setOnClickListener { hideResultCardSafely() }
            })

            buttonsRow.addView(createPrimaryButton("Save").apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }

                setOnClickListener {
                    val savedData = editableValues
                        .mapValues { (_, value) -> value.text?.toString().orEmpty().trim() }
                        .filterValues { it.isNotBlank() }

                    if (savedData.isEmpty()) {
                        Toast.makeText(applicationContext, "Nothing to save", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val savedRecord = ExpenseAnalysisStore.saveRecord(applicationContext, savedData)
                    Toast.makeText(applicationContext, "Analysis data saved", Toast.LENGTH_SHORT).show()
                    hideResultCardSafely()

                    thread {
                        val tableSettings = AzureTableSettingsProvider.fromBuildConfig()
                        if (!tableSettings.isConfigured()) {
                            return@thread
                        }

                        val uploadResult = AzureTableExpenseClient.upsertExpense(savedRecord, tableSettings)
                        mainHandler.post {
                            if (uploadResult.isSuccess) {
                                Toast.makeText(applicationContext, "Synced to Azure Table", Toast.LENGTH_SHORT).show()
                            } else {
                                val message = uploadResult.exceptionOrNull()?.message ?: "Azure Table sync failed"
                                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })

            addView(buttonsRow)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(scrollContent)
        }

        showCard(scrollView)
    }

    private fun showCard(contentView: View) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        hideResultCardSafely()

        val cardContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(248, 28, 31, 35))
                setStroke(dp(1), Color.argb(95, 90, 90, 90))
            }
            elevation = dp(16).toFloat()
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(contentView)
        }

        val maxWidth = (resources.displayMetrics.widthPixels * 0.93f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.68f).toInt()

        val params = WindowManager.LayoutParams(
            maxWidth,
            maxHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        try {
            windowManager.addView(cardContainer, params)
            resultCardView = cardContainer
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to show result card", error)
        }
    }

    private fun hideResultCardSafely() {
        val currentCard = resultCardView ?: return
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(currentCard)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to remove result card", error)
        } finally {
            resultCardView = null
        }
    }

    private fun createPrimaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(255, 52, 122, 201))
            }
        }
    }

    private fun createSecondaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.argb(255, 225, 225, 225))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(255, 54, 57, 62))
                setStroke(dp(1), Color.argb(80, 130, 130, 130))
            }
        }
    }

    private fun hideOverlaySafely() {
        val currentView = overlayView
        try {
            mainHandler.removeCallbacksAndMessages(null)
            hideResultCardSafely()
            currentView?.let {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to remove overlay", error)
        } finally {
            overlayView = null
            overlayLayoutParams = null
            isProcessingCapture = false
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

    private fun Bitmap.toPngBytes(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
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
        private const val IDLE_ALPHA = 0.55f
    }

    private enum class EdgeState {
        LEFT,
        RIGHT,
    }
}
