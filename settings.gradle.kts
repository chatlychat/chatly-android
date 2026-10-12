pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    /*
     * Versions live HERE, once.
     *
     * The library (the root project) and the sample both declared
     * `version "8.4.0"` on an Android plugin, and Gradle refuses that:
     * "Error resolving plugin [id: 'com.android.application', version:
     * '8.4.0'] > the plugin is already on the classpath with an unknown
     * version, so compatibility cannot be checked." With the versions in
     * pluginManagement, each build file names the plugin and nothing else.
     */
    plugins {
        id("com.android.library") version "8.4.0"
        id("com.android.application") version "8.4.0"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

/*
 * The library IS the root project.
 *
 * This read `include(":chatly")` followed by
 * `project(":chatly").projectDir = file(".")` — pointing a subproject at the
 * root directory, which Gradle refuses outright: "Multiple projects in this
 * build have project directory …: [:, :chatly]". Another symptom of a module
 * that had never been built; the first one was a `kotlin("jvm")` plugin on a
 * module with an AndroidManifest.
 *
 * The sample stays a real subproject, in its own directory, where a
 * subproject belongs.
 */
rootProject.name = "chatly-android"

include(":sample")
