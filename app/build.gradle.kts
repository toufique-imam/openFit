plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openfit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.openfit"
        // Isolated package for destructive instrumentation fixtures; normal installs keep their identity.
        if (providers.gradleProperty("openfitValidation").orNull == "true") {
            applicationId = "com.openfit.validation"
        }
        testInstrumentationRunner = "com.openfit.validation.ValidationInstrumentation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    val uploadKey = providers.environmentVariable("OPENFIT_KEYSTORE_PATH").orNull
    val uploadStorePassword = providers.environmentVariable("OPENFIT_STORE_PASSWORD").orNull
    val uploadAlias = providers.environmentVariable("OPENFIT_KEY_ALIAS").orNull
    val uploadKeyPassword = providers.environmentVariable("OPENFIT_KEY_PASSWORD").orNull
    val signingValues = listOf(uploadKey, uploadStorePassword, uploadAlias, uploadKeyPassword)
    require(signingValues.none { !it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) {
        "Supply all four OPENFIT signing environment variables, or leave all unset for an unsigned build."
    }
    val uploadSigning = if (!uploadKey.isNullOrBlank()) signingConfigs.create("upload") {
        storeFile = file(uploadKey)
        storePassword = uploadStorePassword
        keyAlias = uploadAlias
        keyPassword = uploadKeyPassword
    } else null

    buildTypes {
        // A separate app for testing, beside the one in daily use: its own Health Connect permissions, quota and data.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = uploadSigning
        }
    }

    if (providers.gradleProperty("openfitValidation").orNull == "true") {
        sourceSets.getByName("debug") {
            kotlin.srcDir("src/validation/java")
            manifest.srcFile("src/validation/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.health.connect)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.junit)
}
