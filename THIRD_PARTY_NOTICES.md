# Third-party notices

Kontho PDF Reader is free software under the **GNU GPL v3 or later** (see `LICENSE`).
It uses the following other people's work, each under its own license. Their licenses allow this use and are compatible with the GPL.

## Code shipped inside the app

| Component | What it does here | License |
|---|---|---|
| [AndroidX](https://developer.android.com/jetpack/androidx) (core-ktx, activity-ktx, swiperefreshlayout, documentfile) | Android building blocks | Apache-2.0 |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) 2.0.27.0 | Reads PDF text, links and structure (port of Apache PDFBox) | Apache-2.0 |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0 | Android build of the Tesseract OCR engine, with Leptonica, libjpeg and libpng | Apache-2.0 (Tesseract4Android, Tesseract); BSD-style (Leptonica); IJG/BSD-style (libjpeg); libpng license (libpng) |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1 | Runs the offline Bangla voice on the phone; includes ONNX Runtime | Apache-2.0 (sherpa-onnx); MIT (ONNX Runtime) |
| [Bouncy Castle](https://www.bouncycastle.org/) 1.86 | Cryptography for encrypted PDFs | MIT-style (see below) |
| Material Design icons (five reader icons: read aloud, rotate, invert colors, share, download) | Icons | Apache-2.0 |

## Models downloaded by the user (not part of the app file)

| Model | Used for | License |
|---|---|---|
| [tessdata_best](https://github.com/tesseract-ocr/tessdata_best): Bengali, English, Arabic | Kontho OCR (reading scanned pages) | Apache-2.0 |
| [Meta MMS-TTS Bengali](https://huggingface.co/facebook/mms-tts-ben), converted to ONNX for sherpa-onnx | Offline Bangla voice | **CC BY-NC 4.0** (non-commercial; Meta credited; converted from the original) |

These are separate data files fetched on request. They are not linked into the program, so their licenses do not change the license of the code. The Bangla voice stays **non-commercial** wherever it is used.

## Online service (optional, no code included)

- **Microsoft Azure AI Speech**: used only when you add your own key. Kontho calls its public web API. Your use is covered by Microsoft's terms and pricing; no Microsoft code is included.

## Build and test only (not shipped)

- JUnit 4.13.2 (EPL-1.0), Gradle, Android Gradle Plugin and Kotlin (Apache-2.0).

## Archive reading

- **junrar** (https://github.com/junrar/junrar) reads RAR archives for `.cbr` comics. It is distributed under the UnRAR licence, which allows use and redistribution but not use to recreate the RAR compression algorithm. Kontho only extracts; it never creates RAR files.
- **AndroidX RecyclerView** is licensed under Apache License 2.0.

## Full license texts

- GNU GPL v3: `LICENSE`
- Apache License 2.0: `LICENSES/Apache-2.0.txt`

### Bouncy Castle license (MIT-style)

Copyright (c) 2000 - 2025 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
