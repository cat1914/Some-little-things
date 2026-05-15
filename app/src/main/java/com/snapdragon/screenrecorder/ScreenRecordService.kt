package com.snapdragon.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.media.MediaMuxer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.snapdragon.screenrecorder.ACTION_START"
        const val ACTION_STOP = "com.snapdragon.screenrecorder.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_RECORD_MICROPHONE = "record_microphone"
        const val EXTRA_RECORD_SYSTEM_AUDIO = "record_system_audio"
        const val EXTRA_IS_1080P = "is_1080p"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_record_channel"
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 2
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BIT_RATE = 128000
    }

    private var mediaProjection: MediaProjection? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var systemAudioRecord: AudioRecord? = null
    private var videoOutputPath: String? = null
    private var isRecording = AtomicBoolean(false)
    private var recordMicrophone = false
    private var recordSystemAudio = false
    private var floatingView: View? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Any()
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (isRecording.get()) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        recordMicrophone = intent.getBooleanExtra(EXTRA_RECORD_MICROPHONE, false)
        recordSystemAudio = intent.getBooleanExtra(EXTRA_RECORD_SYSTEM_AUDIO, false)
        val is1080p = intent.getBooleanExtra(EXTRA_IS_1080P, true)

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val (width, height, bitRate) = if (is1080p) {
            Triple(1920, 1080, 8000000)
        } else {
            Triple(1280, 720, 4000000)
        }

        try {
            setupVideoEncoder(width, height, bitRate)
            if (recordMicrophone || recordSystemAudio) {
                setupAudioEncoder()
            }
            createOutputFile()
            setupFloatingView()

            isRecording.set(true)
            startVideoEncoding()
            if (recordMicrophone || recordSystemAudio) {
                startAudioEncoding()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording()
        }
    }

    private fun setupVideoEncoder(width: Int, height: Int, bitRate: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)

        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val surface = videoEncoder!!.createInputSurface()
        mediaProjection!!.createVirtualDisplay(
            "ScreenRecord",
            width, height,
            resources.displayMetrics.densityDpi,
            0,
            surface,
            null,
            null
        )
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun createOutputFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        val moviesDir = getExternalFilesDir(null)
        val outputFile = File(moviesDir, fileName)
        videoOutputPath = outputFile.absolutePath
        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun startVideoEncoding() {
        videoThread = Thread {
            try {
                videoEncoder!!.start()
                val bufferInfo = MediaCodec.BufferInfo()

                while (isRecording.get()) {
                    val outputBufferIndex = videoEncoder!!.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val encodedData = videoEncoder!!.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                val format = videoEncoder!!.outputFormat
                                synchronized(muxerLock) {
                                    if (!muxerStarted) {
                                        videoTrackIndex = mediaMuxer!!.addTrack(format)
                                        checkStartMuxer()
                                    }
                                }
                            } else if (bufferInfo.size > 0) {
                                synchronized(muxerLock) {
                                    if (muxerStarted && videoTrackIndex >= 0) {
                                        mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }
                        }
                        videoEncoder!!.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { start() }
    }

    private fun startAudioEncoding() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (recordMicrophone) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        }

        if (recordSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            systemAudioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()
        }

        audioThread = Thread {
            try {
                audioEncoder!!.start()

                audioRecord?.startRecording()
                systemAudioRecord?.startRecording()

                val bufferInfo = MediaCodec.BufferInfo()
                val buffer = ByteArray(minBufferSize)
                var presentationTimeUs = 0L

                while (isRecording.get()) {
                    var totalRead = 0

                    if (recordMicrophone) {
                        val micRead = audioRecord!!.read(buffer, totalRead, buffer.size - totalRead)
                        if (micRead > 0) totalRead += micRead
                    }

                    if (recordSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val systemRead = systemAudioRecord!!.read(buffer, totalRead, buffer.size - totalRead)
                        if (systemRead > 0) totalRead += systemRead
                    }

                    if (totalRead > 0) {
                        val inputBufferIndex = audioEncoder!!.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = audioEncoder!!.getInputBuffer(inputBufferIndex)
                            inputBuffer?.put(buffer, 0, totalRead)
                            audioEncoder!!.queueInputBuffer(inputBufferIndex, 0, totalRead, presentationTimeUs, 0)
                            presentationTimeUs += (totalRead * 1000000L) / (AUDIO_SAMPLE_RATE * 2 * 2)
                        }
                    }

                    var outputBufferIndex = audioEncoder!!.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        val encodedData = audioEncoder!!.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                val format = audioEncoder!!.outputFormat
                                synchronized(muxerLock) {
                                    if (!muxerStarted) {
                                        audioTrackIndex = mediaMuxer!!.addTrack(format)
                                        checkStartMuxer()
                                    }
                                }
                            } else if (bufferInfo.size > 0) {
                                synchronized(muxerLock) {
                                    if (muxerStarted && audioTrackIndex >= 0) {
                                        mediaMuxer!!.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }
                        }
                        audioEncoder!!.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = audioEncoder!!.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { start() }
    }

    private fun checkStartMuxer() {
        val hasVideo = videoTrackIndex >= 0
        val hasAudio = (recordMicrophone || recordSystemAudio) && audioTrackIndex >= 0
        val needsAudio = recordMicrophone || recordSystemAudio

        if (hasVideo && (!needsAudio || hasAudio)) {
            if (!muxerStarted) {
                mediaMuxer!!.start()
                muxerStarted = true
            }
        }
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_control, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        floatingView?.findViewById<View>(R.id.stopButton)?.setOnClickListener {
            stopRecording()
        }

        floatingView?.findViewById<View>(R.id.minimizeButton)?.setOnClickListener {
            removeFloatingView()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { _, event ->
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
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, params)
    }

    private fun removeFloatingView() {
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
    }

    private fun stopRecording() {
        if (!isRecording.get()) return

        isRecording.set(false)

        videoThread?.join()
        audioThread?.join()

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
            audioRecord?.stop()
            audioRecord?.release()
            systemAudioRecord?.stop()
            systemAudioRecord?.release()

            synchronized(muxerLock) {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                    muxerStarted = false
                }
                mediaMuxer?.release()
            }

            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        removeFloatingView()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        handler.post {
            Toast.makeText(this, getString(R.string.recording_stopped, videoOutputPath), Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(stopPendingIntent)
            .build()
    }
}
