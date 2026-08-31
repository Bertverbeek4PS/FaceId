package nl.bert.faceid

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a cropped face into an embedding: a list of numbers where the distance
 * between two lists tells you whether they are the same person.
 *
 * Input size and output size are read from the model itself, so this works
 * unchanged with facenet.tflite (160px, 128 numbers), facenet_512.tflite
 * (160px, 512 numbers) or MobileFaceNet.tflite (112px, 192 numbers).
 */
class FaceEmbedder(context: Context, assetName: String = MODEL_ASSET) {

    companion object {
        const val MODEL_ASSET = "facenet.tflite"
    }

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray

    /** Side length in pixels the model expects, e.g. 160. */
    val inputSize: Int

    /** Number of values in one embedding, e.g. 128. */
    val dim: Int

    init {
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModel(context, assetName), options)

        val inShape = interpreter.getInputTensor(0).shape() // [1, H, W, 3]
        inputSize = inShape[1]
        dim = interpreter.getOutputTensor(0).shape().last()

        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(inputSize * inputSize)
    }

    private fun loadModel(context: Context, assetName: String): ByteBuffer {
        context.assets.openFd(assetName).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }

    /**
     * [face] should be a tight crop around one face. Returns a length-[dim]
     * vector, already scaled to length 1 so that a plain dot product gives
     * cosine similarity.
     */
    @Synchronized
    fun embed(face: Bitmap): FloatArray {
        val scaled = if (face.width == inputSize && face.height == inputSize) {
            face
        } else {
            Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        }

        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Per-image standardisation, the same "prewhitening" FaceNet was trained with.
        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16 and 0xFF).toDouble()
            val g = (p shr 8 and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            sum += r + g + b
            sumSq += r * r + g * g + b * b
        }
        val n = pixels.size * 3.0
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        val std = max(sqrt(max(variance, 0.0)), 1.0 / sqrt(n)).toFloat()
        val meanF = mean.toFloat()

        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16 and 0xFF) - meanF) / std)
            inputBuffer.putFloat(((p shr 8 and 0xFF) - meanF) / std)
            inputBuffer.putFloat(((p and 0xFF) - meanF) / std)
        }

        val output = Array(1) { FloatArray(dim) }
        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)

        if (scaled !== face) scaled.recycle()

        return l2Normalise(output[0])
    }

    private fun l2Normalise(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-10f)
        for (i in v.indices) v[i] = v[i] / norm
        return v
    }

    fun close() = interpreter.close()
}
