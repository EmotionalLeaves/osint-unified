plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.osint.unified"
    // Compiled against 35, still targeting 34. Raising compileSdk makes
    // the newer APIs and the newer lint available; raising targetSdk is
    // a different decision, because Android 15 then forces every window
    // edge to edge and this app's screens would start under the status
    // bar. That is a change somebody has to look at on a phone, and
    // nothing here can.
    compileSdk = 35

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
            // Off here, on purpose. R8 rewrites reflection-dependent code
            // — Room, kotlinx.serialization, reflective overlay loading —
            // and the failures it causes appear at runtime on a device,
            // not in a build log. Nothing in this project can run the app,
            // so a shrunk build cannot be proved to work from here.
            //
            // It is built anyway, as `shrunk` below, and uploaded beside
            // this one. The default download stays the build whose
            // behaviour is known; the smaller one is there to be tried,
            // and if it misbehaves this one is in the same run.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        /**
         * The same app, shrunk.
         *
         * Same signing key and same applicationId, so it installs over
         * the unshrunk build and keeps the case database — and so only
         * one of the two can be on the phone at a time, which is what
         * makes it a fair test rather than a curiosity.
         *
         * Everything R8 must not touch is in proguard-rules.pro, and
         * every rule in it names the thing that finds that class by name
         * at runtime. If this build assembles and then behaves, the
         * unshrunk one can go away.
         */
        create("shrunk") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("shared")
            // initWith already copied release's proguardFiles; listing
            // them again would feed R8 the same rules twice.
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    /**
     * One check turned off, and the reason.
     *
     * ProduceStateDoesNotAssignValue arrived with the API 35 lint and
     * fires on all six produceState calls in this app, every one of
     * which is the same shape:
     *
     *     val x by produceState(initial, key) {
     *         value = withContext(Dispatchers.Default) { ... }
     *     }
     *
     * The value is assigned, directly, in the producer lambda. The check
     * does not see it through the delegated property, so it is wrong
     * about this code — and it is an error rather than a warning, so it
     * fails the build. Six correct call sites are not going to be
     * contorted to satisfy it, and leaving the whole lint gate off to
     * get past one false positive would throw away the checks that have
     * actually caught things here.
     */
    lint {
        disable += "ProduceStateDoesNotAssignValue"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
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
    // On-device text recognition, for reading the words in a
    // photograph. The bundled variant rather than the Play-services
    // one: the model ships inside the APK, so it works with the
    // radio off and the picture never leaves the phone — which
    // matters more here than the size, and size does not matter
    // here at all.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // osmdroid is gone. It existed to draw the flat map, which
    // existed because the bundled whole-Earth mosaic runs out of
    // pixels at about a large country across — so the globe fetches
    // the same satellite tiles itself now and there is one view
    // instead of two.
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
    // Real SQLite on the JVM, so the migration chain can be replayed
    // without a device. Room's own MigrationTestHelper needs an
    // instrumented test and an emulator; the only compiler this project
    // has is a CI runner with neither. Test-only: nothing in the APK.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
