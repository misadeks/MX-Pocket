// Run without an SDK-specific lint installation and before either application assembles.
val production = rootProject.fileTree(rootDir) {
    include("**/src/**", "desktop-preview/**")
    exclude("**/build/**", "**/.gradle/**", "**/src/test*/**", "**/src/*Test/**", "**/src/androidTest*/**")
}
val legacyToolkit = Regex(
    """\b(?:javax\.swing|java\.awt\.(?:Component|Container|Window|Frame|Dialog|Panel|Button|Label|TextField|Menu|event|\*)|javafx\.|org\.eclipse\.swt|android\.widget\.|androidx\.appcompat\.widget\.|androidx\.appcompat\.app\.AlertDialog|android\.app\.(?:AlertDialog|Dialog|ProgressDialog)|android\.webkit\.WebView|com\.google\.android\.material\.|androidx\.compose\.ui\.viewinterop|androidx\.compose\.ui\.awt\.SwingPanel|android\.view\.(?:LayoutInflater|ViewGroup)|setContentView\s*\(|AndroidView(?:Binding)?\s*\()"""
)
val composeControls = Regex("""\bandroidx\.compose\.(?:material(?:3)?|foundation(?:\.|\b)(?!isSystemInDarkTheme))""")
val commentsAndStrings = Regex("""(?s)/\*.*?\*/|//[^\r\n]*|""${'"'}.*?""${'"'}|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")

fun violation(path: String, source: String): String? {
    if (Regex("""/res/(?:layout|menu|navigation)(?:-[^/]+)?/[^/]+\.xml$""").containsMatchIn("/$path"))
        return "XML layout/menu/navigation"
    if (path.substringAfterLast('.').lowercase() in setOf("xaml", "fxml", "ui", "html", "htm"))
        return "alternate UI document"
    if (!path.endsWith(".kt") && !path.endsWith(".java")) return null
    val code = commentsAndStrings.replace(source, " ")
    legacyToolkit.find(code)?.let { return "native/interoperability widget: ${it.value}" }
    if (!path.startsWith("shared-ui/src/commonMain/")) {
        composeControls.find(code)?.let { return "platform-local Compose controls: ${it.value}" }
    }
    return null
}

val testSharedUiBoundary = tasks.register("testSharedUiBoundary") {
    doLast {
        listOf(
            "app/src/main/kotlin/Regressed.kt" to "import android.widget.LinearLayout",
            "desktop-preview/src/main/kotlin/Legacy.kt" to "import javax.swing.JButton",
            "app/src/main/res/layout-land/screen.xml" to "<LinearLayout/>",
            "app/src/main/kotlin/PhoneOnly.kt" to "import androidx.compose.material3.Button",
            "shared-ui/src/androidMain/kotlin/Wrapped.kt" to "import androidx.compose.ui.viewinterop.AndroidView",
        ).forEach { (file, source) -> check(violation(file, source) != null) { "Guard accepted $file" } }
        check(violation("app/src/main/kotlin/Adapter.kt",
            """val shutter = node.className == "android.widget.Button"; val pick = ActivityResultContracts.OpenDocument()""") == null)
    }
}
val checkSharedUi = tasks.register("checkSharedUi") {
    group = "verification"
    description = "Rejects parallel app-owned presentation on Android and desktop."
    dependsOn(testSharedUiBoundary)
    inputs.files(production)
    doLast {
        val errors = production.files.mapNotNull { file ->
            val path = file.relativeTo(rootDir).invariantSeparatorsPath
            val source = if (file.extension in setOf("java", "kt")) file.readText() else ""
            violation(path, source)?.let { "$path: $it" }
        }.sorted()
        check(errors.isEmpty()) { "Application UI must be in shared-ui/src/commonMain:\n${errors.joinToString("\n")}" }
    }
}
subprojects {
    tasks.matching { it.name in setOf("preBuild", "compileKotlin", "compileKotlinDesktop", "check", "run") }
        .configureEach { dependsOn(checkSharedUi) }
}
