package nl.bert.faceid

import android.app.Application
import com.meta.wearable.dat.core.Wearables

/**
 * Initialises the Meta Wearables Device Access Toolkit once for the whole
 * process. Every other SDK call — registration, sessions, camera — fails with
 * NOT_INITIALIZED until this has run, so it belongs here rather than in an
 * Activity that may be created more than once.
 */
class FaceIdApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The glasses are optional; never let an SDK init failure take down the
        // whole app before the phone-camera UI has even shown.
        try {
            Wearables.initialize(this)
            glassesAvailable = true
        } catch (t: Throwable) {
            glassesAvailable = false
            initError = t.message ?: t.javaClass.simpleName
        }
    }

    companion object {
        /** True once the Meta Wearables SDK initialised without throwing. */
        @Volatile
        var glassesAvailable: Boolean = false
            private set

        /** Why SDK init failed, when it did — for surfacing to the user. */
        @Volatile
        var initError: String? = null
            private set
    }
}
