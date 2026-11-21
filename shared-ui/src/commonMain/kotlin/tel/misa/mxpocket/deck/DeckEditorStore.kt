package tel.misa.mxpocket.deck

import kotlinx.coroutines.flow.StateFlow

/** Editor commands target saved configuration. This port deliberately cannot dispatch an ActionCall. */
interface DeckEditorStore {
    val state: StateFlow<DeckState>
    val decision: StateFlow<DeckDecision>
    val loadError: String?
    val cleanupWarning: String?
    suspend fun create(change: DeckCreate): Result<String>
    suspend fun edit(change: DeckEdit): Result<Unit>
}

sealed interface DeckCreate {
    data class Profile(val name: String) : DeckCreate
    data class CopyProfile(val id: String, val name: String) : DeckCreate
    data class Page(val profileId: String, val name: String) : DeckCreate
    data class CopyPage(val profileId: String, val id: String, val name: String) : DeckCreate
}

sealed interface DeckEdit {
    data class RenameProfile(val id: String, val name: String) : DeckEdit
    data class DeleteProfile(val id: String) : DeckEdit
    data class RenamePage(val profileId: String, val id: String, val name: String) : DeckEdit
    data class DeletePage(val profileId: String, val id: String) : DeckEdit
    data class UpdateKey(val profileId: String, val pageId: String, val index: Int, val previous: DeckKey, val key: DeckKey) : DeckEdit
    data class Swap(val profileId: String, val pageId: String, val first: Int, val second: Int) : DeckEdit
    data class Rules(val profileId: String, val previous: List<DeckRule>, val rules: List<DeckRule>) : DeckEdit
    data class Select(val id: String) : DeckEdit
    data class Pin(val id: String?) : DeckEdit
    data class Default(val id: String) : DeckEdit
    data class Automatic(val enabled: Boolean) : DeckEdit
    data class ProfileAutomatic(val id: String, val enabled: Boolean) : DeckEdit
    data class Open(val activeProfileId: String, val pageId: String) : DeckEdit
    data class Back(val activeProfileId: String) : DeckEdit
    data class Root(val activeProfileId: String) : DeckEdit
    data object Restore : DeckEdit
    data object RetryCleanup : DeckEdit
}
