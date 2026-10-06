plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.novareader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novareader.app"
        minSdk = 26   // WebViewAssetLoader и современный TextToSpeech требуют 26+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0") // WebViewAssetLoader
    implementation("androidx.activity:activity-ktx:1.9.0") // registerForActivityResult
    implementation("androidx.recyclerview:recyclerview:1.3.2") // экран библиотеки
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.media:media:1.7.0") // MediaSessionCompat + MediaStyle notification для TtsPlaybackService

    // Онлайн-поиск книг (порт book_search_window.py)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation ("androidx.documentfile:documentfile:1.0.1")
}
