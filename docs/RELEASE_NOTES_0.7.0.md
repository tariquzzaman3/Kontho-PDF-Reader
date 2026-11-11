## Kontho PDF Reader 0.7.0

A new look, a welcome screen, and a choice of reading voices.

### What's new
- **Reading voices (Settings → Voice), best first:**
  - **Azure neural voice** (online): the most natural Bangla (Nabanita or Pradeep). Bring your own free Microsoft Azure Speech key; about 500,000 characters per month are free. Kontho shows how much this phone has used.
  - **Offline Bangla voice** (optional download, about 114 MB): runs on your phone with no internet. It says ড় ঢ় য় correctly, pauses at punctuation, reads numbers aloud, and can respell unwritten vowels (আশাকরি → আশাকোরি).
  - **Device voice** (default): Android's built-in voice, works immediately and offline.
- If a chosen voice is not set up, or fails while reading (no signal, quota used up), Kontho carries on with the device voice from the same paragraph and tells you once.
- **New design:** one calm green-and-paper palette in light and dark, rounded cards, a floating button to open a single PDF, a book count, and gentle animations.
- **Welcome screen** with the Kontho name and slogan.
- The test build installs next to the normal app, so you can try it safely.

### Good to know
- The offline voice is Meta MMS-TTS (Bengali), licensed CC BY-NC 4.0: free for non-commercial use. It is a character-based model, so it can still mispronounce some words. The vowel fix is a best-effort rule that you can switch off.
- English words inside Bangla text are not spoken by the offline voice. Use the Azure or device voice for mixed-language pages.
- Your Azure key stays on your phone and is sent only to Microsoft Azure to create speech. Text you listen to with the Azure voice is sent to Azure; see the privacy policy.

### Existing features
- Read password-protected, Bangla, English, and mixed-language PDFs.
- OCR for scanned Bangla pages, with page-by-page pre-scanning and continuous reading.
- Browse PDF folders with progressive scanning, grid/list layouts, and sorting.
- Manage OCR cache, reading controls, sharing, printing, text selection, and PDF copies.
