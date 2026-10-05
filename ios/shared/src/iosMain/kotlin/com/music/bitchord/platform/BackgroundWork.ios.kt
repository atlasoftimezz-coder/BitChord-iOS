package com.music.bitchord.platform

import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

actual fun beginBackgroundWork(name: String): Long {
    var token = UIBackgroundTaskInvalid
    token = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
        // Out of time: iOS kills an app that does not end an expired task.
        if (token != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(token)
    }
    return token.toLong()
}

actual fun endBackgroundWork(token: Long) {
    val id = token.toULong()
    if (id != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(id)
}
