package com.music.bitchord.ui

// ios-native: the settings the iOS port has so far, grouped like the Android
// SettingsSheet. The Android sheet itself (ui/screens/SettingsSheet.kt) comes
// over with the full UI port; this screen reads and writes the same
// AppSettings flows under the same keys, so nothing has to migrate then.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.platform.recentLog
import com.music.bitchord.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Gray = Color(0xFF9A9AA2)

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            item { Section("Playback") }
            item { SpeedSetting() }
            item { SleepTimerSetting() }
            item { Section("Transitions") }
            item { CrossfadeSetting() }
            item {
                val automix by AppSettings.smartFadeEnabled.collectAsState()
                SwitchRow(
                    "Automix",
                    "Beat-matched DJ transitions planned from each song's tempo, structure and vocals. " +
                        "Analyses songs in the background.",
                    automix,
                ) { AppSettings.smartFadeEnabled.value = it }
            }
            item { AutomixPerformanceSetting() }
            item { Section("Lyrics") }
            item {
                val synced by AppSettings.syncedLyrics.collectAsState()
                SwitchRow("Fetch lyrics", "Ask the lyric services below; also saves lyrics with downloads.", synced) {
                    AppSettings.syncedLyrics.value = it
                }
            }
            item {
                val syllable by AppSettings.prioritizeSyllableSync.collectAsState()
                SwitchRow("Prefer word-synced lyrics", "Keep looking after a line-synced result arrives.", syllable) {
                    AppSettings.prioritizeSyllableSync.value = it
                }
            }
            item {
                val blur by AppSettings.lyricsBlur.collectAsState()
                SwitchRow("Blur other lines", null, blur) { AppSettings.lyricsBlur.value = it }
            }
            item { LyricsOffsetSetting() }
            item { LyricsSourcesSetting() }
            item { Section("Display") }
            item {
                val reduce by AppSettings.reduceAnimation.collectAsState()
                SwitchRow("Reduce animation", null, reduce) { AppSettings.reduceAnimation.value = it }
            }
            item { Section("About") }
            item { AboutRows() }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = Gray, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Label(title: String, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Gray) }
    }
}

@Composable
private fun SpeedSetting() {
    val speed by AppSettings.playbackSpeed.collectAsState()
    Label("Speed", "${formatSpeed(speed)}×")
    ChipRow {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
            FilterChip(
                selected = speed == option,
                onClick = { AppSettings.playbackSpeed.value = option },
                label = { Text("${formatSpeed(option)}×") },
            )
        }
    }
}

private fun formatSpeed(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    return if (hundredths % 100 == 0) "${hundredths / 100}" else "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0').trimEnd('0')}"
}

@Composable
private fun SleepTimerSetting() {
    val minutes by SleepTimer.minutes.collectAsState()
    val afterTrack by SleepTimer.afterTrack.collectAsState()
    val deadline by SleepTimer.deadline.collectAsState()
    var remaining by remember { mutableStateOf(SleepTimer.remainingMs()) }
    LaunchedEffect(deadline) {
        while (deadline != null) {
            remaining = SleepTimer.remainingMs()
            delay(1_000)
        }
        remaining = null
    }
    val status = when {
        afterTrack -> "at the end of this song"
        remaining != null -> {
            val seconds = (remaining!! / 1000).toInt()
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} left"
        }
        else -> "off"
    }
    Label("Sleep timer", status)
    ChipRow {
        FilterChip(selected = !SleepTimer.isRunning, onClick = SleepTimer::cancel, label = { Text("Off") })
        SleepTimer.PRESETS.forEach { preset ->
            FilterChip(
                selected = minutes == preset,
                onClick = { SleepTimer.start(preset) },
                label = { Text("$preset min") },
            )
        }
        FilterChip(selected = afterTrack, onClick = SleepTimer::startAfterTrack, label = { Text("End of song") })
    }
}

@Composable
private fun CrossfadeSetting() {
    val seconds by AppSettings.crossfadeSeconds.collectAsState()
    Label("Crossfade", if (seconds == 0) "off" else "$seconds s")
    Slider(
        value = seconds.toFloat(),
        onValueChange = { AppSettings.crossfadeSeconds.value = it.roundToInt() },
        valueRange = 0f..12f,
        steps = 11,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        "With Automix on, this is only the fallback for songs not yet analysed.",
        color = Gray,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AutomixPerformanceSetting() {
    val mode by AppSettings.automixPerformanceMode.collectAsState()
    Label("Analysis speed")
    ChipRow {
        AutomixPerformanceMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { AppSettings.automixPerformanceMode.value = option },
                label = {
                    Text(
                        when (option) {
                            AutomixPerformanceMode.EFFICIENT -> "Battery saver"
                            AutomixPerformanceMode.BALANCED -> "Balanced"
                            AutomixPerformanceMode.PERFORMANCE -> "Fast"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun LyricsOffsetSetting() {
    val offset by AppSettings.lyricsOffsetMs.collectAsState()
    Label("Lyrics timing", if (offset == 0) "in sync" else "${if (offset > 0) "+" else ""}${offset / 1000.0} s")
    Slider(
        value = offset.toFloat(),
        onValueChange = { AppSettings.lyricsOffsetMs.value = (it / 100).roundToInt() * 100 },
        valueRange = -3000f..3000f,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun LyricsSourcesSetting() {
    val sources by AppSettings.lyricsSources.collectAsState()
    Label("Lyric services")
    Column(Modifier.padding(horizontal = 4.dp)) {
        LyricsSource.entries.forEach { source ->
            SwitchRow(source.label, null, source in sources) { on ->
                AppSettings.lyricsSources.value = if (on) sources + source else sources - source
            }
        }
    }
}

@Composable
private fun AboutRows() {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Label("Version", BuildConfig.VERSION_NAME)
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(recentLog()))
            copied = true
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(if (copied) "Debug log copied" else "Copy debug log") }
}
