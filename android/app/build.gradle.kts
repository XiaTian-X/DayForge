import java.util.Properties
import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// This is a separate measurement diagnostic, never a coverage gate or release benchmark.
val deviceTestCoverageCalibration = providers.gradleProperty("dayforge.deviceTestCalibration").map { value ->
    require(value == "coverage-off") { "Only coverage-off is a supported DayForge device test calibration" }
    true
}.orElse(false)

// Explicit opt-in; ordinary physical verification remains the covered deviceTest variant.
val calibrationMode = providers.gradleProperty("dayforge.calibration").map { value ->
    require(value in setOf("release", "debug-real")) { "Unknown DayForge calibration mode" }
    value
}.orElse("none")
val releaseCalibration = calibrationMode.map { it == "release" }
// Explicit phase attribution only. Never enables diagnostics in the shipping release.
val releaseTimingDiagnostics = providers.gradleProperty("dayforge.releaseTimingDiagnostics").map { value ->
    require(value in setOf("true", "false")) { "Release timing diagnostics must be exactly true or false" }
    value == "true"
}.orElse(false)
require(!releaseTimingDiagnostics.get() || releaseCalibration.get()) {
    "Release timing diagnostics require explicit dayforge.calibration=release"
}
require(calibrationMode.get() != "debug-real" || deviceTestCoverageCalibration.get()) {
    "Real-worker debug calibration requires explicit coverage-off"
}
require(!releaseCalibration.get() || !deviceTestCoverageCalibration.get()) {
    "Select either release calibration or deviceTest coverage-off, never both"
}

android {
    namespace = "com.dayforge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.dayforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0"
        buildConfigField("boolean", "RELEASE_TIMING_DIAGNOSTICS", "false")

        testInstrumentationRunner = if (calibrationMode.get() == "none") "com.dayforge.HiltTestRunner"
            else "com.dayforge.RealWorkerCalibrationRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "150000"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = localProperties.getProperty("release.storePassword")
                ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = localProperties.getProperty("release.keyAlias")
                ?: System.getenv("KEY_ALIAS")
            keyPassword = localProperties.getProperty("release.keyPassword")
                ?: System.getenv("KEY_PASSWORD")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testBuildType = if (releaseCalibration.get()) "releaseCalibration" else "deviceTest"

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // Online version advisories are not reproducible across developer and CI caches.
        // Locked dependency upgrades are reviewed explicitly under the toolchain roadmap.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = true
        }
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += "debug"
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = !deviceTestCoverageCalibration.get()
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        create("releaseCalibration") {
            initWith(getByName("release"))
            applicationIdSuffix = ".testbed"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            matchingFallbacks += "release"
            buildConfigField("boolean", "RELEASE_TIMING_DIAGNOSTICS", releaseTimingDiagnostics.get().toString())
        }
    }

    sourceSets {
        if (calibrationMode.get() == "debug-real") {
            getByName("deviceTest").manifest.srcFile("src/realWorkerDeviceTest/AndroidManifest.xml")
        }
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("androidTest").assets.srcDir(rootProject.file("../contracts"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    // Keep the existing Java generator; a Kotlin-generation migration needs separate DAO review.
    arg("room.generateKotlin", "false")
}

check(fileTree("src") { include("test*/**/*.kt", "test*/**/*.java") }.isEmpty) {
    "Android tests must live in src/androidTest and run on an authorized physical device."
}

androidComponents {
    // All historical tests now live in androidTest and run only on physical devices.
    // Do not create an empty JVM variant that could be mistaken for behavior validation.
    beforeVariants { variant ->
        variant.hostTests.values.forEach { it.enable = false }
    }

    listOf("deviceTest", "releaseCalibration").forEach { buildType ->
        onVariants(selector().withBuildType(buildType)) { variant ->
            // Retain all notices from the instrumentation-only MockK/JUnit dependencies.
            variant.androidTest?.packaging?.resources?.merges?.addAll(
                "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.core.ktx)
    implementation(libs.appcompat)

    // Compose BOM for version alignment
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.material3)
    implementation(libs.material3WindowSizeClass)
    // Only the independent pre-#243 palette regression fixture uses the generator.
    androidTestImplementation(libs.material.color.utilities)

    // Vico chart library
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ViewModel & Lifecycle
    implementation(libs.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // DataStore for preferences
    implementation(libs.datastore.preferences)

    // Retrofit & OkHttp for networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Glance for widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)


    // WorkManager for scheduled tasks
    implementation(libs.work.runtime.ktx)

    // Physical-device instrumentation testing
    debugImplementation(libs.ui.test.manifest)
    add("deviceTestImplementation", libs.ui.test.manifest)
    add("releaseCalibrationImplementation", libs.ui.test.manifest)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.mockk.android) {
        // Explicit real-worker calibration never loads the Android JVMTI agent.
        // Keep the default MockK test dependency graph unchanged.
        if (calibrationMode.get() != "none") {
            exclude(group = "io.mockk", module = "mockk-agent-android")
        }
    }
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
}

// Targeted capacity is chosen before launch, with the same bounded value as the Python owner.
// Direct runs retain 2700 seconds. This never changes the 150000ms per-case deadline.
val targetedDeviceTestTimeoutSeconds = providers.gradleProperty("dayforge.targetedTimeoutSeconds").map { value ->
    require(value.matches(Regex("[1-9][0-9]{3,4}"))) { "Targeted timeout must be an integer from 2700 to 10800" }
    value.toLong().also { require(it in 2700L..10800L) { "Targeted timeout must be an integer from 2700 to 10800" } }
}.orElse(2700L)
val physicalDeviceTestTimeoutSeconds = providers.gradleProperty("dayforge.deviceTestScope").map { scope ->
    when (scope) {
        "full" -> 10800L
        "targeted" -> targetedDeviceTestTimeoutSeconds.get()
        else -> error("Unknown DayForge device test scope")
    }
}.orElse(2700L)

tasks.matching { it.name == "connectedDeviceTestAndroidTest" }.configureEach {
    timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(it) })
}

tasks.matching { it.name == "connectedReleaseCalibrationAndroidTest" }.configureEach {
    timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(it) })
}
