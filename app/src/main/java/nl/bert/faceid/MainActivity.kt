package nl.bert.faceid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bert.faceid.databinding.ActivityMainBinding
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        /** ~3 frames a second is plenty and keeps the phone cool. */
        const val FRAME_INTERVAL_MS = 320L

        /** Don't repeat the same name within this window. */
        const val NAME_REPEAT_MS = 9_000L
        const val UNKNOWN_REPEAT_MS = 7_000L
        const val GUIDE_REPEAT_MS = 3_500L
        const val NO_FACE_REPEAT_MS = 6_000L

        /** A face narrower than this fraction of the frame is too far away. */
        const val MIN_FACE_FRACTION = 0.16f

        const val PREFS = "faceid"
        const val KEY_SENSITIVITY = "sensitivity_level"
    }

    /** Thresholds paired with how they are announced. Index 1 is the default. */
    private val sensitivityLevels = listOf(
        0.48f to R.string.sens_relaxed,
        0.55f to R.string.sens_normal,
        0.60f to R.string.sens_strict,
        0.65f to R.string.sens_very_strict
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var speaker: Speaker
    private lateinit var store: PeopleStore
    private val finder = FaceFinder()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** Own scope: onFrame runs on the camera thread, where touching
     *  lifecycleScope for the first time would not be safe. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var embedder: FaceEmbedder? = null
    private var people: List<Person> = emptyList()

    private var listening = false
    private var busy = false
    private var lastFrameAt = 0L
    private var lastResultSpoken = ""

    private var sensitivityIndex = 1
    private val threshold: Float get() = sensitivityLevels[sensitivityIndex].first

    private val spokenAt = ConcurrentHashMap<String, Long>()

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            setStatus(getString(R.string.status_no_camera))
            speaker.say(getString(R.string.status_no_camera), interrupt = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speaker = Speaker(this)
        store = PeopleStore(this)

        sensitivityIndex = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SENSITIVITY, 1)
            .coerceIn(sensitivityLevels.indices)

        binding.btnMain.setOnClickListener { toggleListening() }
        binding.btnReload.setOnClickListener {
            speaker.tapFeedback()
            reloadPeople()
        }
        binding.btnSensitivity.setOnClickListener { cycleSensitivity() }

        // Tapping the big text repeats the last thing said, so you never have to
        // ask someone to say it again.
        binding.tvResult.setOnClickListener {
            if (lastResultSpoken.isNotEmpty()) speaker.say(lastResultSpoken, interrupt = true)
        }

        loadModelAndPeople()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    // ---- setup ----------------------------------------------------------

    private fun loadModelAndPeople() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    FaceEmbedder(this@MainActivity)
                } catch (e: Exception) {
                    null
                }
            }
            if (loaded == null) {
                setStatus(getString(R.string.status_no_model))
                speaker.say(getString(R.string.status_no_model), interrupt = true)
                binding.btnMain.isEnabled = false
                return@launch
            }
            embedder = loaded
            binding.tvDetail.text = "model ${loaded.inputSize}px · ${loaded.dim}d"
            reloadPeople(firstRun = true)
        }
    }

    private fun reloadPeople(firstRun: Boolean = false) {
        val e = embedder ?: return
        lifecycleScope.launch {
            binding.btnReload.isEnabled = false
            setStatus(getString(R.string.status_loading))
            if (!firstRun) speaker.say(getString(R.string.say_reloading), interrupt = true)

            people = store.load(e, finder)
            spokenAt.clear()

            val n = people.size
            val message = when {
                n == 0 -> getString(R.string.status_no_people)
                n == 1 -> getString(R.string.status_ready_one)
                else -> getString(R.string.status_ready, n)
            }
            setStatus(message)
            val spoken = when {
                n == 0 -> getString(R.string.say_reload_empty)
                n == 1 -> getString(R.string.say_reload_done_one)
                else -> getString(R.string.say_reload_done, n)
            }
            speaker.say(spoken, interrupt = true)
            binding.tvDetail.text = "${store.photoCount()} photos · threshold ${"%.2f".format(threshold)}"
            binding.btnReload.isEnabled = true
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, this::onFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                setStatus(e.message ?: "Camera error")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- controls -------------------------------------------------------

    private fun toggleListening() {
        listening = !listening
        speaker.tapFeedback()
        spokenAt.clear()
        binding.btnMain.text =
            getString(if (listening) R.string.btn_stop else R.string.btn_start)
        val message = getString(
            if (listening) R.string.status_looking else R.string.status_stopped
        )
        setStatus(message)
        speaker.say(message, interrupt = true)
    }

    private fun cycleSensitivity() {
        sensitivityIndex = (sensitivityIndex + 1) % sensitivityLevels.size
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SENSITIVITY, sensitivityIndex)
            .apply()
        speaker.tapFeedback()
        speaker.say(getString(sensitivityLevels[sensitivityIndex].second), interrupt = true)
        binding.tvDetail.text = "threshold ${"%.2f".format(threshold)}"
    }

    // ---- recognition loop -----------------------------------------------

    private fun onFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!listening || busy || now - lastFrameAt < FRAME_INTERVAL_MS || embedder == null) {
            proxy.close()
            return
        }
        lastFrameAt = now

        val rotation = proxy.imageInfo.rotationDegrees
        val raw = try {
            proxy.toBitmap()
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (raw == null) return

        busy = true
        work.launch {
            try {
                process(upright(raw, rotation))
            } catch (e: Exception) {
                // A dropped frame is not worth telling the user about.
            } finally {
                busy = false
            }
        }
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private suspend fun process(bitmap: Bitmap) {
        val e = embedder ?: return

        val faces = finder.detect(bitmap)
        if (faces.isEmpty()) {
            bitmap.recycle()
            announce(getString(R.string.say_no_face), NO_FACE_REPEAT_MS, Feedback.NONE)
            return
        }

        val face = FaceCrop.largest(faces)
        if (face == null) {
            bitmap.recycle()
            return
        }
        val box = face.boundingBox

        // Aiming help: the camera is not where your eyes are, so tell the user
        // what the camera can actually see.
        val relativeWidth = box.width().toFloat() / bitmap.width
        if (relativeWidth < MIN_FACE_FRACTION) {
            bitmap.recycle()
            announce(getString(R.string.say_closer), GUIDE_REPEAT_MS, Feedback.NONE)
            return
        }
        val centre = box.exactCenterX() / bitmap.width
        if (centre < 0.24f) {
            announce(getString(R.string.say_left), GUIDE_REPEAT_MS, Feedback.NONE)
        } else if (centre > 0.76f) {
            announce(getString(R.string.say_right), GUIDE_REPEAT_MS, Feedback.NONE)
        }

        val crop = FaceCrop.crop(bitmap, box)
        bitmap.recycle()
        if (crop == null) return

        val vector = try {
            e.embed(crop)
        } finally {
            crop.recycle()
        }

        if (people.isEmpty()) return
        val result = Matcher.match(vector, people, threshold)

        withContext(Dispatchers.Main) {
            binding.tvDetail.text = buildString {
                append("%.2f".format(result.score))
                result.runnerUpName?.let {
                    append("  ·  next: $it %.2f".format(result.runnerUpScore))
                }
            }
        }

        if (result.recognised) {
            announce(result.name!!, NAME_REPEAT_MS, Feedback.RECOGNISED)
        } else {
            announce(getString(R.string.say_unknown), UNKNOWN_REPEAT_MS, Feedback.UNKNOWN)
        }
    }

    private enum class Feedback { NONE, RECOGNISED, UNKNOWN }

    /** Speaks [text] unless it has already been said within [cooldownMs]. */
    private suspend fun announce(text: String, cooldownMs: Long, feedback: Feedback) {
        val now = System.currentTimeMillis()
        val last = spokenAt[text] ?: 0L
        if (now - last < cooldownMs) return
        spokenAt[text] = now

        withContext(Dispatchers.Main) {
            lastResultSpoken = text
            setStatus(text)
            when (feedback) {
                Feedback.RECOGNISED -> speaker.recognisedFeedback()
                Feedback.UNKNOWN -> speaker.unknownFeedback()
                Feedback.NONE -> {}
            }
            speaker.say(text, interrupt = true)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvResult.text = text }
    }

    // ---- teardown -------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        work.cancel()
        cameraExecutor.shutdown()
        finder.close()
        embedder?.close()
        speaker.shutdown()
    }
}
