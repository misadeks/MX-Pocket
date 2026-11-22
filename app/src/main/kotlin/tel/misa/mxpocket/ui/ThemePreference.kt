package tel.misa.mxpocket.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tel.misa.mxpocket.shared.ui.ThemeMode

/** The appearance chosen in Settings, shared by every MX Pocket screen and kept across restarts. */
internal object ThemePreference {
    private const val FILE = "appearance"
    private const val KEY = "theme"
    private var loaded = false
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun current(context: Context): ThemeMode {
        if (!loaded) {
            loaded = true
            val saved = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            mode = ThemeMode.entries.find { it.name == saved } ?: ThemeMode.SYSTEM
        }
        return mode
    }

    fun set(context: Context, value: ThemeMode) {
        loaded = true
        mode = value
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}
