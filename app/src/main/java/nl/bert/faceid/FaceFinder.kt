package nl.bert.faceid

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Finds where the faces are. ML Kit only locates faces, it does not identify
 * them — identification is [FaceEmbedder] plus [Matcher].
 *
 * Two detectors: the fast one for live camera frames, the accurate one for the
 * enrolment photos, where a few extra milliseconds do not matter and a better
 * crop pays off for the whole lifetime of that person's entry.
 */
class FaceFinder {

    private val fast = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
    )

    private val accurate = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )

    suspend fun detect(bitmap: Bitmap, thorough: Boolean = false): List<Face> =
        suspendCancellableCoroutine { cont ->
            val client = if (thorough) accurate else fast
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    fun close() {
        fast.close()
        accurate.close()
    }
}

object FaceCrop {

    /**
     * Cuts [box] out of [src] with a margin on each side, because the
     * recognition models were trained on crops that include a bit of forehead,
     * chin and hair rather than the eyes-to-mouth region alone.
     */
    fun crop(src: Bitmap, box: Rect, margin: Float = 0.22f): Bitmap? {
        val padX = (box.width() * margin).roundToInt()
        val padY = (box.height() * margin).roundToInt()

        val left = (box.left - padX).coerceAtLeast(0)
        val top = (box.top - padY).coerceAtLeast(0)
        val right = (box.right + padX).coerceAtMost(src.width)
        val bottom = (box.bottom + padY).coerceAtMost(src.height)

        val w = right - left
        val h = bottom - top
        if (w < 24 || h < 24) return null

        return Bitmap.createBitmap(src, left, top, w, h)
    }

    /** Returns the biggest face in the list — the one nearest to the camera. */
    fun largest(faces: List<Face>): Face? =
        faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
}
