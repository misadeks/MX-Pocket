package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import tel.misa.mxpocket.isUnlockedAndInteractive

class CameraControlsActivity : DeckActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = CameraControlsAccess(this)
        val column = page("Studio camera controls")
        val scroll = ScrollView(this)
        column.addView(scroll)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        description(body, CameraControlsAccess.disclosure)
        description(body, DeckAccessActivity.disclosure(false))
        fun toggleLabel() = if (access.enabled) "Turn off keypad camera controls" else "Enable keypad camera controls"
        val toggle = button(body, toggleLabel()) {}
        toggle.setOnClickListener {
            if (isUnlockedAndInteractive()) {
                access.enabled = !access.enabled
                toggle.text = toggleLabel()
            }
        }
        val status = description(body, "In Accessibility, select MX Pocket navigation. Both switches must be on for camera keys.")
        button(body, "Open accessibility settings") {
            try { startActivity(DeckAccessActivity.settings(false)) }
            catch (_: ActivityNotFoundException) { status.text = "Accessibility settings unavailable." }
            catch (_: SecurityException) { status.text = "Android restricted access to settings." }
        }
        val captures = File(filesDir, "captures")
        if (captures.isDirectory) button(body, "Open previous Studio captures") {
            val files = captures.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("jpg", "mp4", "txt") }
                .sortedByDescending { it.name }
            if (files.isEmpty()) { status.text = "No previous Studio captures."; return@button }
            val dates = java.text.DateFormat.getDateTimeInstance()
            val labels = files.map { "${it.extension.uppercase()}  ${dates.format(java.util.Date(it.lastModified()))}" }
            AlertDialog.Builder(this).setTitle("Previous Studio captures").setItems(labels.toTypedArray()) { _, i ->
                if (!isUnlockedAndInteractive()) return@setItems
                val file = files[i]
                val uri = FileProvider.getUriForFile(this, "$packageName.captures", file)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, when (file.extension) {
                        "jpg" -> "image/jpeg"; "mp4" -> "video/mp4"; else -> "text/plain"
                    }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) { status.text = "No viewer for this capture." }
            }.setNegativeButton(android.R.string.cancel, null).show()
        }
        button(body, "Done") { finish() }
    }
}
