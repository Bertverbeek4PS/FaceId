package nl.bert.faceid

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Size
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import java.util.Locale
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

        /** Enrolment is stricter: a bad reference photo poisons every match. */
        const val ENROLL_MIN_FACE_FRACTION = 0.22f
        const val ENROLL_PHOTOS = 3
        const val ENROLL_GAP_MS = 900L

        const val PREFS = "faceid"
        const val KEY_SENSITIVITY = "sensitivity_level"
        const val KEY_BRIGHTNESS = "brightness_level"
    }

    /** Thresholds paired with how they are announced. Index 1 is the default. */
    private val sensitivityLevels = listOf(
        0.48f to R.string.sens_relaxed,
        0.55f to R.string.sens_normal,
        0.60f to R.string.sens_strict,
        0.65f to R.string.sens_very_strict
    )

    /**
     * Backlight levels, dimmest first. The palette alone is not enough for a
     * light-sensitive user: the real fix is turning the backlight down, which
     * an app is allowed to do for its own window.
     *
     * -1f hands control back to the phone's own brightness setting.
     */
    private val brightnessLevels = listOf(
        0.02f to R.string.bright_1,
        0.08f to R.string.bright_2,
        0.25f to R.string.bright_3,
        0.60f to R.string.bright_4,
        -1f to R.string.bright_5
    )

    private enum class Mode { IDLE, LOOKING, ENROLLING }

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

    private var mode = Mode.IDLE
    private var busy = false
    private var lastFrameAt = 0L
    private var lastResultSpoken = ""

    private var sensitivityIndex = 1
    private val threshold: Float get() = sensitivityLevels[sensitivityIndex].first

    private var brightnessIndex = 0

    private val spokenAt = ConcurrentHashMap<String, Long>()

    // Enrolment state
    private val captured = mutableListOf<Bitmap>()
    private var lastCaptureAt = 0L

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            setStatus(getString(R.string.status_no_camera))
            speaker.say(getString(R.string.status_no_camera), interrupt = true)
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (heard.isNullOrBlank()) promptTypedName() else confirmName(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speaker = Speaker(this)
        store = PeopleStore(this)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sensitivityIndex = prefs.getInt(KEY_SENSITIVITY, 1)
            .coerceIn(sensitivityLevels.indices)
        brightnessIndex = prefs.getInt(KEY_BRIGHTNESS, 0)
            .coerceIn(brightnessLevels.indices)
        applyBrightness()

        binding.btnMain.setOnClickListener { onMainButton() }
        binding.btnAdd.setOnClickListener { startEnrolment() }
        binding.btnPeople.setOnClickListener { showPeopleDialog() }
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

        // Press and hold anywhere on the big text to step the screen brightness.
        // It is the largest target in the app, so it can be found without looking.
        binding.tvResult.setOnLongClickListener {
            cycleBrightness()
            true
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
                binding.btnAdd.isEnabled = false
                return@launch
            }
            embedder = loaded
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
            setStatus(
                when {
                    n == 0 -> getString(R.string.status_no_people)
                    n == 1 -> getString(R.string.status_ready_one)
                    else -> getString(R.string.status_ready, n)
                }
            )
            speaker.say(
                when {
                    n == 0 -> getString(R.string.say_reload_empty)
                    n == 1 -> getString(R.string.say_reload_done_one)
                    else -> getString(R.string.say_reload_done, n)
                },
                interrupt = true
            )
            showDetail("${store.photoCount()} photos · threshold %.2f".format(threshold))
            binding.btnReload.isEnabled = true
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

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
                // Analysis only. No Preview use case: nothing is drawn to the
                // screen, which removes the brightest thing in the app and
                // saves battery at the same time.
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, analysis
                )
            } catch (e: Exception) {
                setStatus(e.message ?: "Camera error")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- controls -------------------------------------------------------

    private fun onMainButton() {
        speaker.tapFeedback()
        if (mode == Mode.ENROLLING) {
            cancelEnrolment()
            return
        }
        mode = if (mode == Mode.LOOKING) Mode.IDLE else Mode.LOOKING
        spokenAt.clear()
        val message = getString(
            if (mode == Mode.LOOKING) R.string.status_looking else R.string.status_stopped
        )
        refreshMainButton()
        setStatus(message)
        speaker.say(message, interrupt = true)
    }

    private fun refreshMainButton() {
        binding.btnMain.text = getString(
            when (mode) {
                Mode.ENROLLING -> R.string.btn_cancel
                Mode.LOOKING -> R.string.btn_stop
                Mode.IDLE -> R.string.btn_start
            }
        )
        val enrolling = mode == Mode.ENROLLING
        binding.btnAdd.isEnabled = !enrolling
        binding.btnPeople.isEnabled = !enrolling
        binding.btnReload.isEnabled = !enrolling
    }

    private fun applyBrightness() {
        val attributes = window.attributes
        attributes.screenBrightness = brightnessLevels[brightnessIndex].first
        window.attributes = attributes
    }

    private fun cycleBrightness() {
        brightnessIndex = (brightnessIndex + 1) % brightnessLevels.size
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BRIGHTNESS, brightnessIndex)
            .apply()
        applyBrightness()
        speaker.tapFeedback()
        speaker.say(getString(brightnessLevels[brightnessIndex].second), interrupt = true)
    }

    private fun cycleSensitivity() {
        sensitivityIndex = (sensitivityIndex + 1) % sensitivityLevels.size
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SENSITIVITY, sensitivityIndex)
            .apply()
        speaker.tapFeedback()
        speaker.say(getString(sensitivityLevels[sensitivityIndex].second), interrupt = true)
        showDetail("threshold %.2f".format(threshold))
    }

    // ---- enrolment ------------------------------------------------------

    private fun startEnrolment() {
        if (embedder == null) return
        speaker.tapFeedback()
        recycleCaptured()
        lastCaptureAt = 0L
        mode = Mode.ENROLLING
        refreshMainButton()
        setStatus(getString(R.string.enroll_hold))
        speaker.say(getString(R.string.enroll_hold), interrupt = true)
    }

    private fun cancelEnrolment() {
        mode = Mode.IDLE
        recycleCaptured()
        refreshMainButton()
        setStatus(getString(R.string.enroll_cancelled))
        speaker.say(getString(R.string.enroll_cancelled), interrupt = true)
    }

    private fun recycleCaptured() {
        for (b in captured) if (!b.isRecycled) b.recycle()
        captured.clear()
    }

    /** Called on the worker thread for each frame while enrolling. */
    private suspend fun enrolFrame(bitmap: Bitmap) {
        val faces = finder.detect(bitmap)
        val face = FaceCrop.largest(faces)
        if (face == null) {
            bitmap.recycle()
            announce(getString(R.string.say_no_face), GUIDE_REPEAT_MS, Feedback.NONE)
            return
        }

        val box = face.boundingBox
        if (box.width().toFloat() / bitmap.width < ENROLL_MIN_FACE_FRACTION) {
            bitmap.recycle()
            announce(getString(R.string.say_closer), GUIDE_REPEAT_MS, Feedback.NONE)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < ENROLL_GAP_MS) {
            bitmap.recycle()
            return
        }
        lastCaptureAt = now

        // A generous crop: enough context for the detector to find the face
        // again on reload, small enough that the JPEG stays tiny.
        val crop = FaceCrop.crop(bitmap, box, margin = 0.45f)
        bitmap.recycle()
        if (crop == null) return

        captured += crop
        val n = captured.size

        withContext(Dispatchers.Main) {
            val message = getString(R.string.enroll_photo, n, ENROLL_PHOTOS)
            setStatus(message)
            speaker.recognisedFeedback()
            speaker.say(message, interrupt = true)
            if (n >= ENROLL_PHOTOS) {
                mode = Mode.IDLE
                refreshMainButton()
                askForName()
            }
        }
    }

    /**
     * Asks by voice, because typing a name is the least accessible part of this
     * whole app. The system's own speech UI handles the microphone permission,
     * and typing stays available as a fallback.
     */
    private fun askForName() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.name_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            promptTypedName()
        }
    }

    private fun confirmName(heard: String) {
        val clean = store.sanitiseName(heard)
        if (clean.isEmpty()) {
            promptTypedName()
            return
        }
        val question = getString(R.string.name_confirm, clean)
        speaker.say(question, interrupt = true)
        AlertDialog.Builder(this)
            .setTitle(question)
            .setPositiveButton(R.string.name_save) { _, _ -> savePerson(clean) }
            .setNeutralButton(R.string.name_again) { _, _ -> askForName() }
            .setNegativeButton(R.string.name_type) { _, _ -> promptTypedName() }
            .setOnCancelListener { recycleCaptured() }
            .show()
    }

    private fun promptTypedName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            textSize = 26f
            hint = getString(R.string.name_type_title)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_type_title)
            .setView(input)
            .setPositiveButton(R.string.name_save) { _, _ ->
                savePerson(store.sanitiseName(input.text.toString()))
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> recycleCaptured() }
            .setOnCancelListener { recycleCaptured() }
            .show()
    }

    private fun savePerson(name: String) {
        if (name.isEmpty() || captured.isEmpty()) {
            speaker.say(getString(R.string.save_failed), interrupt = true)
            recycleCaptured()
            return
        }
        val photos = captured.toList()
        captured.clear()

        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { store.savePerson(name, photos) }
            for (b in photos) if (!b.isRecycled) b.recycle()

            if (written == 0) {
                setStatus(getString(R.string.save_failed))
                speaker.say(getString(R.string.save_failed), interrupt = true)
                return@launch
            }
            val message = getString(R.string.saved_person, name, written)
            setStatus(message)
            speaker.say(message, interrupt = true)
            reloadPeople(firstRun = true)
        }
    }

    // ---- managing people ------------------------------------------------

    private fun showPeopleDialog() {
        speaker.tapFeedback()
        val names = store.personNames()
        if (names.isEmpty()) {
            speaker.say(getString(R.string.people_empty), interrupt = true)
            return
        }
        speaker.say(getString(R.string.people_title), interrupt = true)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.people_title) + " (${names.size})")
            .setItems(names.toTypedArray()) { _, which -> confirmDelete(names[which]) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDelete(name: String) {
        val question = getString(R.string.delete_confirm, name)
        speaker.say(question, interrupt = true)
        AlertDialog.Builder(this)
            .setTitle(question)
            .setPositiveButton(R.string.delete_yes) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { store.deletePerson(name) }
                    if (ok) {
                        val message = getString(R.string.removed_person, name)
                        setStatus(message)
                        speaker.say(message, interrupt = true)
                        reloadPeople(firstRun = true)
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---- recognition loop -----------------------------------------------

    private fun onFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (mode == Mode.IDLE || busy || now - lastFrameAt < FRAME_INTERVAL_MS || embedder == null) {
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
                val upright = upright(raw, rotation)
                when (mode) {
                    Mode.ENROLLING -> enrolFrame(upright)
                    Mode.LOOKING -> recognise(upright)
                    Mode.IDLE -> upright.recycle()
                }
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

    private suspend fun recognise(bitmap: Bitmap) {
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
        if (box.width().toFloat() / bitmap.width < MIN_FACE_FRACTION) {
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

        showDetail(
            buildString {
                append("%.2f".format(result.score))
                result.runnerUpName?.let {
                    append("  ·  next: ")
                    append(it)
                    append(" %.2f".format(result.runnerUpScore))
                }
            }
        )

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

    private fun showDetail(text: String) {
        runOnUiThread { binding.tvDetail.text = text }
    }

    // ---- teardown -------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        mode = Mode.IDLE
        work.cancel()
        cameraExecutor.shutdown()
        recycleCaptured()
        finder.close()
        embedder?.close()
        speaker.shutdown()
    }
}
