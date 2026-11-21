package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import tel.misa.mxpocket.isUnlockedAndInteractive

abstract class DeckActivity : AppCompatActivity() {
    protected fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    protected fun page(heading: String): LinearLayout {
        title = heading
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(column) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(dp(16) + safe.left, dp(8) + safe.top, dp(16) + safe.right, dp(8) + safe.bottom)
            insets
        }
        setContentView(column)
        column.addView(TextView(this).apply {
            text = heading; textSize = 24f
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        return column
    }
    protected fun description(parent: LinearLayout, value: String) = TextView(this).apply {
        text = value; textSize = 16f; setPadding(0, dp(8), 0, dp(8))
        parent.addView(this)
    }
    protected fun button(parent: LinearLayout, label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        setOnClickListener { if (isUnlockedAndInteractive()) action() }
        parent.addView(this, if (parent.orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
    }
}

class DeckAccessActivity : DeckActivity() {
    companion object {
        internal fun settings(media: Boolean) =
            Intent(if (media) Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS else Settings.ACTION_ACCESSIBILITY_SETTINGS)

        internal fun disclosure(media: Boolean) = if (media)
            "Android grants broad notification access. MX Pocket only queries active media sessions; it does not read, store or act on notifications. With Audio selected, the connected, authorized keypad shows track titles and local cover art even when the phone is locked. Switch profiles or revoke access to stop showing them. Basic media keys and volume work without access."
        else "The MX Pocket navigation accessibility service can inspect the current window and act across apps. On a key press it can go Back or Home, open Recents, scroll, move focus or activate an item. It does not read or store screen text, record keys or automate gestures. Without access, navigation stays inside MX Pocket."

        fun intent(context: Context, command: DeckCommand): Intent {
            require(command == DeckCommand.MEDIA_ACCESS || command == DeckCommand.NAV_ACCESS)
            return Intent(context, DeckAccessActivity::class.java).putExtra("access", command.id)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = DeckCommand.fromId(intent.getStringExtra("access").orEmpty())
        if (command !in setOf(DeckCommand.MEDIA_ACCESS, DeckCommand.NAV_ACCESS)) { finish(); return }
        val media = command == DeckCommand.MEDIA_ACCESS
        val column = page(if (media) "Media session access" else "Navigation access")
        val scroll = android.widget.ScrollView(this)
        column.addView(scroll)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        description(body, disclosure(media))
        description(body, "Access is optional. You can turn it off in the same settings at any time.")
        val status = description(body, "")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        button(body, "Open Android settings") {
            try {
                startActivity(settings(media))
            } catch (_: ActivityNotFoundException) { status.text = "These settings are unavailable on this device." }
            catch (_: SecurityException) { status.text = "Android restricted these settings." }
        }
        button(body, "Done") { finish() }
    }
}
