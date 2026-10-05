package android.app

import android.content.Context

/** android.app.Application for ported code: the single app Context. */
open class Application : Context()

/** android.app.Activity placeholder for ported signatures; iOS has one window and no activities. */
open class Activity : Context()
