# Kontho PDF Reader

Kontho is a Kotlin Android PDF reader for Bangla and English books, designed as a foundation for a Bangla audiobook app.

## First-run setup

On first launch, a short step-by-step wizard presents:
1. Language: বাংলা or English (shown with Bangladesh and UK flags).
2. Theme: system, light, or dark.
3. Library layout: list or grid.
4. PDF folder: select the folder Kontho should scan.

The Settings gear keeps these preferences available afterward, plus grid-column count, sort order, narration pace, OCR language, and the future audio/cache destination.

## Library and PDF reader

- The library header contains the app name and Settings gear; the Sort popup sits beneath it.
- Choose list or grid, with grid size configured in Settings.
- Pull down to rescan the chosen folder. Sorting is maintained, and returning to the app or rotating the device does not rescan the folder.
- Book thumbnails can use a page chosen from the PDF. Long-press a book to open, rename, or delete it where Android's document provider allows those actions.
- Open a book in immersive full-screen mode. All PDF pages flow vertically in one continuous scroll.
- Two-finger pinch zoom applies to every page together. The zoom controls are available in the reader menu; zooming does not affect the library.
- The top-right glass-style three-dot menu and bottom-center playback controls fade after two seconds. Scrolling does not reveal these controls.
- Reader menu actions include orientation rotation, device-default read aloud, OCR read aloud and settings, saving a copy of the open PDF, page navigation, and cover selection.
- Playback controls include play, pause/resume, stop, restart, and close.
- A small green progress bar displays brief status messages when scanning books, rendering pages, preparing speech, downloading OCR models, scanning OCR, or saving a copy.

## OCR details

OCR uses the open-source Tesseract4Android engine with the fast Bengali and English trained-data models. On first OCR use, the selected model(s) are downloaded from the official `tesseract-ocr/tessdata_fast` repository and kept in the app's private storage. That first model download requires an internet connection; later OCR operations can run locally with the cached models.

OCR is processed one page at a time to limit memory use, and recognized text is cached for future narration. Use the OCR gear to select Bengali, English, or both, or clear the OCR text cache.

## Speech details

Read aloud uses the Android Text-to-Speech engine and device-default locale. The voice, Bangla support, and quality depend on the engine installed on the phone. Rate and pitch can be adjusted from the voice settings. Paragraph pauses are inserted between short TTS segments. Pause/resume restarts the current paragraph because Android's TTS API does not provide a consistent universal pause operation.

A custom expressive AI voice still requires a separately selected, legally usable model and recordings for which the user has permission. Custom voice training and emotional acting are not included in this build.

## Limitations

- OCR accuracy depends on page quality and the trained model. Complex layouts, columns, small type, and low-resolution scans can be imperfect.
- Large or encrypted PDFs may fail to render or extract.
- Rename/delete depend on document-provider permissions.
- The selected audio/cache folder is saved as a future destination; generated audiobook files are not created yet.

## Build

Uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Android SDK platform 35 and Tesseract4Android 4.9.0.

```bash
gradle --no-daemon assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK and uploads `kontho-pdf-reader-debug` when the build passes.
