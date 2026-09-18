import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

// ✅ CORRECTION 1 : Suppression du warning de dépréciation pour android {}
@Suppress("Deprecation")
android {
    namespace = "com.github.libretube.baselineprofile"
    // ✅ CORRECTION 2 : Mise à jour vers compileSdk 37 (Android 16)
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    defaultConfig {
        minSdk = 28
        // ✅ CORRECTION 3 : Mise à jour vers targetSdk 37 pour éliminer le warning
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

// This is the configuration block for the Baseline Profile plugin.
// You can specify to run the generators on a managed devices or connected devices.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.espressoCore)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// ✅ CORRECTION 4 : Suppression du warning @Incubating pour getTestedApks()
@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { v ->
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { v.artifacts.getBuiltArtifactsLoader().load(it)?.applicationId }
        )
    }
}