# Kontho PDF Reader

Kontho is a lightweight Kotlin Android reader for Bangla and English books, designed as the foundation for a Bangla audiobook app.

## Current features

- Open an individual PDF or choose a books folder using Android's system document pickers.
- Scan the chosen folder and subfolders for PDFs, with a library shown as a grid or list.
- Choose 2, 3, or 4 grid columns and sort by name or modified date.
- Reopen recent PDFs; long-press a library item to open, rename, or delete it where the document provider allows those operations.
- Render PDF pages with Android's built-in `PdfRenderer`, move between pages, and jump to a page number.
- Save any page as a book's library-cover thumbnail.
- Extract selectable page text with PDFBox-Android and read it aloud through the phone's installed Android TTS engine.
- Prefer Bangladeshi Bangla for Bangla text and English for English text; add short pauses between extracted paragraphs and allow the narration speed to be adjusted in Settings.
- Choose English/Bangla app labels, light/dark/system appearance, library layout, sorting, and an audio/cache folder.
- No account or server required.

## Narration and AI voice status

The current reader uses the installed Android TTS engine. Its Bangla availability and voice quality depend on the engine installed on the device. The slower storyteller pace and paragraph pauses make reading less rushed, but **they do not create a custom expressive AI voice** or guarantee emotional acting for character dialogue.

A custom voice should be added only after selecting a legally usable open-source model and training or fine-tuning with recordings for which you have permission. YouTube audio should not be used for training without the necessary rights. Model training, voice assets, and on-device/server inference are not included in this Android build yet.

## Limitations

- Scanned or image-only PDFs need OCR; OCR is not implemented.
- Very large or encrypted PDFs may fail to render or extract.
- Rename/delete depend on document-provider permissions.
- The selected audio/cache folder is saved as a future destination; the app does not yet produce generated audiobook files.

## Build

Uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21 and Android SDK platform 35.

```bash
gradle --no-daemon assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK and uploads `kontho-pdf-reader-debug` when the build passes.
