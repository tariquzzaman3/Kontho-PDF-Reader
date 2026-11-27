## Kontho PDF Reader 0.7.1

A dedicated OCR screen, one Read aloud button, and fixes for PDFs opened from the browser and other apps.

### What's new
- **Settings → Reading → OCR** (new screen), best first:
  - **Kontho OCR**: reads pictures of pages (scanned PDFs, "photo to Word" style) in **Bangla, English and Arabic**. A small one-time download, then it works offline. Download it by hand from this screen, like the voices, and delete it any time.
  - **Phone default**: reads the text already inside the PDF. Works at once, offline, nothing to download.
- The **OCR text cache limit** moved into this screen, together with its usage and a Clear cache button.
- **Offline or online:** with no internet, Kontho uses the phone default. When there is internet and Kontho OCR is not downloaded yet, it offers the (small) download. You can choose "Use phone default" or "Don't ask again".
- **One Read aloud button** in the PDF page menu (it used to be two). The engine you pick in Settings → OCR decides how the page is read. If the phone default finds no text on a scanned page, Kontho switches to Kontho OCR or offers it.

### Fixed
- **Duplicate / blank-thumbnail PDFs.** A PDF opened from the browser and the same file in your library folder no longer show up twice. The same file reached through different Android addresses is now recognised and merged, keeping the one that stays readable.
- **"Password-protected PDF" on a PDF with no password.** That message appeared when Android had not given Kontho access to the file. Kontho now tells a missing-access problem apart from a real password. Real password prompts are unchanged.
- **"Permission Denial: opening provider com.android.externalstorage…"** (blank pages, "could not open PDF") on some phones. Files from other apps are routed through your library-folder permission when it covers them, entries whose access Android has removed are cleaned out of the list instead of staying as blank cards, and if access is truly missing Kontho explains it and offers to pick the file or folder again. Older phones (Android 10 and below) can read plain file paths.

### Good to know
- If a blank card from an older version is still in your list, pull to refresh once: it is removed automatically when its access is gone.
- Kontho OCR models are Tesseract (Apache 2.0) and are checked for integrity after download. Pages are read on your phone; nothing is uploaded.
