plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    jvm("desktop") { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.commonMain.dependencies {
        api(compose.foundation)
        api(compose.material3)
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    }
    sourceSets.getByName("androidMain") {
        kotlin.srcDir("src/jvmSharedMain/kotlin")
        dependencies { implementation("com.google.code.gson:gson:2.13.2") }
    }
    sourceSets.getByName("desktopMain") {
        kotlin.srcDir("src/jvmSharedMain/kotlin")
        dependencies {
            implementation("com.google.code.gson:gson:2.13.2")
            implementation("org.json:json:20250517")
        }
    }
    sourceSets.named("desktopTest") {
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

android {
    namespace = "tel.misa.mxpocket.shared.ui"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
