package com.aetherqa.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayBubbleService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Floating AI Bubble Active! Open your game/app.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen capture permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("aetherqa_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "AetherQA Auto-Pilot Agent"
            textSize = 22f
        }

        val geminiInput = EditText(this).apply {
            hint = "Gemini API Key (from AI Studio)"
            setText(prefs.getString("gemini_key", ""))
        }

        val ghOwnerInput = EditText(this).apply {
            hint = "GitHub Username / Owner"
            setText(prefs.getString("gh_owner", ""))
        }

        val ghRepoInput = EditText(this).apply {
            hint = "Target Game/App Repo Name"
            setText(prefs.getString("gh_repo", ""))
        }

        val ghTokenInput = EditText(this).apply {
            hint = "GitHub Fine-Grained Token (Issues R/W)"
            setText(prefs.getString("gh_token", ""))
        }

        val goalInput = EditText(this).apply {
            hint = "Testing Goal (e.g. Play level 1 & check UI contrast)"
            setText(
                prefs.getString(
                    "goal_prompt",
                    "Explore all menus and gameplay; audit UI/UX design and report bugs."
                )
            )
        }

        val overlayBtn = Button(this).apply {
            text = "1. Grant Overlay Permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val accessibilityBtn = Button(this).apply {
            text = "2. Enable Auto-Pilot Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val startBtn = Button(this).apply {
            text = "3. Save & Launch Floating AI Bubble"
            setOnClickListener {
                prefs.edit()
                    .putString("gemini_key", geminiInput.text.toString().trim())
                    .putString("gh_owner", ghOwnerInput.text.toString().trim())
                    .putString("gh_repo", ghRepoInput.text.toString().trim())
                    .putString("gh_token", ghTokenInput.text.toString().trim())
                    .putString("goal_prompt", goalInput.text.toString().trim())
                    .apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Grant Overlay permission first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        listOf(
            title,
            geminiInput,
            ghOwnerInput,
            ghRepoInput,
            ghTokenInput,
            goalInput,
            overlayBtn,
            accessibilityBtn,
            startBtn
        ).forEach { container.addView(it) }

        scroll.addView(container)
        setContentView(scroll)
    }
}
