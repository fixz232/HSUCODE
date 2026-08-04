// Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified
// work based on https://github.com/kusesad-1122/XINCODE-Public. This file adds
// state-resume test support; existing copyright and license notices are retained.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hsucode.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":provider"))
    implementation(project(":data"))
    implementation(project(":security"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

android { testOptions { unitTests.isReturnDefaultValues = true } }
