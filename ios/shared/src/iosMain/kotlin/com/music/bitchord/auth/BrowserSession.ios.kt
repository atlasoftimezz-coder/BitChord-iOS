package com.music.bitchord.auth

import com.music.bitchord.data.DebugLog as Log
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual object BrowserSession {
    private val store get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore

    private val GOOGLE_DOMAINS = listOf("youtube.com", "google.com")
    private val YOUTUBE_DOMAINS = listOf(".youtube.com")

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

    actual fun clearGoogleCookies(onDone: () -> Unit) = onMain {
        store.getAllCookies { cookies ->
            val doomed = cookies.orEmpty().mapNotNull { it as? NSHTTPCookie }
                .filter { cookie -> GOOGLE_DOMAINS.any { cookie.domain.trimStart('.').endsWith(it) } }
            if (doomed.isEmpty()) {
                onDone()
                return@getAllCookies
            }
            var remaining = doomed.size
            doomed.forEach { cookie ->
                store.deleteCookie(cookie) {
                    remaining--
                    if (remaining == 0) {
                        Log.d("BitChord", "cleared ${doomed.size} browser cookies for Google")
                        onDone()
                    }
                }
            }
        }
    }

    actual fun installGoogleCookies(cookieHeader: String, onDone: () -> Unit) {
        clearGoogleCookies {
            val entries = cookieHeader.split(';').mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            val cookies = YOUTUBE_DOMAINS.flatMap { domain ->
                entries.mapNotNull { (name, value) ->
                    NSHTTPCookie.cookieWithProperties(
                        mapOf<Any?, Any?>(
                            NSHTTPCookieName to name,
                            NSHTTPCookieValue to value,
                            NSHTTPCookieDomain to domain,
                            NSHTTPCookiePath to "/",
                            NSHTTPCookieSecure to "TRUE",
                        ),
                    )
                }
            }
            if (cookies.isEmpty()) {
                onDone()
                return@clearGoogleCookies
            }
            var remaining = cookies.size
            onMain {
                cookies.forEach { cookie ->
                    store.setCookie(cookie) {
                        remaining--
                        if (remaining == 0) {
                            Log.d("BitChord", "restored ${entries.size} browser cookies for the selected account")
                            onDone()
                        }
                    }
                }
            }
        }
    }

    actual fun cookieHeaderFor(origin: String, onResult: (String?) -> Unit) = onMain {
        val host = origin.substringAfter("://").substringBefore('/')
        store.getAllCookies { cookies ->
            val matching = cookies.orEmpty().mapNotNull { it as? NSHTTPCookie }.filter { cookie ->
                val domain = cookie.domain
                if (domain.startsWith('.')) host.endsWith(domain.drop(1)) || host == domain.drop(1)
                else host == domain
            }
            onResult(matching.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" })
        }
    }
}
