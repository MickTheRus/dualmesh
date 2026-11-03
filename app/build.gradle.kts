import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep private signing material outside the repository. Without this variable, CI
// continues to build an unsigned release APK.
val releaseSigningProperties = providers.environmentVariable("DUALMESH_SIGNING_PROPERTIES").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "io.dualmesh.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.dualmesh.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningProperties != null) {
            create("production") {
                fun signingValue(name: String) = requireNotNull(releaseSigningProperties.getProperty(name)) {
                    "Missing release signing property: $name"
                }
                storeFile = file(signingValue("storeFile"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (releaseSigningProperties != null) signingConfig = signingConfigs.getByName("production")
        }
        // Documentation fixtures never ship in debug/release.
        create("screenshots") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".screenshots"
            versionNameSuffix = "-screenshots"
            matchingFallbacks += "debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.meshtastic:protobufs:2.7.26")
    implementation("org.maplibre.gl:android-sdk:13.2.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
