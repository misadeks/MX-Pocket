package tel.misa.mxpocket.deck

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import tel.misa.mxpocket.actions.ActionCall

/** Strict token types, bounded depth and collections, and duplicate/unknown field rejection for v1. */
internal object DeckJson {
    fun decode(text: String): DeckState {
        checkSize(text)
        checkLexemes(text)
        val obj = JsonReader(StringReader(text)).use { reader ->
            val root = read(reader, 0) as? JSONObject ?: error("Expected deck object")
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON" }
            root
        }
        val xoProfileRemoved = if (obj.has("xoProfileRemoved")) obj.remove("xoProfileRemoved") as Boolean else false
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
        )
        validateDeck(state)
        return state
    }

    private fun profile(obj: JSONObject): DeckProfile {
        obj.fields("id", "name", "rootPageId", "pages", "automatic", "rules")
        return DeckProfile(
            obj.string("id"), obj.string("name"), obj.string("rootPageId"),
            obj.array("pages") {
                it.fields("id", "name", "keys")
                DeckPage(it.string("id"), it.string("name"), it.array("keys", ::key))
            },
            obj.get("automatic") as Boolean,
            obj.array("rules") {
                it.fields("signal", "value", "weight")
                DeckRule(it.string("signal"), it.string("value"), it.get("weight") as Int)
            },
        )
    }

    private fun key(obj: JSONObject): DeckKey {
        obj.fields("icon", "label", "color", "action", "enabled")
        val action = if (obj.isNull("action")) null else (obj.get("action") as JSONObject).let {
            it.fields("id", "params")
            val p = it.get("params") as JSONObject
            ActionCall(it.string("id"), p.keys().asSequence().associateWith { name -> p.string(name) })
        }
        return DeckKey(obj.string("icon"), obj.string("label"), obj.get("color") as Int, action, obj.get("enabled") as Boolean)
    }

    fun encode(deck: DeckState) = serialize(deck).also(::checkSize)
    fun fits(deck: DeckState) = fits(serialize(deck))

    private fun serialize(deck: DeckState): String {
        val profiles = deck.profiles.map { profile ->
            val pages = profile.pages.map { page ->
                val keys = page.keys.map { key ->
                    val call = key.action?.let {
                        JSONObject().put("id", it.id).put("params", JSONObject(it.params))
                    }
                    JSONObject().put("icon", key.icon).put("label", key.label).put("color", key.color)
                        .put("action", call ?: JSONObject.NULL).put("enabled", key.enabled)
                }
                JSONObject().put("id", page.id).put("name", page.name).put("keys", JSONArray(keys))
            }
            val rules = profile.rules.map {
                JSONObject().put("signal", it.signal).put("value", it.value).put("weight", it.weight)
            }
            JSONObject().put("id", profile.id).put("name", profile.name).put("rootPageId", profile.rootPageId)
                .put("pages", JSONArray(pages)).put("automatic", profile.automatic).put("rules", JSONArray(rules))
        }
        val histories = JSONObject()
        deck.histories.forEach { (id, pages) -> histories.put(id, JSONArray(pages)) }
        return JSONObject().put("version", deck.version).put("profiles", JSONArray(profiles))
            .put("activeProfileId", deck.activeProfileId).put("defaultProfileId", deck.defaultProfileId)
            .put("pinnedProfileId", deck.pinnedProfileId ?: JSONObject.NULL).put("automatic", deck.automatic)
            .put("histories", histories).put("selectionReason", deck.selectionReason)
            .apply { if (deck.xoProfileRemoved) put("xoProfileRemoved", true) }.toString()
    }

    private fun JSONObject.string(name: String) = get(name) as String
    private fun JSONObject.fields(vararg names: String) =
        require(length() == names.size && names.all(::has)) { "Unexpected or missing deck field" }

    private fun <T> JSONObject.array(name: String, transform: (JSONObject) -> T): List<T> {
        val a = get(name) as JSONArray
        return List(a.length()) { transform(a.get(it) as JSONObject) }
    }

    private fun fits(text: String) =
        text.length <= DeckLimits.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= DeckLimits.MAX_BYTES

    private fun checkSize(text: String) = require(fits(text)) { "Deck exceeds ${DeckLimits.MAX_BYTES} bytes" }

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
                        require(ch >= ' ') { "Unescaped control in JSON string" }
                        if (ch == '\\') {
                            require(i < input.length) { "Truncated JSON escape" }
                            when (input[i++]) {
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                                'u' -> repeat(4) {
                                    require(i < input.length && input[i++] in "0123456789abcdefABCDEF") { "Invalid Unicode escape" }
                                }
                                else -> error("Invalid JSON escape")
                            }
                        }
                    }
                    require(i < input.length) { "Unterminated JSON string" }
                    i++
                }
                't', 'f', 'n' -> {
                    val literal = when (c) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(input.startsWith(literal, i - 1)) { "Invalid JSON literal" }
                    i += literal.length - 1
                }
                '-', in '0'..'9' -> {
                    val start = if (c == '-') i else i - 1
                    require(start < input.length && input[start] in '0'..'9') { "Invalid JSON integer" }
                    while (i < input.length && input[i] in '0'..'9') i++
                    require(i - start == 1 || input[start] != '0') { "Invalid JSON integer" }
                }
                else -> error("Unexpected JSON token")
            }
        }
    }

    private fun read(reader: JsonReader, depth: Int): Any {
        require(depth <= 12) { "Deck nesting limit exceeded" }
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(length() < 32) { "Deck array limit exceeded" }
                    put(read(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(length() < 32 && !has(name)) { "Too many or duplicate fields" }
                    put(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextString().toIntOrNull() ?: error("Expected a 32-bit integer")
            JsonToken.STRING -> reader.nextString()
            else -> error("Invalid deck JSON")
        }
    }
}
