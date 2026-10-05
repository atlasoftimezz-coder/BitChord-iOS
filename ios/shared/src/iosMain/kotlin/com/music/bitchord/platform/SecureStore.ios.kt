package com.music.bitchord.platform

import kotlinx.cinterop.BetaInteropApi
import platform.CoreFoundation.CFTypeRefVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Keychain-backed secrets — the iOS counterpart of the Android app's
 * EncryptedSharedPreferences. AfterFirstUnlockThisDeviceOnly: readable in the
 * background (the player needs the cookie with the screen locked), never
 * synced to iCloud or restored to another device.
 */
@OptIn(BetaInteropApi::class)
actual object SecureStore {
    private const val SERVICE = "com.music.bitchord"

    /** A query dictionary; the dictionary retains what is added, so bridged values are released after. */
    private inline fun <T> withQuery(key: String, fill: (CFMutableDictionaryRef?) -> Unit, use: (CFMutableDictionaryRef?) -> T): T {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(key)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, service)
            CFDictionaryAddValue(dict, kSecAttrAccount, account)
            fill(dict)
            return use(dict)
        } finally {
            CFRelease(service)
            CFRelease(account)
            CFRelease(dict)
        }
    }

    actual fun read(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(
            key,
            fill = { dict ->
                CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
                CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
            },
            use = { dict -> SecItemCopyMatching(dict, result.ptr) },
        )
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    actual fun write(key: String, value: String?) {
        withQuery(key, fill = {}, use = { dict -> SecItemDelete(dict) })
        if (value == null) return
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val bridged: CFTypeRef? = CFBridgingRetain(data)
        try {
            withQuery(
                key,
                fill = { dict ->
                    CFDictionaryAddValue(dict, kSecValueData, bridged)
                    CFDictionaryAddValue(dict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                },
                use = { dict -> SecItemAdd(dict, null) },
            )
        } finally {
            CFRelease(bridged)
        }
    }
}
