package dev.memoh.android.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Only run identities and delivered alert identities survive process death; no message content. */
class TaskCheckpointStore(private val root: File, private val json: Json) {
    private fun segment(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun path(account: String, bot: String) = File(File(root, segment(account)), "${segment(bot)}.json")

    suspend fun read(account: String, bot: String): Map<String, TaskRunCheckpoint> = withContext(Dispatchers.IO) {
        val file = path(account, bot)
        if (!file.exists()) emptyMap() else runCatching {
            json.decodeFromString<Map<String, TaskRunCheckpoint>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    suspend fun save(account: String, bot: String, checkpoint: Map<String, TaskRunCheckpoint>) = withContext(Dispatchers.IO) {
        val file = path(account, bot)
        if (checkpoint.isEmpty()) {
            Files.deleteIfExists(file.toPath())
        } else {
            file.parentFile!!.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(checkpoint))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}
