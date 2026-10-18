import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

buildscript {
    dependencies {
        classpath(libs.android.gradlePlugin)
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dependencyLicenseReport)
    alias(libs.plugins.compose.compiler)
}

val licenseResDir = "$projectDir/build/dependency-license-res"

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "org.kde.kdeconnect_tp"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.vivere_181.janus" // ge.connect: io.github.<GitHub user>.janus ("-" isn't allowed in Android ids)
        minSdk = 24 // ge.connect: Material 3 Expressive (1.5) needs 24
        targetSdk = 37
        versionCode = 110000
        versionName = "1.0.0"
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main") {
        res.directories += licenseResDir
        // ge.connect: AirPlay receiving on a TV (src/airplay/README.md): the receiver's prebuilt engine and its Kotlin side.
        jniLibs.directories += "src/airplay/jniLibs"
        kotlin.directories += "src/airplay/java"
    }
    // Branding overlay: build-type source sets override main, so src/brand/res replaces
    // upstream strings/drawables without editing upstream files (keeps merges clean).
    listOf("debug", "release").forEach {
        sourceSets.getByName(it).res.directories += "src/brand/res"
        sourceSets.getByName(it).kotlin.directories += "src/brand/java"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
        // English only: upstream translations are full of "KDE Connect".
        localeFilters += "en"
    }

    packaging {
        resources {
            merges += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE")
        }
        // ge.connect: native libraries compressed in the APK (the AirPlay receiver's are big, and only TVs use them).
        jniLibs { useLegacyPackaging = true }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Personal key for ge.connect (gitignored keystore.properties + signing.keystore).
        // Every build must use the same key, or updates need an uninstall (losing pairing).
        val ownKeyProps = file("keystore.properties")
        if (ownKeyProps.exists()) {
            val props = Properties().apply { ownKeyProps.inputStream().use { load(it) } }
            create("own") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        val signing = signingConfigs.findByName("own") ?: signingConfigs.getByName("debug")
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signing
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signing
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        // When the "Generate Signed APK/Bundle" wizard is used, copy the source map to the output directory
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        // The 'android.injected.apk.location' property is only set when using the wizard
        val apkLocation = providers.gradleProperty("android.injected.apk.location")
        // Plain task with doLast (no declared outputs) so we don't clash with AGP tasks that also write into the
        // destination folder (e.g. createReleaseApkListingFileRedirect writing output-metadata.json).
        val mappingFile = layout.buildDirectory.file("outputs/mapping/$variantName/mapping.txt")
        val nativeSymbolsFile = layout.buildDirectory.file("outputs/native-debug-symbols/$variantName/native-debug-symbols.zip")
        val destDir = apkLocation.map { File(it, variantName) }
        val copyExtras = tasks.register("copySigningExtraOutputs$capitalized") {
            description = "Copies R8 mapping.txt and native-debug-symbols.zip next to the signed $variantName APK/bundle."
            onlyIf { apkLocation.isPresent }
            doLast {
                val dest = destDir.get().apply { mkdirs() }
                val mapping = mappingFile.get().asFile
                if (mapping.exists()) mapping.copyTo(File(dest, "mapping.txt"), overwrite = true)
                val symbols = nativeSymbolsFile.get().asFile
                if (symbols.exists()) symbols.copyTo(File(dest, "native-debug-symbols.zip"), overwrite = true)
            }
        }
        tasks.matching { it.name == "assemble$capitalized" || it.name == "bundle$capitalized" }
            .configureEach { finalizedBy(copyExtras) }
    }
}

dependencies {
    // Newer Java APIs (java.time, java.nio.file) on older Android versions.
    coreLibraryDesugaring(libs.android.desugarJdkLibsNio)

    // Compose
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget) // ge.connect: home-screen widget
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime) // ge.connect: Glance's own WorkManager 2.7 breaks under R8 (WorkDatabase)
    implementation(libs.androidx.camera.camera2) // ge.connect: remote camera (phone ↔ phone)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core) // ge.connect: scanning the Mac's pairing QR code (R41)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process) // ProcessLifecycleOwner (was in the deprecated lifecycle-extensions)
    implementation(libs.androidx.gridlayout)
    implementation(libs.google.android.material)
    implementation(libs.disklrucache) //For caching album art bitmaps. FIXME: Not updated in 10+ years. Replace with Kache.

    implementation(libs.bcpkix.jdk18on) //For SSL certificate generation

    // The android-smsmms library is the only way I know to handle MMS in Android
    // (Shouldn't a phone OS make phone things easy?)
    // This library was originally authored as com.klinkerapps at https://github.com/klinker41/android-smsmms.
    // However, that version is under-loved. I have therefore made "some fixes" and published it.
    // Please see https://invent.kde.org/sredman/android-smsmms/-/tree/master
    implementation(files("libs/android-smsmms/android-smsmms.aar")) // ge.connect: vendored (libs/android-smsmms/README.md)
    implementation(libs.okhttp2) // what android-smsmms needs (its own dependencies; a local .aar doesn't bring them)
    implementation(libs.okhttp2.urlconnection)
    implementation(libs.logger)

    implementation(libs.commons.io)
    implementation(libs.commons.collections4)
    implementation(libs.commons.lang3)

    implementation(libs.univocity.parsers)
    implementation(libs.okhttp) // ge.connect: Janus Cloud relay (HTTP/2 + WebSocket, R36)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.jsonassert)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)

    // For device controls
    implementation(libs.reactive.streams)
    implementation(libs.rxjava)
}

licenseReport {
    configurations = LicenseReportExtension.ALL
    renderers = arrayOf<ReportRenderer>(TextReportRenderer())
}

tasks.named("generateLicenseReport") {
    val outputFile = file("$licenseResDir/raw/license")
    val inputFiles = files(
        layout.projectDirectory.file("src/brand/NOTICE.txt"),
        layout.projectDirectory.file("LICENSES/GPL-3.0-only.txt"), // ge.connect: Janus is published under GPL-3.0
        layout.projectDirectory.file("src/brand/OFL-GoogleSansFlex.txt"),
        layout.buildDirectory.file("reports/dependency-license/THIRD-PARTY-NOTICES.txt")
    )
    inputs.files("src/brand/NOTICE.txt", "LICENSES/GPL-3.0-only.txt", "src/brand/OFL-GoogleSansFlex.txt")
    outputs.file(outputFile)
    doLast {
        outputFile.apply {
            parentFile.mkdirs()
            writeText(inputFiles.joinToString(separator = "\n" + "-".repeat(80) + "\n") { it.readText() })
        }
    }
}

tasks.named("preBuild") {
    dependsOn("generateLicenseReport")
}
