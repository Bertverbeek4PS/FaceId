package nl.bert.faceid

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
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
import kotlinx.coroutines.delay
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
 *   - connect() opens a device session and a low-resolution RAW stream. RAW is
 *     essential: without it the link delivers compressed (HEVC) frames, which the
 *     preview drops, leaving the phone screen blank.
 *   - The raw video frames feed a live preview (setPreviewListener). They arrive
 *     as uncompressed RGBA, so each one is a straight copy into a Bitmap — no HEVC
 *     decoder needed.
 *   - capture() serves the most recent raw frame to the recognition pipeline.
 *     capturePhoto() shares the same constrained Bluetooth link and returned
 *     nothing in practice, so recognition reuses the live stream instead.
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

    /** Why the last connect() attempt failed, when it did. */
    val lastError: String?

    suspend fun connect(): Boolean

    /** Serves the latest live frame to the registered frame listener. */
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

    /** The most recent raw frame, kept so capture() can serve recognition
     *  from the live stream instead of a separate, flaky capturePhoto call. */
    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    @Volatile
    override var connected: Boolean = false
        private set

    @Volatile
    override var lastError: String? = null
        private set

    override suspend fun connect(): Boolean {
        disconnect()
        lastError = null

        // A session started a moment before the glasses are ready just reads
        // STOPPED and never recovers, so retry with a fresh session each time.
        var newSession: DeviceSession? = null
        val seen = mutableListOf<DeviceSessionState>()
        var attempt = 0
        while (attempt < CONNECT_ATTEMPTS) {
            attempt++
            val s = Wearables.createSession(AutoDeviceSelector())
                .getOrElse { err -> lastError = "Create session: $err"; return false }
            s.start()
            val started = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                s.state.first { st ->
                    if (seen.lastOrNull() != st) seen.add(st)
                    st == DeviceSessionState.STARTED
                }
            }
            if (started != null) {
                newSession = s
                break
            }
            s.stop()
            if (attempt < CONNECT_ATTEMPTS) delay(RETRY_DELAY_MS)
        }

        val activeSession = newSession
        if (activeSession == null) {
            val trail = if (seen.isEmpty()) "none" else seen.joinToString(" → ")
            lastError = "Session never reached STARTED after $CONNECT_ATTEMPTS tries " +
                "(states: $trail). Wear the glasses and keep them awake; make sure the " +
                "Meta AI app isn't actively using the camera; only one glasses pair paired."
            return false
        }
        session = activeSession

        // LOW (360x640) at 15 fps: the sharpest per-frame quality over the
        // Bluetooth link, and plenty for both preview and face recognition.
        // RAW is required: without it the link delivers compressed (HEVC) frames,
        // which the preview collector drops — leaving the phone screen blank.
        val cam = activeSession.addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.LOW,
                frameRate = 15
            )
        ).getOrElse { err -> lastError = "Add camera: $err"; return false }
        camera = cam

        cam.stream.start().getOrElse { err -> lastError = "Start stream: $err"; return false }
        val streaming = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
            cam.stream.state.first { it == StreamState.STREAMING }
        }
        if (streaming == null) {
            lastError = "Camera stream did not reach STREAMING within ${SETUP_TIMEOUT_MS / 1000}s."
            return false
        }

        connected = true
        startPreview(cam.stream)
        return true
    }

    /**
     * Hands the recognition pipeline the latest live frame. capturePhoto() shares
     * the same constrained Bluetooth link as the stream and was returning nothing,
     * so recognition is served from the raw stream that already feeds the preview.
     */
    override suspend fun capture() {
        val snapshot = synchronized(frameLock) {
            latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return
        listener?.onFrame(snapshot)
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
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
    }

    private fun startPreview(stream: Stream) {
        previewJob?.cancel()
        previewJob = scope.launch {
            stream.videoStream.collect { frame ->
                if (frame.isCompressed) return@collect
                val bitmap = frame.toBitmap() ?: return@collect
                // Retain a copy for on-demand capture; the preview takes the original.
                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                previewListener?.onPreview(bitmap)
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
        // Retry a fresh session a few times: the first start often races the
        // glasses becoming ready and lands in STOPPED.
        const val CONNECT_ATTEMPTS = 3
        const val ATTEMPT_TIMEOUT_MS = 30_000L
        const val RETRY_DELAY_MS = 3_000L
        const val SETUP_TIMEOUT_MS = 30_000L
    }
}
