package com.mlrecorder.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val requestCode = 4102
    private val audioPermissionCode = 4101
    private var pendingStart = false
    private lateinit var quality: Spinner
    private lateinit var fps: Spinner
    private lateinit var bitrate: Spinner
    private lateinit var internalAudio: CheckBox
    private lateinit var mic: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFF202124.toInt())
            setPadding(0, 18, 0, 6)
        }
        layout.addView(TextView(this).apply {
            text = "ML Recorder"
            textSize = 28f
            setTextColor(0xFF172B4D.toInt())
        })
        layout.addView(TextView(this).apply {
            text = "Performance-first screen recording prototype"
            textSize = 14f
            setTextColor(0xFF5F6368.toInt())
        })
        layout.addView(label("Video resolution"))
        quality = spinner(listOf("1280 × 720", "1920 × 1080"))
        layout.addView(quality)
        layout.addView(label("Frame rate"))
        fps = spinner(listOf("30 FPS", "60 FPS"))
        layout.addView(fps)
        layout.addView(label("Video bitrate"))
        bitrate = spinner(listOf("6 Mbps", "8 Mbps", "12 Mbps", "16 Mbps"))
        layout.addView(bitrate)
        internalAudio = CheckBox(this).apply { text = "Record internal game audio"; isChecked = true }
        mic = CheckBox(this).apply { text = "Record microphone"; isChecked = false }
        layout.addView(internalAudio)
        layout.addView(mic)
        val start = Button(this).apply {
            text = "START RECORDING"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingStart = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionCode)
                } else requestProjection()
            }
        }
        layout.addView(start)
        layout.addView(TextView(this).apply {
            text = "Android may block audio from some apps. Recording performance depends on the device, game, and encoder. Start with 720p / 30 FPS."
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 18, 0, 0)
        })
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun requestProjection() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingStart) {
            pendingStart = false
            requestProjection()
        } else if (requestCode == audioPermissionCode) {
            pendingStart = false
            Toast.makeText(this, "Audio permission is required for capture", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API, kept for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            val w = if (quality.selectedItemPosition == 0) 1280 else 1920
            val h = if (quality.selectedItemPosition == 0) 720 else 1080
            val f = if (fps.selectedItemPosition == 0) 30 else 60
            val rates = intArrayOf(6, 8, 12, 16)
            val intent = Intent(this, RecorderService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
                putExtra("width", w)
                putExtra("height", h)
                putExtra("fps", f)
                putExtra("bitrate", rates[bitrate.selectedItemPosition] * 1_000_000)
                putExtra("internalAudio", internalAudio.isChecked)
                putExtra("mic", mic.isChecked)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Recording service started", Toast.LENGTH_SHORT).show()
        }
    }
}
