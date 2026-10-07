import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val embeddedDefaults = Properties().apply {
    val source = rootProject.file("default-config.properties")
    if (source.isFile) source.inputStream().use(::load)
}
val signingProperties = Properties().apply {
    val source = rootProject.file(".signing/keystore.properties")
    if (source.isFile) source.inputStream().use(::load)
}
val hasReleaseSigning = signingProperties.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "cn.zzmllk.amadeus"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.zzmllk.amadeus"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.4.1"

        resValue("string", "default_host", embeddedDefaults.getProperty("host", ""))
        resValue("string", "default_ssh_port", embeddedDefaults.getProperty("sshPort", "22"))
        resValue("string", "default_user", embeddedDefaults.getProperty("user", "root"))
        resValue("string", "default_api_port", embeddedDefaults.getProperty("apiPort", "18317"))
        resValue("string", "default_astrbot_port", embeddedDefaults.getProperty("astrBotPort", "16185"))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/**",
            "META-INF/services/java.security.Provider",
            "org/bouncycastle/pqc/**"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    val composeBom = platform("androidx.compose:compose-bom:2025.03.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation("com.github.mwiede:jsch:2.28.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
