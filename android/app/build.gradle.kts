import java.util.Properties

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
    jacoco
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        finalizedBy("jacocoTestReport")
    }
}

tasks.register("jacocoTestReport", JacocoReport::class) {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required = true
        html.required = true
    }

    classDirectories.setFrom(
        fileTree("${buildDir}/intermediates/javac/debug/classes") {
            exclude(
                "**/R.class",
                "**/R\$*.class",
                "**/BuildConfig.class",
                "**/Manifest*.class",
                "**/*_Factory.class",
                "**/*_MembersInjector.class",
                "**/Hilt_*.class",
                "**/dagger/hilt/internal/**/*.class"
            )
        }
    )

    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir).include("jacoco/testDebugUnitTest.exec"))
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

    // LocalBroadcastManager for widget updates
    implementation(libs.localbroadcastmanager)

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
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
}
