# Nuadyr-mpv

An Android video and audio player. It is a fork of [mpv-android](https://github.com/mpv-android/mpv-android) with a new home screen for Android TV and a restyled UI. Playback is still libmpv.

## What's different

- **Android TV home screen.** A grid of thumbnails for every video and audio file on the device (internal storage, SD cards, USB drives). No buttons.
- **Aengus theme.** One palette, shape and focus style across the app, in light and dark. TVs always use the dark palette.
- **Everything else is mpv-android.** Phones and tablets keep the original button home screen. The player, gestures and settings screens are upstream's.

## Using it on a TV

| Key | Does |
| --- | --- |
| **OK** | Plays the selected file |
| **Menu**, or hold **OK** | Opens the menu |

The menu has:

- **Refresh list** (or **Allow storage access** if permission is missing)
- **Sort by**: newest, oldest, name A-Z, name Z-A, longest, shortest
- **Show**: videos and audio, videos only, audio only
- **Group by**: none, type, folder
- **Open URL**
- **Browse folders**
- **Settings**

Sort, show and group choices are remembered. Ringtones, notification sounds and alarms are left out of the grid. Frame rate matching is on by default on TVs only.

## Using it on a phone or tablet

The home screen has buttons for opening a folder, picking a file, opening a URL and Settings, plus a "remember my choice" switch.

The app also opens media from other apps:

- Share and "open with" for audio and video
- `http`, `https`, `ftp`, `rtmp`, `rtsp`, `udp` and similar links
- `.m3u8`, `.mpd` and `.strm` links

## Player

- Hardware and software decoding, switchable while playing
- Swipe gestures. Defaults: horizontal seeks, left side brightness, right side volume. Taps are configurable.
- Playlists, chapters, subtitle and audio tracks, playback speed, aspect ratio
- Picture-in-Picture and background playback with a media notification
- Keyboard input mapped to mpv keys
- Settings: General, UI, Video, Gestures, Advanced (includes editing `mpv.conf` and `input.conf`), Developer, About

## Requirements

| | |
| --- | --- |
| Android | 6.0+ (minSdk 23), target SDK 36 |
| Architectures | armeabi-v7a, arm64-v8a, x86, x86_64 (CI builds the first two) |
| Kotlin / AGP / Gradle | 2.2.21 / 9.2.1 / 9.4.1 |
| JDK | 17 |
| NDK | r30 |

## Build flavours

| Flavour | Storage access | minSdk |
| --- | --- | --- |
| `default` | Media permissions (`READ_MEDIA_*`, or `READ_EXTERNAL_STORAGE` on older Android) | 23 |
| `allstorage` | "All files access" (`MANAGE_EXTERNAL_STORAGE`) | 30 |

## Building

### With GitHub Actions

Push a commit. The `build` workflow compiles the native libraries, then the app, for armeabi-v7a and arm64-v8a. It uploads the **debug** APKs as workflow artifacts. On pushes (not pull requests) it also publishes a GitHub release called `build-<run number>` with the APKs attached.

Native dependencies are cached, so only the first run is slow.

### On your machine

Linux or macOS only. Windows and WSL do not work.

```sh
cd buildscripts
./download.sh                    # Android SDK, NDK and dependency sources
./buildall.sh --arch arm64 mpv   # native libraries (default is 32-bit ARM only)
./buildall.sh -n                 # the app itself
```

Run `--arch` once for each architecture you want. APKs land in `app/build/outputs/apk/`. See `buildscripts/README.md` for rebuilding a single component and using Android Studio.

### Things to know

- CI fetches mpv from its `master` branch, and dav1d and libass unpinned. FFmpeg is pinned (`n9.0.2`). A change upstream can break or alter a build.
- FFmpeg is built with `--enable-gpl --enable-version3`. Keep that in mind before redistributing APKs.
- Debug builds only: long-press **Settings** on the phone home screen for the Intent test and Codec info screens.

## Layout

```
app/src/main/java/is/xyz/mpv/   Kotlin app
    MainActivity                  picks the TV or phone home screen
    TvLibraryFragment             the TV thumbnail grid
    MainScreenFragment            the phone/tablet home screen
    MPVActivity, MPVView, MPVLib  player and libmpv bridge
    preferences/                  settings screens
app/src/main/java/is/xyz/filepicker/   folder and file picker
app/src/main/jni/               C++ glue between Java and libmpv
app/src/main/res/               layouts, strings, Aengus colours and styles
app/src/debug/                  debug-only test screens
buildscripts/                   builds FFmpeg, libass, mpv and friends for Android
docs/, fastlane/                release process, privacy page, store text
.github/                        CI workflow and issue templates
```

## Licence

MIT, as upstream. See `LICENSE`. The real work is by the mpv and mpv-android developers.
