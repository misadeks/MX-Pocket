package tel.misa.mxpocket.decks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputFilter
import android.util.LruCache
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.BaseAdapter
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.PocketApplication

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
    private var apps = listOf<Favorite>()
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val column = page("App Launcher")
        val hint = description(column, "Loading launchable apps...")
        val search = EditText(this).apply {
            setSingleLine(); setHint("Find an app"); filters = arrayOf(InputFilter.LengthFilter(80))
        }
        column.addView(search)
        val list = ListView(this)
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        var visible = apps
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = getItem(position)
                return ((convertView as? CheckBox) ?: CheckBox(this@FavoritesActivity)).apply {
                    text = item.label; minHeight = dp(56)
                    setOnCheckedChangeListener(null)
                    isChecked = item.component in session.favoriteApps.selected()
                    val icon = session.favoriteApps.icon(item.component)?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                    icon?.setBounds(0, 0, dp(32), dp(32))
                    setCompoundDrawablesRelative(null, null, icon, null)
                    setOnCheckedChangeListener { _, on ->
                        session.favoriteApps.setSelected(item.component, on)
                        session.publishFavorites(apps)
                        hint.text = summary()
                    }
                }
            }
        }
        list.adapter = adapter
        fun filter() {
            visible = apps.filter { it.label.contains(search.text.toString(), ignoreCase = true) }
            hint.text = if (visible.isEmpty() && search.length() > 0) "No matching apps." else summary()
            adapter.notifyDataSetChanged()
        }
        search.doAfterTextChanged { filter() }
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { session.favoriteApps.discover() }
            session.publishFavorites(apps)
            filter()
        }
        button(column, "Done") { finish() }
    }
    private fun summary(): String {
        val selected = session.favoriteApps.selected()
        return if (apps.isEmpty()) "No launchable apps available in this Android profile."
            else "${apps.count { it.component in selected }} selected. Checked apps appear on the keypad."
    }
}
