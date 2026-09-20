import java.util.Properties
import java.time.Duration

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

        testInstrumentationRunner = "com.dayforge.HiltTestRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "150000"
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

    testBuildType = "deviceTest"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

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
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += "debug"
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDir(rootProject.file("../contracts"))
        getByName("test").resources.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    timeout.set(Duration.ofMinutes(15))
    if (providers.environmentVariable("CI").orNull == "true") {
        testLogging.events("started", "failed", "skipped")
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
    implementation(libs.material.color.utilities)

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

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    add("deviceTestImplementation", libs.ui.test.manifest)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
}

// Bound physical-device runs too; a hung instrumentation process is a failure.
tasks.matching { it.name == "connectedDeviceTestAndroidTest" }.configureEach {
    timeout.set(Duration.ofMinutes(15))
}
