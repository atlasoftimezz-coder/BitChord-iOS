package com.music.bitchord.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.music.bitchord.data.DebugLog as Log
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

private const val MUSIC_ORIGIN = "https://music.youtube.com"

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/**
 * Google refuses to sign in inside an embedded browser whose user agent looks
 * like one ("This browser or app may not be secure"). A WKWebView's default
 * agent lacks the Safari token; presenting as Mobile Safari is what makes the
 * real login page usable, as it is in every iOS app that embeds it.
 */
private const val SAFARI_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.7 Mobile/15E148 Safari/604.1"

private class PageDelegate(val onFinished: (String?) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished(webView.URL?.absoluteString)
    }
}

@Composable
actual fun YtMusicLoginScreen(
    mode: WebSessionMode,
    initialCookie: String?,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier,
    captureRequest: Int,
    onCaptureUnavailable: () -> Unit,
    onPageReady: (Boolean) -> Unit,
) {
    var webView by remember { mutableStateOf<WKWebView?>(null) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val delegate = remember { PageDelegate { url -> currentOnPageReady(url?.startsWith(MUSIC_ORIGIN) == true) } }

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        val view = webView
        if (view == null) currentOnUnavailable() else captureFrom(view, currentOnCaptured, currentOnUnavailable)
    }

    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.defaultDataStore()
            }
            val view = WKWebView(frame = CGRectZero, configuration = config)
            view.customUserAgent = SAFARI_AGENT
            view.navigationDelegate = delegate
            webView = view
            val start = if (mode == WebSessionMode.SIGN_IN) LOGIN_URL else "$MUSIC_ORIGIN/"
            val load = { view.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(start)!!)) }
            // Never visit Google's logout endpoint: clearing the local jar is
            // enough for a fresh login and keeps other saved accounts valid.
            if (mode == WebSessionMode.SIGN_IN) BrowserSession.clearGoogleCookies { load() }
            else if (initialCookie != null) BrowserSession.installGoogleCookies(initialCookie) { load() }
            else load()
            view
        },
    )
}

/** Takes the session from [view] if the page holds a signed-in one. */
private fun captureFrom(
    view: WKWebView,
    onCaptured: (CapturedSession) -> Unit,
    onUnavailable: () -> Unit,
) {
    BrowserSession.cookieHeaderFor(MUSIC_ORIGIN) { cookies ->
        if (cookies == null || !AuthStore.hasApiSid(cookies)) {
            onUnavailable()
            return@cookieHeaderFor
        }
        view.evaluateJavaScript(YTCFG_PROBE) { result, _ ->
            val config = (result as? String).parseConfig()
            val loggedIn = (config?.get("loggedIn") as? JsonPrimitive)?.content == "true"
            if (config == null || !loggedIn) {
                Log.w("BitChord", "confirmation requested before the page exposed a signed-in identity")
                onUnavailable()
                return@evaluateJavaScript
            }
            val pageId = config.string("pageId")
            onCaptured(
                CapturedSession(
                    cookie = cookies,
                    dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
                    pageId = pageId,
                    authUser = config.string("authUser"),
                    visitorData = config.string("visitorData"),
                    clientVersion = config.string("clientVersion"),
                    loggedIn = true,
                ),
            )
        }
    }
}

/** Same probe as Android, stringified so WebKit hands back a String rather than an NSDictionary. */
private const val YTCFG_PROBE = """
JSON.stringify((function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})())
"""

private val json = Json { ignoreUnknownKeys = true }

private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
