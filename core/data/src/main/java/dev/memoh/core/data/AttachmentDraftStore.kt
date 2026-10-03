package dev.memoh.core.data

import dev.memoh.core.model.ChatAttachment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Owns copied attachment bytes, so document-provider URI grants need not survive process death. */
class AttachmentDraftStore(private val root: File, private val json: Json) {
    private sealed interface Operation {
        data class Write(val path: File, val attachments: List<ChatAttachment>, val onError: (String) -> Unit) : Operation
        data class Read(val path: File, val result: CompletableDeferred<List<ChatAttachment>>) : Operation
    }
    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private val serializer = ListSerializer(ChatAttachment.serializer())
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (operation in operations) when (operation) {
                is Operation.Write -> try {
                    if (operation.attachments.isEmpty()) {
                        if (operation.path.exists()) check(operation.path.delete()) { "附件草稿清理失败" }
                    } else {
                        operation.path.parentFile!!.mkdirs()
                        val temp = File(operation.path.parentFile, operation.path.name + ".tmp")
                        temp.writeText(json.encodeToString(serializer, operation.attachments))
                        Files.move(temp.toPath(), operation.path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) { operation.onError("附件草稿保存失败") }
                is Operation.Read -> try {
                    operation.result.complete(if (operation.path.exists()) json.decodeFromString(serializer, operation.path.readText()) else emptyList())
                } catch (e: Exception) { operation.result.completeExceptionally(e) }
            }
        }
    }
    private fun segment(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun path(account: String, team: String, bot: String, session: String) =
        File(File(File(root, segment(account)), segment(team.ifBlank { "self" })), "${segment("$bot:$session")}.json")
    fun save(account: String, team: String, bot: String, session: String, attachments: List<ChatAttachment>, onError: (String) -> Unit = {}) {
        operations.trySend(Operation.Write(path(account, team, bot, session), attachments.toList(), onError))
    }
    suspend fun read(account: String, team: String, bot: String, session: String): List<ChatAttachment> {
        val result = CompletableDeferred<List<ChatAttachment>>()
        operations.send(Operation.Read(path(account, team, bot, session), result))
        return result.await()
    }
}
