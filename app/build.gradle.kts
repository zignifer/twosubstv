plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.twosub.tv"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "app.twosub.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val localKey = rootProject.file(".local-signing/debug.keystore")
            if (localKey.exists()) storeFile = localKey
        }
        val releaseStore = providers.environmentVariable("TWOSUB_KEYSTORE").orNull
        if (!releaseStore.isNullOrBlank()) {
            create("publicRelease") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("TWOSUB_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TWOSUB_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TWOSUB_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Isolated device QA never touches the user's existing session.
            if (providers.gradleProperty("isolatedQa").orNull == "true") applicationIdSuffix = ".qa"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("publicRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
