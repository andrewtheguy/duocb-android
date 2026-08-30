import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// libduocb.so delivery.
//
// Default: download the pinned duocb release zip (libduocb-android.zip, the
// jniLibs/<abi>/libduocb.so tree built by ../duocb/build-android.sh) by
// URL + sha256 into build/, mirroring how duocb-ios pins its xcframework.
// Local FFI dev: DUOCB_LOCAL_JNILIBS=1 (exactly) points the jniLibs source set
// at ../duocb/dist/android/jniLibs instead. Any other value selects the release.
val duocbLocalJniLibs = System.getenv("DUOCB_LOCAL_JNILIBS") == "1"
val duocbReleaseTag = providers.gradleProperty("duocb.releaseTag").get()
val duocbReleaseSha256 = providers.gradleProperty("duocb.releaseSha256").getOrElse("")
val duocbReleaseUrl =
    "https://github.com/andrewtheguy/duocb/releases/download/$duocbReleaseTag/libduocb-android.zip"
val duocbLocalDir = rootProject.file("../duocb/dist/android/jniLibs")
val duocbFetchedDir = layout.buildDirectory.dir("duocb-jnilibs")
val duocbJniLibsDir: File =
    if (duocbLocalJniLibs) duocbLocalDir else duocbFetchedDir.get().asFile.resolve("jniLibs")

val fetchDuocbJniLibs = tasks.register("fetchDuocbJniLibs") {
    description = "Downloads the pinned libduocb-android.zip release and verifies its sha256."
    val url = duocbReleaseUrl
    val sha256 = duocbReleaseSha256
    val outDir = duocbFetchedDir
    val skip = duocbLocalJniLibs
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.dir(outDir)
    onlyIf { !skip }
    doLast {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "duocb.releaseSha256 is not set in gradle.properties: run " +
                "scripts/bump-jnilibs.sh <tag> to pin a published duocb release, " +
                "or build ../duocb with ./build-android.sh and set DUOCB_LOCAL_JNILIBS=1."
        }
        val dir = outDir.get().asFile
        val stamp = dir.resolve("sha256.txt")
        if (stamp.isFile && stamp.readText().trim() == sha256 &&
            dir.resolve("jniLibs").isDirectory
        ) {
            return@doLast
        }
        dir.deleteRecursively()
        dir.mkdirs()
        logger.lifecycle("Downloading $url")
        val bytes = URI(url).toURL().openConnection().run {
            connectTimeout = 30_000
            readTimeout = 120_000
            getInputStream().use { it.readBytes() }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == sha256) {
            "sha256 mismatch for $url: expected $sha256, got $actual"
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = dir.resolve(entry.name).canonicalFile
                require(target.path.startsWith(dir.canonicalPath)) { "zip entry escapes dir: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
            }
        }
        require(dir.resolve("jniLibs").isDirectory) { "zip did not contain a jniLibs/ tree" }
        stamp.writeText(sha256)
    }
}

android {
    namespace = "com.andrewtheguy.duocb"
    // Compose 1.12 needs the API 37 platform to compile against; that says
    // nothing about the *target* SDK below, which is what gates behaviour.
    compileSdk = 37

    defaultConfig {
        // The JNI symbols in libduocb.so (duocb/crates/duocb-ffi/src/android.rs)
        // are bound to the class com.andrewtheguy.duocb.DuocbNative; the
        // applicationId can change, the package of that class cannot.
        applicationId = "com.andrewtheguy.duocb"
        minSdk = 29
        // Android 17. From this target on, Android gates every local-network
        // socket (the in-process mDNS the core relies on, the unicast side
        // channel, a direct LAN path) behind the ACCESS_LOCAL_NETWORK runtime
        // permission, which the app declares and asks for before a session on
        // a LAN channel starts (LocalNetworkPermission, SessionController).
        // Below API 37 no such permission exists and INTERNET grants local
        // access implicitly, so the ask is API-gated, not target-gated.
        targetSdk = 37
        versionCode = providers.gradleProperty("duocb.versionCode").get().toInt()
        versionName = providers.gradleProperty("duocb.versionName").get()

        // arm64 only (Google Play's 64-bit requirement; 32-bit devices are not
        // supported). Only lib/arm64-v8a/libduocb.so from the core zip is
        // packaged, so the APK refuses to install on any other ABI.
        ndk { abiFilters.add("arm64-v8a") }
    }

    // Release signing comes from the environment (scripts/build-release-apk.sh
    // sets it up): DUOCB_KEYSTORE (path), DUOCB_KEYSTORE_PASSWORD, optional
    // DUOCB_KEY_ALIAS (default "duocb") and DUOCB_KEY_PASSWORD (defaults to the
    // keystore password). Without DUOCB_KEYSTORE, assembleRelease produces an
    // unsigned APK (app-release-unsigned.apk) that no device will install.
    val releaseKeystore = System.getenv("DUOCB_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("DUOCB_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
                ?: error("DUOCB_KEYSTORE is set but DUOCB_KEYSTORE_PASSWORD is not")
            keyAlias = System.getenv("DUOCB_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "duocb"
            keyPassword = System.getenv("DUOCB_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets["main"].jniLibs.srcDir(duocbJniLibsDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The .so is built against the NDK with 16 KiB page alignment; keep it
        // uncompressed and aligned as the system expects.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(fetchDuocbJniLibs)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
