plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.deepspace"
    compileSdk = 36

    defaultConfig {
        // applicationId harus UNIK per perangkat dan kini menandai aplikasi AR-only
        // (mode VR/Cardboard sudah dihapus). namespace di atas sengaja TIDAK diubah karena
        // itu nama package kode (nama kelas + simbol JNI) yang di-hardcode di source.
        // Otoritas FileProvider memakai ${applicationId}.fileprovider, jadi otomatis ikut.
        applicationId = "com.deepspace.ar"
        minSdk = 26  // komponen render native (SceneView/Filament/OpenCV) butuh API 26+
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // useLegacyPackaging = true: OpenCV memuat .so-nya lewat jalur ekstraksi klasik,
        // jadi library native harus tetap terkompresi di dalam APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    val cameraXVersion = "1.3.4"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("org.opencv:opencv:4.10.0")

    // Proper ARCore Sceneview
    implementation("io.github.sceneview:arsceneview:2.3.3")

    // Non-AR SceneView — dipakai layout/pemakaian internal arsceneview.
    implementation("io.github.sceneview:sceneview:2.3.3")

    // Google Filament — render 3D native langsung dari halaman AR (tanpa SceneView).
    // Pinned to 1.68.2 to match the Filament runtime SceneView 2.3.3 pulls in —
    // filamat MUST match or material version mismatches crash at load.
    implementation("com.google.android.filament:filament-android:1.68.2")
    implementation("com.google.android.filament:gltfio-android:1.68.2")
    // Runtime material compiler — dipakai DecalMaterial (sticker/decal halaman AR)
    // supaya tidak perlu mengirim aset .filamat.
    implementation("com.google.android.filament:filamat-android:1.68.2")

    testImplementation("junit:junit:4.13.2")
    // org.json di android.jar hanya stub; implementasi asli dibutuhkan agar jalur
    // GlbBuilder/GlbReader bisa diuji sebagai unit test JVM.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

