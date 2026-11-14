package com.mlrecorder.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal MediaCodec + MediaMuxer recorder prototype. */
class RecorderService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val running = AtomicBoolean(false)
    private val muxLock = Any()
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var output: File? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); return START_NOT_STICKY }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        try { startCapture(intent ?: return START_NOT_STICKY) }
        catch (e: Exception) { stopCapture(); stopSelf(); }
        return START_NOT_STICKY
    }

    private fun startCapture(i: Intent) {
        val resultCode = i.getIntExtra("resultCode", ActivityResult.RESULT_CANCELED)
        val data = i.getParcelableExtra<Intent>("projectionData") ?: return
        val width = i.getIntExtra("width", 1280)
        val height = i.getIntExtra("height", 720)
        val fps = i.getIntExtra("fps", 30)
        val bitRate = i.getIntExtra("bitrate", 6_000_000)
        val wantInternal = i.getBooleanExtra("internalAudio", true)
        val wantMic = i.getBooleanExtra("mic", false)
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)

        val dir = getExternalFilesDir("Movies") ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        output = File(dir, "ML_$stamp.mp4")
        muxer = MediaMuxer(output!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface: Surface = videoCodec!!.createInputSurface()
        videoCodec!!.start()
        display = projection!!.createVirtualDisplay("MLRecorder", width, height,
            resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null)

        if (wantInternal || wantMic) setupAudio(wantInternal, wantMic)
        running.set(true)
        videoThread = Thread { drainVideo() }.also { it.start() }
        if (audioCodec != null && audioRecord != null) audioThread = Thread { captureAudio() }.also { it.start() }
    }

    private fun setupAudio(internal: Boolean, mic: Boolean) {
        val sampleRate = 48000
        val channels = if (mic && !internal) 1 else 2
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer.coerceAtLeast(8192))
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val builder = AudioRecord.Builder().setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(channelMask).build()
        ).setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(16384))
        if (internal && Build.VERSION.SDK_INT >= 29) {
            val projectionConfig = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(projectionConfig)
        } else {
            builder.setAudioSource(if (mic) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.DEFAULT)
        }
        audioRecord = builder.build()
        audioRecord!!.startRecording()
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = videoCodec?.dequeueOutputBuffer(info, 10_000) ?: -1
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    videoTrack = muxer!!.addTrack(videoCodec!!.outputFormat)
                    startMuxerIfReady()
                }
                index >= 0 -> {
                    val buffer = videoCodec!!.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) synchronized(muxLock) {
                        if (muxerStarted) { buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer!!.writeSampleData(videoTrack, buffer, info) }
                    }
                    videoCodec!!.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun captureAudio() {
        val codec = audioCodec ?: return
        val recorder = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(8192)
        while (running.get()) {
            val read = recorder.read(pcm, 0, pcm.size)
            if (read > 0) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val b = codec.getInputBuffer(input)!!; b.clear(); b.put(pcm, 0, read)
                    codec.queueInputBuffer(input, 0, read, System.nanoTime() / 1000, 0)
                }
            }
            var out = codec.dequeueOutputBuffer(info, 0)
            while (out >= 0) {
                val b = codec.getOutputBuffer(out)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (b != null && info.size > 0) synchronized(muxLock) {
                    if (muxerStarted && audioTrack >= 0) { b.position(info.offset); b.limit(info.offset + info.size); muxer!!.writeSampleData(audioTrack, b, info) }
                }
                codec.releaseOutputBuffer(out, false)
                out = codec.dequeueOutputBuffer(info, 0)
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(muxLock) {
                audioTrack = muxer!!.addTrack(codec.outputFormat); startMuxerIfReady()
            }
        }
    }

    private fun startMuxerIfReady() {
        if (!muxerStarted && videoTrack >= 0 && (audioCodec == null || audioTrack >= 0)) {
            muxer!!.start(); muxerStarted = true
        }
    }

    private fun stopCapture() {
        if (!running.getAndSet(false) && projection == null) { stopSelf(); return }
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { videoThread?.join(500) } catch (_: Exception) {}
        try { audioThread?.join(500) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "ML Recorder", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): Notification {
        val stop = Intent(this, RecorderService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            .setContentTitle("ML Recorder is recording").setContentText("Tap Stop to finish recording")
            .setSmallIcon(android.R.drawable.presence_video_online).addAction(Notification.Action.Builder(null, "Stop", pi).build()).build()
        else Notification.Builder(this).setContentTitle("ML Recorder is recording").setSmallIcon(android.R.drawable.presence_video_online).build()
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "ml_recorder"
        private const val NOTIFICATION_ID = 4103
        private const val ACTION_STOP = "com.mlrecorder.app.STOP"
    }
}

private object ActivityResult { const val RESULT_CANCELED = 0 }
