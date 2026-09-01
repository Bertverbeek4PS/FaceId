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
        Wearables.initialize(this)
    }
}
