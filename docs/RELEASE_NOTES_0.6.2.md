# Kontho PDF Reader 0.6.2

## Highlights

- Download stable APK updates directly from Kontho using Android Download Manager, with system notification progress and the Android package installer.
- Use a persistent release-signing key so future stable APK updates can be installed over the existing app.
- Keep existing PDF reader, Bangla OCR, OCR cache, and read-aloud functionality.

## Upgrade note

Earlier public builds used temporary debug signing keys. Moving from those builds to the persistent release key can require a one-time uninstall and reinstall. Uninstalling may clear Kontho's settings and OCR cache; PDFs stored outside Kontho's app-private storage are not removed.

The stable APK is published only after GitHub Actions verifies its release signature.

Kontho is distributed directly through GitHub and is not a Google Play release. OCR accuracy depends on scan quality, and Bangla speech quality depends on the speech engine and voice installed on the device.
