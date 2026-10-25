# Google Play release checklist — Kontho PDF Reader

## Build status and technical setup

- [x] Application ID set: `org.kontho.pdfreader`.
- [x] Target and compile SDK set to Android 16 / API 36, currently required for new mobile app submissions.
- [x] Launcher icon added (legacy vector and adaptive launcher variants).
- [x] App-internal privacy policy added to Settings and public policy page added to this repository.
- [x] CI builds a debug APK and a release Android App Bundle.
- [ ] Test the new build on real phones, including Android 15/16, light/dark theme, rotation, PDF folder permission, OCR downloads, and TTS settings.
- [ ] Inspect the release AAB and confirm whether CI signed it. Do not upload an unsigned bundle.

## Signing the release bundle

Create a dedicated Android upload keystore on a trusted computer and store it securely. Never commit the keystore or its passwords to Git. To have GitHub Actions sign `bundleRelease`, add these repository Actions secrets:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`: base64-encoded keystore file
- `ANDROID_UPLOAD_STORE_PASSWORD`: keystore password
- `ANDROID_UPLOAD_KEY_ALIAS`: upload key alias
- `ANDROID_UPLOAD_KEY_PASSWORD`: upload key password

The action creates the keystore under the runner's temporary directory. If any of those values is missing, CI builds an unsigned release AAB instead. Use Play App Signing in Play Console and retain secure backups of the upload key.

## Play Console steps

- [ ] Create the app entry and confirm the package name is exactly `org.kontho.pdfreader`.
- [ ] Set up app signing / Play App Signing and upload a signed AAB.
- [ ] Complete the Data safety form accurately, including the OCR-model download and the behavior of the user's selected Android TTS engine.
- [ ] Add the privacy-policy URL: https://github.com/tariquzzaman3/Kontho-PDF-Reader/blob/main/docs/privacy-policy.md
- [ ] Complete app access, ads declaration (the current build has no ads), target-audience, content-rating, and any other required App content declarations.
- [ ] Add store listing text, developer contact details, a 512 × 512 PNG app icon, feature graphic, screenshots, and localized Bangla/English listing information as desired.
- [ ] Test with Play internal/closed testing as required by the Play Console account, fix issues, and then submit for review.

## Important release notes

- Bengali OCR recognition is available, but spoken Bengali depends on a compatible Bengali voice supplied by the Android TTS engine on the user's device. Kontho now requests a Bengali voice for Bengali OCR and offers a shortcut to the system TTS settings; no custom Bengali voice model is bundled.
- The repository and GitHub Actions cannot complete the human-facing Play Console declarations or submit/release the app on their own.
