package androidx.core.net

import android.net.Uri

/** androidx.core.net.toUri for ported code. */
fun String.toUri(): Uri = Uri.parse(this)
