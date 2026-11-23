package com.aetherqa.agent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: TextView
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoPilotJob: Job? = null
    private var isRunning = false
    private var bugsReported = 0

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "aetherqa_autopilot_channel"
        val channel = NotificationChannel(
            channelId,
            "AetherQA AutoPilot Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AetherQA Auto-Pilot Ready")
            .setContentText("Tap floating AI icon to start/stop autonomous testing")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(101, notification)
    }

    private fun setupFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        bubbleView = TextView(this).apply {
            text = "AI\nOFF"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC1E293B"))
            setPadding(28, 22, 28, 22)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 260
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(event.rawX - initialTouchX) > 12 || Math.abs(event.rawY - initialTouchY) > 12
                    if (!moved) toggleAutoPilot()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AetherQACapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureScreenBase64(): String? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            val scaled = Bitmap.createScaledBitmap(cropped, image.width / 2, image.height / 2, true)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun toggleAutoPilot() {
        isRunning = !isRunning
        if (isRunning) {
            bubbleView.setBackgroundColor(Color.parseColor("#CC16A34A"))
            bubbleView.text = "AI\nRUN"
            startAutoPilotLoop()
        } else {
            bubbleView.setBackgroundColor(Color.parseColor("#CC1E293B"))
            bubbleView.text = "AI\nOFF"
            autoPilotJob?.cancel()
        }
    }

    private fun startAutoPilotLoop() {
        val prefs = getSharedPreferences("aetherqa_prefs", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_key", "").orEmpty()
        val ghToken = prefs.getString("gh_token", "").orEmpty()
        val ghOwner = prefs.getString("gh_owner", "").orEmpty()
        val ghRepo = prefs.getString("gh_repo", "").orEmpty()
        val goalPrompt = prefs.getString(
            "goal_prompt",
            "Explore all screens, buttons, and gameplay loops; audit UI/UX design and report bugs."
        ).orEmpty()

        val gemini = GeminiVisionClient(apiKey = geminiKey)
        val reporter = if (ghToken.isNotBlank() && ghOwner.isNotBlank() && ghRepo.isNotBlank()) {
            GitHubIssueReporter(ghOwner, ghRepo, ghToken)
        } else null

        autoPilotJob = serviceScope.launch {
            while (isActive && isRunning) {
                val base64 = captureScreenBase64()
                val accessibility = AutoPilotAccessibilityService.instance
                val uiSummary = accessibility?.collectUiSummary() ?: "Accessibility service not enabled."

                if (base64 != null && geminiKey.isNotBlank()) {
                    bubbleView.text = "AI\nSCAN"
                    val decision = gemini.evaluateScreenAndDecide(base64, uiSummary, goalPrompt)

                    if (decision.bugFound && reporter != null) {
                        val issueBody = """
                            ### Autonomous AI QA & UI/UX Finding
                            - **Observations:** ${decision.uxNotes}
                            - **Suggested Next Action:** `${decision.action}` at `(${decision.x}, ${decision.y})`
                            - **UI Tree Snapshot:**
                            ```text
                            $uiSummary
                            ```
                        """.trimIndent()
                        reporter.createIssue(decision.bugTitle, issueBody)
                        bugsReported++
                    }

                    when (decision.action) {
                        "TAP" -> accessibility?.tapAt(decision.x, decision.y)
                        "SWIPE" -> accessibility?.swipe(decision.x, decision.y, decision.endX, decision.endY)
                        "BACK" -> accessibility?.pressBackAction()
                        "STOP" -> {
                            isRunning = false
                            bubbleView.setBackgroundColor(Color.parseColor("#CC1E293B"))
                            bubbleView.text = "AI\nDONE"
                            break
                        }
                    }
                    bubbleView.text = "AI:$bugsReported\nRUN"
                }
                delay(2800L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoPilotJob?.cancel()
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
