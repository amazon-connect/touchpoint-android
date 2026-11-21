package com.acmebank.demo

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.acmebank.demo.support.LiveMode
import com.acmebank.demo.support.LiveSyncCoordinator
import com.acmebank.demo.support.SupportCoordinator

/**
 * Holds the demo's state for the process: the activity is recreated on rotation, and neither the
 * signed-in session nor the support conversation should go with it.
 */
class AcmeBankApplication : Application() {
    val model: AppModel by lazy { AppModel() }
    val liveSync: LiveSyncCoordinator by lazy { LiveSyncCoordinator(this, model) }
    val support: SupportCoordinator by lazy { SupportCoordinator(this).also { it.liveSync = liveSync; liveSync.session = { it.session } } }

    override fun onCreate() {
        super.onCreate()
        PartnerLook.restore(this)
        // Debug builds with live.properties start in live mode; the Live Sync keys saved from the
        // sign-in screen load first, as the session's contact flow depends on them.
        if (LiveMode.configured) support.changeMode(SupportCoordinator.Mode.LIVE)
        MainScope().launch { LiveMode.loadSavedKeys(this@AcmeBankApplication) }
        Appearance.restore(this)
        // Live Sync follows the app: the socket closes in the
        // background, where the backend's lease lapses anyway, and resumes in the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = liveSync.background()

                override fun onStart(owner: LifecycleOwner) = liveSync.foreground()
            },
        )
    }
}
