plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sampleAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val sampleBannerId = "ca-app-pub-3940256099942544/6300978111"
val configuredAdMobAppId = providers.gradleProperty("ADMOB_APP_ID").orElse(sampleAdMobAppId)
val configuredBannerId = providers.gradleProperty("ADMOB_BANNER_ID").orElse(sampleBannerId)

android {
    namespace = "com.n9nik.audiocutter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.n9nik.audiocutter"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = configuredAdMobAppId.get()
        buildConfigField("String", "ADMOB_BANNER_ID", "\"${configuredBannerId.get()}\"")
    }

    signingConfigs {
        create("release") {
            // Env-based signing, no hardcoded secrets
            val keystorePath = System.getenv("UPLOAD_KEYSTORE_PATH") ?: "app/upload-keystore.jks"
            val keystoreFile = rootProject.file(keystorePath)
            val storePasswordEnv = System.getenv("UPLOAD_KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("UPLOAD_KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("UPLOAD_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")

            // Only configure signing when secrets are present; otherwise leave unsigned.
            if (storePasswordEnv != null && keyAliasEnv != null && keyPasswordEnv != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            val keystorePath = System.getenv("UPLOAD_KEYSTORE_PATH") ?: "app/upload-keystore.jks"
            val keystoreFile = rootProject.file(keystorePath)
            val hasSecrets = (System.getenv("UPLOAD_KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")) != null
            if (keystoreFile.exists() && hasSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Minification stays OFF: TinyPic 1.0.0 with R8 crashed on a real device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val verifyReleaseAds by tasks.registering {
    group = "verification"
    description = "Warns if production bundle uses Google's sample ad IDs (allowed for closed testing)."
    doLast {
        if (configuredAdMobAppId.get() == sampleAdMobAppId || configuredBannerId.get() == sampleBannerId) {
            logger.warn("WARNING: Using Google sample AdMob IDs - OK for closed testing, replace with real IDs before Production.")
        }
        val isProduction = System.getenv("PRODUCTION") == "true"
        if (isProduction) {
            check(configuredAdMobAppId.get() != sampleAdMobAppId) {
                "PRODUCTION=true but ADMOB_APP_ID is still sample ID. Set real AdMob App ID."
            }
            check(configuredBannerId.get() != sampleBannerId) {
                "PRODUCTION=true but ADMOB_BANNER_ID is still sample ID. Set real Banner ID."
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseAds)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // CameraX for document capture
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.google.android.gms:play-services-ads:25.3.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
