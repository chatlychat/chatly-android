pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
