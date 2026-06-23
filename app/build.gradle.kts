import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadProperty(key: String, default: String): String {
    val props = Properties()
    rootProject.file("gradle.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    return props.getProperty(key, default)
}

val useCompositeSdk = loadProperty("useCompositeSdk", "false").toBoolean()
val useMavenSdk = loadProperty("useMavenSdk", "false").toBoolean()
val posrouterVersion = loadProperty("posrouterVersion", "1.0.2")

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}

android {
    namespace = "com.posrouter.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.posrouter.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "PARTICIPANT_KEY",
            "\"${localProperties.getProperty("PARTICIPANT_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "TERMINAL_ID",
            "\"${localProperties.getProperty("TERMINAL_ID", "TID001")}\""
        )
        buildConfigField(
            "String",
            "EZYPOS_MID",
            "\"${localProperties.getProperty("EZYPOS_MID", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    when {
        useCompositeSdk -> implementation("com.posrouter:posrouter")
        useMavenSdk -> implementation("com.posrouter:posrouter:$posrouterVersion")
        else -> {
            implementation(files("libs/posrouter-release.aar"))
            implementation("io.nats:jnats:2.20.5")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        }
    }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
