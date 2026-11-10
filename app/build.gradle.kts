import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val monitorOnly = providers.gradleProperty("presenceMonitorOnly").map { it.toBooleanStrict() }.getOrElse(false)
val discordSdk = file("libs/discord_partner_sdk.aar")
if (!monitorOnly && !discordSdk.isFile) {
    throw GradleException("Discord Social SDK 1.10+ の Android AAR を app/libs/discord_partner_sdk.aar に配置してください。監視だけをビルドする場合は -PpresenceMonitorOnly=true を明示してください。")
}

val releaseKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "cc.lem0n.androidrichpresence"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cc.lem0n.androidrichpresence"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").apply {
        kotlin.directories.add(if (monitorOnly) "src/monitorOnly/java" else "src/discord/java")

    }
    if (!monitorOnly) {
        sourceSets.getByName("debug").manifest.srcFile("src/discord/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/discord/AndroidManifest.xml")
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                path = file("src/discord/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        val archiveAbis = ZipFile(discordSdk).use { archive ->
            archive.entries().asSequence().mapNotNull { entry ->
                Regex("(?:jni/|prefab/modules/[^/]+/libs/android\\.)([^/]+)/.*\\.so")
                    .matchEntire(entry.name)?.groupValues?.get(1)
            }.toSet()
        }
        require(archiveAbis.isNotEmpty()) { "Discord AAR に Android のネイティブライブラリがありません。Android 用 SDK を配置してください。" }
        defaultConfig {
            ndk { abiFilters += archiveAbis }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // Leave packageScope at "**". Restricting it to androidx/kotlin splits those
                // classes from the app classes that extend them, and the release APK then
                // dies in InitializationProvider with IllegalAccessError.
            }
        }
    }
    signingConfigs {
        if (releaseKeystore.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
            buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        prefab = !monitorOnly
    }
}

dependencies {
    implementation("androidx.browser:browser:1.8.0")
    if (!monitorOnly) implementation(files(discordSdk))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}