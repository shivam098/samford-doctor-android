plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Baked-in discovery document URL (see gradle.properties). Testers can still override it in the app.
val discoveryUrl: String = providers.gradleProperty("samford.discoveryUrl").orElse("").get()

// Release signing comes only from the local environment (scripts/build-release.ps1 decrypts the
// DPAPI-protected passwords into process-scoped variables). Nothing secret lives in the repo.
val keystoreFile: String? = providers.environmentVariable("SAMFORD_KEYSTORE_FILE").orNull
val keystorePassword: String? = providers.environmentVariable("SAMFORD_KEYSTORE_PASSWORD").orNull
val keyAliasName: String = providers.environmentVariable("SAMFORD_KEY_ALIAS").orElse("samford-doctor").get()
val keyPasswordValue: String? = providers.environmentVariable("SAMFORD_KEY_PASSWORD").orNull
val hasReleaseSigning = keystoreFile != null && file(keystoreFile).isFile && keystorePassword != null

android {
    namespace = "com.samford.doctor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.samford.doctor"
        // Galaxy Tab S-series tablets with S Pen run Android 13+; API 33 also gives per-app
        // language (LocaleManager) and FLAG_CANCELED palm-rejection signals without compat shims.
        minSdk = 33
        // 36 deliberately: API 37 adds local-network permission rules that would block testers
        // reaching a mock backend on their LAN. Revisit when the backend is on public HTTPS.
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISCOVERY_URL", "\"$discoveryUrl\"")
        buildConfigField("String", "INK_FORMAT", "\"samford-ink/1\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.graphicsMode", "NATIVE")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Integration tests against the real mock backend run only when this is set.
                System.getenv("SAMFORD_IT_API_BASE")?.let { test.systemProperty("samford.it.apiBase", it) }
                System.getenv("SAMFORD_SEED_OUT")?.let { test.systemProperty("samford.seed.out", it) }
                System.getenv("SAMFORD_E2E_OUT")?.let { test.systemProperty("samford.e2eOut", it) }
                test.maxHeapSize = "3g"
            }
        }
        animationsDisabled = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Version-bump and targetSdk advisories are tracked manually (see README "Stack").
        // Stylistic KTX suggestions; ObsoleteLintCustomCheck comes from a library's bundled lint jar.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion", "UseKtx", "ObsoleteLintCustomCheck")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.input.motionprediction)
    implementation(libs.profileinstaller)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
