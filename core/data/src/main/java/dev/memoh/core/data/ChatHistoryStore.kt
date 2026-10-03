package dev.memoh.core.data

import dev.memoh.core.model.Session
import dev.memoh.core.model.UITurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

@Serializable
data class CachedChat(val botId: String, val session: Session, val turns: List<UITurn>, val syncedAt: Long)
@Serializable
data class HistoryCacheOptions(val enabled: Boolean = true, val sessions: Int = 30)

/** Only REST history is cached; live runtime snapshots never become offline terminal states. */
class ChatHistoryStore(private val root: File, private val json: Json) {
    private val lock = Mutex()
    private fun segment(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun folder(account: String, team: String) = File(File(root, segment(account)), segment(team.ifBlank { "self" }))
    private fun path(account: String, team: String, bot: String, session: String) = File(folder(account, team), "${segment("$bot:$session")}.json")
    private fun options(dir: File): HistoryCacheOptions = runCatching { json.decodeFromString<HistoryCacheOptions>(File(dir, "options").readText()) }.getOrDefault(HistoryCacheOptions())

    suspend fun options(account: String, team: String): HistoryCacheOptions = withContext(Dispatchers.IO) { lock.withLock { options(folder(account, team)) } }
    suspend fun setOptions(account: String, team: String, options: HistoryCacheOptions) = withContext(Dispatchers.IO) { lock.withLock {
        require(options.sessions in listOf(10, 30, 50))
        val dir = folder(account, team).apply { mkdirs() }
        File(dir, "options").writeText(json.encodeToString(options))
        prune(dir, options.sessions)
    } }
    suspend fun read(account: String, team: String, bot: String, session: String): CachedChat? = withContext(Dispatchers.IO) { lock.withLock {
        runCatching { json.decodeFromString<CachedChat>(path(account, team, bot, session).readText()) }.getOrNull()
    } }
    suspend fun save(account: String, team: String, bot: String, session: Session, turns: List<UITurn>) = withContext(Dispatchers.IO) { lock.withLock {
        val dir = folder(account, team)
        val options = options(dir)
        if (!options.enabled) return@withLock
        dir.mkdirs()
        val destination = path(account, team, bot, session.id)
        val temp = File(dir, "${destination.name}.tmp")
        temp.writeText(json.encodeToString(CachedChat(bot, session, turns.takeLast(500), System.currentTimeMillis())))
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        prune(dir, options.sessions)
    } }
    suspend fun list(account: String, team: String, query: String = ""): List<CachedChat> = withContext(Dispatchers.IO) { lock.withLock {
        folder(account, team).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            runCatching { json.decodeFromString<CachedChat>(it.readText()) }.getOrNull()
        }.filter { chat -> query.isBlank() || chat.session.title.orEmpty().contains(query, true) || chat.turns.any { turn ->
            turn.text.orEmpty().contains(query, true) || turn.messages.orEmpty().any { it.content.orEmpty().contains(query, true) }
        } }.sortedByDescending { it.syncedAt }
    } }
    suspend fun clear(account: String, team: String) = withContext(Dispatchers.IO) { lock.withLock {
        folder(account, team).listFiles().orEmpty().filter { it.name != "options" }.forEach { it.delete() }
    } }
    private fun prune(dir: File, count: Int) { dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.drop(count).forEach { it.delete() } }
}
