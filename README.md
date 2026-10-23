# Kontho PDF Reader

A small Android PDF reader built with Kotlin and Android's built-in `PdfRenderer`.

## Basic features

- Open a PDF using Android's system file picker.
- Display PDF pages as images.
- Move to the previous or next page.
- Keep the selected PDF's read permission when the document provider supports it.
- Render pages on a background thread to keep the interface responsive.
- No account, server, or broad file-storage permission is required.

## Limitations

- This is the basic reader foundation. Text selection, search, bookmarks, OCR for scanned pages, and read-aloud are not included in this version.
- Encrypted or unsupported PDFs may not open.
- Page zoom and annotations are not included yet.

## Build

Uses JDK 17, Gradle, Android Gradle Plugin, Kotlin, and Android SDK platform 35.

```bash
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions is configured to build the debug APK and upload the `kontho-pdf-reader-debug` artifact when the workflow succeeds.
