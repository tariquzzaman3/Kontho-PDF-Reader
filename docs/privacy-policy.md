# Kontho PDF Reader Privacy Policy

**Effective date:** October 25, 2026  
**Developer/contact:** Tariquzzaman3 — [repository issues](https://github.com/tariquzzaman3/Kontho-PDF-Reader/issues)

## Information handled by the app

Kontho is a local PDF reader. It processes PDFs that you open or files and folders to which you explicitly grant access through Android's document picker. The app stores preferences, recent-book references, selected cover-page numbers, OCR text cache, and downloaded OCR language models in private app storage.

The app does not provide user accounts and currently includes no advertisements, analytics, or app-operated backend that receives PDF files or recognized OCR text. The app does not sell user data.

## Network connections

On first use of OCR for a language, Kontho downloads public Tesseract language models over HTTPS from GitHub. Bangla and Bangla + English use the `tesseract-ocr/tessdata_best` repository when available; English-only uses `tesseract-ocr/tessdata_fast`. GitHub may receive ordinary connection metadata, such as your IP address. Once the models are cached, OCR recognition runs locally on your device.

When automatic update checks are enabled, Kontho requests public release metadata from GitHub at most once per day; a manual check in Settings makes the same metadata request. These requests do not include PDFs, OCR text, or library contents. GitHub may receive ordinary connection metadata for these requests too.

If you choose the optional offline Bangla voice in Settings → Voice, Kontho downloads the voice file once over HTTPS from the Hugging Face dataset host. Hugging Face may receive ordinary connection metadata, such as your IP address. After the download, that voice runs entirely on your device.

Kontho does not upload your PDFs or OCR results to its own server.

## Text-to-speech

Read aloud uses the Android text-to-speech (TTS) engine installed and selected on your device. Depending on the engine and its settings, speech may be synthesized locally or text may be processed by the engine provider. That provider's privacy practices are governed by its own privacy policy and settings.

Settings → Voice offers three reading voices:

- **Device voice (default):** the Android engine described above.
- **Offline Bangla voice (optional):** a speech model (Meta MMS-TTS, Bengali, CC BY-NC 4.0) that runs on your device. Text is not sent anywhere while it speaks.
- **Azure neural voice (optional, online):** if you choose it and enter your own Microsoft Azure Speech key, the text being read aloud is sent over HTTPS, together with your key, directly from your phone to Microsoft Azure (`<region>.tts.speech.microsoft.com`) so that Azure can return audio. Kontho has no server in between and does not receive this text. Azure's handling of the text is governed by Microsoft's own terms and privacy statement, and any usage beyond Azure's free monthly allowance is billed by Microsoft to your account. Your key is stored only in Kontho's private app storage on your phone and is removed when you tap Remove key or uninstall the app. Azure is used only when you select it and only for Bangla text.

## File access and retention

Kontho accesses the documents and folders you select and uses Android-granted permissions. File rename, delete, and copy actions are performed only when you request them and Android/provider permissions allow them. App preferences and OCR text cache remain in app storage. You can clear the OCR text cache in OCR settings or uninstall the app to remove private app data. Android system behavior, user-selected TTS engines, and document providers may have their own data-handling rules.

## Children and changes to this policy

Kontho does not include a child-directed account or advertising system. This policy may be updated when app functionality changes. The effective date above will be updated when a new policy is published.

## Contact

For privacy questions, contact the developer through [Kontho PDF Reader repository issues](https://github.com/tariquzzaman3/Kontho-PDF-Reader/issues).
