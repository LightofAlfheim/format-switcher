# Format Switcher (Android)

Switch formats of images, videos and audio, or compress them to 8 MB / 10 MB / 20 MB.
Works fully offline on your phone (built and tuned for Android 11+, e.g. Galaxy S25).

## What it does

| You pick | Convert to | Compress |
|---|---|---|
| Image (JPG, PNG, WEBP, HEIC, GIF, BMP…) | JPG, PNG, WEBP | JPG/WEBP under 8 / 10 / 20 MB |
| Video (MP4, MOV, MKV, WEBM, 3GP…) | MP4 (H.264), MP4 (H.265), M4A or WAV audio-only | MP4 under 8 / 10 / 20 MB |
| Audio (MP3, M4A, AAC, WAV, FLAC, OGG, OPUS…) | M4A (AAC), WAV | M4A under 8 / 10 / 20 MB |

Results are saved as **new files** in `Pictures/`, `Movies/` or `Music/` → `Format Switcher`.

## Safety
- No internet permission (removed in the manifest), so nothing can be uploaded anywhere.
- No storage permission: files are picked through Android's own file picker.
- Originals are never modified or deleted.
- Re-saved photos have their EXIF data (including GPS location) removed.
- No ads, analytics or third-party SDKs. Only Google's AndroidX libraries are used.

## Build the APK — Option A: GitHub (nothing to install)
1. Make a free account at github.com, then create a new **repository**.
2. Click **"uploading an existing file"** and drag in **everything inside this folder**
   (including the hidden `.github` folder – on Windows, turn on *View → Show → Hidden items*).
   Commit.
3. Open the **Actions** tab. The *Build APK* job runs automatically (about 5–8 minutes).
4. Open the finished run, scroll to **Artifacts** and download `FormatSwitcher-apk`.
   Unzip it and you'll find `app-release.apk`.

## Build the APK — Option B: Android Studio (Windows)
1. Install Android Studio from developer.android.com/studio.
2. **File → Open** and choose this `FormatSwitcher` folder. Wait for "Gradle sync" to finish.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Click **locate** in the pop-up; the APK is in `app/build/outputs/apk/`.

## Install on the Galaxy S25
1. Copy the APK to the phone (USB, Google Drive, or email it to yourself).
2. Tap it in **My Files**. When asked, allow *Install unknown apps* for that app (one time).
3. If **Auto Blocker** is on (Settings → Security and privacy → Auto Blocker), turn it off
   temporarily, install, then turn it back on.
4. Play Protect may say it doesn't recognise the app (it's not from the Play Store) –
   choose **Install anyway**.

## Notes
- Video work uses the phone's hardware encoder. A few minutes of 4K can take a minute or two.
- HDR videos are converted to normal colour when saving as H.264 (so they look right everywhere).
- Very long videos/songs may not fit under 8 MB with decent quality; the app tells you if
  it couldn't get all the way under the limit.
