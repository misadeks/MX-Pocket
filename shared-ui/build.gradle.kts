plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Android's Compose resource loader does not decode SVG. Both hosts consume the
// same transparent build product; the approved SVG is the only authored housing.
val svgRasterizer by configurations.creating
dependencies { svgRasterizer("org.apache.xmlgraphics:batik-rasterizer:1.19") }
val housingResources = layout.buildDirectory.dir("generated/housingResources")
val rasterizeHousing = tasks.register<JavaExec>("rasterizeHousing") {
    val svg = layout.projectDirectory.file("src/commonMain/artwork/mx-keypad-housing.svg")
    val png = housingResources.get().file("drawable/mx_keypad_housing.png").asFile
    inputs.file(svg)
    outputs.dir(housingResources)
    classpath = svgRasterizer
    mainClass.set("org.apache.batik.apps.rasterizer.Main")
    args("-w", "1440", "-d", png, svg.asFile)
    doFirst { png.parentFile.mkdirs() }
}

compose.resources {
    packageOfResClass = "tel.misa.mxpocket.shared.resources"
    customDirectory("commonMain", rasterizeHousing.map { housingResources.get() })
}

kotlin {
    androidTarget { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    jvm("desktop") { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.commonMain.dependencies {
        api(compose.foundation)
        api(compose.material3)
        implementation(compose.components.resources)
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
