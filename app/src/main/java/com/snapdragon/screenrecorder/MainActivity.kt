
package com.snapdragon.screenrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.snapdragon.screenrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -&gt;
        if (result.resultCode == RESULT_OK) {
            startRecording(result.resultCode, result.data)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissions()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions -&gt;
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                checkPermissions()
            }
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf&lt;String&gt;()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            overlayPermissionLauncher.launch(intent)
            return
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startRecording(resultCode: Int, data: Intent?) {
        val recordMicrophone = binding.microphoneSwitch.isChecked
        val recordSystemAudio = binding.systemAudioSwitch.isChecked
        val is1080p = binding.resolution1080p.isChecked

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
            putExtra(ScreenRecordService.EXTRA_RECORD_MICROPHONE, recordMicrophone)
            putExtra(ScreenRecordService.EXTRA_RECORD_SYSTEM_AUDIO, recordSystemAudio)
            putExtra(ScreenRecordService.EXTRA_IS_1080P, is1080p)
        }

        if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isRecording = true
        updateUI()
        Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
        updateUI()
    }

    private fun updateUI() {
        binding.startButton.text = if (isRecording) getString(R.string.stop_record) else getString(R.string.start_record)
    }
}

