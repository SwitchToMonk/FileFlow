# FileFlow

A file manager for Android that organises, cleans and watches your storage.

Built on the Gitly design system, with a native file engine behind a WebView UI.

---

## Features

**Organise**
- One-tap sorting of files into category folders — Images, Videos, Audio, Documents, Archives, APKs, Code, Fonts
- Custom rules that route files by extension or by a word in the name
- Optional automatic organise, group and empty-folder clean-up, off by default

**Clean up**
- Duplicate detection by content hash, with the space each set would free
- Files sharing a name across folders, largest files, stale screenshots, leftover APKs and empty folders
- Folder management — browse folders with their file counts and sizes, open one to see its files, or delete it with everything inside
- Fuzzy name search that matches differently-worded copies — `gitly apk` finds `Gitly account sync.apk`
- Adjustable match strength; searches the recycle bin as well as storage

**Manage**
- Long-press to select, multi-select, select all, bulk move to bin
- Recycle bin with restore, and preview without restoring
- Built-in viewer for images, video, audio and text/code
- Sort by date, size or name

**Auto-delete timers**
- A notification when a new file lands, offering a delete timer
- Save your own presets in Settings and mark one as the default
- The notification carries two actions — **Default timer** applies the saved default in one tap, **Set timer** opens the panel to pick a specific one
- Custom day/hour/minute picker alongside the presets
- Timers permanently delete the file when they expire, and the confirmation clears itself after 20 seconds
- A compact panel appears over any app, and every timer can be cancelled from Home

**Interface**
- Light, dark and pitch-black themes, eight accent colours or any custom hex code
- Compact controls sized for one-handed use
- Recent files, activity history and live storage breakdown

---

## Permissions

| Permission | Why |
|---|---|
| All files access | To read, organise, move and delete files in shared storage |
| Notifications | New-file alerts and timer confirmations |
| Display over other apps | To show the timer panel on top of other apps |
| Usage access (optional) | To close the timer panel the moment you leave an app |

All processing happens on the device. Nothing is uploaded.

---

## Build

Requires JDK 17 and an Android SDK with `platforms/android-34` and `build-tools/34.0.0`.

```bash
ANDROID_HOME=/path/to/android-sdk ./build.sh
```

The script compiles resources with `aapt2`, builds with `javac` and `d8`, then aligns and signs the APK with the bundled debug keystore.

---

## Project layout

```
AndroidManifest.xml
src/com/fileflow/app/     native layer — file engine, watchers, services, bridge
assets/www/               WebView UI — HTML, CSS, JavaScript
res/                      icons and theme resources
gen_icon.py               regenerates launcher icons from artwork
build.sh                  build script
```

---

## Notes

- Minimum Android 7.0 (API 24); targets Android 14 (API 34).
- Automatic background work runs as a foreground service, so Android may ask you to keep FileFlow unrestricted for reliable alerts on aggressive OEM builds.
