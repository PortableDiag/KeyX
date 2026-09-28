plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keyx.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keyx.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.2"

        vectorDrawables.useSupportLibrary = true
    }

    // There is deliberately NO signingConfig here, and no .jks in this repo.
    //
    // Release artifacts are signed by keystore-manager, which never hands out a
    // key: `pf sign app-release-unsigned.apk --key <record>`. A signing config
    // in Gradle means a keystore and its passwords have to exist somewhere a
    // build can read them, and "somewhere a build can read them" has meant
    // committed to the repo more than once.
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // Lets a debug build sit alongside a release install. The smoke
            // gate reads the real id back out of the APK with aapt2, so the
            // suffix does not have to be remembered anywhere.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // A lint gate that only warns is a lint gate that is never read.
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub under unit tests; the parsers need the real one.
    testImplementation("org.json:json:20231013")
}
