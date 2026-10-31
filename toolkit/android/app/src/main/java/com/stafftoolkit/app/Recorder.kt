package com.stafftoolkit.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Saves pictures and videos into the phone's gallery (Pictures / Movies → Staff Toolkit). */
object Media {
    fun newVideo(ctx: Context, name: String): Uri? = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Staff Toolkit")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    })

    fun newImage(ctx: Context, name: String): Uri? = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Staff Toolkit")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    })

    fun finish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun size(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (_: Exception) { 0L }

    fun delete(ctx: Context, uri: Uri) { try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {} }

    fun stamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.UK).format(java.util.Date())
}

/**
 * Encodes the screen (and sound) into H.264 video. The encoded video feeds both the replay
 * buffer (the last few seconds, kept in memory) and full recordings (written straight to the
 * gallery, so they can be as long as you like).
 */
class Recorder(
    private val ctx: Context,
    private val pipeline: ScreenPipeline,
    private val projection: MediaProjection,
    private val fps: Int,
    private val bitrate: Int,
    private val gameSound: Boolean,
    private val gameVolume: Float,
    private val mic: Boolean,
    private val micVolume: Float,
) {
    private class Sample(val audio: Boolean, val pts: Long, val flags: Int, val data: ByteArray)

    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    @Volatile private var running = false
    @Volatile var videoFormat: MediaFormat? = null
        private set
    @Volatile var audioFormat: MediaFormat? = null
        private set
    private val hasAudio = gameSound || mic

    // Replay buffer
    private val lock = Object()
    private val ring = ArrayDeque<Sample>()
    private var ringBytes = 0L
    var replaySeconds = 30

    // Full recording
    private var muxer: MediaMuxer? = null
    private var muxUri: Uri? = null
    private var muxV = -1
    private var muxA = -1
    private var muxBase = -1L
    private var muxWaitingKey = false
    val recording: Boolean get() = muxer != null

    fun start() {
        running = true
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, pipeline.width, pipeline.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        val v = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        v.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = v.createInputSurface()
        v.start()
        video = v
        pipeline.attachEncoder(input, fps)
        thread(name = "video-out") { drain(v, false) }
        if (hasAudio) startAudio()
    }

    private fun drain(codec: MediaCodec, isAudio: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val i = try { codec.dequeueOutputBuffer(info, 20_000) } catch (_: Exception) { break }
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (isAudio) audioFormat = codec.outputFormat else videoFormat = codec.outputFormat
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.get(data, 0, info.size)
                        add(Sample(isAudio, info.presentationTimeUs, info.flags, data))
                    }
                    codec.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val rate = 48000
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = max(minBuf * 2, 8192)
        // Game / phone sound (apps that allow it; most games do)
        val game = if (gameSound) try {
            AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()).build()
        } catch (_: Exception) { null } else null
        val micRec = if (mic) try {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(format).setBufferSizeInBytes(bufSize).build()
        } catch (_: Exception) { null } else null
        if (game == null && micRec == null) return

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        a.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        a.start()
        audio = a
        game?.startRecording()
        micRec?.startRecording()
        thread(name = "audio-out") { drain(a, true) }
        thread(name = "audio-in") {
            val frame = 4096   // bytes per read (1024 stereo samples)
            val g = ShortArray(frame / 2)
            val m = ShortArray(frame / 2)
            val startUs = System.nanoTime() / 1000
            var samples = 0L
            while (running) {
                // The main source sets the pace; the other is mixed in if it has sound ready
                val n: Int
                if (game != null) {
                    n = game.read(g, 0, g.size)
                    if (micRec != null) {
                        val got = micRec.read(m, 0, max(0, n), AudioRecord.READ_NON_BLOCKING)
                        for (k in 0 until max(0, n)) {
                            val mix = g[k] * gameVolume + (if (k < got) m[k] * micVolume else 0f)
                            g[k] = mix.toInt().coerceIn(-32768, 32767).toShort()
                        }
                    } else if (gameVolume != 1f) {
                        for (k in 0 until max(0, n)) g[k] = (g[k] * gameVolume).toInt().coerceIn(-32768, 32767).toShort()
                    }
                } else {
                    n = micRec!!.read(g, 0, g.size)
                    if (micVolume != 1f) for (k in 0 until max(0, n)) g[k] = (g[k] * micVolume).toInt().coerceIn(-32768, 32767).toShort()
                }
                if (n <= 0) continue
                val idx = try { a.dequeueInputBuffer(20_000) } catch (_: Exception) { break }
                if (idx < 0) continue
                val ib = a.getInputBuffer(idx) ?: continue
                ib.clear()
                val bytes = min(n * 2, ib.capacity())
                val bb = ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until bytes / 2) bb.putShort(g[k])
                ib.put(bb.array(), 0, bytes)
                val pts = startUs + samples * 1_000_000L / rate
                samples += bytes / 4
                a.queueInputBuffer(idx, 0, bytes, pts, 0)
            }
            try { game?.stop(); game?.release() } catch (_: Exception) {}
            try { micRec?.stop(); micRec?.release() } catch (_: Exception) {}
        }
    }

    private fun add(s: Sample) {
        synchronized(lock) {
            // Replay buffer: keep the last few seconds (and a little spare)
            ring.addLast(s)
            ringBytes += s.data.size
            val keepUs = (replaySeconds + 3) * 1_000_000L
            val newest = s.pts
            while (ring.isNotEmpty() && (newest - ring.first().pts > keepUs || ringBytes > 600L * 1024 * 1024)) {
                ringBytes -= ring.removeFirst().data.size
            }
            // Full recording
            val m = muxer ?: return
            if (muxWaitingKey) {
                if (s.audio || s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
                muxWaitingKey = false
                muxBase = s.pts
            }
            if (s.pts < muxBase) return
            val track = if (s.audio) muxA else muxV
            if (track < 0) return
            val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.pts - muxBase, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) }
            try { m.writeSampleData(track, ByteBuffer.wrap(s.data), info) } catch (_: Exception) {}
        }
    }

    private fun waitForFormats(): Boolean {
        val until = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < until) {
            if (videoFormat != null && (!hasAudio || audio == null || audioFormat != null)) return true
            Thread.sleep(50)
        }
        return videoFormat != null
    }

    /** Starts a full recording into the gallery. Returns false if it couldn't start. */
    fun startRecording(): Boolean {
        if (!waitForFormats()) return false
        val uri = Media.newVideo(ctx, "Recording ${Media.stamp()}.mp4") ?: return false
        val fd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return false
        val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        synchronized(lock) {
            muxV = m.addTrack(videoFormat!!)
            muxA = audioFormat?.let { m.addTrack(it) } ?: -1
            m.start()
            muxer = m
            muxUri = uri
            muxWaitingKey = true
            muxFd = fd
        }
        // Ask for a key frame now, so the recording starts straight away
        try { video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
        return true
    }
    private var muxFd: android.os.ParcelFileDescriptor? = null

    /** Ends the recording; returns where it was saved. */
    fun stopRecording(): Uri? {
        val uri: Uri?
        synchronized(lock) {
            val m = muxer ?: return null
            try { m.stop() } catch (_: Exception) {}
            try { m.release() } catch (_: Exception) {}
            try { muxFd?.close() } catch (_: Exception) {}
            muxer = null; muxFd = null
            uri = muxUri; muxUri = null
        }
        uri?.let { Media.finish(ctx, it) }
        return uri
    }

    /** Saves the last [seconds] from the replay buffer as a clip in the gallery. */
    fun saveClip(seconds: Int): Uri? {
        if (!waitForFormats()) return null
        val snapshot: List<Sample> = synchronized(lock) { ring.toList() }
        if (snapshot.isEmpty()) return null
        val newest = snapshot.maxOf { it.pts }
        val from = newest - seconds * 1_000_000L
        // Start on a key frame so the clip plays from the first moment
        val keyIdx = snapshot.indexOfFirst { !it.audio && it.pts >= from && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            .takeIf { it >= 0 } ?: snapshot.indexOfFirst { !it.audio && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
        if (keyIdx < 0) return null
        val base = snapshot[keyIdx].pts
        val uri = Media.newVideo(ctx, "Clip ${Media.stamp()}.mp4") ?: return null
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")!!.use { fd ->
                val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val tv = m.addTrack(videoFormat!!)
                val ta = audioFormat?.let { m.addTrack(it) } ?: -1
                m.start()
                val info = MediaCodec.BufferInfo()
                for (s in snapshot) {   // kept in the encoder's order, which the video needs
                    if (s.pts < base) continue
                    val t = if (s.audio) ta else tv
                    if (t < 0) continue
                    info.set(0, s.data.size, s.pts - base, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    m.writeSampleData(t, ByteBuffer.wrap(s.data), info)
                }
                m.stop(); m.release()
            }
            Media.finish(ctx, uri)
            return uri
        } catch (_: Exception) {
            Media.delete(ctx, uri)
            return null
        }
    }

    fun stop() {
        stopRecording()
        running = false
        pipeline.detachEncoder()
        Thread.sleep(100)
        try { video?.stop(); video?.release() } catch (_: Exception) {}
        try { audio?.stop(); audio?.release() } catch (_: Exception) {}
        video = null; audio = null
        synchronized(lock) { ring.clear(); ringBytes = 0 }
    }
}
