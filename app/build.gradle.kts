plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("dagger.hilt.android.plugin")
}

android {
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        testInstrumentationRunnerArguments += mapOf("clearPackageData" to "true")
        versionCode = rootProject.extra["versionCode"] as Int
        versionName = rootProject.extra["versionName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        applicationId = "org.liberty.android.freeotpplus"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "Blue TOTP")
        }
        getByName("release") {
            resValue("string", "app_name", "Blue TOTP")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }
}

dependencies {
    implementation(project(":token-images"))
    implementation(project(":text-drawable"))
    implementation(project(":token-data"))
    implementation(libs.appCompat)
    implementation(libs.material)
    implementation(libs.zxing)
    implementation(libs.gson)
    implementation(libs.glide)
    implementation(libs.coreKtx)
    implementation(libs.activityKtx)
    implementation(libs.bundles.kotlinxCoroutines)
    implementation(kotlin("stdlib-jdk8"))

    implementation(libs.bundles.cameraX)
    implementation(libs.biometric)

    implementation(libs.hiltAndroid)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.datastore:datastore-preferences-core:1.0.0")
    kapt(libs.hiltAndroidCompiler)

    androidTestImplementation(libs.bundles.androidxTest)
    androidTestUtil(libs.androidxTestOrchestrator)

    androidTestImplementation(libs.bundles.androidxJunit)
    androidTestImplementation(libs.androidxTruth)
    androidTestImplementation(libs.bundles.espresso)
}
