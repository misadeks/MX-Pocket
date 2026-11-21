package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

data class FavoriteOption(val id: String, val label: String)

sealed interface FavoritesState {
    data object Loading : FavoritesState
    data class Unavailable(val reason: String) : FavoritesState
    data class Ready(val apps: List<FavoriteOption>, val selected: Set<String>,
        val emptyMessage: String = "No launchable apps available.") : FavoritesState
}

@Composable
fun FavoritesScreen(state: FavoritesState, onSetSelected: (String, Boolean) -> Unit, onDone: () -> Unit,
    loadIcon: (suspend (String) -> ImageBitmap?)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    SubsidiaryPage("App Launcher", onDone) {
        when (state) {
            FavoritesState.Loading -> SubsidiaryStatus("Loading launchable apps...")
            is FavoritesState.Unavailable -> SubsidiaryStatus(state.reason)
            is FavoritesState.Ready -> {
                val matches = remember(state.apps, query) {
                    state.apps.filter { it.label.contains(query, ignoreCase = true) }
                }
                val count = remember(state.apps, state.selected) { state.apps.count { it.id in state.selected } }
                SubsidiaryStatus(when {
                    state.apps.isEmpty() -> state.emptyMessage
                    matches.isEmpty() -> "No matching apps."
                    else -> "$count selected. Checked apps appear on the keypad."
                })
                OutlinedTextField(value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                    label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn {
                    items(matches, key = { it.id }) { app ->
                        val selected = app.id in state.selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(selected, role = Role.Checkbox) { onSetSelected(app.id, it) }
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (loadIcon != null) {
                                val icon by produceState<ImageBitmap?>(null, app.id, loadIcon) {
                                    value = loadIcon(app.id)
                                }
                                icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
