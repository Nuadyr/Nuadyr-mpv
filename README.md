# Nuadyr

A fork of [mpv-android](https://github.com/mpv-android/mpv-android) — the Android video player built on [libmpv](https://mpv.io) — with a redesigned interface.

> **Status:** early / experimental. The phone interface is being overhauled in stages; the playback engine is unchanged from upstream.

<!-- Add screenshots here once you have them, e.g.
![Home](docs/screenshots/home.png) ![Player](docs/screenshots/player.png)
-->

## What's different from upstream

Nuadyr keeps mpv-android's playback, gestures and settings, and changes how it looks and feels.

- **Aengus palette.** One colour system (Black Steel and Periwinkle, with calm Bone text in dark mode) across every screen, in light and dark.
- **Home screen.** Large rounded tiles — filled for the main actions, tonal for the secondary ones.
- **Player overlay.** A floating dark-glass control panel, a larger Periwinkle play button, a pill for the top controls and a round unlock chip.
- **Dialogs and menus.** Bigger touch targets, rounded surfaces, and readable text in both light and dark mode (player dialogs now follow the system theme).
- **File picker, About and editors** restyled to match.
- **Android TV** keeps its own layout and D-pad navigation.

Everything else — hardware decoding, subtitles and audio tracks, playlists, PiP, background playback, gestures, `mpv.conf` / `input.conf` editing — works as in mpv-android.

## Requirements

- Android 6.0 (API 23) or newer
- Android TV supported

## Building

The native libraries (mpv, FFmpeg, libass, etc.) are built with the scripts in [`buildscripts/`](buildscripts/README.md). Follow that guide first, then build the app with Gradle:

```bash
./gradlew assembleDebug
```

The repository also contains a GitHub Actions workflow (`.github/workflows/build.yml`) that builds the project on push.

## Project layout

| Path | What's in it |
| --- | --- |
| `app/src/main/java/is/xyz/mpv/` | Activities, player UI logic, dialogs |
| `app/src/main/java/is/xyz/filepicker/` | Built-in file picker |
| `app/src/main/jni/` | JNI bridge to libmpv |
| `app/src/main/res/values/` | Colours, themes and shared widget styles (`styles.xml`) |
| `app/src/main/res/layout/` | Phone and tablet layouts |
| `app/src/main/res/layout-television/` | Android TV layouts |
| `buildscripts/` | Native dependency build scripts |

### Design notes

- Colour tokens are named `aengus_*` and defined for light (`values/`), dark (`values-night/`) and TV (`values-television/`). Layouts and styles use theme attributes (for example `?attr/colorSurface`) so the same screens work in light, dark and Material You.
- Shared styles live in `values/styles.xml` under `Widget.Aengus.*`, `TextAppearance.Aengus.*` and `ThemeOverlay.Aengus.*`.
- The player overlay always sits on video, so its colours (`player_*`, `tint_*`) don't change with day/night.

## Contributing

Issues and pull requests are welcome. When changing UI, please:

1. Check both light and dark mode.
2. Keep touch targets at least 48dp.
3. Keep view ids and types stable — the player code relies on them.
4. Test on a small phone (around 360dp wide) and in landscape.

## Credits and licence

Nuadyr is built on [mpv-android](https://github.com/mpv-android/mpv-android) and [mpv](https://mpv.io), and bundles FFmpeg, libass and other open-source libraries. Thanks to all of their authors and contributors.
