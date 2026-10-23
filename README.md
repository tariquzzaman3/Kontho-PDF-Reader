# Kontho PDF Reader

Kontho is a Kotlin Android PDF reader for Bangla and English books, designed as a foundation for a Bangla audiobook app.

## First-run setup

On the first launch, a short step-by-step wizard presents:
1. Language: বাংলা or English (shown with Bangladesh and UK flags).
2. Theme: system, light, or dark.
3. Library layout: list or grid.
4. PDF folder: select the folder Kontho should scan.

The Settings gear keeps these preferences available afterward, plus grid-column count, sort order, and the future audio/cache destination.

## Library and PDF reader

- The library header contains the app name and Settings gear; the Sort popup sits below it.
- Choose from a list or grid of PDF books, with grid size set in Settings.
- Pull down at the top of the library to rescan the chosen folder. Sorting is maintained during refresh; merely returning to the app does not continuously rescan.
- Book thumbnails can use a page chosen from the PDF. Long-press a book for open, rename, or delete where Android's document provider permits those actions.
- Open a book in immersive full-screen mode. All PDF pages flow vertically in one continuous scroll.
- The top-right glass-style three-dot menu and the bottom-center read-aloud controls fade after two seconds. Scrolling pages does not reveal these controls.
- Jump to a page, set a cover page, or open another PDF from the reader menu.
- Extract selectable text with PDFBox-Android and read the current page aloud through Android's installed TTS engine.
- For text containing Bangla, prefer Bangladeshi Bangla then fall back to Bengali if available; English pages use an English voice. Narration includes paragraph pauses and has an adjustable pace.

## Narration and AI voice status

The current reader uses the Android TTS voice installed on the device. Availability and quality vary by device. Slower narration and pauses do not create a custom expressive AI storyteller voice or guarantee emotional acting for character dialogue.

A custom voice model still requires a separately selected, legally usable model and recordings that are licensed or otherwise permitted for training. YouTube audio should not be used without the necessary rights. Model training, custom voice assets, and inference are not included in this Android build.

## Limitations

- Scanned/image-only PDFs need OCR; OCR is not implemented.
- Very large or encrypted PDFs may fail to render or extract.
- Rename/delete depend on document-provider permissions.
- The selected audio/cache folder is saved as a future destination; generated audiobook files are not created yet.

## Build

Uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Android SDK platform 35.

```bash
gradle --no-daemon assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK and uploads `kontho-pdf-reader-debug` when the build passes.
