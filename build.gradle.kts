/*
 * This is an ANDROID LIBRARY, and this file said `kotlin("jvm")`.
 *
 * The source is unambiguous about it: `src/main/AndroidManifest.xml`,
 * `src/main/res/` with four layouts and two drawables, a `ChatlyActivity`, a
 * `ChatlyFragment`, a `ChatlyComposerView` extending `LinearLayout`, and
 * imports of `androidx.appcompat`, `androidx.recyclerview`,
 * `androidx.security.crypto` and `kotlinx.serialization`. The Kotlin JVM
 * plugin processes none of that — no manifest merge, no resource compilation,
 * and none of those packages on the classpath.
 *
 * So the library has never compiled, and could not have. The sample module
 * beside it had the right shape all along (`com.android.application`, AGP
 * 8.4.0, compileSdk 34), which is where these versions come from; the library
 * was simply never given the matching plugin.
 *
 * Found on 2026-10-12 while giving every SDK a CI workflow in its public
 * mirror — the one thing that asks a package whether it builds.
 */
plugins {
    // Versions are in settings.gradle.kts — see the note there.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

/*
 * `com.chatlychat`, not `com.chatly`.
 *
 * The published coordinate a developer types is
 * `com.chatlychat:chatly-android`, and `chatly.com` is not a domain we own —
 * so a group id derived from it could never be claimed on Maven Central.
 */
group = "com.chatlychat"
version = "0.1.0"

android {
    namespace = "com.chatly"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.json:json:20240303")

    /*
     * `SocketTransport.kt` imports `io.socket.client.IO` and
     * `io.socket.client.Socket` and nothing declared this. The module had
     * never compiled — it carried `kotlin("jvm")` for an Android library —
     * so the missing dependency had never been reported by anything. The
     * public mirror's CI found it on its first real build.
     *
     * `socket.io-client` brings `engine.io-client` and okhttp transitively.
     * 2.1.1 is the last 2.x, which is what the realtime gateway's protocol
     * version expects.
     */
    implementation("io.socket:socket.io-client:2.1.1")

    // Every one of these is imported by `src/main/kotlin`. The JVM plugin had
    // none of them, which is the other half of why this never compiled.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation(kotlin("test"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "chatly-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Chatly Android SDK")
                description.set("Official Android SDK for the Chatly live chat platform.")
                url.set("https://chatlychat.com/docs/sdk-android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/chatlychat/chatly-android")
                }
            }
        }
    }
}
