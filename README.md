# Nuadyr-mpv

A video and audio player for Android TV, phones and tablets.
A fork of [mpv-android](https://github.com/mpv-android/mpv-android), powered by [libmpv](https://github.com/mpv-player/mpv).

The player engine (decoding, gestures, subtitles) is upstream's. This fork adds a new front end.

## Features

- **Library grid.** Thumbnails of all your videos and audio, on TV, phone and tablet.
- **Folder cards.** A folder with 2+ files shows as one card. Open it to see the files.
- **Resume.** Remembers where you stopped and shows a progress bar on the tile.
- **Auto queue.** Pick a file and the files after it play next.
- **Frame rate matching.** Asks the TV to switch to the video's frame rate (e.g. 23.976 Hz).
- **Aengus theme.** Light and dark. TV is always dark.

## Use it

| Device | Play | Options |
| --- | --- | --- |
| TV | **OK** | **Menu** button, or hold **OK** |
| Phone / tablet | Tap a tile | Three-dot button, or hold a tile |

Options: Refresh list, Sort by, Show, Group by, Folder cards, Open URL, Browse folders, Settings.

Missing a file? The grid only shows what Android's media index lists. Use **Browse folders**.

### Folder cards

- Cover image: `poster`, `folder`, `cover`, `front` or `thumb` (jpg, png, webp) in the folder. Otherwise the first file's thumbnail.
- `Show/Season 1/` rolls up into the `Show` card.
- Files directly in Downloads, Movies, Music, DCIM etc. stay as single tiles.
- Turn it off in the menu: **Folder cards**.

### Queue

The queue follows the order on screen, skips the other media type, and holds up to 300 files.
**Back** in the player clears it.

### Settings

| Setting | Where | Default |
| --- | --- | --- |
| Match display frame rate (Android 11+) | Settings > Video | On for TV, off otherwise |
| Save position on quit | Settings > General | On for TV, off otherwise |

Files under 2 minutes aren't tracked. Past 95% counts as finished.

## Install

Grab an APK from [Releases](../../releases). Every push to GitHub builds one (`arm64-v8a` and `armeabi-v7a`).

- Needs Android 6.0 (API 23) or newer.
- Package name: `io.github.nuadyr.mpv`, so it installs next to mpv-android.

## Build

Linux or macOS only (no Windows/WSL). Needs JDK 17.

```sh
cd buildscripts
./download.sh                      # SDK, NDK, sources
./buildall.sh --arch arm64 mpv     # native libs (add --arch for others)
./buildall.sh -n                   # the APK
```

APKs land in `app/build/outputs/apk/`. More detail in [`buildscripts/README.md`](buildscripts/README.md).

Tooling: Kotlin 2.2.21, AGP 9.2.1, Gradle 9.4.1, NDK r30.

## Licence

MIT, same as upstream. See [`LICENSE`](LICENSE).
