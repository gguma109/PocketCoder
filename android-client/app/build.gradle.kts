plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketcoder.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketcoder.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "0.9.6"
    }

    buildFeatures { buildConfig = true }
    signingConfigs {
        create("distribution") {
            val path = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "pocketcoder"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir("../../shared/src/main/kotlin")
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // zxing-android-embedded calls ContextCompat when the scanner opens but does not ship androidx.core.
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
