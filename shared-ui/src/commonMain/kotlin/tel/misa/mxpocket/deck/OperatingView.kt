package tel.misa.mxpocket.deck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.actions.ActionCall

/** Confined to the input owner's dispatcher. Measure the overlap, not the first button's dwell. */
class PageButtons(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val longPressMs: () -> Long,
    private val browse: (Int) -> Unit,
    private val home: () -> Unit,
    private val game: () -> Unit,
) {
    private val held = mutableMapOf<Int, () -> Boolean>()
    private var consumed = false
    private var deadline: Long? = null
    private var timer: Job? = null

    fun cancel() {
        timer?.cancel()
        timer = null
        deadline = null
        consumed = held.isNotEmpty()
    }

    fun reset() { held.clear(); cancel() }

    fun input(delta: Int, down: Boolean, synthetic: Boolean = false, at: Long = now(), current: () -> Boolean = { true }) {
        if (synthetic) {
            if (!down) held.remove(delta)
            cancel()
            return
        }
        if (down) {
            if (delta in held) return // Keyboard auto-repeat isn't a fresh press.
            held[delta] = current
            if (!consumed && held.size == 2) {
                consumed = true
                val end = at + longPressMs()
                deadline = end
                timer = scope.launch {
                    // Queue even an overdue decision behind releases already delivered by USB.
                    delay((end - now()).coerceAtLeast(1L))
                    deadline = null
                    if (held.values.all { it() }) game()
                }
            }
        } else if (delta in held) {
            val valid = held.values.all { it() }
            val end = deadline
            val single = !consumed
            held.remove(delta)
            cancel() // Both chord releases belong to the same decision.
            if (valid) when {
                end != null -> if (at < end) home() else game()
                single -> browse(delta)
            }
        }
    }
}

internal fun DeckState.homeProjection(batch: Int): DeckState {
    val choices = visibleProfiles
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
