package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Android's LocalContext for ported Compose code; always the single app Context. */
val LocalContext = staticCompositionLocalOf { Context.app }
