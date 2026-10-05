package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import com.music.bitchord.compat.PluralRes
import com.music.bitchord.compat.formatAndroid

/*
 * Android's resource lookups for ported Compose code. On iOS `R.string.x` is
 * the (English) text itself — see the generated com.music.bitchord.R — so
 * these just format it.
 */

@Composable
fun stringResource(id: String): String = id

@Composable
fun stringResource(id: String, vararg formatArgs: Any): String = formatAndroid(id, formatArgs)

@Composable
fun stringArrayResource(id: List<String>): Array<String> = id.toTypedArray()

@Composable
fun pluralStringResource(id: PluralRes, count: Int): String = id.forCount(count)

@Composable
fun pluralStringResource(id: PluralRes, count: Int, vararg formatArgs: Any): String =
    formatAndroid(id.forCount(count), formatArgs)
