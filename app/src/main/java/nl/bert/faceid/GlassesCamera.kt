package nl.bert.faceid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PHASE 2 — the glasses camera as a frame source.
 *
 * The whole recognition pipeline takes a Bitmap and does not care where it came
 * from, so this file is the only thing that had to change to move off the phone
 * camera. MainActivity keeps both sources and lets the user switch between them.
 *
 * WHAT YOU STILL NEED (one-time, outside the code)
 *   1. Meta AI app -> your glasses -> enable Developer Mode.
 *   2. A GitHub personal access token (classic) with the read:packages scope,
 *      placed in local.properties as github_token=... so Gradle can download the
 *      SDK from GitHub Packages. See INSTALL.md.
 *
 * HOW IT WORKS
 *   - Registration and the camera permission are brokered by the Meta AI app and
 *     handled in MainActivity (they need an Activity and the Activity Result API).
 *   - connect() opens a device session and a low-resolution raw stream. A stream
 *     must be live for photo capture to work at all.
 *   - The raw video frames feed a live preview (setPreviewListener). They arrive
 *     as uncompressed RGBA, so each one is a straight copy into a Bitmap — no HEVC
 *     decoder needed.
 *   - capture() takes a single sharper still for recognition and hands the decoded
 *     Bitmap to the frame listener.
 *
 * NOTE: the toolkit is a developer preview, so a few symbol or package names may
 * shift between versions. If a name does not resolve, let Android Studio auto-import
 * it, or check https://wearables.developer.meta.com/llms.txt?full=true.
 */
interface GlassesCamera {

    fun interface FrameListener {
        fun onFrame(bitmap: Bitmap)
    }

    fun interface PreviewListener {
        fun onPreview(bitmap: Bitmap)
    }

    /** True once a session and stream with the glasses are open. */
    val connected: Boolean

    suspend fun connect(): Boolean

    /** Requests one photo. The decoded frame arrives on the registered listener. */
    suspend fun capture()

    fun setFrameListener(listener: FrameListener)

    fun setPreviewListener(listener: PreviewListener)

    fun disconnect()
}

/**
 * Real Device Access Toolkit implementation. Registration and permission are
 * assumed to be granted already (MainActivity handles those); this class only
 * owns the session and stream lifecycle.
 *
 * [scope] outlives a single connection and drives the continuous preview frame
 * collection; it is cancelled by the owner, not here.
 */
class MetaGlassesCamera(private val scope: CoroutineScope) : GlassesCamera {

    private var listener: GlassesCamera.FrameListener? = null
    private var previewListener: GlassesCamera.PreviewListener? = null
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var previewJob: Job? = null

    @Volatile
    override var connected: Boolean = false
        private set

    override suspend fun connect(): Boolean {
        disconnect()

        val newSession = Wearables.createSession(AutoDeviceSelector())
            .getOrElse { return false }
        session = newSession
        newSession.start()

        val started = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
            newSession.state.first { it == DeviceSessionState.STARTED }
        }
        if (started == null) return false

        // LOW (360x640) at 15 fps: the sharpest per-frame quality over the
        // Bluetooth link, and plenty for both preview and face recognition.
        val cam = newSession.addCamera(
            StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 15)
        ).getOrElse { return false }
        camera = cam

        cam.stream.start().getOrElse { return false }
        val streaming = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
            cam.stream.state.first { it == StreamState.STREAMING }
        }
        if (streaming == null) return false

        connected = true
        startPreview(cam.stream)
        return true
    }

    override suspend fun capture() {
        val cam = camera ?: return
        cam.stream.capturePhoto().onSuccess { photo ->
            // PhotoData is either a ready Bitmap or HEIC bytes to decode.
            val bitmap = when (photo) {
                is PhotoData.Bitmap -> photo.bitmap
                is PhotoData.HEIC -> {
                    val buffer = photo.data.duplicate().apply { rewind() }
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            if (bitmap != null) listener?.onFrame(bitmap)
        }
    }

    override fun setFrameListener(listener: GlassesCamera.FrameListener) {
        this.listener = listener
    }

    override fun setPreviewListener(listener: GlassesCamera.PreviewListener) {
        this.previewListener = listener
    }

    override fun disconnect() {
        connected = false
        previewJob?.cancel()
        previewJob = null
        camera?.stop()
        session?.stop()
        camera = null
        session = null
    }

    private fun startPreview(stream: Stream) {
        previewJob?.cancel()
        previewJob = scope.launch {
            stream.videoStream.collect { frame ->
                if (frame.isCompressed) return@collect
                frame.toBitmap()?.let { previewListener?.onPreview(it) }
            }
        }
    }

    /** Raw frames are RGBA, so a preview Bitmap is a direct pixel copy. */
    private fun VideoFrame.toBitmap(): Bitmap? = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            buffer.rewind()
            copyPixelsFromBuffer(buffer)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SETUP_TIMEOUT_MS = 30_000L
    }
}
