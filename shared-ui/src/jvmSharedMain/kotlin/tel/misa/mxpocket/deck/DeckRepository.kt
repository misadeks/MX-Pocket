package tel.misa.mxpocket.deck

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.FOREGROUND_APP
import tel.misa.mxpocket.APP_RULE_WEIGHT
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.i18n.LayoutCopy

/** Own one instance per app session. Successful edits replace the file before publishing the new snapshot. */
class DeckRepository(private val file: File) {

    private val mutable: MutableStateFlow<DeckState>
    val state: StateFlow<DeckState>
    private val mutableReason: MutableStateFlow<String>
    val reason: StateFlow<String>
    private val mutableDecision: MutableStateFlow<DeckDecision>
    val decision: StateFlow<DeckDecision>

    /** An unreadable or future-format file is preserved until an explicit restore or valid import. */
    private var unreadable = false
    private var cleanupPending = false
    val loadError: String? get() = if (unreadable) LayoutCopy.UnreadableDeck.text() else null
    val cleanupWarning: String? get() = if (cleanupPending) LayoutCopy.CleanupWarning.text() else null

    private var candidate: String? = null
    private var candidateSince = 0L
    private var lastSample = -1L
    // The call page is a temporary projection. Never serialize a meeting or undo an editor change.
    private var saved: DeckState
    private var showingCall = false
    private var callEpisode = false
    private var resumeCall = false
    private var confirmedCall = false
    private var teamsInForeground = false
    private var teamsWorkOverridden = false

    init {
        val loaded = try {
            if (!file.exists()) defaultDeck() else {
                val text = file.inputStream().use {
                    val length = it.channel.size()
                    require(length in 1L..DeckLimits.MAX_BYTES.toLong()) { LayoutCopy.InvalidFileSize.text() }
                    val bytes = ByteArray(length.toInt())
                    java.io.DataInputStream(it).readFully(bytes)
                    require(it.read() == -1) { LayoutCopy.FileChanged.text() }
                    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                }
                val decoded = DeckJson.decode(text)
                decoded.withPrivateReason().also { clean ->
                    if (clean !== decoded) {
                        try { write(DeckJson.encode(clean)) }
                        catch (_: IOException) { cleanupPending = true }
                        catch (_: SecurityException) { cleanupPending = true }
                    }
                }
            }
        } catch (e: Exception) {
            unreadable = true
            defaultDeck()
        }
        val initial = loaded.withBuiltIns()
        saved = initial.snapshot()
        mutable = MutableStateFlow(saved)
        state = mutable.asStateFlow()
        mutableReason = MutableStateFlow(initial.selectionReason)
        reason = mutableReason.asStateFlow()
        mutableDecision = MutableStateFlow(DeckDecision(initial.selectionReason))
        decision = mutableDecision.asStateFlow()
    }

    @Synchronized fun exportJson(): String = DeckJson.encode(saved)
    fun importJson(text: String) = edit(recover = true, force = true, navigation = true) {
        DeckJson.decode(text).withPrivateReason().withBuiltIns()
    }
    fun restoreDefaults() = edit(recover = true, force = true, navigation = true) { defaultDeck() }
    fun save() = edit(force = true) { it }

    fun selectProfile(id: String, source: SelectionSource = SelectionSource.USER) = edit(navigation = true) { old ->
        val profile = old.profile(id)
        if (source == SelectionSource.AUTOMATIC) {
            require(old.automatic && old.activeProfile.automatic && profile.automatic && old.pinnedProfileId == null) {
                LayoutCopy.SelectionDisabled.text()
            }
        }
        old.copy(activeProfileId = id, pinnedProfileId = old.pinnedProfileId?.let { id },
            selectionReason = when {
                old.pinnedProfileId != null -> "Manually selected and pinned"
                source == SelectionSource.USER -> "Manually selected"
                else -> "Selected automatically"
            })
    }

    fun setPinned(id: String?) = edit(navigation = true) { old ->
        if (id == null && old.pinnedProfileId == null) old
        else if (id == null) old.copy(pinnedProfileId = null, selectionReason = "Last selected by manual pin (now unpinned)")
        else old.copy(activeProfileId = old.profile(id).id, pinnedProfileId = id, selectionReason = "Manually pinned")
    }
    fun setDefault(id: String) = edit { it.copy(defaultProfileId = it.profile(id).id) }
    fun setAutomatic(enabled: Boolean) = edit { it.copy(automatic = enabled) }
    fun setProfileAutomatic(id: String, enabled: Boolean) = updateProfile(id) { it.copy(automatic = enabled) }
    fun setRules(id: String, rules: List<DeckRule>) = updateProfile(id) {
        require(rules.size <= DeckLimits.RULES) { LayoutCopy.TooManyRules.text() }
        it.copy(rules = rules.toList())
    }

    fun openPage(id: String) = edit(navigation = true) { old ->
        require(old.activeProfile.pages.any { it.id == id }) { LayoutCopy.UnknownPage.text() }
        val history = old.histories.getValue(old.activeProfileId)
        val previous = history.indexOf(id)
        val next = if (previous >= 0) history.take(previous + 1) else history + id
        old.withHistory(next)
    }
    fun root() = edit(navigation = true) { it.withHistory(listOf(it.activeProfile.rootPageId)) }
    fun back() = edit(navigation = true) { old ->
        val history = old.histories.getValue(old.activeProfileId)
        if (history.size > 1) old.withHistory(history.dropLast(1)) else old
    }

    fun changePage(delta: Int): Result<Unit> {
        val deck = state.value
        val pages = deck.activeProfile.pages
        return when {
            delta < 0 && deck.histories.getValue(deck.activeProfileId).size > 1 -> back()
            deck.activeProfileId == TEAMS && pages.indexOf(deck.activePage) + delta !in pages.indices -> cycleProfile(delta)
            pages.size > 1 -> openPage(pages[Math.floorMod(pages.indexOf(deck.activePage) + delta, pages.size)].id)
            else -> cycleProfile(delta)
        }
    }

    fun cycleProfile(delta: Int) = edit(navigation = true) { old ->
        val profiles = old.profiles
        val current = profiles.indexOfFirst { it.id == old.activeProfileId }
        val index = Math.floorMod(current.toLong() + delta, profiles.size.toLong()).toInt()
        val id = profiles[index].id
        old.copy(activeProfileId = id, pinnedProfileId = old.pinnedProfileId?.let { id },
            selectionReason = if (old.pinnedProfileId != null) "Manually cycled and pinned" else "Manually cycled")
    }

    fun createProfile(name: String) = create { id, old ->
        val profile = DeckProfile(id, name, "root", listOf(DeckPage("root", "Home")))
        old.copy(profiles = old.profiles + profile, histories = old.histories + (id to listOf("root")))
    }
    fun duplicateProfile(id: String, name: String) = create { newId, old ->
        val profile = old.profile(id)
        // Page IDs are scoped to the profile, so internal page links remain valid in a copy.
        val copy = profile.copy(id = newId, name = name, pages = profile.pages.map { page ->
            page.mapActions { action ->
                if (action.id == DeckActions.PROFILE && action.params[DeckActions.PROFILE_ID] == id)
                    action.copy(params = mapOf(DeckActions.PROFILE_ID to newId)) else action
            }
        })
        old.copy(profiles = old.profiles + copy, histories = old.histories + (newId to listOf(copy.rootPageId)))
    }
    fun renameProfile(id: String, name: String) = updateProfile(id) { it.copy(name = name) }
    fun deleteProfile(id: String) = edit { old ->
        old.profile(id)
        require(old.profiles.size > 1) { LayoutCopy.LastProfile.text() }
        val remaining = old.profiles.filterNot { it.id == id }.map { profile ->
            profile.copy(pages = profile.pages.map { page ->
                page.mapActions { call -> call.takeUnless { it.id == DeckActions.PROFILE && it.params[DeckActions.PROFILE_ID] == id } }
            })
        }
        val defaultId = if (id == old.defaultProfileId) remaining.first().id else old.defaultProfileId
        old.copy(profiles = remaining, defaultProfileId = defaultId, histories = old.histories - id,
            xoProfileRemoved = old.xoProfileRemoved || id == "game.xo",
            teamsProfileRemoved = old.teamsProfileRemoved || id == TEAMS,
            activeProfileId = if (id == old.activeProfileId) defaultId else old.activeProfileId,
            pinnedProfileId = old.pinnedProfileId?.takeUnless { it == id },
            selectionReason = if (id == old.activeProfileId) "Default selected after profile deletion" else old.selectionReason)
    }

    fun createPage(profileId: String, name: String) = create { id, old ->
        old.withProfile(profileId) { it.copy(pages = it.pages + DeckPage(id, name)) }
    }
    fun duplicatePage(profileId: String, pageId: String, name: String) = create { id, old ->
        old.withProfile(profileId) { profile ->
            val page = profile.page(pageId).copy(id = id, name = name).mapActions { call ->
                if (call.id == DeckActions.PAGE && call.params[DeckActions.PAGE_ID] == pageId)
                    call.copy(params = mapOf(DeckActions.PAGE_ID to id)) else call
            }
            profile.copy(pages = profile.pages + page)
        }
    }
    fun renamePage(profileId: String, pageId: String, name: String) =
        updateProfile(profileId) { profile -> profile.withPage(pageId) { it.copy(name = name) } }

    fun deletePage(profileId: String, pageId: String) = edit { old ->
        val replaced = old.withProfile(profileId) { profile ->
            profile.page(pageId)
            require(pageId != profile.rootPageId) { LayoutCopy.RootPage.text() }
            profile.copy(pages = profile.pages.filterNot { it.id == pageId }.map { page ->
                page.mapActions { call -> call.takeUnless { it.id == DeckActions.PAGE && it.params[DeckActions.PAGE_ID] == pageId } }
            })
        }
        val history = old.histories.getValue(profileId).filterNot { it == pageId }
        replaced.copy(histories = old.histories + (profileId to history))
    }
    fun setKey(profileId: String, pageId: String, index: Int, key: DeckKey) = updateProfile(profileId) { profile ->
        require(index in 0..8) { LayoutCopy.KeyIndex.text() }
        require((key.action?.params?.size ?: 0) <= DeckLimits.PARAMS &&
            (key.holdAction?.params?.size ?: 0) <= DeckLimits.PARAMS) { LayoutCopy.TooManyParameters.text() }
        profile.withPage(pageId) { page ->
            page.copy(keys = page.keys.mapIndexed { i, previous -> if (i == index) key else previous })
        }
    }

    fun swapKeys(profileId: String, pageId: String, first: Int, second: Int) = updateProfile(profileId) { profile ->
        require(first in 0..8 && second in 0..8) { LayoutCopy.KeyIndex.text() }
        profile.withPage(pageId) { page ->
            page.copy(keys = page.keys.toMutableList().also { java.util.Collections.swap(it, first, second) })
        }
    }

    /**
     * Sample a complete snapshot on a monotonic clock, including ticks after the debounce period.
     * Only observed signal/value pairs score. Retain the current profile on a tie or a one-point
     * lead over a positive incumbent. With no matches use the default, if opted in.
     */
    @Synchronized
    fun observe(signals: Map<String, String>, nowMillis: Long, allowTeamsWork: Boolean = false): Result<Unit> {
        val deck = state.value
        val teamsApp = allowTeamsWork && signals[FOREGROUND_APP] == TEAMS_PACKAGE
        if (!teamsApp) teamsWorkOverridden = false
        teamsInForeground = teamsApp
        if (confirmedCall && teamsApp) teamsWorkOverridden = true
        if (confirmedCall || teamsApp && teamsWorkOverridden) return Result.success(Unit)
        if (!deck.automatic || !deck.activeProfile.automatic || deck.pinnedProfileId != null || nowMillis < 0L) {
            clearObservation()
            return Result.success(Unit)
        }
        if (nowMillis < lastSample) clearObservation()
        lastSample = nowMillis
        val eligible = deck.profiles.filter { it.automatic }
        // Don't add a stored rule on upgrade or compete with an explicit mapping of this app.
        val workRule = DeckRule(FOREGROUND_APP, TEAMS_PACKAGE, APP_RULE_WEIGHT).takeIf { rule ->
            teamsApp && eligible.none { p -> p.rules.any { it.signal == rule.signal && it.value == rule.value } }
        }
        val matches = eligible.associate { profile ->
            val matching = profile.rules.filter { it.matches(signals) }
            profile.id to (if (profile.id == TEAMS && workRule != null) matching + workRule else matching).locked()
        }
        val scores = matches.mapValues { (_, rules) -> rules.sumOf { it.weight } }
        val best = scores.maxByOrNull { it.value }
        val incumbent = scores[deck.activeProfileId] ?: 0
        val target = when {
            best == null -> null
            best.value == 0 -> deck.defaultProfileId.takeIf { it in scores }
            incumbent > 0 && best.value - incumbent < SCORE_MARGIN -> deck.activeProfileId
            else -> best.key
        }
        val openWork = target == TEAMS && workRule != null
        if (target == null || target == deck.activeProfileId && (!openWork || deck.activePage.id == deck.activeProfile.rootPageId)) candidate = null
        else if (candidate != target) {
            candidate = target
            candidateSince = nowMillis
        } else if (nowMillis - candidateSince >= DEBOUNCE_MS) {
            val rules = matches.getValue(target)
            val explanation = if (rules.isEmpty()) "Automatic: default profile (no matching rules)"
                else if (rules.any { it.signal == FOREGROUND_APP }) APP_MATCH_REASON
                else "Automatic: score ${scores[target]} — " + rules.take(4).joinToString { "${it.signal}=${it.value} (+${it.weight})" } +
                    if (rules.size > 4) " and ${rules.size - 4} more" else ""
            val result = edit {
                val next = it.copy(activeProfileId = target, selectionReason = explanation)
                if (openWork) next.withHistory(listOf(next.activeProfile.rootPageId)) else next
            }
            mutableDecision.value = DeckDecision(reason.value, scores.locked(), matches.locked(), candidate)
            return result
        }
        mutableDecision.value = DeckDecision(reason.value, scores.locked(), matches.locked(), candidate)
        return Result.success(Unit)
    }

    private fun DeckState.withPrivateReason() =
        if (selectionReason.startsWith("Automatic: score ") && "$FOREGROUND_APP=" in selectionReason)
            copy(selectionReason = APP_MATCH_REASON) else this

    @Synchronized
    fun clearObservation() {
        candidate = null
        lastSample = -1L
        mutableDecision.value = DeckDecision(reason.value)
    }

    private fun updateProfile(id: String, update: (DeckProfile) -> DeckProfile) =
        edit { it.withProfile(id, update) }

    private fun create(update: (String, DeckState) -> DeckState): Result<String> {
        val id = UUID.randomUUID().toString()
        return edit { update(id, it) }.map { id }
    }

    /** null means uncertain (lock, access loss). Release our page but do not steal it again after a manual override. */
    @Synchronized fun showTeamsCall(joined: Boolean?) {
        confirmedCall = joined == true
        if (joined == true && !callEpisode) {
            callEpisode = true
            showingCall = saved.callProjection() != null
        } else if (joined == true && resumeCall) {
            showingCall = saved.callProjection() != null
            resumeCall = false
        } else if (joined != true) {
            resumeCall = joined == null && (showingCall || resumeCall)
            showingCall = false
            if (joined == false) callEpisode = false
        }
        publish()
    }

    private fun DeckState.callProjection(): DeckState? {
        val teams = profiles.find { it.id == TEAMS && it.automatic } ?: return null
        if (pinnedProfileId != null || !activeProfile.automatic || teams.pages.none { it.id == IN_CALL }) return null
        val history = listOf(teams.rootPageId, IN_CALL).distinct()
        return copy(activeProfileId = TEAMS, histories = (histories + (TEAMS to history.locked())).locked(),
            selectionReason = "Local Teams call")
    }

    private fun publish() {
        val projection = if (showingCall) saved.callProjection() else null
        if (projection == null) showingCall = false
        val next = projection ?: saved
        mutableReason.value = next.selectionReason
        if (mutable.value != next) clearObservation()
        mutable.value = next
    }

    @Synchronized
    private fun edit(recover: Boolean = false, force: Boolean = false, navigation: Boolean = false,
        update: (DeckState) -> DeckState): Result<Unit> = try {
        check(recover || !unreadable) { LayoutCopy.UnreadableDeck.text() }
        val previous = if (navigation) state.value else saved
        val change = update(previous)
        val next = if (navigation && showingCall && !recover && change.activeProfileId != TEAMS && TEAMS in change.histories)
            change.copy(histories = change.histories + (TEAMS to saved.histories.getValue(TEAMS))) else change
        val persistent = if (navigation && showingCall && !recover && next.selectionReason == "Local Teams call")
            next.copy(selectionReason = "Manually selected") else next
        if (force || saved != persistent) {
            val snapshot = persistent.snapshot()
            validateDeck(snapshot)
            write(DeckJson.encode(snapshot))
            unreadable = false
            cleanupPending = false
            saved = snapshot
        }
        if (navigation) {
            showingCall = false
            resumeCall = false
            if (teamsInForeground) teamsWorkOverridden = true
        }
        publish()
        clearObservation()
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(IOException(LayoutCopy.WriteFailed.text(), e))
    } catch (e: SecurityException) {
        Result.failure(SecurityException(LayoutCopy.WriteFailed.text(), e))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun write(json: String) {
        val path = file.toPath()
        val staging = Files.createTempFile(path.parent, ".deck-", ".new")
        try {
            staging.toFile().outputStream().use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    companion object {
        private const val APP_MATCH_REASON = "Automatic: matched an app mapping"
        const val DEBOUNCE_MS = 1500L
        const val SCORE_MARGIN = 2
    }
}

private fun DeckState.withBuiltIns(): DeckState {
    val games = if (xoProfileRemoved) this else addBuiltIn(defaultDeck().profile("game.xo"))
    return if (teamsProfileRemoved) games else games.addBuiltIn(teamsProfile())
}

private fun DeckState.addBuiltIn(profile: DeckProfile): DeckState {
    if (profiles.size >= DeckLimits.PROFILES || profiles.any { it.id == profile.id }) return this
    val augmented = copy(profiles = profiles + profile, histories = histories + (profile.id to listOf(profile.rootPageId)))
    return if (DeckJson.fits(augmented)) augmented else this
}

private fun DeckState.profile(id: String) = profiles.firstOrNull { it.id == id } ?: error(LayoutCopy.UnknownProfile.text())
private fun DeckProfile.page(id: String) = pages.firstOrNull { it.id == id } ?: error(LayoutCopy.UnknownPage.text())
private fun DeckState.withHistory(history: List<String>) = copy(histories = histories + (activeProfileId to history))
private fun DeckState.withProfile(id: String, update: (DeckProfile) -> DeckProfile): DeckState {
    val next = update(profile(id))
    return copy(profiles = profiles.map { if (it.id == id) next else it })
}
private fun DeckProfile.withPage(id: String, update: (DeckPage) -> DeckPage): DeckProfile {
    val next = update(page(id))
    return copy(pages = pages.map { if (it.id == id) next else it })
}
private fun DeckPage.mapActions(transform: (ActionCall) -> ActionCall?) =
    copy(keys = keys.map { it.mapActions(transform) })
