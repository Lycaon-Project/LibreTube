// ============================================================================
// LibreTube Settings - Ubdated for Build Performance
// ============================================================================

// Suppress warnings for incubating APIs that are stable in practice
// These APIs are widely used and unlikely to change
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        // Order matters for performance: most-used repositories first
        google {
            content {
                // Only fetch Google/Android plugins from here
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Auto-provision JDK toolchains (downloads JDK if needed)
plugins {
    // Version 1.0.0 is the latest stable release
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Fail if any module tries to declare its own repositories
    // Ensures centralized repository management
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // Google repository (Android-specific dependencies)
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        // ✅ CORRECTION CRITIQUE : Maven Central sans exclusion de com.google.*
        // protobuf (com.google.protobuf) et guava (com.google.guava) sont sur Maven Central
        // Seuls com.android.* et androidx.* sont exclus car ils sont sur le repo Google
        mavenCentral {
            content {
                // Exclude groups that are available on Google repository
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
                // ⚠️ NE PAS exclure com.google.* car protobuf et guava sont sur Maven Central
            }
        }

        // JitPack for GitHub-hosted libraries
        maven("https://jitpack.io") {
            content {
                // Only fetch specific groups from JitPack to avoid timeouts
                includeGroup("com.github.TeamNewPipe")
                includeGroup("com.github.libre-tube")
                includeGroup("com.github.Lycaon-Project")
                includeGroupByRegex("com\\.github\\..*")
            }
        }

        // Local Maven repository (last resort, can slow down builds)
        // Only used for locally published artifacts
        mavenLocal()
    }
}

// Project configuration
rootProject.name = "LibreTube"

// Include modules
include(":app")
include(":baselineprofile")