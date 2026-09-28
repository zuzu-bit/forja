plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    // Capturile de ecran pe JVM (./gradlew :app:recordRoborazziDebug → app/build/outputs/roborazzi/*.png).
    // Adaugă doar sarcini de test; assembleDebug nu le atinge.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.forja.app"
    compileSdk = 35

    defaultConfig {
        // Același pachet ca aplicația de pe telefon (3.7-online.xx) → 4.0 se instalează PESTE ea.
        applicationId = "com.forja.app.research"
        minSdk = 26
        targetSdk = 35
        versionCode = 66
        versionName = "4.4"
        vectorDrawables { useSupportLibrary = true }
        // MapLibre aduce libmaplibre.so (~10-13 MB per ABI): doar ARM, fără x86 (emulatoarele x86 nu sunt ținta noastră).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Serverul central FORJA — injectat de CI după deploy; implicit = worker-ul deja publicat.
        val apiUrl = System.getenv("FORJA_API_URL")?.takeIf { it.isNotBlank() } ?: "https://forja-api.forja-22e7ea2d.workers.dev"
        buildConfigField("String", "FORJA_API_URL", "\"$apiUrl\"")
        // Site-ul FORJA (panoul online) — worker-ul forja-insights.
        buildConfigField("String", "INSIGHTS_URL", "\"https://forja-insights.forja-22e7ea2d.workers.dev\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // Robolectric vede resursele aplicației (fonturile FORJA) — doar pentru testele JVM.
            isIncludeAndroidResources = true
            all {
                // Randare „hardware” la PixelCopy: umbre, straturi și decupaje ca pe telefon.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // ~500 de teste Robolectric + planșe mari (casca_notificari): 512 MB implicit nu ajung.
                it.maxHeapSize = "4g"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work)

    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation(libs.play.services.location)
    implementation(libs.maplibre)
    implementation(libs.mlkit.barcode)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Verificarea vizuală (app/src/test/.../screenshots, workflow ui-shots.yml) — doar teste, nimic în APK.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
