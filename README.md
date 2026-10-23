# Kontho PDF Reader

A lightweight Android PDF reader designed for Bangla books, with local text extraction and read-aloud controls.

## Current MVP

- Open PDF files using Android's document picker.
- Render PDF pages using Android's built-in `PdfRenderer`.
- Extract selectable PDF text with PDFBox-Android.
- Read the current page using Android's installed text-to-speech engine.
- Keep PDF content on the device; no account or server is required.
- GitHub Actions builds a debug APK and uploads it as an artifact.

## Important limitations

- Bangla voice quality depends on the speech engine installed on the device. This first version does **not** yet include a custom cloned voice or expressive audiobook model.
- Scanned/image-only PDFs do not contain extractable text. OCR is not implemented yet.
- The PDF renderer and text extraction are intended as a first MVP; test with your own PDFs and report failures.

## Build

The project uses Gradle 8.9, JDK 17, Android Gradle Plugin 8.7.3, and Kotlin 2.0.21.

On a computer with Android SDK platform 35 installed:

```bash
gradle --no-daemon assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

A GitHub Actions run uploads the same file as the `kontho-pdf-reader-debug` artifact.

## Planned work

1. Improve PDF navigation and reading progress.
2. Add paragraph-aware speech, speed controls, and reliable stop/resume.
3. Evaluate a small, legally usable offline Bangla voice model that can run on low-memory Android phones.
4. Investigate personal voice cloning and expressive narration only after a compatible model is proven on the target device.

## Privacy

Do not commit private books, voice recordings, API keys, access tokens, or model weights to this repository. Only use voice recordings you own or have permission to use.
