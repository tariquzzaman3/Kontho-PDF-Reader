# Kontho PDF Reader

A lightweight Android PDF reader designed for Bangla books, with a local book grid, PDF text extraction, and read-aloud controls.

## Current MVP

- Choose a books folder with Android's folder picker; the app scans PDFs in that folder and its subfolders.
- Show discovered books in a two-column grid with cover thumbnails and filenames.
- Remember the selected folder permission and recently opened PDFs.
- Set any PDF page as a book's library cover, either from the page currently being viewed or by entering a page number.
- Open PDF files using Android's document picker.
- Render PDF pages using Android's built-in `PdfRenderer`.
- Extract selectable PDF text with PDFBox-Android.
- Read the current page using Android's installed text-to-speech engine.
- Keep books, cover selections, and reading content on the device; no account or server is required.
- GitHub Actions builds a debug APK and uploads it as an artifact.

## Using the library

1. Open Kontho PDF Reader.
2. Tap **Choose books folder** and select the folder containing your PDFs (for example, Downloads or a folder where you keep ebooks). Android requires you to grant folder access; the app cannot silently scan every file on the phone.
3. Tap a book in the grid to open it.
4. To replace its thumbnail cover, navigate to the page containing the cover and tap **Use this page as cover**. Or tap **Choose page…** and enter the PDF page number. The selection is stored locally for that book.
5. Return to **Library** to see the updated cover. Books that have no saved cover selection use page 1 by default.

## Important limitations

- The grid can only list PDFs in the folder you grant access to, plus recently opened PDFs. Select a different folder to browse another location.
- Bangla voice quality depends on the speech engine installed on the device. This version does not yet include a custom cloned voice or expressive audiobook model.
- Scanned/image-only PDFs do not contain extractable text. OCR is not implemented yet.
- Encrypted or damaged PDFs may not preview properly.

## Build

The project uses Gradle 8.9, JDK 17, Android Gradle Plugin 8.7.3, and Kotlin 2.0.21.

On a computer with Android SDK platform 35 installed:

```bash
gradle --no-daemon assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

A GitHub Actions run uploads the same file as the `kontho-pdf-reader-debug` artifact.

## Privacy

Do not commit private books, voice recordings, API keys, access tokens, or model weights to this repository. Only use voice recordings you own or have permission to use.
