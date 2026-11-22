plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets["main"].kotlin.srcDir(rootProject.file("mx-driver/src/jvmShared/kotlin"))
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.processResources {
    from(rootProject.file("mx-driver/src/main/assets/mx-pocket-provenance.txt"))
}

dependencies {
    implementation(project(":shared-ui"))
    // Ship the native Skiko renderer for the supported Windows JVM, even on ARM64 hosts.
    if (System.getProperty("os.name").startsWith("Windows")) implementation(compose.desktop.windows_x64)
    else implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop.application {
    mainClass = "tel.misa.mxpocket.preview.PreviewWindowKt"
    nativeDistributions {
        packageName = "MXPocketPreview"
        packageVersion = "1.0.0"
    }
}
