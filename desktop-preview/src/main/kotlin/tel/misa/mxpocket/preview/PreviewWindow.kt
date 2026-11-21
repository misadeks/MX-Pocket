package tel.misa.mxpocket.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import tel.misa.mxpocket.shared.ui.KeypadGrid
import tel.misa.mxpocket.shared.ui.KeypadTile
import tel.misa.mxpocket.shared.ui.PocketTheme

private fun now() = System.nanoTime() / 1_000_000

fun main() = application {
    val deck = remember { PreviewDeck() }
    var profile by remember { mutableStateOf(deck.profileId) }
    var tiles by remember { mutableStateOf(deck.tiles(now())) }
    var message by remember { mutableStateOf("Select a key to try this profile.") }
    fun refresh() { profile = deck.profileId; tiles = deck.tiles(now()) }
    fun navigate(delta: Int) {
        deck.cycle(delta)
        message = "Switched to ${deck.profileName}."
        refresh()
    }
    LaunchedEffect(deck) {
        while (true) {
            delay(1_000)
            if (deck.tick(now())) message = "Focus timer finished. No tone played."
            refresh()
        }
    }
    val profileName = deck.profiles.single { it.first == profile }.second
    Window(::exitApplication, title = "$profileName - MX Pocket | Compose preview",
        state = rememberWindowState(width = 860.dp, height = 548.dp)) {
        PocketTheme {
            val keyColor = when (profile) {
                "focus" -> Color(0xFF194375)
                "audio" -> Color(0xFF303438)
                "phone", "studio" -> Color(0xFF49345F)
                "launcher" -> Color(0xFF384674)
                else -> Color(0xFF254C69)
            }
            Surface(Modifier.fillMaxSize()) {
                val board: @Composable () -> Unit = {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(profileName, style = MaterialTheme.typography.headlineLarge)
                        Text("Profile ${deck.profileIndex + 1} of ${deck.profileCount}",
                            style = MaterialTheme.typography.labelLarge)
                    }
                    KeypadGrid(tiles.map { KeypadTile(it.label, footer = it.footer, color = keyColor,
                        enabled = it.enabled, readOnly = it.readOnly, showNumber = profile != "audio") },
                        onKey = { slot, pressed ->
                            if (pressed) { message = deck.press(slot, now()); refresh() }
                        }, modifier = Modifier.background(Color(0xFF162131), MaterialTheme.shapes.medium).padding(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ navigate(-1) }) { Text("Previous profile") }
                        Button({ navigate(1) }) { Text("Next profile") }
                    }
                    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("Simulation only. No USB, media, camera or accessibility access.",
                        style = MaterialTheme.typography.bodySmall)
                    }
                }
                BoxWithConstraints {
                    if (maxWidth >= 840.dp) Row {
                        Column(Modifier.width(184.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("Profiles", Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
                            deck.profiles.forEach { (id, name) ->
                                NavigationDrawerItem(label = { Text(name) }, selected = profile == id,
                                    onClick = { deck.select(id); message = "Switched to $name."; refresh() })
                            }
                        }
                        Box(Modifier.weight(1f)) { board() }
                    } else board()
                }
            }
        }
    }
}
