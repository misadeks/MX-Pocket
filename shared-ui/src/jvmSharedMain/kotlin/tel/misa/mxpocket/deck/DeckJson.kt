package tel.misa.mxpocket.deck

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.i18n.LayoutCopy
import tel.misa.mxpocket.plugins.TIC_TAC_TOE_TITLE

/** Strict token types, bounded depth and collections, and duplicate/unknown field rejection. */
object DeckJson {
    fun decode(text: String): DeckState = try {
        decodeDocument(text)
    } catch (e: ClassCastException) {
        throw IllegalArgumentException(LayoutCopy.InvalidDeckJson.text(), e)
    } catch (e: JSONException) {
        throw IllegalArgumentException(LayoutCopy.InvalidDeckJson.text(), e)
    } catch (e: IOException) {
        // The reader is backed by a String, so I/O failures here denote malformed input, not storage errors.
        throw IllegalArgumentException(LayoutCopy.InvalidDeckJson.text(), e)
    }

    private fun decodeDocument(text: String): DeckState {
        checkSize(text)
        checkLexemes(text)
        val obj = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val root = read(reader, 0) as? JSONObject ?: error(LayoutCopy.ExpectedObject.text())
            require(reader.peek() == JsonToken.END_DOCUMENT) { LayoutCopy.TrailingJson.text() }
            root
        }
        val xoProfileRemoved = if (obj.has("xoProfileRemoved")) obj.remove("xoProfileRemoved") as Boolean else false
        val teamsRemoved = if (obj.has("teamsProfileRemoved")) obj.remove("teamsProfileRemoved") as Boolean else false
        obj.fields("version", "profiles", "activeProfileId", "defaultProfileId", "pinnedProfileId", "automatic", "histories", "selectionReason")
        val state = DeckState(
            version = obj.get("version") as Int,
            profiles = obj.array("profiles", ::profile),
            activeProfileId = obj.string("activeProfileId"),
            defaultProfileId = obj.string("defaultProfileId"),
            pinnedProfileId = if (obj.isNull("pinnedProfileId")) null else obj.string("pinnedProfileId"),
            automatic = obj.get("automatic") as Boolean,
            histories = (obj.get("histories") as JSONObject).let { histories ->
                histories.keys().asSequence().associateWith { id ->
                    val pages = histories.get(id) as JSONArray
                    List(pages.length()) { pages.get(it) as String }
                }
            },
            selectionReason = obj.string("selectionReason"),
            xoProfileRemoved = xoProfileRemoved,
            teamsProfileRemoved = teamsRemoved,
        )
        if (state.version == 1 || state.version == 2) {
            val validated = state.copy(version = DeckLimits.VERSION)
            validateDeck(validated)
            val audio = if (state.version == 1) upgradeAudioSlots(validated) else validated
            return audio.upgradeStockNavigationRoot()
        }
        validateDeck(state)
        return state
    }

    // v1 plugin.key identified a position, not a command. Upgrade the stock page; freeze the
    // old meanings on edited pages and copies, including explicit mute versus unmute.
    private fun upgradeAudioSlots(state: DeckState): DeckState {
        val legacy = listOf(DeckCommand.QUIETER, DeckCommand.PLAY, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
            DeckCommand.MEDIA_ACCESS, DeckCommand.NEXT, DeckCommand.MUTE, DeckCommand.VOLUME_LEVEL, DeckCommand.UNMUTE)
        val stock = defaultDeck().profiles.single { it.id == "audio" }.pages.single()
        return state.copy(profiles = state.profiles.map { profile ->
            profile.copy(pages = profile.pages.map { page ->
                if (profile.id == "audio" && page.id == profile.rootPageId && page == stock) page
                else page.copy(keys = page.keys.map { key ->
                    val call = key.action
                    if (call?.id == "plugin.key" && call.params["pluginId"] == "audio")
                        key.copy(action = ActionCall(legacy[call.params.getValue("index").toInt()].id,
                            mapOf(DeckActions.LEGACY_SLOT_OWNER to "audio")))
                    else key
                })
            })
        })
    }

    private fun profile(obj: JSONObject): DeckProfile {
        obj.fields("id", "name", "rootPageId", "pages", "automatic", "rules")
        val id = obj.string("id")
        val root = obj.string("rootPageId")
        fun title(name: String) = if (id == "game.xo" && name == "X-O") TIC_TAC_TOE_TITLE else name
        return DeckProfile(
            id, title(obj.string("name")), root,
            obj.array("pages") {
                it.fields("id", "name", "keys")
                val pageId = it.string("id")
                val name = it.string("name")
                DeckPage(pageId, if (pageId == root) title(name) else name, it.array("keys", ::key))
            },
            obj.get("automatic") as Boolean,
            obj.array("rules") {
                it.fields("signal", "value", "weight")
                DeckRule(it.string("signal"), it.string("value"), it.get("weight") as Int)
            },
        )
    }

    private fun key(obj: JSONObject): DeckKey {
        val holdAction = action(obj.remove("holdAction"))
        obj.fields("icon", "label", "color", "action", "enabled")
        return DeckKey(obj.string("icon"), obj.string("label"), obj.get("color") as Int,
            action(obj.get("action")), obj.get("enabled") as Boolean, holdAction)
    }

    private fun action(value: Any?): ActionCall? {
        if (value == null || value === JSONObject.NULL) return null
        val obj = value as JSONObject
        obj.fields("id", "params")
        val p = obj.get("params") as JSONObject
        return ActionCall(obj.string("id"), p.keys().asSequence().associateWith { name -> p.string(name) })
    }

    private fun ActionCall.json() = JSONObject().put("id", id).put("params", JSONObject(params))

    fun encode(deck: DeckState) = serialize(deck).also(::checkSize)
    fun fits(deck: DeckState) = fits(serialize(deck))

    private fun serialize(deck: DeckState): String {
        val profiles = deck.profiles.map { profile ->
            // Keep the original wire spelling: an existing max-size backup must still round trip.
            fun title(name: String) = if (profile.id == "game.xo" && name == TIC_TAC_TOE_TITLE) "X-O" else name
            val pages = profile.pages.map { page ->
                val keys = page.keys.map { key ->
                    JSONObject().put("icon", key.icon).put("label", key.label).put("color", key.color)
                        .put("action", key.action?.json() ?: JSONObject.NULL).put("enabled", key.enabled)
                        // Omit an unassigned Hold so even a limit-sized legacy layout still fits.
                        .apply { key.holdAction?.let { put("holdAction", it.json()) } }
                }
                JSONObject().put("id", page.id).put("name", if (page.id == profile.rootPageId) title(page.name) else page.name)
                    .put("keys", JSONArray(keys))
            }
            val rules = profile.rules.map {
                JSONObject().put("signal", it.signal).put("value", it.value).put("weight", it.weight)
            }
            JSONObject().put("id", profile.id).put("name", title(profile.name)).put("rootPageId", profile.rootPageId)
                .put("pages", JSONArray(pages)).put("automatic", profile.automatic).put("rules", JSONArray(rules))
        }
        val histories = JSONObject()
        deck.histories.forEach { (id, pages) -> histories.put(id, JSONArray(pages)) }
        return JSONObject().put("version", deck.version).put("profiles", JSONArray(profiles))
            .put("activeProfileId", deck.activeProfileId).put("defaultProfileId", deck.defaultProfileId)
            .put("pinnedProfileId", deck.pinnedProfileId ?: JSONObject.NULL).put("automatic", deck.automatic)
            .put("histories", histories).put("selectionReason", deck.selectionReason)
            .apply {
                if (deck.xoProfileRemoved) put("xoProfileRemoved", true)
                if (deck.teamsProfileRemoved) put("teamsProfileRemoved", true)
            }.toString()
    }

    private fun JSONObject.string(name: String) = get(name) as String
    private fun JSONObject.fields(vararg names: String) =
        require(length() == names.size && names.all(::has)) { LayoutCopy.MissingField.text() }

    private fun <T> JSONObject.array(name: String, transform: (JSONObject) -> T): List<T> {
        val a = get(name) as JSONArray
        return List(a.length()) { transform(a.get(it) as JSONObject) }
    }

    private fun fits(text: String) =
        text.length <= DeckLimits.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= DeckLimits.MAX_BYTES

    private fun checkSize(text: String) = require(fits(text)) { LayoutCopy.DeckSize.text(DeckLimits.MAX_BYTES) }

    // Android's non-lenient JsonReader still accepts raw newlines, unknown escapes, and
    // case-insensitive literals. Reject these before it normalizes them into ordinary values.
    private fun checkLexemes(input: String) {
        var i = 0
        while (i < input.length) {
            when (val c = input[i++]) {
                ' ', '\t', '\r', '\n', '{', '}', '[', ']', ':', ',' -> Unit
                '"' -> {
                    while (i < input.length && input[i] != '"') {
                        val ch = input[i++]
                        require(ch >= ' ') { LayoutCopy.JsonControl.text() }
                        if (ch == '\\') {
                            require(i < input.length) { LayoutCopy.TruncatedEscape.text() }
                            when (input[i++]) {
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                                'u' -> repeat(4) {
                                    require(i < input.length && input[i++] in "0123456789abcdefABCDEF") { LayoutCopy.UnicodeEscape.text() }
                                }
                                else -> error(LayoutCopy.JsonEscape.text())
                            }
                        }
                    }
                    require(i < input.length) { LayoutCopy.JsonString.text() }
                    i++
                }
                't', 'f', 'n' -> {
                    val literal = when (c) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(input.startsWith(literal, i - 1)) { LayoutCopy.JsonLiteral.text() }
                    i += literal.length - 1
                }
                '-', in '0'..'9' -> {
                    val start = if (c == '-') i else i - 1
                    require(start < input.length && input[start] in '0'..'9') { LayoutCopy.JsonInteger.text() }
                    while (i < input.length && input[i] in '0'..'9') i++
                    require(i - start == 1 || input[start] != '0') { LayoutCopy.JsonInteger.text() }
                }
                else -> error(LayoutCopy.JsonToken.text())
            }
        }
    }

    private fun read(reader: JsonReader, depth: Int): Any {
        require(depth <= 12) { LayoutCopy.NestingLimit.text() }
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(length() < 32) { LayoutCopy.ArrayLimit.text() }
                    put(read(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(length() < 32 && !has(name)) { LayoutCopy.FieldLimit.text() }
                    put(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextString().toIntOrNull() ?: error(LayoutCopy.ExpectedInt.text())
            JsonToken.STRING -> reader.nextString()
            else -> error(LayoutCopy.InvalidDeckJson.text())
        }
    }
}
