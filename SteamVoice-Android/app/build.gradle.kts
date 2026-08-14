import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.haoze.steamvoice"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.haoze.steamvoice"
        minSdk = 24
        targetSdk = 36
        versionCode = 10001
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
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
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
            // Run R8 shrinking/obfuscation with the optimized Android defaults.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

val apkVersionName = android.defaultConfig.versionName ?: "unknown"

listOf("debug", "release").forEach { buildType ->
    val capitalizedBuildType = buildType.replaceFirstChar { it.uppercase() }
    val apkOutputDirectory = layout.buildDirectory.dir("outputs/apk/$buildType")
    val versionedApkOutputDirectory = layout.buildDirectory.dir("outputs/apk/versioned/$buildType")

    val copyApkTask = tasks.register<Copy>("copy${capitalizedBuildType}ApkWithVersion") {
        dependsOn("assemble$capitalizedBuildType")
        from(apkOutputDirectory)
        include("app-$buildType.apk")
        rename("app-$buildType.apk", "SteamVoice-$buildType-v$apkVersionName.apk")
        into(versionedApkOutputDirectory)
    }

    tasks.configureEach {
        if (name == "assemble$capitalizedBuildType") {
            finalizedBy(copyApkTask)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
