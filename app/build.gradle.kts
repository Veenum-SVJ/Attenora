import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.opensource.focusguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opensource.focusguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            // Test signing key for sideload installs. NEVER use this for Play Store.
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val p = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        // About/version labels are derived from versionName so they can never
        // drift out of sync with the build.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Hard acceptance criterion: the release APK must stay under 20 MB.
tasks.register("checkApkSize") {
    group = "verification"
    description = "Fails if the release APK exceeds the 20 MB budget"
    dependsOn("assembleRelease")
    doLast {
        val apkDir = file("build/outputs/apk/release")
        val apk = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: throw GradleException("No release APK found in $apkDir")
        val limit = 20L * 1024 * 1024
        val size = apk.length()
        val mb = size / 1024.0 / 1024.0
        logger.lifecycle("Release APK: ${apk.name} = ${"%.2f".format(mb)} MB (budget 20 MB)")
        if (size > limit) {
            throw GradleException(
                "Release APK is ${"%.2f".format(mb)} MB, over the 20 MB budget. " +
                    "Trim dependencies or enable more shrinking."
            )
        }
    }
}
