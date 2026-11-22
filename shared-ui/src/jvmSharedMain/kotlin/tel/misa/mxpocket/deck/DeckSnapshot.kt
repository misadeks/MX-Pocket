package tel.misa.mxpocket.deck

import java.util.Collections

internal fun <T> List<T>.locked(): List<T> = Collections.unmodifiableList(ArrayList(this))
internal fun <K, V> Map<K, V>.locked(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))

internal fun DeckState.snapshot() = copy(
    profiles = profiles.map { profile ->
        profile.copy(
            rules = profile.rules.locked(),
            pages = profile.pages.map { page ->
                page.copy(keys = page.keys.map { key ->
                    key.mapActions { it.copy(params = it.params.locked()) }
                }.locked())
            }.locked(),
        )
    }.locked(),
    histories = histories.mapValues { it.value.locked() }.locked(),
)
