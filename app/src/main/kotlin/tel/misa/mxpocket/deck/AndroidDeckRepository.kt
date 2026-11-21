package tel.misa.mxpocket.deck

import android.content.Context
import java.io.File

@Suppress("FunctionName")
fun DeckRepository(context: Context) = DeckRepository(File(context.applicationContext.filesDir, "deck.json"))
