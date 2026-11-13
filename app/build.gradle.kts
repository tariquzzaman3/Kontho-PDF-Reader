import java.io.File
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val uploadKeyStorePath = System.getenv("ANDROID_UPLOAD_KEYSTORE_PATH")
val uploadStorePassword = System.getenv("ANDROID_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = System.getenv("ANDROID_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = System.getenv("ANDROID_UPLOAD_KEY_PASSWORD")
val releaseSigningReady = !uploadKeyStorePath.isNullOrBlank() &&
    File(uploadKeyStorePath).isFile &&
    !uploadStorePassword.isNullOrBlank() &&
    !uploadKeyAlias.isNullOrBlank() &&
    !uploadKeyPassword.isNullOrBlank()

// sherpa-onnx runs the optional offline Bangla voice. The official AAR (Kotlin API + native
// libraries) is a plain GitHub release file, so it is fetched once into app/libs instead of
// being committed to the repository.
val sherpaVersion = "2.0.1"
// SHA-256 of sherpa-onnx-2.0.1.aar. The build refuses a download that does not match, so a
// changed or tampered file can never end up inside the app. Update it together with sherpaVersion.
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile

val fetchSherpaAar by tasks.registering {
    description = "Downloads the sherpa-onnx AAR used by the offline MMS voice."
    outputs.file(sherpaAar)
    onlyIf { !sherpaAar.isFile || sherpaAar.length() < 1_000_000L }
    doLast {
        sherpaAar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        val part = File(sherpaAar.parentFile, sherpaAar.name + ".part")
        URL(url).openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(part.length() > 1_000_000L) { "Download of $url looks incomplete." }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sherpaSha256, ignoreCase = true)) {
            part.delete()
            throw GradleException("sherpa-onnx AAR checksum mismatch: expected $sherpaSha256 but got $actual.")
        }
        check(part.renameTo(sherpaAar)) { "Could not save ${sherpaAar.name}." }
    }
}

tasks.named("preBuild") { dependsOn(fetchSherpaAar) }

android {
    namespace = "org.kontho.pdfreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.kontho.pdfreader"
        minSdk = 23
        targetSdk = 36
        versionCode = 16
        versionName = "0.7.0"
        manifestPlaceholders["appLabel"] = "Kontho PDF Reader"

        // Keep the APK small: real phones are arm64 or 32-bit ARM. x86 is only emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(checkNotNull(uploadKeyStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // Test builds install next to the real app instead of failing on a signature mismatch.
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "Kontho Test"
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            // The AAR ships several ABIs; keep only the filtered ones and compress them.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation(files(sherpaAar).builtBy(fetchSherpaAar))

    // Plain-JVM tests for the Bangla text preparation (run: gradle testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")

    // PdfBox-Android 2.0.27.0 brings Bouncy Castle 1.72 transitively.
    // Keep all three modules on the compatible security-fixed release.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.86")
}
