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
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.bert.faceid.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

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
        const val ENROLL_PHOTOS = 5

        const val PREFS = "faceid"
        const val KEY_SENSITIVITY = "sensitivity_level"

        /** Registration deeplinks through the Meta AI app, so allow the user time. */
        const val REGISTRATION_TIMEOUT_MS = 120_000L
    }

    /** Thresholds paired with how they are announced. Index 1 is the default. */
    private val sensitivityLevels = listOf(
        0.48f to R.string.sens_relaxed,
        0.55f to R.string.sens_normal,
        0.60f to R.string.sens_strict,
        0.65f to R.string.sens_very_strict
    )


    private enum class Mode { IDLE, LOOKING, ENROLLING }

    private lateinit var binding: ActivityMainBinding
    private lateinit var speaker: Speaker
    private lateinit var store: PeopleStore
    private val finder = FaceFinder()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var useGlasses = false
    private var glassesLoop: Job? = null
    private var permissionCont: CancellableContinuation<PermissionStatus>? = null

    /** The most recent glasses preview frame, recycled once the next one replaces it. */
    private var lastPreview: Bitmap? = null

    /** Own scope: onFrame runs on the camera thread, where touching
     *  lifecycleScope for the first time would not be safe. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Ray-Ban Meta camera, used instead of the phone camera when selected. */
    private val glasses = MetaGlassesCamera(work)

    private var embedder: FaceEmbedder? = null
    private var people: List<Person> = emptyList()

    private var mode = Mode.IDLE
    private var busy = false
    private var lastFrameAt = 0L
    private var lastResultSpoken = ""

    private var sensitivityIndex = 1
    private val threshold: Float get() = sensitivityLevels[sensitivityIndex].first


    private val spokenAt = ConcurrentHashMap<String, Long>()

    // Enrolment state
    private val captured = mutableListOf<Bitmap>()

    /** Set by the shutter button; the next frame with a usable face is kept. */
    @Volatile
    private var captureRequested = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            setStatus(getString(R.string.status_no_camera))
            speaker.say(getString(R.string.status_no_camera), interrupt = true)
        }
    }

    /** The glasses camera permission is brokered by the Meta AI app, not Android. */
    private val glassesCameraPermission = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        var status = PermissionStatus.Denied
        result.onSuccess { status = it }
        permissionCont?.resume(status)
        permissionCont = null
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

        binding.btnMain.setOnClickListener { onMainButton() }
        binding.btnAdd.setOnClickListener {
            if (mode == Mode.ENROLLING) cancelEnrolment() else startEnrolment()
        }
        binding.btnPeople.setOnClickListener { showPeopleDialog() }
        binding.btnReload.setOnClickListener {
            speaker.tapFeedback()
            reloadPeople()
        }
        binding.btnSensitivity.setOnClickListener { cycleSensitivity() }
        binding.btnCamera.setOnClickListener { toggleCamera() }

        // Glasses frames arrive already upright; feed them into the same pipeline
        // the phone camera uses.
        glasses.setFrameListener { bitmap -> submitFrame(bitmap) }
        glasses.setPreviewListener { bitmap -> showGlassesPreview(bitmap) }
        refreshCameraButton()

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
            // Only speak when the user asked for a reload. Announcing the
            // headcount on every launch is noise.
            if (!firstRun) {
                speaker.say(
                    when {
                        n == 0 -> getString(R.string.say_reload_empty)
                        n == 1 -> getString(R.string.say_reload_done_one)
                        else -> getString(R.string.say_reload_done, n)
                    },
                    interrupt = true
                )
            }
            showDetail("${store.photoCount()} photos · threshold %.2f".format(threshold))
            binding.btnReload.isEnabled = true
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

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

    // ---- glasses camera -------------------------------------------------

    private fun stopPhoneCamera() {
        cameraProvider?.unbindAll()
    }

    private fun toggleCamera() {
        if (mode == Mode.ENROLLING) return
        speaker.tapFeedback()
        if (useGlasses) {
            stopGlasses()
        } else {
            stopPhoneCamera()
            startGlasses()
        }
    }

    /**
     * Brings the glasses online: registers with the Meta AI app if needed, gets
     * the camera permission it brokers, then opens a session and stream. Each
     * step can round-trip through the Meta AI app, so this is one linear coroutine
     * that falls back to the phone camera on any failure.
     */
    private fun startGlasses() {
        binding.btnCamera.isEnabled = false
        setStatus(getString(R.string.glasses_connecting))
        speaker.say(getString(R.string.glasses_connecting), interrupt = true)

        lifecycleScope.launch {
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                Wearables.startRegistration(this@MainActivity)
                val registered = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
                    Wearables.registrationState.first { it == RegistrationState.REGISTERED }
                }
                if (registered == null) {
                    glassesFailed()
                    return@launch
                }
            }

            if (!ensureGlassesCameraPermission()) {
                glassesFailed()
                return@launch
            }

            if (!glasses.connect()) {
                glassesFailed()
                return@launch
            }

            useGlasses = true
            binding.btnCamera.isEnabled = true
            binding.glassesPreview.visibility = View.VISIBLE
            refreshCameraButton()
            setStatus(getString(R.string.glasses_ready))
            speaker.say(getString(R.string.glasses_ready), interrupt = true)
            startGlassesCaptureLoop()
        }
    }

    private fun glassesFailed() {
        useGlasses = false
        binding.btnCamera.isEnabled = true
        clearGlassesPreview()
        refreshCameraButton()
        setStatus(getString(R.string.glasses_failed))
        speaker.say(getString(R.string.glasses_failed), interrupt = true)
        startCamera()
    }

    private fun stopGlasses() {
        glassesLoop?.cancel()
        glassesLoop = null
        glasses.disconnect()
        useGlasses = false
        clearGlassesPreview()
        refreshCameraButton()
        setStatus(getString(R.string.glasses_off))
        speaker.say(getString(R.string.glasses_off), interrupt = true)
        startCamera()
    }

    private suspend fun ensureGlassesCameraPermission(): Boolean {
        var status: PermissionStatus? = null
        Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { status = it }
        if (status == PermissionStatus.Granted) return true
        return requestGlassesPermission() == PermissionStatus.Granted
    }

    private suspend fun requestGlassesPermission(): PermissionStatus =
        suspendCancellableCoroutine { cont ->
            permissionCont = cont
            cont.invokeOnCancellation { permissionCont = null }
            glassesCameraPermission.launch(Permission.CAMERA)
        }

    /**
     * Drives the glasses at the same cadence as the phone camera. A still is only
     * requested when the pipeline is free, so slow Bluetooth captures apply their
     * own backpressure rather than piling up.
     */
    private fun startGlassesCaptureLoop() {
        glassesLoop?.cancel()
        glassesLoop = work.launch {
            while (isActive && useGlasses) {
                if (mode != Mode.IDLE && !busy) {
                    try {
                        glasses.capture()
                    } catch (e: Exception) {
                        // A dropped frame is not worth surfacing.
                    }
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun refreshCameraButton() {
        binding.btnCamera.text = getString(
            if (useGlasses) R.string.btn_camera_glasses else R.string.btn_camera_phone
        )
    }

    /** Swaps in the newest raw preview frame and recycles the one it replaces. */
    private fun showGlassesPreview(bitmap: Bitmap) {
        runOnUiThread {
            if (!useGlasses) {
                bitmap.recycle()
                return@runOnUiThread
            }
            binding.glassesPreview.setImageBitmap(bitmap)
            lastPreview?.recycle()
            lastPreview = bitmap
        }
    }

    private fun clearGlassesPreview() {
        binding.glassesPreview.visibility = View.GONE
        binding.glassesPreview.setImageDrawable(null)
        lastPreview?.recycle()
        lastPreview = null
    }

    /** Feeds one already-upright frame into the recognition/enrolment pipeline. */
    private fun submitFrame(upright: Bitmap) {
        if (mode == Mode.IDLE || busy || embedder == null) {
            upright.recycle()
            return
        }
        busy = true
        work.launch {
            try {
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

    // ---- controls -------------------------------------------------------

    private fun onMainButton() {
        speaker.tapFeedback()
        if (mode == Mode.ENROLLING) {
            // The shutter. The next frame holding a usable face is kept, so a
            // press while the camera is badly aimed waits rather than saving
            // a bad reference photo.
            captureRequested = true
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
        val enrolling = mode == Mode.ENROLLING
        binding.btnMain.text = when {
            enrolling -> getString(
                R.string.btn_take_photo,
                (captured.size + 1).coerceAtMost(ENROLL_PHOTOS),
                ENROLL_PHOTOS
            )
            mode == Mode.LOOKING -> getString(R.string.btn_stop)
            else -> getString(R.string.btn_start)
        }
        binding.btnAdd.text =
            getString(if (enrolling) R.string.btn_cancel else R.string.btn_add)
        binding.btnPeople.isEnabled = !enrolling
        binding.btnReload.isEnabled = !enrolling
        binding.btnSensitivity.isEnabled = !enrolling
        binding.btnCamera.isEnabled = !enrolling
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
        captureRequested = false
        mode = Mode.ENROLLING
        refreshMainButton()
        setStatus(getString(R.string.enroll_hold))
        speaker.say(getString(R.string.enroll_hold), interrupt = true)
    }

    private fun cancelEnrolment() {
        mode = Mode.IDLE
        captureRequested = false
        recycleCaptured()
        refreshMainButton()
        setStatus(getString(R.string.enroll_cancelled))
        speaker.say(getString(R.string.enroll_cancelled), interrupt = true)
    }

    private fun recycleCaptured() {
        for (b in captured) if (!b.isRecycled) b.recycle()
        captured.clear()
    }

    /**
     * Called on the worker thread for each frame while enrolling.
     *
     * Frames are inspected continuously so the aiming help stays live, but a
     * photo is only kept when the shutter has been pressed. Automatic capture
     * meant the photo was taken before the user had finished aiming.
     */
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

        if (!captureRequested) {
            bitmap.recycle()
            return
        }
        captureRequested = false

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
            } else {
                refreshMainButton()
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
        AlertDialog.Builder(this, R.style.DarkDialog)
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
            textSize = 24f
            hint = getString(R.string.name_type_title)
            setTextColor(ContextCompat.getColor(context, R.color.amber))
            setHintTextColor(ContextCompat.getColor(context, R.color.grey_dim))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this, R.style.DarkDialog)
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
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle(getString(R.string.people_title) + " (${names.size})")
            .setItems(names.toTypedArray()) { _, which -> confirmDelete(names[which]) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDelete(name: String) {
        val question = getString(R.string.delete_confirm, name)
        speaker.say(question, interrupt = true)
        AlertDialog.Builder(this, R.style.DarkDialog)
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

        submitFrame(upright(raw, rotation))
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
        glassesLoop?.cancel()
        glasses.disconnect()
        lastPreview?.recycle()
        lastPreview = null
        work.cancel()
        cameraExecutor.shutdown()
        recycleCaptured()
        finder.close()
        embedder?.close()
        speaker.shutdown()
    }
}
