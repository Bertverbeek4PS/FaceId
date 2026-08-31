package nl.bert.faceid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Person(val name: String, val vectors: List<FloatArray>)

/**
 * The "database" is a folder of folders. One folder per person, folder name is
 * the spoken name, photos inside:
 *
 *   .../files/People/Anna/1.jpg
 *   .../files/People/Anna/2.jpg
 *   .../files/People/Oma Trees/whatever.jpg
 *
 * Full path on the phone:
 *   Android/data/nl.bert.faceid/files/People/
 *
 * That location needs no storage permission at all, and it is visible over USB
 * and in any file manager, so a sighted helper can drop photos in without
 * touching the app.
 *
 * Computed embeddings are cached in internal storage and keyed by file size and
 * modification time, so reloading after adding one photo only processes that
 * one photo.
 */
class PeopleStore(private val context: Context) {

    val root: File
        get() = File(context.getExternalFilesDir(null), "People").apply { mkdirs() }

    private val cacheFile: File
        get() = File(context.filesDir, "embeddings.json")

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun photoCount(): Int =
        root.listFiles { f -> f.isDirectory }
            ?.sumOf { dir -> imagesIn(dir).size } ?: 0

    private fun imagesIn(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in imageExtensions
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * Rebuilds the in-memory people list. [onProgress] is called with a short
     * spoken-friendly message roughly once per person.
     */
    suspend fun load(
        embedder: FaceEmbedder,
        finder: FaceFinder,
        onProgress: (String) -> Unit = {}
    ): List<Person> = withContext(Dispatchers.Default) {

        val cache = readCache(embedder.dim)
        val freshCache = JSONArray()
        val people = mutableListOf<Person>()

        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

        for (dir in dirs) {
            val name = dir.name.trim()
            if (name.isEmpty() || name.startsWith(".")) continue

            val vectors = mutableListOf<FloatArray>()
            val images = imagesIn(dir)
            if (images.isEmpty()) continue

            onProgress(name)

            for (image in images) {
                val key = "$name/${image.name}|${image.length()}|${image.lastModified()}"
                val cached = cache[key]
                if (cached != null) {
                    vectors += cached
                    freshCache.put(entryJson(key, cached))
                    continue
                }

                val vector = embed(image, embedder, finder) ?: continue
                vectors += vector
                freshCache.put(entryJson(key, vector))
            }

            if (vectors.isNotEmpty()) people += Person(name, vectors)
        }

        writeCache(freshCache, embedder.dim)
        people
    }

    private suspend fun embed(
        file: File,
        embedder: FaceEmbedder,
        finder: FaceFinder
    ): FloatArray? {
        val bitmap = decodeUpright(file) ?: return null
        return try {
            val faces = finder.detect(bitmap, thorough = true)
            val face = FaceCrop.largest(faces) ?: return null
            val crop = FaceCrop.crop(bitmap, face.boundingBox) ?: return null
            try {
                embedder.embed(crop)
            } finally {
                crop.recycle()
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes at a sane size and applies the EXIF rotation phones write. */
    private fun decodeUpright(file: File, maxSide: Int = 1200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val degrees = try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }

        if (degrees == 0f) return bitmap

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // ---- cache ----------------------------------------------------------

    private fun entryJson(key: String, vector: FloatArray): JSONObject {
        val arr = JSONArray()
        for (v in vector) arr.put(v.toDouble())
        return JSONObject().put("k", key).put("v", arr)
    }

    private fun readCache(dim: Int): Map<String, FloatArray> {
        if (!cacheFile.exists()) return emptyMap()
        return try {
            val root = JSONObject(cacheFile.readText())
            if (root.optInt("dim") != dim) return emptyMap()
            val entries = root.optJSONArray("entries") ?: return emptyMap()
            val out = HashMap<String, FloatArray>(entries.length())
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val arr = e.getJSONArray("v")
                val vec = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                out[e.getString("k")] = vec
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeCache(entries: JSONArray, dim: Int) {
        try {
            cacheFile.writeText(
                JSONObject()
                    .put("dim", dim)
                    .put("entries", entries)
                    .toString()
            )
        } catch (e: Exception) {
            // Cache is an optimisation only; losing it just means a slower reload.
        }
    }
}
