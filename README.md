# Nuadyr-mpv: mpv video player for Android TV

Nuadyr-mpv is a video and audio player for Android TV, phones and tablets. It is a fork of [mpv-android](https://github.com/mpv-android/mpv-android) and plays with [libmpv](https://github.com/mpv-player/mpv).

## What's different

- **TV home screen.** A grid of thumbnails for all your videos and audio. No buttons.
- **Frame rate matching.** Asks the TV to switch to the video's frame rate, for example 23.976 Hz for films.
- **Resume and progress bars.** Remembers where you stopped and shows it on the tile.
- **Auto queue.** Pick a file and the ones after it play next.
- **Aengus theme.** One look in light and dark. TVs always use dark.

Everything else is mpv-android.

## Compared with mpv-android

| | mpv-android | Nuadyr-mpv |
| --- | --- | --- |
| Player engine | libmpv | libmpv |
| Decoding, gestures, subtitles, Open URL, PiP | Yes | Same |
| Android TV launcher | Yes | Yes |
| TV home screen | Buttons | Thumbnail grid |
| Sort, filter, group library | No | Yes |
| Progress bars | No | Yes |
| Save position on quit | Off by default | On by default on TV |
| Match display frame rate | No | Yes, on by default on TV |
| Theme | Default | Aengus |

Checked against mpv-android commit `ebf1b3f` (8 November 2026).

## Using it on a TV

| Key | Does |
| --- | --- |
| **OK** | Play |
| **Menu**, or hold **OK** | Open the menu |

The menu has Refresh list, Sort by, Show, Group by, Open URL, Browse folders and Settings.

The grid shows files that Android's media index (MediaStore) lists. If a file is missing, use **Browse folders**.

## Frame rate matching

**Settings > Video > Match display frame rate.** On by default on TVs. Needs Android 11 or newer. Android's own "Match content frame rate" setting can override it.

## Resume

**Settings > General > Save position on quit.** On by default on TVs. Files under 2 minutes are not tracked. A file counts as finished past 95%.

## Requirements

Android 6.0 or newer. Kotlin 2.2.21, AGP 9.2.1, Gradle 9.4.1, JDK 17, NDK r30.

## Build

Push to GitHub. The `build` workflow makes debug APKs for armeabi-v7a and arm64-v8a and attaches them to a release.

To build on Linux or macOS yourself:

```sh
cd buildscripts
./download.sh
./buildall.sh --arch arm64 mpv
./buildall.sh -n
```

APKs land in `app/build/outputs/apk/`. More in [`buildscripts/README.md`](buildscripts/README.md).

## FAQ

**Can I install it next to mpv-android?** No. Both use the package name `is.xyz.mpv`.

**Which Android versions?** 6.0 or newer.

## Licence

MIT, as upstream. See [`LICENSE`](LICENSE).
