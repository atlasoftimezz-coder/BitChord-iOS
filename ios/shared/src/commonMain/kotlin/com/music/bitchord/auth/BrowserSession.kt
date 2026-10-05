package com.music.bitchord.auth

/**
 * The in-app browser's Google cookies (WKWebsiteDataStore on iOS). Same contract
 * as the Android BrowserSession: clear without ever visiting Google's logout
 * endpoint (that would invalidate other saved accounts), or install a saved
 * account's cookie so its channel picker opens signed in.
 */
expect object BrowserSession {
    fun clearGoogleCookies(onDone: () -> Unit = {})
    fun installGoogleCookies(cookieHeader: String, onDone: () -> Unit = {})
    /** The cookie header the browser would send to [origin]. */
    fun cookieHeaderFor(origin: String, onResult: (String?) -> Unit)
}
