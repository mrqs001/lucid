plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "lucid.wear"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as the phone app: required by the Wear Data Layer.
        applicationId = "io.github.mrqs001.lucid"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Overnight use runs this build: a debug build runs largely interpreted with JNI checks,
            // which a profile on a Galaxy Watch7 showed as ~70% of the app's CPU time.
            isMinifyEnabled = false
            // Same key as the phone app (debug key on a dev machine): the Wear Data Layer only
            // connects apps with the same package name and signature.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Samsung Health Sensor SDK adapter: compiled only when the AAR from developer.samsung.com is
    // placed in wear/libs/ (it is not on Maven and is not redistributed here).
    val samsungAar = file("libs").listFiles { f -> f.name.startsWith("samsung-health-sensor-api") && f.extension == "aar" }
        ?.firstOrNull()
    if (samsungAar != null) {
        sourceSets["main"].kotlin.directories += "src/samsung/kotlin"
    } else {
        sourceSets["main"].kotlin.directories += "src/nosamsung/kotlin"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:sim"))

    file("libs").listFiles { f -> f.name.startsWith("samsung-health-sensor-api") && f.extension == "aar" }
        ?.firstOrNull()?.let { implementation(files(it)) }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.wear)
    implementation(libs.wear.ongoing)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
