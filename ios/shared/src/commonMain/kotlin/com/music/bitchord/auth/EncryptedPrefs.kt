package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import android.content.StoredPreferences

/**
 * iOS counterpart of the Android EncryptedPrefs: the same named store, kept in
 * the Keychain. [plainName] (Android's fallback when the Keystore is broken)
 * has no counterpart — the Keychain is always there.
 */
internal object EncryptedPrefs {
    fun open(context: Context, name: String, plainName: String): SharedPreferences =
        StoredPreferences.open(name, secure = true)
}
