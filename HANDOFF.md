# BitChord iOS port: handoff

Read this first in a new session. It covers what exists, how it is built and installed, what each phase delivered, and what is left.

## Goal

A full-feature iPhone version of **BitChord** (https://github.com/kushagrasinghx/BitChord), a native Android YouTube Music client written in Kotlin and Jetpack Compose (about 119k lines, GPL-3.0). It is for personal use, and everything must stay free:

- No Mac: builds run on GitHub Actions.
- No paid Apple developer account: the app is installed with Sideloadly using a free Apple ID.

| | |
|---|---|
| Local repo | `D:\Projects\Bitchord_ios` (Windows PC) |
| GitHub | https://github.com/atlasoftimezz-coder/BitChord-iOS (public). `origin` = this repo, `upstream` = the original Android repo |
| Phone | iPhone 15, iOS 18.7.8 |
| Account | Free Apple ID. An install expires 7 days after sideloading; reinstall to renew |

## Repo layout

```
app/                 original Android app (untouched; the source we port from)
ios/                 standalone Gradle build for iOS (Kotlin 2.4.20, Compose Multiplatform 1.12.1)
  shared/            KMP module -> static framework "Shared"
    src/commonMain/kotlin/
      com/music/bitchord/...   ported Android code (same packages) + iOS-native code
      android/ androidx/ java/ okhttp3/ org/json/   compat shims (see "Port strategy")
    src/iosMain/kotlin/        iOS actuals (NSURLSession, Keychain, NSUserDefaults, WKWebView, files, crypto...)
  iosApp/            Swift app shell; project.yml is turned into an Xcode project by XcodeGen on CI
    iosApp/AudioEngine.swift          AVQueuePlayer engine, lock screen, remote commands, interruptions
    iosApp/ChunkedResourceLoader.swift  googlevideo bounded-range fetcher (AVAssetResourceLoader)
  tools/             port_files.py, fix_ported.py, check_imports.py, gen_strings.py
  parked/            Android UI sources already copied for the full UI port, kept OUT of the build
.github/workflows/ios.yml   CI: builds an unsigned, ad-hoc-signed BitChord.ipa as an artifact
```

## Build, download, install

1. Push to `main` (only changes under `ios/**` or the workflow trigger a build), or run the workflow manually. A build takes about 10–20 minutes.
2. Watch the run and download the result. **Always pass `-R`**, because `gh` may otherwise pick the `upstream` remote.
   ```
   gh run list -R atlasoftimezz-coder/BitChord-iOS --limit 1
   gh run download <id> -R atlasoftimezz-coder/BitChord-iOS -n BitChord-ipa -D dist
   ```
   The `.ipa` lands in `D:\Projects\Bitchord_ios\dist\BitChord.ipa`.
3. Install on the phone:
   - Open the **Apple Devices** app first. It starts Apple's device service; without it Sideloadly shows "no devices detected".
   - In **Sideloadly**, open Advanced Options and set **Signing Mode** to **"Apple ID Sideload"**, with the Apple ID filled in.
   - If Signing Mode is wrong, installs fail with 0xe800801c or 0xe8008014.
4. The user has **declined** installing a local JDK or Kotlin toolchain. All compile checks go through CI.
   - The workflow's "Compile Kotlin" step lists every Kotlin error at once, before Xcode runs.
   - Batch changes so CI round-trips stay few.

## Phase status

| Phase | Scope | State |
|---|---|---|
| P0 | CI pipeline, placeholder app, sideload | Done, confirmed on phone |
| P1 | Search (suggestions, filters, paging) and playback via InnerTubeX | Done, confirmed |
| P2 | Background audio, lock screen and Control Center, gapless queue (next track pre-resolved), interruptions, headphone unplug | Done, confirmed |
| P2 fixes | Crash on first log line (NSLog varargs), crash catcher with "Copy report", "Copy debug log" button, steadier timeline, InnerTubeX warm-up and persisted cipher caches (slow first play) | Built; partly confirmed |
| P3 | Lyrics: all providers, word-synced panel, translation and romanization, provider picker | Built 2026-10-05 13:20, **not yet confirmed by user** |
| P4 | Google sign-in (WKWebView, Safari user agent), Keychain session, Home feed, Library (liked songs, library songs, playlists/albums/artists), album/playlist/artist pages with Play and Shuffle, Like button, tabs | Built 2026-10-05 15:01, **not yet tested by user** |
| P5 | Downloads (queue, 3 workers, batch bar, album/playlist Download), offline playback with stream fallback, lyrics `.lrc` sidecar + EmbeddedLyrics, Files-app import ("On this device") | Built 2026-10-05 15:54, **not yet tested by user** |
| P6 | Crossfade and Automix: second AVPlayer deck, ported CrossfadeController (filter sweep, bass swap, vocal separation), C++ analyzer and the Beat This! / open-unmix ONNX models on iOS | Built 2026-10-05, **compile status: see "Next step"; not tested by user** |
| P7a | Settings screen (Library → gear), playback speed 0.5–2×, sleep timer (minutes / end of song) | In progress |
| P7b | History and search history, replay/stats | Todo |
| P7c | Scrobbling (Last.fm, ListenBrainz), Discord Rich Presence | Todo |
| P7d | Animated album canvas | Todo |
| P7e | Pluggable sources, PoToken, multiple accounts / brand channels, quality options | Todo |
| P7f | Listen Together | Todo |
| P7g | Port of the real BitChord UI (MainActivity, NowPlayingScreen, screens in `ios/parked/`) | Todo |

### Open user feedback

- **Quality is always "AAC ~130 kbps".** That is YouTube's best AAC (itag 140), and AVPlayer cannot decode WebM/Opus. Options:
  - Opus ~160 kbps would need a custom WebM/Opus decoder (AudioToolbox supports Opus; a WebM demuxer is needed). Planned in P6/P7.
  - AAC 256 (itag 141) is Premium-only; it may come for free now that sign-in exists (InnerTubeX receives the cookie).
  - Hi-res FLAC comes from BitChord's module sources (P7); AVPlayer can play FLAC.
- **"Timeline was not good"** was fixed (seek target held until the seek lands, lock screen re-anchored). Not re-confirmed.
- **"Loading long time"** led to the warm-up and caches. Not re-confirmed. If it persists, ask for "Copy debug log": its `TIMING` lines show where the time went.

## Remaining features to port

Full parity with the Android README. Each item's Android source is under `app/src/main/java/com/music/bitchord/...`.

**P5: done (how it works on iOS)**
- `download/`: `DownloadSession`, `LyricsTag`, `data/lyrics/LrcWriter` and `EmbeddedLyrics` are ported. `Downloads` is a port with iOS changes. `DownloadStore` and `Downloader` are iOS-native.
- Files: `Application Support/Downloads/<videoId>.m4a`, plus `.jpg` (cover) and `.m4a.lrc` (lyrics sidecar). Records hold **relative** paths, because the container path changes on reinstall. The folders are excluded from iCloud backup (iOSApp.swift).
- AAC/MP4 from YouTube only. Not ported: no retagging (the sidecar replaces it), and no Mp4/Flac/Webm taggers, OfflineDash/Hls or source routes. These come with pluggable sources in P7, along with the Wi-Fi-only setting.
- Downloads run in-process. A UIKit background-task grant gives about 30 s after the app leaves the foreground; while audio is playing, the app keeps running anyway.
- `PlayerController.localStream` plays the downloaded or imported file. If the file fails, it streams the track once instead. Swift `AudioEngine` plays `file://` directly; artwork can be a plain path.
- Local files: `data/LocalMediaRepository.kt` (Kotlin) and `iosApp/LocalFilePicker.swift` (UIDocumentPicker + AVFoundation tags). Ids are `local:<uuid>`. MPMediaLibrary was not used.
- Not done in P5: the Android download sheet/Downloads page UI (P7).

**P6: Automix and AI**
- `playback/CrossfadeController.kt` (crossfade 0–12 s), Automix/beat-matching, `TransitionFilterProcessor`, QueueCoordinator.
- ONNX models in `app/src/main/assets`: `beat_this_int8.onnx` and `vocals_umxhq_int8.onnx`. Run them with onnxruntime-objc/C on iOS. The JNI C++ in `app/src/main/cpp` (mel, analysis, vocal) can be compiled for iOS.
- iOS playback must move from AVQueuePlayer to **AVAudioEngine** to get crossfade, EQ, speed, skip-silence and audio processing.

**P7: the rest**
- Real BitChord UI: MainActivity (4566 lines), NowPlayingScreen (3096), MainViewModel (2861) and the screens.
  - Sources are already copied into `ios/parked/` and need the compat layer. Missing pieces include `android.graphics.Bitmap` and androidx Palette (used for artwork colours), `SpotifyToken` (WebView), and `CanvasCache` (media3 cache).
  - Re-run `check_imports.py` after moving files back into the build.
- Animated album canvas (`data/canvas/*`: Spotify, Apple, Tidal, community; `ui/player/CanvasArtworkPlayer`), played as video with AVPlayer.
- Discord Rich Presence (`data/discord/*`, `auth/DiscordLoginScreen.kt`).
- Scrobbling to Last.fm and ListenBrainz (`data/scrobbling/*`; Last.fm keys come from the user).
- Pluggable sources (`data/sources/*`: AddonSource, ModuleSource, JioSaavn, SourceRegistry/Resolver). A placeholder exists in `data/sources/SourceStubs.kt`.
- Listen Together (`data/listentogether/*`, `playback/PartySync.kt`, Go backend in `backend/`).
- Per-network quality, speed 0.5×–2×, skip silence, sleep timer, in-app equalizer (iOS has no system EQ), stats for nerds.
- Replay/stats (`data/stats/*`, `ui/replay/*`; already copied to `parked/`).
- Settings screen (`ui/screens/SettingsSheet.kt`, `data/settings/AppSettings.kt`; the iOS AppSettings currently holds only the lyrics subset).
- SMB and WebDAV remote libraries (`data/smb`, `data/webdav`). SMBJ is JVM-only, so this needs an iOS SMB client or must be skipped.
- History and search history (`HistoryScreen`, `SearchHistory`).
- Multiple Google accounts and brand-channel switching (`AccountProfileSelector`, `AccountChannelDialog`). The data model is ported.
- PoToken via WKWebView (`data/innertube/potoken/*`, used for age-restricted tracks via WEB_REMIX). InnerTubeX accepts a `TokenProvider`.

**Not possible on iOS with a free account:** CarPlay (needs an Apple entitlement), home screen widgets (need App Groups), system equalizer, APK self-update.

## Port strategy (from P3 on)

- **Copy, don't rewrite.** `python ios/tools/port_files.py <path-or-dir>` copies Android sources into `ios/shared/src/commonMain/kotlin/com/music/bitchord/` under the same packages.
- **Fix.** `python ios/tools/fix_ported.py` applies the mechanical rewrites:
  - `lowercase(Locale.ROOT)` becomes `lowercase()`;
  - `System.currentTimeMillis` and charsets are replaced;
  - imports are added automatically for `Dispatchers.IO`, `@Volatile`, `synchronized`, `.format`, `putIfAbsent`.
- **Check.** `python ios/tools/check_imports.py` lists imports that point at code not yet ported or shimmed. Run it before every push.
- **Shims** keep Android imports valid on iOS:

  | Shim | Backed by |
  |---|---|
  | `okhttp3.*` | NSURLSession |
  | `android.content.Context` / `SharedPreferences` | NSUserDefaults, or the Keychain for secure stores |
  | `android.widget.Toast` | ToastHost |
  | `android.os.Build` / `SystemClock`, `android.util.Log` / `LruCache` | — |
  | `android.net.Uri` | ktor Url |
  | `androidx.compose.ui.res.stringResource` | generated `R.kt` |
  | `java.io.File` | NSFileManager |
  | `java.time`, `java.util.Locale`, `java.util.Base64` | — |
  | `java.util.concurrent` (ConcurrentHashMap, atomics) | — |
  | `java.security.MessageDigest` | CommonCrypto |
  | `org.json` | kotlinx.serialization |
  | `androidx.core.graphics.ColorUtils`, `media3.Player` constants, `BackHandler` | — |

  Prefer extending a shim over editing a ported file. Files written for iOS only carry `// ios-native`.
- **Strings:** `R.string.x` is the English text itself. Regenerate it with `python ios/tools/gen_strings.py` after upstream changes.
- **Playback architecture:** Kotlin `PlayerController` (queue, resolve, retries) drives the Swift `AudioEngine` through the Kotlin `AudioEngine` interface. Streams are AAC/MP4 from InnerTubeX (`StreamResolver.kt`). InnerTubeX v0.7.4 iOS klibs come from JitPack; they require Kotlin ≥ 2.4.10.

## How P6 works on iOS

- **Engine:** `AudioEngine.swift` has two `AVQueuePlayer` decks. `player` is current: it drives progress, Now Playing and the remote commands. `other` holds either an armed standby (the incoming track, silent, seeked to the cue point) or, after `handoffToStandby()`, the tail of the outgoing track. The Kotlin `AudioEngine` interface has a "second deck" section listing the calls.
- **Filters:** `TransitionFilter.swift` is a port of Android's `TransitionFilterProcessor` (24 dB/oct LP + HP, glided cutoffs). It runs as an `MTAudioProcessingTap` on the item's audio mix and is attached lazily, the first time a transition asks for a non-open filter. Playback without transitions never has a tap.
- **Controller:** `playback/CrossfadeController.kt` is Android's logic with the same constants. Phases are IDLE/ARMING/FADING/BAILING; there is no sleep fade or party mode.
  - The standby is loaded from `PlayerController.queuedStream`, the gapless pre-resolve of the next track.
  - At the handoff, `PlayerController.handedOff()` advances the queue.
- **Analysis:**
  - Ported unchanged: `playback/smart/` TransitionPlanner, TransitionPolicy, TrackAnalysis, AnalysisStore, TrackFeatures.parse and the BeatTracker post-processing.
  - `TrackAnalyzer.kt` is iOS-native. Its audio source is the downloaded or imported file, or for a streamed track a one-off fetch into `Caches/analysis/`, deleted afterwards. Results persist in `Application Support/smart_analysis`.
- **Native:** `native/analyzer/*.cpp` is compiled into the app (project.yml `sources`). `iosApp/Analysis/BitChordAnalysis.{h,cpp}` is the C bridge and replaces the JNI files; it is exposed to Swift through `iosApp-Bridging-Header.h`.
  - `iosApp/Analysis/AnalysisBridge.swift` implements Kotlin's `NativeAnalysis`: decode with AVAudioFile, resample, then mel + Beat This! and STFT + open-unmix on ONNX Runtime.
  - ONNX Runtime comes from SPM `onnxruntime-swift-package-manager` 1.20.0, Swift `import OnnxRuntimeBindings`.
  - Both `.onnx` files are bundled from `app/src/main/assets`.
- **UI:** Now Playing has Automix and Crossfade chips and an analysis status line. Settings (P7a) has the full controls.

## P7 plan

P7 is split into P7a–P7g (table above), one CI build and one phone test each. P7g is last because the ported UI calls into everything else.

**P7a (in progress):** `ui/SettingsScreen.kt`, `playback/SleepTimer.kt` (ported) and `AppSettings.playbackSpeed`.
- Speed: `AudioEngine.setPlaybackSpeed`. Every item uses `.spectral` pitch keeping, and the current deck plays at speed × beatmatch rate.
- Sleep timer: the deadline is handled in `PlayerController`. "End of song" drops the gapless next and suppresses crossfades.
- Not done: skip silence and the in-app equalizer. Both need sample access on the main deck: grow `TransitionFilter`'s tap into a small DSP chain (EQ biquads, silence detection). Planned as the rest of P7a.

## Gotchas (all hit and solved)

- XcodeGen's `info:` key overwrites Info.plist. Use the `INFOPLIST_FILE` build setting instead.
- An unsigned bundle fails to install with 0xe800801c. CI now ad-hoc signs it, and Sideloadly re-signs.
- `NSLog("%@", kotlinString)` crashes, because Kotlin does not bridge Strings through C varargs. Pass the escaped text as the format string.
- NSURLRequest/NSURLSession category methods (`setHTTPMethod`, `setValue:forHTTPHeaderField:`, `dataTaskWithRequest:completionHandler:`) need explicit `platform.Foundation.*` imports. So do NSLocale `preferredLanguages` and NSTimeZone `localTimeZone`.
- A Kotlin class named `Context` is exported to Swift and clashes with SwiftUI's `Context`. Swift code uses `UIViewControllerRepresentableContext<…>`.
- A Kotlin class named `URL` is exported via Shared: Swift files that `import Shared` must write `Foundation.URL` in type positions.
- `fix_ported.py` rewrites *every* bare `.toByteArray()` to `.encodeToByteArray()`, including on streams, and it re-runs over all ported files each time. `ByteArrayOutputStream` therefore has an `encodeToByteArray()` member in the shim. `Charsets.*` has no shim: replace it by hand (see EmbeddedLyrics).
- The Bash tool's heredocs choke on some Python edit scripts (quotes, `\\.` key paths). Write the script to the scratchpad with Write and run `python <file>`.
- **On this PC, the Bash tool's heredocs and `python -c` strings eat backslashes.** Write any file containing `\` with the Write tool, or build the character with `chr(92)`.
- The PC's Windows drive C: is nearly full (about 7 GB free). Keep everything on D:.

## Next step

1. Check the latest CI run (`gh run list -R atlasoftimezz-coder/BitChord-iOS --limit 3`). P6 and P7a compile fixes come first if it is red. The Swift side of P6 (MTAudioProcessingTap callback types, ONNX Runtime Swift API, C++ linking) has not been through Xcode yet.
2. Get the user's test results for P3, P4, P5, P6 and P7a, and fix what is broken.
3. Finish P7a (equalizer, skip silence), then P7b. One sub-phase at a time. The user objected when work went beyond the phase in hand (the full UI port belongs to P7).
