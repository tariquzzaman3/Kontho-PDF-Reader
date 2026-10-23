# Kontho PDF Reader

A lightweight Android PDF reader for Bangla and English books, with a simple book library, custom cover thumbnails, and read-aloud controls.

## Library and personalization

- On first launch, open **Settings** to choose language (English/Bangla), appearance (system/light/dark), list or grid, grid columns (2/3/4), and sort order.
- Reopen Settings at any time to change these choices.
- Choose a books folder. Kontho scans PDFs in that selected folder and its subfolders whenever the app starts, and you can refresh the library manually.
- The library supports a grid with 2, 3, or 4 columns, or a compact list view.
- Sort by filename ascending/descending or file date newest/oldest first.
- Long-press a book to open, rename, or delete it. Rename/delete depend on whether the Android document provider grants write access.
- Set any PDF page as the book cover thumbnail from inside the reader.
- Choose a cache/audio folder through Android's folder picker. This saves the selected destination for future generated audio/cache files; the current MVP does not yet generate audiobook cache files.

## Android storage limits

Android does not allow an app to silently scan every private and shared folder. Select the folder(s) that contain your books through the system folder picker. You may choose phone storage or an SD card if Android exposes it in the picker. The app scans the selected folder each time it starts; it does not request broad "all files access" permission.

The cache folder preference is a configured destination only at this stage. Audio caching will be connected when audiobook generation is implemented.

## Current MVP

- Open PDFs through Android's document picker.
- Render PDF pages using Android's built-in `PdfRenderer`.
- Extract selectable text with PDFBox-Android.
- Read the current page using the phone's installed TTS engine.
- Save selected cover-page preferences locally.
- No account or server required.

## Limitations

- Bangla speech availability and voice quality depend on the installed TTS engine. A custom cloned voice and expressive audiobook model are not included yet.
- Scanned/image-only PDFs need OCR, which is not implemented.
- Some file providers do not permit rename/delete through their shared document interface.
- Large or encrypted PDFs may fail to preview or extract.

## Build

Uses Gradle 8.9, JDK 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Android SDK platform 35.

```bash
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions uploads a `kontho-pdf-reader-debug` artifact when the Android Build workflow succeeds.

## Privacy

Do not commit private books, voice recordings, access tokens, or model weights to this repository. Only use voice recordings you own or have permission to use.
