// `java` inside an `android` block is Gradle's own extension, not the package.
import java.util.Base64

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// The same signature on every build, where CI has been given one.
//
// Without this the APK is signed with a debug key Gradle mints inside the build
// container and throws away with it, so every build has a different signature
// and Android refuses to install one over the last -- which means uninstalling,
// and losing the session and the cache, to try any change on a real phone.
//
// A key for sideloading and nothing more: when there are stable releases they
// get one of their own. Absent the secrets -- in a fork, or on a laptop -- the
// ordinary debug key is used and everything still builds.
private val keystoreBase64 = providers.environmentVariable("STRATUS_KEYSTORE_BASE64")
private val keystorePassword = providers.environmentVariable("STRATUS_KEYSTORE_PASSWORD")

// The Play upload key, a second one on purpose: the sideloading key is in every
// CI run, and this one only in the release workflow (stratus-app#91). Play
// re-signs what it is given with a key of its own, so losing this one is a
// reset requested from Google rather than the end of the listing.
private val uploadKeystoreBase64 = providers.environmentVariable("STRATUS_UPLOAD_KEYSTORE_BASE64")
private val uploadKeystorePassword = providers.environmentVariable("STRATUS_UPLOAD_KEYSTORE_PASSWORD")

// Play refuses a versionCode it has seen, so the release workflow hands in its
// run number; anything else builds as 1.
private val releaseVersionCode = providers.environmentVariable("STRATUS_VERSION_CODE")
private val releaseVersionName = providers.environmentVariable("STRATUS_VERSION")

android {
    namespace = "dev.stratus.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stratus.app"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode.orNull?.toInt() ?: 1
        versionName = releaseVersionName.orNull ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every test starts from an app that has never run: no instance, no
        // cache, no permission. `pm clear` would kill the process the test runs
        // in, so the orchestrator does it between tests instead.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        // The whole app against a real server (stratus-app#78). The same image
        // as :core's device tests, so the cache the Makefile mounts serves both.
        managedDevices {
            localDevices.create("emulator") {
                device = "Pixel 6"
                apiLevel = 34
                systemImageSource = "aosp-atd"
                testedAbi = "x86_64"
            }
        }
    }

    if (keystoreBase64.isPresent && keystorePassword.isPresent) {
        val keystore = layout.buildDirectory.file("signing/stratus.jks").get().asFile
        keystore.parentFile.mkdirs()
        keystore.writeBytes(Base64.getDecoder().decode(keystoreBase64.get()))

        signingConfigs.create("sideloaded") {
            storeFile = keystore
            storePassword = keystorePassword.get()
            keyAlias = "stratus-debug"
            keyPassword = keystorePassword.get()
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("sideloaded")
    }

    if (uploadKeystoreBase64.isPresent && uploadKeystorePassword.isPresent) {
        val keystore = layout.buildDirectory.file("signing/upload.jks").get().asFile
        keystore.parentFile.mkdirs()
        keystore.writeBytes(Base64.getDecoder().decode(uploadKeystoreBase64.get()))

        signingConfigs.create("upload") {
            storeFile = keystore
            storePassword = uploadKeystorePassword.get()
            keyAlias = "upload"
            keyPassword = uploadKeystorePassword.get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("upload")
    }
}

// Two halves of sentry-android left out of the APK: session replay records the
// screen, which here is somebody's photographs, and the NDK handler is for
// native code this app does not have.
configurations.configureEach {
    exclude(group = "io.sentry", module = "sentry-android-replay")
    exclude(group = "io.sentry", module = "sentry-android-ndk")
}

dependencies {
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Named rather than inherited: the suite presses buttons by the constants
    // the bar draws them from (stratus-app#137), so it imports from :ui.
    androidTestImplementation(project(":ui"))
    // For arranging and checking the server behind the app's back, with the
    // app's own WebDAV client rather than a second one to keep in step.
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.core)
}
