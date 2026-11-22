package tel.misa.mxpocket.decks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.FavoriteOption
import tel.misa.mxpocket.shared.ui.FavoritesScreen
import tel.misa.mxpocket.shared.ui.FavoritesState
import tel.misa.mxpocket.plugins.IntegrationApp

internal class FavoriteApps(private val app: Context) {
    private val pm get() = app.packageManager
    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val icons = LruCache<String, Bitmap>(128)
    fun selected(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    fun setSelected(component: String, on: Boolean) {
        val old = selected()
        prefs.edit().putStringSet("favorites", if (on) old + component else old - component).apply()
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    fun discover(): List<Favorite> = pm.queryIntentActivities(launcher(), 0).mapNotNull {
        val a = it.activityInfo
        if (!a.exported || !a.enabled || a.packageName == app.packageName) return@mapNotNull null
        Favorite(ComponentName(a.packageName, a.name).flattenToString(),
            it.loadLabel(pm).toString().replace(Regex("[\\p{C}\\s]+"), " ").trim().take(80).ifEmpty { a.packageName })
    }.distinctBy { it.component }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Favorite::label).thenBy { it.component })

    @Suppress("DEPRECATION")
    fun intentFor(component: String?): Intent? {
        if (component == null || component !in selected()) return null
        val name = ComponentName.unflattenFromString(component) ?: return null
        // An explicit intent alone bypasses intent-filter matching. Re-query MAIN/LAUNCHER first.
        val available = pm.queryIntentActivities(launcher().setPackage(name.packageName), 0)
            .any { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.name == name.className }
        return if (available) Intent.makeMainActivity(name) else null
    }
    fun invalidateIcons() = icons.evictAll()

    /** Read drawable resources only; neither integration needs to be launched or granted access. */
    fun integrationIcons(): Map<IntegrationApp, Bitmap> = buildMap {
        for ((id, pkg) in listOf(IntegrationApp.TEAMS to TEAMS_PACKAGE, IntegrationApp.ONENOTE to OneNoteAccessService.ONENOTE_PACKAGE)) {
            val drawable = try { pm.getApplicationIcon(pkg) }
                catch (_: PackageManager.NameNotFoundException) { continue }
                catch (_: SecurityException) { continue }
            put(id, drawable.toBitmap(64, 64, Bitmap.Config.ARGB_8888))
        }
    }

    fun icon(component: String): Bitmap? {
        icons.get(component)?.let { return it }
        val name = ComponentName.unflattenFromString(component) ?: return null
        return try {
            val drawable = pm.getActivityIcon(name)
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, it.width, it.height)
                drawable.draw(Canvas(it))
                icons.put(component, it)
            }
        } catch (_: PackageManager.NameNotFoundException) { null }
    }
}

class FavoritesActivity : DeckActivity() {
    private val session get() = (application as PocketApplication).session
    private var apps = emptyList<Favorite>()
    private var state by mutableStateOf<FavoritesState>(FavoritesState.Loading)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("App Launcher") { FavoritesScreen(state, ::setSelected, ::finish, ::loadIcon) }
        lifecycleScope.launch {
            state = try {
                apps = withContext(Dispatchers.IO) { session.favoriteApps.discover() }
                session.publishFavorites(apps)
                FavoritesState.Ready(apps.map { FavoriteOption(it.component, it.label) }, session.favoriteApps.selected(),
                    "No launchable apps available in this Android profile.")
            } catch (_: SecurityException) {
                FavoritesState.Unavailable("Android restricted access to the apps in this profile.")
            }
        }
    }

    private suspend fun loadIcon(id: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try { session.favoriteApps.icon(id)?.asImageBitmap() } catch (_: SecurityException) { null }
    }

    private fun setSelected(id: String, selected: Boolean) {
        val ready = state as? FavoritesState.Ready ?: return
        if (!isUnlockedAndInteractive() || apps.none { it.component == id }) return
        session.favoriteApps.setSelected(id, selected)
        session.publishFavorites(apps)
        state = ready.copy(selected = session.favoriteApps.selected())
    }
}
