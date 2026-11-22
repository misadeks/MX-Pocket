package tel.misa.mxpocket.deck

import tel.misa.mxpocket.actions.ActionCall

/** Resolve a single on release. Once the two controls overlap, consume both until both are up. */
class PageButtons {
    private val held = mutableSetOf<Int>()
    private var consumed = false
    fun reset() { held.clear(); consumed = false }
    fun input(delta: Int, down: Boolean, synthetic: Boolean, browse: (Int) -> Unit, game: () -> Unit) {
        if (down) {
            if (!synthetic && held.add(delta) && held.size == 2 && !consumed) {
                consumed = true
                game()
            }
        } else if (held.remove(delta)) {
            if (!consumed && !synthetic) browse(delta)
            if (held.isEmpty()) consumed = false
        }
    }
}

internal fun DeckState.homeProjection(batch: Int): DeckState {
    val choices = navigationProfiles
    val batches = choices.chunked(if (choices.size <= 9) 9 else 8)
    val index = batch.coerceIn(batches.indices)
    val keys = batches[index].map { profile ->
        DeckKey(label = profile.name, action = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to profile.id)))
    }.toMutableList()
    if (batches.size > 1) {
        while (keys.size < 8) keys += DeckKey()
        keys += DeckKey(label = "More profiles", action = ActionCall(MORE_PROFILES))
    }
    while (keys.size < 9) keys += DeckKey()
    val page = DeckPage("batch-$index", "Profile launcher", keys)
    return operatingProjection(DeckProfile(PROFILE_HOME, "Home", page.id, listOf(page)), "Choose a profile")
}

internal fun DeckState.operatingProjection(profile: DeckProfile, reason: String) = copy(
    profiles = profiles.filterNot { it.id == profile.id } + profile,
    activeProfileId = profile.id, pinnedProfileId = null,
    histories = histories + (profile.id to listOf(profile.rootPageId)), selectionReason = reason,
)
