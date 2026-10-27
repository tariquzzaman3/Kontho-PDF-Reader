# Kontho PDF Reader

**A Bangla-first Android PDF reader with OCR-based read-aloud for scanned and image-based PDFs.**

Kontho (কণ্ঠ) is an Android application built with Kotlin for reading Bangla and English PDFs. Its main strength is recognizing Bangla text on scanned PDF pages—even when the page is stored as an image and does not contain selectable text—and reading the recognized content aloud.

## Download preview

The current preview APK is published on the [GitHub Releases page](https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases/tag/v0.6.0-preview.1).

This is a **debug-signed preview build for manual installation and testing**, not a production-signed APK or a Google Play release. Bangla OCR depends on scan quality, and spoken Bangla depends on the TTS voice available on the device. The app has passed the automated GitHub Actions build; physical-device testing is still required.

## Key Features

### Read Bangla scanned PDFs

- **OCR for image-based pages:** Recognizes text from scanned or picture-based PDF pages that ordinary text extraction cannot read.
- **Recognition quality as a priority:** Bangla and Bangla + English OCR use Tesseract's `tessdata_best` models when available. English-only OCR uses `tessdata_fast` for quicker recognition.
- **Three OCR language options:** Choose Bangla, English, or Bangla + English.
- **On-device page recognition:** After the required language models have been downloaded, page recognition runs on the device. A first-time model download requires an internet connection.
- **Page-by-page processing:** Each page is rendered at OCR-friendly resolution and processed separately to help control memory use. Sparse-text pages receive an additional recognition attempt.

### Read aloud

- Uses the Android Text-to-Speech (TTS) engine to speak selectable PDF text or OCR-recognized text.
- Requests a Bangla voice for Bangla OCR when configured; available voices depend on the TTS engine installed on the device.
- Provides controls for reading speed and voice pitch, plus play, pause/resume, stop, restart, and close.
- Lets readers select and test Bangla voices reported by the installed Android TTS engine; saved choices are reused while reading Bangla text. Voice options and naturalness depend on the engine and installed voice data.
- Provides a shortcut to Android Text-to-speech settings so readers can change engines or install available Bangla voice data. Network-required voices may need internet access.
- Can scan the next OCR page while the current page is being read and continue to the next page automatically when that option is enabled. When continuous OCR reading is active, closing the playback controls or leaving the PDF prompts for confirmation before stopping narration.
- Displays the page currently being narrated and the reading progress through the book.
- Offers options to skip standalone page numbers and repeated page-header or footer lines that match the PDF filename. These filters are best-effort and may not identify every printed page number or title.

### PDF library and reader

- Import PDFs through Android's document picker or select a folder for the library to scan.
- Browse books in grid or list view, sort by name or modification date, and choose a page for a book's thumbnail.
- Read pages in one continuous vertical scroll and use pinch gestures or the reader menu to adjust zoom.
- Rotate the reading screen, keep the display awake while a PDF is open, jump to a page, share a PDF, print it, or save a copy.
- Use light, dark, or system appearance and choose English or বাংলা for the app interface.

### OCR cache and preferences

- Saves recognized text in app-private storage so previously scanned pages can be reused.
- Configures the OCR text-cache limit to 10, 25, 50, 100, 250, or 500 MB, or no limit. When the limit is reached, older cached pages are removed first.
- Lets readers retain a book's OCR cache or clear it when leaving the book. OCR text cache and downloaded language models use separate storage.
- Includes settings for next-page pre-scanning, automatic continuation, reading-position visibility, and background playback.

## Reading a scanned Bangla PDF

1. Open Kontho and select a PDF from the library, or use the PDF picker to open one.
2. Open the reader menu and choose **Read aloud · Accurate OCR** for pages that are scans or images rather than selectable text.
3. Open the read-aloud settings and choose **Bangla**, **English**, or **Bangla + English** to match the document.
4. On first use of a language, connect to the internet so Kontho can download the required OCR model. Later recognition can run locally using the downloaded model.
5. Start reading. Use the playback controls to pause, resume, restart the page, or stop. Enable next-page pre-scanning and automatic continuation for a more continuous listening experience.

For PDFs that already contain selectable text, use **Read aloud** instead. Text extraction can be quicker because it does not require OCR.

## OCR and speech: what to expect

Kontho uses the open-source Tesseract4Android OCR engine. The app prioritizes higher-recognition-quality models for Bangla where possible, but OCR is not perfect. Results depend on scan resolution, contrast, page alignment, font size, and page layout. Some pages may require correction or a repeat scan.

Speech is produced by the Android TTS engine, not by a built-in AI voice. In the reader's **Read aloud settings**, use **Choose / test Bangla voice** to preview the Bangla voices listed by the current engine. To install another voice or change engines, open Settings → **Bangla speech voice & engine**. Not every device offers multiple Bangla voices, and an installed voice can still sound synthetic. If a compatible Bangla voice is unavailable, Kontho may use the device's default voice and prompt the reader to check Android TTS settings.

## Privacy and storage

- PDFs and recognized OCR text are handled in the app; Kontho does not upload them to an app-operated server.
- Tesseract language models are downloaded from the public Tesseract repositories on GitHub when needed.
- If automatic update checks are enabled, the app requests public GitHub release information. These requests do not contain PDF files or recognized OCR text.
- Depending on the Android TTS engine and its settings, speech may be processed locally or by the TTS provider.
- OCR text and language models are stored separately in app-private storage. You can clear the OCR text cache from the OCR options.

See the [privacy policy](docs/privacy-policy.md) for more details.

## Current limitations

- Recognition can be less reliable for low-resolution scans, small text, unusual fonts, multi-column pages, and complex layouts.
- The title-skip option compares likely header/footer lines with the PDF filename; it cannot reliably detect every printed book title.
- Background playback depends on Android keeping the app process alive. Force-stopping the app or having the operating system terminate its process stops playback.
- Kontho currently reads text aloud during playback; exporting narration as standalone audiobook files is not implemented.
- Renaming and deleting PDFs depend on the permissions provided by Android and the selected document provider.

## Build from source

### Requirements

- JDK 17
- Gradle 8.13
- Android SDK platform 36
- An internet connection for Gradle dependencies and any OCR language models that are not already available

### Build commands

From the repository root, run:

```bash
gradle --no-daemon clean assembleDebug bundleRelease
```

The expected output paths are:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release Android App Bundle: `app/build/outputs/bundle/release/app-release.aab`

GitHub Actions is configured to build and upload both artifacts. A release bundle is signed only when the required `ANDROID_UPLOAD_*` GitHub Actions secrets are configured; an unsigned bundle must not be submitted to Google Play.

## Project status and release preparation

Kontho is under active development. Before distributing a build, verify the latest GitHub Actions run and test the resulting APK on a physical Android device, especially with scanned Bangla PDFs and the intended Bangla TTS voice.

For release requirements, see the [Google Play release checklist](docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md). Store listing details, data-safety declarations, app signing, and final submission still need to be completed in Play Console.
