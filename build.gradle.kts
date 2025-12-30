import java.util.Properties

// Load API key AFTER plugins block
// Top-level Gradle build file for project-wide configuration
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}


buildscript {
    dependencies {
        // For Google services, Firebase (if needed later)
        classpath("com.google.gms:google-services:4.4.0")
    }
}

allprojects {
    // Fixes dependency conflicts for OkHttp, coroutines, BLE
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
            force("com.squareup.okio:okio:3.4.0")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
