package tel.misa.mxpocket.deck

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.i18n.LayoutCopy

private val identifier = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
private val hourPattern = Regex("(?:[01]?[0-9]|2[0-3])(?:-(?:[01]?[0-9]|2[0-3]))?")

fun DeckRule.matches(observed: Map<String, String>): Boolean {
    val sample = observed[signal] ?: return false
    if (signal != "hour") return sample == value
    val hour = sample.toIntOrNull()?.takeIf { it in 0..23 } ?: return false
    val start = value.substringBefore('-').toInt()
    val end = value.substringAfter('-', value).toInt()
    return if (start <= end) hour in start..end else hour >= start || hour <= end
}

private fun validId(value: String) = require(value.matches(identifier)) { LayoutCopy.InvalidIdentifier.text() }

private fun text(value: String, maxLength: Int, nonBlank: Boolean = false) {
    require(value.length <= maxLength && (!nonBlank || value.isNotBlank())) { LayoutCopy.InvalidLength.text() }
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        require(when {
            c.isHighSurrogate() -> i < value.length && value[i++].isLowSurrogate()
            else -> !c.isLowSurrogate() && c != '\u0000'
        }) { LayoutCopy.InvalidUnicode.text() }
    }
}

fun validateDeck(deck: DeckState) {
    require(deck.version == DeckLimits.VERSION) { LayoutCopy.UnsupportedVersion.text() }
    text(deck.selectionReason, 1024, nonBlank = true)
    require(deck.profiles.size in 1..DeckLimits.PROFILES) { LayoutCopy.InvalidProfileCount.text() }
    val profileIds = deck.profiles.map { it.id }.toSet()
    require(profileIds.size == deck.profiles.size) { LayoutCopy.DuplicateProfileId.text() }
    require(deck.activeProfileId in profileIds && deck.defaultProfileId in profileIds) { LayoutCopy.UnknownSelected.text() }
    require(deck.pinnedProfileId == null || deck.pinnedProfileId == deck.activeProfileId) { LayoutCopy.InvalidPin.text() }
    require(deck.histories.keys == profileIds) { LayoutCopy.InvalidHistories.text() }
    for (profile in deck.profiles) {
        validId(profile.id)
        text(profile.name, DeckLimits.NAME, nonBlank = true)
        require(profile.rules.size <= DeckLimits.RULES) { LayoutCopy.TooManyContextRules.text() }
        require(profile.rules.distinctBy { it.signal to it.value }.size == profile.rules.size) { LayoutCopy.DuplicateRule.text() }
        for (rule in profile.rules) {
            validId(rule.signal)
            text(rule.value, DeckLimits.RULE_VALUE, nonBlank = true)
            require(rule.signal != "hour" || hourPattern.matches(rule.value)) { LayoutCopy.InvalidHour.text() }
            require(rule.weight in 1..DeckLimits.MAX_WEIGHT) { LayoutCopy.InvalidWeight.text() }
        }
        require(profile.pages.size in 1..DeckLimits.PAGES) { LayoutCopy.InvalidPageCount.text() }
        val pageIds = profile.pages.map { it.id }.toSet()
        require(pageIds.size == profile.pages.size && profile.rootPageId in pageIds) { LayoutCopy.InvalidPageIds.text() }
        val history = deck.histories.getValue(profile.id)
        require(history.size in 1..DeckLimits.HISTORY && history.first() == profile.rootPageId &&
            history.all { it in pageIds } && history.distinct().size == history.size) { LayoutCopy.InvalidHistory.text() }
        for (page in profile.pages) {
            validId(page.id)
            text(page.name, DeckLimits.NAME, nonBlank = true)
            require(page.keys.size == 9) { LayoutCopy.NineKeys.text() }
            for (key in page.keys) {
                text(key.icon, DeckLimits.ICON)
                text(key.label, DeckLimits.LABEL)
                key.action?.let { validateAction(it, pageIds, profileIds) }
                key.holdAction?.let { validateAction(it, pageIds, profileIds) }
            }
        }
    }
}

private fun validateAction(call: ActionCall, pageIds: Set<String>, profileIds: Set<String>) {
    validId(call.id)
    require(call.params.size <= DeckLimits.PARAMS) { LayoutCopy.TooManyParameters.text() }
    for ((name, value) in call.params) {
        validId(name)
        text(value, DeckLimits.PARAM_VALUE)
    }
    when (call.id) {
        DeckActions.PAGE -> require(call.params.keys == setOf(DeckActions.PAGE_ID) &&
            call.params[DeckActions.PAGE_ID] in pageIds) { LayoutCopy.UnknownTargetPage.text() }
        DeckActions.PROFILE -> require(call.params.keys == setOf(DeckActions.PROFILE_ID) &&
            call.params[DeckActions.PROFILE_ID] in profileIds) { LayoutCopy.UnknownTargetProfile.text() }
        DeckActions.BACK, DeckActions.ROOT -> require(call.params.isEmpty()) { LayoutCopy.UnexpectedParameters.text() }
        "plugin.key" -> {
            require(call.params.keys == setOf("pluginId", "index") && call.params["index"] in keyIndices) {
                LayoutCopy.InvalidAssignment.text()
            }
            validId(call.params.getValue("pluginId"))
        }
    }
}

private val keyIndices = (0..8).map(Int::toString).toSet()
