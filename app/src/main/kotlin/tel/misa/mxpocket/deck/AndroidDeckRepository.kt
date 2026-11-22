package tel.misa.mxpocket.deck

import android.content.Context
import java.io.File

@Suppress("FunctionName")
fun DeckRepository(context: Context, disabledPluginIds: Set<String> = emptySet(), wallTimeMillis: () -> Long = System::currentTimeMillis) =
    DeckRepository(File(context.applicationContext.filesDir, "deck.json"), disabledPluginIds, wallTimeMillis)
