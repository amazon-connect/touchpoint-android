@file:OptIn(InternalTouchpointUi::class, com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.TouchpointException
import androidx.compose.runtime.LaunchedEffect
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.amazon.connect.touchpoint.core.ResolvedView
import com.amazon.connect.touchpoint.ui.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * What the chat needs for Connect's Views, as web's: `DescribeView` for a view's definition and the
 * answer sent back to the flow, both through the session, and the instance Connect's renderer loads
 * from. As on web, a card resolves without the instance; only opening it needs it, and without it the
 * guide says it could not load.
 */
internal class ViewHost(
    val instanceUrl: String?,
    val describe: suspend (viewToken: String) -> ResolvedView?,
    val submit: suspend (action: String, data: String, viewName: String?) -> Boolean,
    /** A refusal made for the customer's safety, for the host's events (I6). */
    val rejected: (TouchpointException) -> Unit = {},
)

internal val LocalViewHost = staticCompositionLocalOf<ViewHost?> { null }

/** Web's `OpenGuide`: what a card hands up when opened, for the transcript's [GuideModal]. */
internal class OpenGuide(
    val title: String,
    val view: ResolvedView,
    val inputData: String,
    val onComplete: () -> Unit,
)

/** Opens a guide over the transcript, as web's `setActiveGuide`. */
internal val LocalGuideOpener = staticCompositionLocalOf<((OpenGuide) -> Unit)?> { null }

/** Web's guide title: the view's name, else the view data's heading, else the template's title. */
internal fun guideTitle(
    view: ResolvedView?,
    heading: String?,
): String? {
    val templateTitle =
        view?.let {
            runCatching { Json.parseToJsonElement(it.template).jsonObject["Head"]?.jsonObject?.get("Title")?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
    return view?.name?.takeIf { it.isNotEmpty() } ?: heading ?: templateTitle
}

/**
 * A guide opened: a full-screen sheet on the chat's own surface — a title bar with the
 * view's title and Close, and Connect's own view renderer filling the rest, shown once it is ready —
 * where web opens a modal inside its panel: a phone gives a form the whole screen. The renderer loads
 * from the host's instance and shows the view to the customer (`END_CUSTOMER`); its action goes to
 * [onSubmit], then the sheet closes — web's `handleAction` order.
 *
 * The renderer runs in a WebView, as web runs it in the page: JavaScript on because the renderer is a
 * script; no file or content access; no navigation anywhere; one bridge method, the action. The
 * script comes from the host's own instance over `https` only.
 */
@Composable
internal fun GuideModal(
    guide: OpenGuide,
    onSubmit: (action: String, data: String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val host = LocalViewHost.current
    val options = LocalChatOptions.current
    val instanceUrl = host?.instanceUrl?.takeIf { ConnectViewPage.origin(it) != null }
    var state by remember(guide) { mutableStateOf(if (instanceUrl == null) RendererState.FAILED else RendererState.LOADING) }
    // Given one, and not a Connect instance: a host's mistake, or someone else's URL.
    val refused = host?.instanceUrl != null && instanceUrl == null
    LaunchedEffect(guide) { if (refused) TouchpointSecurityEvents.report(TouchpointSecurityEvent.GuideInstanceRefused) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalTouchpointTheme provides theme) {
            Box(Modifier.fillMaxSize()) {
                WebBackground(Modifier.matchParentSize(), depth = options.backgroundDepthLayer)
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = metrics.inset),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(metrics.smallGap),
                    ) {
                        BasicText(
                            guide.title,
                            Modifier.weight(1f).semantics { heading() },
                            style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight).copy(fontWeight = FontWeight.SemiBold),
                        )
                        IconButton(TouchpointIcons.Close, stringResource(R.string.touchpoint_guide_close), onClose)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (instanceUrl != null) {
                            RendererView(
                                guide.view, guide.inputData, instanceUrl,
                                // Hidden until ready: a half-drawn form is not shown.
                                Modifier.fillMaxSize().alpha(if (state == RendererState.READY) 1f else 0f),
                                onState = { state = it },
                                // Sent by the caller, whose scope outlives this sheet: closing it must not cancel the answer.
                                onAction = { action, data ->
                                    onSubmit(action, data)
                                    onClose()
                                },
                                onRejected = { host?.rejected?.invoke(it) },
                            )
                        }
                        when (state) {
                            RendererState.LOADING -> Loading(stringResource(R.string.touchpoint_guide_loading))
                            RendererState.FAILED ->
                                BasicText(
                                    stringResource(R.string.touchpoint_guide_error),
                                    Modifier.padding(metrics.inset),
                                    style = theme.text(theme.errorPrimary, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center),
                                )
                            RendererState.READY -> Unit
                        }
                    }
                }
            }
        }
    }
}

internal enum class RendererState { LOADING, READY, FAILED }

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun RendererView(
    view: ResolvedView,
    inputData: String,
    instanceUrl: String,
    modifier: Modifier,
    onState: (RendererState) -> Unit,
    onAction: (action: String, data: String) -> Unit,
    onRejected: (TouchpointException) -> Unit,
) {
    val state by rememberUpdatedState(onState)
    val action by rememberUpdatedState(onAction)
    val rejected by rememberUpdatedState(onRejected)
    val page = remember(view, inputData, instanceUrl) { ConnectViewPage.html(instanceUrl, view, inputData) }
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                removeJavascriptInterface(ConnectViewPage.BRIDGE)
                destroy()
            }
            // What the renderer stored for the instance goes with the page — its storage and its
            // cookies — so the page keeps nothing. (Android's WebView has no
            // per-view store; these are the origin's own, expired by name.)
            ConnectViewPage.origin(instanceUrl)?.let { origin ->
                WebStorage.getInstance().deleteOrigin(origin)
                val jar = CookieManager.getInstance()
                ConnectViewPage.cookieNames(jar.getCookie(origin)).forEach { jar.setCookie(origin, "$it=; Max-Age=0; Path=/") }
                jar.flush()
            }
        }
    }
    // A new page is a new WebView: the renderer and its form start afresh, never on a stale view.
    androidx.compose.runtime.key(page) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    web = this
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = GuideWebViewClient(ConnectViewPage.scriptUrl(instanceUrl), { rejected(it) }, { state(RendererState.FAILED) })
                    val main = Handler(Looper.getMainLooper())
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun ready() {
                                main.post { state(RendererState.READY) }
                            }

                            @JavascriptInterface
                            fun failed() {
                                main.post { state(RendererState.FAILED) }
                            }

                            @JavascriptInterface
                            fun action(detail: String) {
                                val (a, d) = ConnectViewPage.action(detail) ?: return
                                main.post { action(a, d) }
                            }
                        },
                        ConnectViewPage.BRIDGE,
                    )
                    if (page == null) state(RendererState.FAILED) else loadDataWithBaseURL(ConnectViewPage.origin(instanceUrl), page, "text/html", "utf-8", null)
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * The renderer's page goes nowhere: it draws in place, and a link out is not this sheet's. Every
 * navigation — the instance's own pages, another tenant's, anywhere else — is refused, reported as
 * [TouchpointSecurityEvent.GuideNavigationBlocked], and told to [onRejected] without where it was
 * going. [onRendererFailed] when the renderer's script ([scriptUrl]) cannot load.
 */
internal class GuideWebViewClient(
    private val scriptUrl: String?,
    private val onRejected: (TouchpointException) -> Unit,
    private val onRendererFailed: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.GuideNavigationBlocked)
        onRejected(TouchpointException.InvalidConfiguration("guide renderer blocked a navigation"))
        return true
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (scriptUrl != null && request.url.toString() == scriptUrl) onRendererFailed()
    }
}

/** The page the renderer runs in, and what its action carries. Pure, for the JVM tests. */
internal object ConnectViewPage {
    const val BRIDGE = "TouchpointViews"
    private const val ELEMENT = "connect-view-renderer"
    private val json = Json { ignoreUnknownKeys = true }

    /** Amazon Connect instance hosts: the newer `*.my.connect.aws` and the classic `*.awsapps.com`. */
    private val ALLOWED_HOST_SUFFIXES = listOf(".my.connect.aws", ".awsapps.com")

    /** A page answer is a small JSON object; the cap guards the app from a runaway or hostile page. */
    const val MAX_ACTION_BYTES = 64 * 1024

    /**
     * The instance's origin, `https://host[:port]`, when it is an Amazon Connect instance; `null` for
     * anything else. Only `https`, and at least one label before
     * an allowed suffix: the bare `my.connect.aws`, and a look-alike such as
     * `x.my.connect.aws.evil.com`, are both refused. A bare host is read as `https`.
     */
    fun origin(instanceUrl: String): String? {
        val withScheme = if (instanceUrl.contains("://")) instanceUrl else "https://$instanceUrl"
        val uri = runCatching { java.net.URI(withScheme.trimEnd('/')) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        if (uri.scheme?.lowercase() != "https" || host.isNullOrEmpty()) return null
        if (ALLOWED_HOST_SUFFIXES.none { host.endsWith(it) && host.length > it.length }) return null
        return "https://$host${if (uri.port != -1) ":${uri.port}" else ""}"
    }

    /**
     * The page's Content-Security-Policy: every fetch to the instance alone; scripts only
     * from the instance or as the page's own boot script, which carries [nonce] — no `'unsafe-inline'`
     * and no `'unsafe-eval'` (Connect's renderer uses neither). Styles keep `'unsafe-inline'`: the
     * renderer injects its own.
     */
    fun contentSecurityPolicy(
        origin: String,
        nonce: String,
    ): String =
        listOf(
            "default-src 'self' $origin",
            "script-src 'self' $origin 'nonce-$nonce'",
            "style-src 'self' $origin 'unsafe-inline'",
            "img-src 'self' $origin data: blob:",
            "font-src 'self' $origin data:",
            "connect-src 'self' $origin",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "form-action 'none'",
        ).joinToString("; ")

    /** The names in a `Cookie` header's value (`a=1; b=2`), to expire them. */
    fun cookieNames(header: String?): List<String> = header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

    /** Web's `viewRendererScriptUrl`. */
    fun scriptUrl(instanceUrl: String): String? = origin(instanceUrl)?.let { "$it/connectwidget/static/views/renderer/latest/index.js" }

    /** Web's `ViewPayload`: the view's content, its template and schema as JSON, and the flow's input data. */
    fun payload(
        view: ResolvedView,
        inputData: String,
    ): JsonObject =
        buildJsonObject {
            put(
                "Content",
                buildJsonObject {
                    put("Actions", JsonArray(view.actions.map(::JsonPrimitive)))
                    put("InputSchema", parse(view.inputSchema))
                    put("Template", parse(view.template))
                },
            )
            put("InputData", parse(inputData))
        }

    /**
     * The page, or `null` when the instance's address is not usable. The payload is a JSON block, never
     * code, with `<` and `>` escaped so nothing in it can end the block; the one script that runs from
     * the page itself is the boot script, under a fresh [nonce] each page (fixed only by tests).
     */
    fun html(
        instanceUrl: String,
        view: ResolvedView,
        inputData: String,
        nonce: String = UUID.randomUUID().toString().replace("-", ""),
    ): String? {
        val origin = origin(instanceUrl) ?: return null
        val script = scriptUrl(instanceUrl) ?: return null
        val block = payload(view, inputData).toString().replace("<", "\\u003c").replace(">", "\\u003e")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(origin, nonce)}">
            <style>html,body{margin:0;padding:0;background:transparent}$ELEMENT{display:block;width:100%}</style>
            </head><body>
            <script type="application/json" id="tp-view">$block</script>
            <div id="view"></div>
            <script nonce="$nonce">
            (function () {
              function boot() {
                var view;
                try { view = document.getElementById('tp-view').textContent; JSON.parse(view); } catch (e) { $BRIDGE.failed(); return; }
                var el = document.createElement('$ELEMENT');
                el.setAttribute('guideParticipant', 'END_CUSTOMER');
                el.setAttribute('interactionMode', 'Actual');
                el.setAttribute('locale', 'en_US');
                el.setAttribute('view', view);
                el.addEventListener('onAction', function (e) { $BRIDGE.action(JSON.stringify(e.detail || {})); });
                document.getElementById('view').appendChild(el);
                $BRIDGE.ready();
              }
              var s = document.createElement('script');
              s.src = '$script';
              s.onload = function () { customElements.whenDefined('$ELEMENT').then(boot, function () { $BRIDGE.failed(); }); };
              s.onerror = function () { $BRIDGE.failed(); };
              document.head.appendChild(s);
            })();
            </script></body></html>
            """.trimIndent()
    }

    /** Web's reading of `onAction`: its `Action`, and its `Output` or `Data` as JSON. `null` when it names no action. */
    fun action(detail: String): Pair<String, String>? {
        if (detail.toByteArray(Charsets.UTF_8).size > MAX_ACTION_BYTES) return null
        val o = runCatching { json.parseToJsonElement(detail).jsonObject }.getOrNull() ?: return null
        val a = runCatching { o["Action"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val data = o["Output"] ?: o["Data"] ?: JsonObject(emptyMap())
        return a to data.toString()
    }

    private fun parse(text: String): JsonElement = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}
