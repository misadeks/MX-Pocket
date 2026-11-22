package tel.misa.mxpocket.deck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.i18n.LayoutCopy

/** Both hosts edit their real repository. Successful writes publish only after atomic file replacement. */
fun DeckRepository.asEditorStore(): DeckEditorStore = RepositoryEditorStore(this)

private class RepositoryEditorStore(private val repo: DeckRepository) : DeckEditorStore {
    override val state get() = repo.state
    override val decision get() = repo.decision
    override val loadError get() = repo.loadError
    override val cleanupWarning get() = repo.cleanupWarning

    override suspend fun create(change: DeckCreate) = withContext(Dispatchers.IO) {
        when (change) {
            is DeckCreate.Profile -> repo.createProfile(change.name)
            is DeckCreate.CopyProfile -> repo.duplicateProfile(change.id, change.name)
            is DeckCreate.Page -> repo.createPage(change.profileId, change.name)
            is DeckCreate.CopyPage -> repo.duplicatePage(change.profileId, change.id, change.name)
        }
    }

    override suspend fun edit(change: DeckEdit) = withContext(Dispatchers.IO) {
        // Compare the edit's baseline under the same monitor as the repository transaction.
        synchronized(repo) {
            when (change) {
                is DeckEdit.RenameProfile -> repo.renameProfile(change.id, change.name)
                is DeckEdit.DeleteProfile -> repo.deleteProfile(change.id)
                is DeckEdit.RenamePage -> repo.renamePage(change.profileId, change.id, change.name)
                is DeckEdit.DeletePage -> repo.deletePage(change.profileId, change.id)
                is DeckEdit.UpdateKey -> {
                    val actual = state.value.profiles.find { it.id == change.profileId }
                        ?.pages?.find { it.id == change.pageId }?.keys?.getOrNull(change.index)
                    if (actual != change.previous) conflict(LayoutCopy.KeyConflict.text())
                    else repo.setKey(change.profileId, change.pageId, change.index, change.key)
                }
                is DeckEdit.Swap -> repo.swapKeys(change.profileId, change.pageId, change.first, change.second)
                is DeckEdit.Rules -> {
                    if (state.value.profiles.find { it.id == change.profileId }?.rules != change.previous)
                        conflict(LayoutCopy.RuleConflict.text())
                    else repo.setRules(change.profileId, change.rules)
                }
                is DeckEdit.Select -> repo.selectProfile(change.id)
                is DeckEdit.Pin -> repo.setPinned(change.id)
                is DeckEdit.Default -> repo.setDefault(change.id)
                is DeckEdit.Automatic -> repo.setAutomatic(change.enabled)
                is DeckEdit.ProfileAutomatic -> repo.setProfileAutomatic(change.id, change.enabled)
                is DeckEdit.Open -> navigate(change.activeProfileId) { repo.openPage(change.pageId) }
                is DeckEdit.Back -> navigate(change.activeProfileId, repo::back)
                is DeckEdit.Root -> navigate(change.activeProfileId, repo::root)
                DeckEdit.Restore -> repo.restoreDefaults()
                DeckEdit.RetryCleanup -> repo.save()
            }
        }
    }

    private fun conflict(message: String) = Result.failure<Unit>(IllegalStateException(message))
    private fun navigate(expected: String, action: () -> Result<Unit>) =
        if (repo.operatingState.value.activeProfileId != expected) conflict(LayoutCopy.ProfileConflict.text())
        else action()
}
