package com.music.bitchord.auth

import com.music.bitchord.data.DebugLog as Log

/** What the in-app browser is being opened for. */
enum class WebSessionMode {
    /** No usable session yet: sign in to Google. */
    SIGN_IN,

    /**
     * Already signed in, but on the wrong channel. Opens YouTube Music itself
     * so its own Accounts switcher can be used, and takes the session from
     * whatever page the listener ends up on.
     */
    SWITCH_CHANNEL,
}

/**
 * Value accepted by Innertube as `context.user.onBehalfOfUser`.
 * YouTube commonly exposes `DATASYNC_ID` as `account||delegated`; the second
 * half is the active identity, while plain accounts can leave it empty.
 */
internal fun normalizeDataSyncId(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    if (!value.contains("||")) return value
    return value.substringAfter("||").takeIf { it.isNotBlank() }
        ?: value.substringBefore("||").takeIf { it.isNotBlank() }
}

/**
 * A session lifted out of the in-app browser: the cookie, plus who the page
 * being looked at says it is.
 *
 * The identity fields come from the live page's `ytcfg` rather than from a
 * later server-side fetch of the shell, and that is the entire point. Which
 * channel YouTube Music serves by default is not a question this app gets to
 * answer, but which channel the page in front of the listener is *currently*
 * showing is not a question at all — it is written down in the page. Reading
 * it there is what lets "switch to the channel I want, then save" work.
 *
 * Individual identity fields are nullable because a personal viewer need not
 * have a channel. The capture itself is accepted only from a page whose config
 * says it is signed in, and the API verifies it before it is persisted.
 */
data class CapturedSession(
    val cookie: String,
    /** `DELEGATED_SESSION_ID` — set only while a brand channel is selected. */
    val pageId: String?,
    /** Active identity used as `context.user.onBehalfOfUser`. */
    val dataSyncId: String?,
    /** `SESSION_INDEX` — which Google account in the cookie jar. */
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
    /** Whether the page reported itself signed in at all. */
    val loggedIn: Boolean,
)

// BrowserSession (the in-app browser's cookie jar) is platform code: see BrowserSession.ios.kt.
