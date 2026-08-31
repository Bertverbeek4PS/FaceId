package nl.bert.faceid

import android.graphics.Bitmap

/**
 * PHASE 2 — swapping the phone camera for the glasses camera.
 *
 * Everything above this file is finished and does not need to change. The whole
 * pipeline takes a Bitmap and does not care where it came from, so moving to the
 * Ray-Ban Meta camera means implementing exactly one thing: a source of frames.
 *
 * WHAT YOU NEED FIRST
 *   1. Meta AI app -> your glasses -> enable Developer Mode.
 *   2. Add the SDK from github.com/facebook/meta-wearables-dat-android
 *      to app/build.gradle.kts.
 *   3. Meta ships AI-ready docs and coding skills for exactly this SDK. Point
 *      Claude Code at them and at this file — that is far more reliable than
 *      guessing method names, because the toolkit is still in developer preview
 *      and the API surface moves between versions.
 *
 * WHAT TO IMPLEMENT
 *   - connect(): discover the paired glasses, request the camera permission that
 *     the Meta AI app brokers, and open a session.
 *   - Photo capture, not video. Video over Bluetooth caps at 720p/30fps and
 *     burns battery; a single still on demand is sharper and cheaper, and face
 *     recognition only ever needs one good frame.
 *   - Deliver each captured frame to onFrame() as a Bitmap. MainActivity's
 *     process() function can then be called with it unchanged.
 *
 * WHAT TO CHANGE IN MainActivity
 *   - Replace startCamera() with glassesCamera.connect().
 *   - Replace the ImageAnalysis analyzer with the onFrame callback below.
 *   - Drop the CAMERA permission from the manifest: the phone camera is no
 *     longer used, and the glasses camera is permissioned through the Meta AI
 *     app instead.
 *
 * DISTRIBUTION NOTE
 *   Developer Preview lets you build and test but not publish. Installing your
 *   own build on your own phone is testing, so this is fine — but do not expect
 *   to put it on Play Store until Meta opens publishing.
 */
interface GlassesCamera {

    fun interface FrameListener {
        fun onFrame(bitmap: Bitmap)
    }

    /** True once a session with the glasses is open. */
    val connected: Boolean

    suspend fun connect(): Boolean

    /** Requests one photo. The result arrives on the registered listener. */
    fun capture()

    fun setFrameListener(listener: FrameListener)

    fun disconnect()
}

/**
 * Placeholder so the project compiles today. Replace the body of each method
 * with real Device Access Toolkit calls.
 */
class MetaGlassesCamera : GlassesCamera {

    private var listener: GlassesCamera.FrameListener? = null

    override val connected: Boolean = false

    override suspend fun connect(): Boolean {
        // TODO: DAT session setup goes here.
        return false
    }

    override fun capture() {
        // TODO: request a still from the glasses camera; hand the decoded
        //       Bitmap to listener?.onFrame(bitmap).
    }

    override fun setFrameListener(listener: GlassesCamera.FrameListener) {
        this.listener = listener
    }

    override fun disconnect() {
        listener = null
    }
}
