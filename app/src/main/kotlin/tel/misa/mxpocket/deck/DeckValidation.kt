package tel.misa.mxpocket.deck

private val identifier = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
private val hourPattern = Regex("(?:[01]?[0-9]|2[0-3])(?:-(?:[01]?[0-9]|2[0-3]))?")

internal fun DeckRule.matches(observed: Map<String, String>): Boolean {
    val sample = observed[signal] ?: return false
    if (signal != "hour") return sample == value
    val hour = sample.toIntOrNull()?.takeIf { it in 0..23 } ?: return false
    val start = value.substringBefore('-').toInt()
    val end = value.substringAfter('-', value).toInt()
    return if (start <= end) hour in start..end else hour >= start || hour <= end
}

private fun validId(value: String) = require(value.matches(identifier)) { "Invalid identifier" }

private fun text(value: String, maxLength: Int, nonBlank: Boolean = false) {
    require(value.length <= maxLength && (!nonBlank || value.isNotBlank())) { "Invalid text length" }
    // String.getBytes(UTF_8) silently replaces unpaired surrogates. Reject lossy input.
    require(value.codePoints().noneMatch { it in 0xd800..0xdfff || it == 0 }) { "Invalid Unicode text" }
}

internal fun validateDeck(deck: DeckState) {
    require(deck.version == DeckLimits.VERSION) { "Unsupported deck format version" }
    text(deck.selectionReason, 1024, nonBlank = true)
    require(deck.profiles.size in 1..DeckLimits.PROFILES) { "Invalid number of profiles" }
    val profileIds = deck.profiles.map { it.id }.toSet()
    require(profileIds.size == deck.profiles.size) { "Duplicate profile ID" }
    require(deck.activeProfileId in profileIds && deck.defaultProfileId in profileIds) { "Unknown selected profile" }
    require(deck.pinnedProfileId == null || deck.pinnedProfileId == deck.activeProfileId) { "Invalid pin" }
    require(deck.histories.keys == profileIds) { "Invalid profile histories" }
    for (profile in deck.profiles) {
        validId(profile.id)
        text(profile.name, DeckLimits.NAME, nonBlank = true)
        require(profile.rules.size <= DeckLimits.RULES) { "Too many context rules" }
        require(profile.rules.distinctBy { it.signal to it.value }.size == profile.rules.size) { "Duplicate context rule" }
        for (rule in profile.rules) {
            validId(rule.signal)
            text(rule.value, DeckLimits.RULE_VALUE, nonBlank = true)
            require(rule.signal != "hour" || hourPattern.matches(rule.value)) { "Use a local hour or inclusive hour range, e.g. 9-17" }
            require(rule.weight in 1..DeckLimits.MAX_WEIGHT) { "Invalid rule weight" }
        }
        require(profile.pages.size in 1..DeckLimits.PAGES) { "Invalid number of pages" }
        val pageIds = profile.pages.map { it.id }.toSet()
        require(pageIds.size == profile.pages.size && profile.rootPageId in pageIds) { "Invalid page IDs" }
        val history = deck.histories.getValue(profile.id)
        require(history.size in 1..DeckLimits.HISTORY && history.first() == profile.rootPageId &&
            history.all { it in pageIds } && history.distinct().size == history.size) { "Invalid page history" }
        for (page in profile.pages) {
            validId(page.id)
            text(page.name, DeckLimits.NAME, nonBlank = true)
            require(page.keys.size == 9) { "Each page needs nine keys" }
            for (key in page.keys) {
                text(key.icon, DeckLimits.ICON)
                text(key.label, DeckLimits.LABEL)
                val call = key.action ?: continue
                validId(call.id)
                require(call.params.size <= DeckLimits.PARAMS) { "Too many action parameters" }
                for ((name, value) in call.params) {
                    validId(name)
                    text(value, DeckLimits.PARAM_VALUE)
                }
                when (call.id) {
                    DeckActions.PAGE -> require(call.params.keys == setOf(DeckActions.PAGE_ID) &&
                        call.params[DeckActions.PAGE_ID] in pageIds) { "Unknown target page" }
                    DeckActions.PROFILE -> require(call.params.keys == setOf(DeckActions.PROFILE_ID) &&
                        call.params[DeckActions.PROFILE_ID] in profileIds) { "Unknown target profile" }
                    DeckActions.BACK, DeckActions.ROOT -> require(call.params.isEmpty()) { "Unexpected navigation parameters" }
                    "plugin.key" -> {
                        require(call.params.keys == setOf("pluginId", "index") && call.params["index"] in keyIndices) {
                            "Invalid plugin key assignment"
                        }
                        validId(call.params.getValue("pluginId"))
                    }
                }
            }
        }
    }
}

private val keyIndices = (0..8).map(Int::toString).toSet()
