plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.osint.unified"
    compileSdk = 34

    // CI passes -PosintVersionCode / -PosintVersionName so every build is a
    // distinct, installable version (same versionCode blocks updates).
    val verCode = (project.findProperty("osintVersionCode") as String?)?.toIntOrNull() ?: 1
    val verName = (project.findProperty("osintVersionName") as String?) ?: "dev"

    /**
     * One signing key, for every build, forever.
     *
     * Android refuses an update signed by a different key. Every CI runner
     * used to generate its own throwaway debug keystore, so installing a
     * new build meant uninstalling the old one — which deleted every
     * investigation and every watch. That made the app unusable for work
     * that lasts longer than one release.
     *
     * The keystore in `keystore/` is committed deliberately and its
     * password is in this file. It is not a secret and is not pretending to
     * be one: anyone can sign an APK that this device will accept as an
     * update, so only install builds from your own Actions runs. To use a
     * real private key instead, set the OSINT_KEYSTORE_B64,
     * OSINT_KEYSTORE_PASSWORD, OSINT_KEY_ALIAS and OSINT_KEY_PASSWORD
     * repository secrets; CI decodes them and this block prefers them.
     */
    signingConfigs {
        create("shared") {
            val fromSecret = rootProject.file("keystore/ci.jks")
            val committed = rootProject.file("keystore/osint-unified.jks")
            if (fromSecret.exists()) {
                storeFile = fromSecret
                storePassword = System.getenv("OSINT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OSINT_KEY_ALIAS")
                keyPassword = System.getenv("OSINT_KEY_PASSWORD")
            } else {
                storeFile = committed
                storePassword = "osint-unified"
                keyAlias = "osint-unified"
                keyPassword = "osint-unified"
            }
        }
    }

    defaultConfig {
        applicationId = "com.osint.unified"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = verName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The fingerprint of the key that is committed to this repository.
        // The app compares its own signing certificate against it and says
        // so when they match, because a published private key means
        // anyone can install an app with this identity — and the database
        // encryption rests on exactly that not being possible.
        buildConfigField(
            "String",
            "PUBLIC_KEY_SHA256",
            "\"34277c715bd7124b541c048915dd3bfb5ab065026aec3106e6271b5f85307a51\""
        )
    }

    buildTypes {
        debug {
            // Signed with the shared key so a new build installs over the
            // last one instead of forcing an uninstall.
            signingConfig = signingConfigs.getByName("shared")
            // A debuggable build lets anyone with the phone read the case
            // database over adb. There is no reason for this app ever to
            // ship one, so even the debug type is not debuggable.
            isDebuggable = false
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            // Off, deliberately, and this is the honest reason: nothing
            // here can run the app. R8 rewrites reflection-dependent code
            // — Room, kotlinx.serialization, osmdroid's overlay loading —
            // and the failures it causes appear at runtime on a device,
            // not in a build log. Shipping a shrunk build that cannot be
            // tested is trading a few megabytes for the chance that a
            // screen is blank for reasons nobody can see from here.
            // Turning it on wants one person with the APK and ten minutes.
            isMinifyEnabled = false
            isShrinkResources = false
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Keep bundled tool binaries uncompressed + extracted so they can be
        // executed from the app's nativeLibraryDir at runtime.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.35")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.browser:browser:1.8.0")
    // So the in-app browser can be put through the same proxy as
    // everything else. A WebView has its own networking and ignores the
    // platform's proxy selector, which would have made the one screen
    // that loads a target's own pages the one that went direct.
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // The case database is encrypted at rest. An investigation holds
    // who was looked at and everything every source said about them, and
    // until now that sat in a plain SQLite file.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 drags in fragment 1.2.x, which predates the
    // ActivityResult contracts this activity registers — lint calls that
    // fatal and it is right to: the permission request would not come
    // back. Pinned forward.
    implementation("androidx.fragment:fragment-ktx:1.8.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
