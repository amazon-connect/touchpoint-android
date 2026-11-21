package com.acmebank.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import com.acmebank.demo.support.LiveMode
import com.acmebank.demo.support.SupportCoordinator
import com.amazon.connect.touchpoint.core.DeepLinkContactIdResolver
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        seed(application as AcmeBankApplication)
    }

    /**
     * Debug builds: a one-shot seed file written over adb switches the demo to live mode (see
     * LiveMode.seed). Checked on every resume, so a seed pushed to a running app takes effect.
     * Release: nothing.
     */
    private fun seed(app: AcmeBankApplication) {
        if (LiveMode.seed(app)) app.support.changeMode(SupportCoordinator.Mode.LIVE)
        // A seeded call: the assistant on the phone line drives the app from here on.
        LiveMode.takeContactId()?.let { app.liveSync.bind(it, app.model.screenName) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bindFromLink(intent)
        completeSignIn(intent)
        openSupport(intent)
    }

    /** A reply's notification: back to the conversation, which the process still holds. */
    private fun openSupport(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SUPPORT, false) != true) return
        val app = application as AcmeBankApplication
        if (app.model.isSignedIn) app.model.supportExpanded = true
    }

    companion object {
        const val EXTRA_OPEN_SUPPORT = "com.acmebank.demo.OPEN_SUPPORT"
    }

    /**
     * Debug builds: the sign-in page's redirect, handed to the session — it checks it against what
     * Connect sent, and the demo backend relays it. The card says how it went.
     */
    private fun completeSignIn(intent: Intent?) {
        val redirect = intent?.dataString ?: return
        if (!LiveMode.AVAILABLE || !redirect.startsWith(LiveMode.AUTHENTICATION_REDIRECT)) return
        val session = (application as AcmeBankApplication).support.session ?: return
        lifecycleScope.launch {
            try {
                session.completeAuthentication(redirect)
            } catch (_: TouchpointException) {
                // the card shows the failure
            }
        }
    }

    /** Debug builds: a deep link to a call made from the phone binds Live Sync to it, through the SDK's resolver. */
    private fun bindFromLink(intent: Intent?) {
        if (!LiveMode.AVAILABLE) return
        val app = application as AcmeBankApplication
        lifecycleScope.launch {
            DeepLinkContactIdResolver(intent?.dataString, scheme = "acmebank", host = "support", pathPrefix = "/livesync").resolveContactId()?.let { app.liveSync.bind(it, app.model.screenName) }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AcmeBankApplication
        if (savedInstanceState == null) {
            bindFromLink(intent)
            completeSignIn(intent)
        }
        setContent {
            AcmeBankTheme {
                // Back closes the support panel first, then walks the bank's own stack; with nothing
                // left, the system's default (leave the app) applies.
                BackHandler(enabled = app.model.isSignedIn && (app.model.supportExpanded || app.model.stack.size > 1)) {
                    if (app.model.supportExpanded) app.model.supportExpanded = false else app.model.back()
                }
                // Test tags double as resource ids, so UI Automator and adb tooling can find them too.
                Root(app.model, app.support, Modifier.semantics { testTagsAsResourceId = true }, app.liveSync)
            }
        }
    }
}
