plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.castcharm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.castcharm.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 15
        versionName = "1.6.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    debugImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Room
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Retrofit + OkHttp
    val retrofitVersion = "2.10.0"
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-moshi:$retrofitVersion")
    val okHttpVersion = "4.12.0"
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")

    // Moshi (JSON)
    val moshiVersion = "1.15.0"
    implementation("com.squareup.moshi:moshi-kotlin:$moshiVersion")

    // Media3 / ExoPlayer
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // Legacy Media support for constants
    implementation("androidx.media:media:1.7.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coil (image loading)
    val coilVersion = "2.7.0"
    implementation("io.coil-kt:coil-compose:$coilVersion")
    implementation("io.coil-kt:coil:$coilVersion")

    // Extended Material Icons (Visibility, SkipNext, etc.)
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    val coroutinesVersion = "1.9.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // DataStore (for SharedPreferences replacement)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Guava (for Futures)
    implementation("com.google.guava:guava:32.1.3-android")

    // Drag-to-reorder for LazyColumn (used in PlaylistDetailScreen)
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
