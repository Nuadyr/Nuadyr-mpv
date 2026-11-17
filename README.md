# Nuadyr-mpv: mpv video player for Android TV

Nuadyr-mpv is a video and audio player for Android TV, phones and tablets. It is a fork of [mpv-android](https://github.com/mpv-android/mpv-android) and plays with [libmpv](https://github.com/mpv-player/mpv).

## What's different

This is mainly a front-end overhaul of [mpv-android](https://github.com/mpv-android/mpv-android), the project it is forked from, with a few small quality-of-life changes. The player engine, decoding, gestures and subtitles are upstream's, unchanged.

- **Library home screen.** A grid of thumbnails for all your videos and audio, on TVs, phones and tablets. A TV has no buttons. A phone has a top bar menu.
- **Folder cards.** A custom folder with two or more files shows as one card. OK opens it and lists the files.
- **Frame rate matching.** Asks the TV to switch to the video's frame rate, for example 23.976 Hz for films.
- **Resume and progress bars.** Remembers where you stopped and shows it on the tile.
- **Auto queue.** Pick a file and the ones after it play next. Leaving the player clears the queue, so the next file you pick starts a fresh one.
- **Aengus theme.** One look in light and dark. TVs always use dark.

Everything else is mpv-android.

## Compared with mpv-android

| | mpv-android | Nuadyr-mpv |
| --- | --- | --- |
| Player engine | libmpv | libmpv |
| Decoding, gestures, subtitles, Open URL, PiP | Yes | Same |
| Android TV launcher | Yes | Yes |
| Home screen | Buttons | Thumbnail grid on TV, phone and tablet |
| Sort, filter, group library | No | Yes |
| Folder cards | No | Yes |
| Progress bars | No | Yes |
| Queue cleared on exit | No | Yes, for files started from the library |
| Save position on quit | Off by default | On by default on TV |
| Match display frame rate | No | Yes, on by default on TV |
| Theme | Default | Aengus |

Checked against mpv-android commit `ebf1b3f` (8 November 2026).

## Using it on a TV

| Key | Does |
| --- | --- |
| **OK** | Play |
| **Menu**, or hold **OK** | Open the menu |

The menu has Refresh list, Sort by, Show, Group by, Folder cards, Open URL, Browse folders and Settings.

The grid shows files that Android's media index (MediaStore) lists. If a file is missing, use **Browse folders**.

## Using it on a phone or tablet

The app opens on the same grid. Tap a tile to play it. Tap the three-dot button in the top bar, or hold a tile, to open the options.

The options are Refresh list, Sort by, Show, Group by, Folder cards, Open document tree, Open URL, Browse folders and Settings. They replace the old button home screen.

The phone and TV grids are separate copies of the same code, and each keeps its own saved Sort, Show, Group by and Folder cards choices.

## Folder cards

With **Folder cards** on (the default), a custom folder that holds two or more playable files shows as one card named after the folder. The card has a count badge and a "N files" line.

- **OK or tap** opens the card and lists its files in name order. Focus starts on the file you left half-watched. **Back** returns to the grid.
- **Playing a file inside a card** queues the files after it in that card.
- **Cover.** A `poster`, `folder`, `cover`, `front` or `thumb` image (jpg, png or webp) in the folder is used. Otherwise it is the first file's thumbnail.
- **Season folders.** `Show/Season 1/` rolls up into the `Show` card.
- **System folders stay as single tiles.** Files directly in Downloads, Movies, Music, DCIM, Camera, Recordings, Documents, Pictures and similar folders, or in the root of a drive, are not grouped. A subfolder of one of them, such as `Downloads/Some Show/`, is a card.
- **One file is not a card.** A folder with a single file shows that file as a normal tile.
- Under **Group by > Folder**, cards are not used, because each section already is a folder.

Turn it off under **Folder cards** in the menu.

## Auto queue

Picking a file in the library queues it and the files after it, in the order shown. The queue stops at the next section title, skips files of the other kind (a video does not queue songs) and holds at most 300 files.

Pressing **Back** in the player clears the queue. Picking a file in the library always replaces what was playing, including when the player was still running in the background, so old files never join the new queue.

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
