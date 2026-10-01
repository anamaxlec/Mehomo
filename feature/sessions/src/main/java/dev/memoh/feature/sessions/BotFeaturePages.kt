package dev.memoh.feature.sessions

/** In-memory page contents, scoped to the repository's existing account generation. */
internal class BotFeaturePages {
    private var accountGeneration: Long? = null
    private val pages = mutableMapOf<Pair<String, BotFeature>, BotFeatureState>()

    private fun forAccount(generation: Long) {
        if (generation != accountGeneration) {
            pages.clear()
            accountGeneration = generation
        }
    }

    fun get(botId: String, feature: BotFeature, generation: Long): BotFeatureState? {
        forAccount(generation)
        return pages[botId to feature]
    }

    fun save(state: BotFeatureState, generation: Long) {
        forAccount(generation)
        if (state.botId.isBlank()) return
        pages[state.botId to state.feature] = state.copy(
            loading = false, busy = false, error = null, notice = null,
            graph = null, logs = null, logSchedule = null, removal = null,
        )
    }
}
