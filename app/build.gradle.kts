plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing. The keystore and passwords are NEVER stored in the project: they come from
// environment variables (GitHub Actions secrets) or from ~/.gradle/gradle.properties.
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }

val ksPath = signingValue("ALMUS_KEYSTORE_PATH", "almus.keystore.path")
val ksPassword = signingValue("ALMUS_KEYSTORE_PASSWORD", "almus.keystore.password")
val ksKeyAlias = signingValue("ALMUS_KEY_ALIAS", "almus.key.alias")
val ksKeyPassword = signingValue("ALMUS_KEY_PASSWORD", "almus.key.password")
val hasSigning = listOf(ksPath, ksPassword, ksKeyAlias, ksKeyPassword).all { it != null } &&
    ksPath?.let { file(it).isFile } == true

android {
    namespace = "com.almus.ytdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.almus.ytdownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.0"

        // yt-dlp ships native Python and ffmpeg per CPU type; phones only need these two.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Without the secrets (forks, local builds) the release APK is simply left unsigned.
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // yt-dlp and ffmpeg are run as programs, so the native libraries must be extracted to disk.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    // Room writes its schema here so every database version can be checked by a migration test.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
}
