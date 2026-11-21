plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.main {
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.include("tel/misa/mxpocket/preview/**", "tel/misa/mxpocket/FocusTimer.kt",
            "tel/misa/mxpocket/actions/ActionDispatcher.kt", "tel/misa/mxpocket/deck/DeckModel.kt",
            "tel/misa/mxpocket/plugins/TicTacToeGame.kt")
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared-ui"))
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test-junit"))
}

compose.desktop.application {
    mainClass = "tel.misa.mxpocket.preview.PreviewWindowKt"
    nativeDistributions {
        packageName = "MXPocketPreview"
        packageVersion = "1.0.0"
    }
}
