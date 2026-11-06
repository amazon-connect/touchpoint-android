@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.chat.AuthenticationRedirect
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.amazon.connect.touchpoint.core.AuthenticationCard
import com.amazon.connect.touchpoint.core.AuthenticationRequest
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the sign-in card asks of the session. [begin] returns the page to open, or `null` when it was
 * refused — the card then says why. [cancel] continues without; with `unlessRedirected`, only if the
 * page's redirect never came back.
 */
internal class AuthenticationActions(
    val begin: suspend () -> AuthenticationRequest?,
    val cancel: suspend (unlessRedirected: Boolean) -> Unit,
    /** What the sign-in tab handed back on its own: the redirect, for the session. */
    val complete: suspend (redirect: String) -> Unit = {},
)

/**
 * Web's `Authentication.tsx`: the heading, then a bordered box holding either the two actions (the
 * prompt) or one status line with its icon.
 *
 * The page opens in an ephemeral sign-in tab: Chrome's Auth Tab, which hands the redirect — the app's
 * scheme, or an https link on its domain — straight back to the SDK.
 * A browser without it opens a Custom Tab, and the redirect comes back to the host's activity, which
 * hands it to the session (the SDK declares no scheme). **Deviation:** a Custom Tab says nothing
 * when it is closed, so a return to the app with no redirect after [RETURN_GRACE_MS] counts as closed
 * and cancels — web waits 400 ms for the same race between its popup's
 * message and its close.
 */
@Composable
internal fun AuthenticationCardView(
    card: AuthenticationCard,
    actions: AuthenticationActions?,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by rememberSaveable(card.id) { mutableStateOf(false) }
    // The page is open, and then the app has left the screen for it: the next return decides whether
    // it was closed. Saved, as the host's activity may be recreated behind the page.
    var awaitingReturn by rememberSaveable(card.id) { mutableStateOf(false) }
    var leftForPage by rememberSaveable(card.id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, card.id) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> if (awaitingReturn) leftForPage = true
                    Lifecycle.Event.ON_RESUME ->
                        if (awaitingReturn && leftForPage) {
                            awaitingReturn = false
                            leftForPage = false
                            scope.launch {
                                delay(RETURN_GRACE_MS)
                                actions?.cancel?.invoke(true)
                            }
                        }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The Auth Tab's answer: the redirect, or nothing (closed, or a domain whose links did not verify).
    val registry = LocalActivityResultRegistryOwner.current
    val signInTab =
        registry?.let {
            rememberLauncherForActivityResult(SignInTabResult) { redirect -> if (redirect != null) scope.launch { actions?.complete?.invoke(redirect) } }
        }

    fun authenticate() {
        val a = actions ?: return
        if (working) return
        working = true
        scope.launch {
            try {
                val request = a.begin() ?: return@launch // the card already says why
                val redirect = AuthenticationRedirect.of(request.redirectUri)
                val opened = if (signInTab != null && redirect != null) launch(signInTab, request.url, redirect) else open(context, request.url)
                if (opened) awaitingReturn = true else a.cancel(false) // nothing to open it with: the flow goes on without
            } finally {
                working = false
            }
        }
    }

    val failure = card.status == AuthenticationStatus.FAILED || card.status == AuthenticationStatus.EXPIRED || card.status == AuthenticationStatus.CANCELLED
    val shape = RoundedCornerShape(theme.outerRadius)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
        BasicText(
            stringResource(R.string.touchpoint_authentication_heading),
            Modifier.semantics { heading() },
            style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight),
        )
        Column(
            Modifier.fillMaxWidth().border(metrics.hairline, theme.primary20, shape).padding(metrics.smallGap),
            verticalArrangement = Arrangement.spacedBy(metrics.smallGap),
        ) {
            if (card.status == AuthenticationStatus.PROMPT) {
                RowButton(
                    stringResource(R.string.touchpoint_authentication_authenticate),
                    TouchpointIcons.ArrowForward,
                    onClick = if (actions != null && !working) ::authenticate else null,
                    main = true,
                )
                if (actions != null) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = metrics.minimumTouchTarget)
                            .clickable(enabled = !working, role = Role.Button) { scope.launch { actions.cancel(false) } }
                            .padding(vertical = metrics.tinyGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            stringResource(R.string.touchpoint_authentication_skip),
                            // `primary60`, web's hover colour, not its resting `primary40`: a link meets AA.
                            style = theme.text(theme.primary60, metrics.smallText, metrics.smallLineHeight).copy(textAlign = TextAlign.Center),
                        )
                    }
                }
            } else {
                val color = if (failure) theme.errorPrimary else theme.primary60
                Row(
                    Modifier.fillMaxWidth().heightIn(min = metrics.minimumTouchTarget).padding(vertical = metrics.tinyGap).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(metrics.smallGap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        card.status == AuthenticationStatus.SUCCESS -> Glyph(TouchpointIcons.Check, color, metrics.iconSize)
                        failure -> Glyph(TouchpointIcons.Error, color, metrics.iconSize)
                        else -> PulseDot(color)
                    }
                    BasicText(statusText(card.status), style = theme.text(color, metrics.bodyText, metrics.bodyLineHeight))
                }
            }
        }
    }
}

/** Web's `animate-auth-pulse`, still when motion is reduced. */
@Composable
private fun PulseDot(color: androidx.compose.ui.graphics.Color) {
    val still = reduceMotion()
    val scale =
        if (still) {
            1f
        } else {
            val t by rememberInfiniteTransition().animateFloat(1f, PULSE_SCALE, infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse))
            t
        }
    Box(Modifier.size(TouchpointMetrics.authenticationPulseDot).scale(scale).background(color, CircleShape))
}

@Composable
internal fun statusText(status: AuthenticationStatus): String =
    stringResource(
        when (status) {
            AuthenticationStatus.PROMPT -> R.string.touchpoint_authentication_heading
            AuthenticationStatus.IN_PROGRESS -> R.string.touchpoint_authentication_in_progress
            AuthenticationStatus.SUCCESS -> R.string.touchpoint_authentication_success
            AuthenticationStatus.FAILED -> R.string.touchpoint_authentication_failed
            AuthenticationStatus.EXPIRED -> R.string.touchpoint_authentication_expired
            AuthenticationStatus.CANCELLED -> R.string.touchpoint_authentication_cancelled
        },
    )

/** The sign-in page in a Custom Tab, or the browser; `false` when nothing on the device can open it. Only https. */
private fun open(
    context: Context,
    url: String,
): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return false
    val activity = findActivity(context)
    val launcher = activity ?: context
    return try {
        val tab = signInTab()
        if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(launcher, uri)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false // a context in a state to start nothing: no sign-in, never a crash
    }
}

/**
 * The sign-in tab: ephemeral — its own cookie jar, nothing
 * kept after it closes, so the identity provider's session never lands in the customer's browser. The
 * flag is androidx.browser 1.9's `EXTRA_ENABLE_EPHEMERAL_BROWSING`, set on 1.8's intent so a host is
 * not raised to 1.9 (and its compileSdk 36); a browser that does not know it opens a standard tab.
 */
internal fun signInTab(): CustomTabsIntent =
    CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.putExtra(EXTRA_ENABLE_EPHEMERAL_BROWSING, true) }

/**
 * [signInTab] as Chrome's Auth Tab, for [url], coming back on [redirect]: androidx.browser 1.9's
 * `AuthTabIntent`, built from its documented extras on 1.8's intent, for the same reason as the
 * ephemeral flag. A browser without Auth Tab opens it as a Custom Tab.
 */
internal fun signInAuthTab(
    url: Uri,
    redirect: AuthenticationRedirect,
): Intent =
    signInTab().intent.apply {
        data = url
        putExtra(EXTRA_LAUNCH_AUTH_TAB, true)
        when (redirect) {
            is AuthenticationRedirect.Scheme -> putExtra(EXTRA_REDIRECT_SCHEME, redirect.scheme)
            is AuthenticationRedirect.Https -> {
                putExtra(EXTRA_HTTPS_REDIRECT_HOST, redirect.host)
                putExtra(EXTRA_HTTPS_REDIRECT_PATH, redirect.path)
            }
        }
    }

/** The Auth Tab's result: the redirect it caught, on `RESULT_OK` only, as `AuthTabIntent`'s own contract reads it. */
internal object SignInTabResult : ActivityResultContract<Intent, String?>() {
    override fun createIntent(
        context: Context,
        input: Intent,
    ): Intent = input

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): String? = intent?.data?.toString()?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Opens the sign-in tab for a result; `false` when nothing on the device can. Only https. */
private fun launch(
    launcher: ActivityResultLauncher<Intent>,
    url: String,
    redirect: AuthenticationRedirect,
): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return false
    return try {
        launcher.launch(signInAuthTab(uri, redirect))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false // a registry in no state to launch: no sign-in, never a crash
    }
}

internal const val EXTRA_ENABLE_EPHEMERAL_BROWSING = "androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING"
internal const val EXTRA_LAUNCH_AUTH_TAB = "androidx.browser.auth.extra.LAUNCH_AUTH_TAB"
internal const val EXTRA_REDIRECT_SCHEME = "androidx.browser.auth.extra.REDIRECT_SCHEME"
internal const val EXTRA_HTTPS_REDIRECT_HOST = "androidx.browser.auth.extra.HTTPS_REDIRECT_HOST"
internal const val EXTRA_HTTPS_REDIRECT_PATH = "androidx.browser.auth.extra.HTTPS_REDIRECT_PATH"

/** Time for the redirect to reach the session after the app comes back: well past web's 400 ms. */
private const val RETURN_GRACE_MS = 1_500L
private const val PULSE_MS = 500
private const val PULSE_SCALE = 1.5f
