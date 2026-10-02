package dev.memoh.android

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.util.UUID

data class SharedContent(val id: String, val text: String, val uris: List<Uri>) {
    companion object {
        fun from(intent: Intent): SharedContent? {
            if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
            val uris = (streams + clips).filter { it.scheme == "content" }.distinct()
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isBlank() && uris.isEmpty()) return null
            return SharedContent(UUID.randomUUID().toString(), text, uris)
        }
    }
}

data class ChatTarget(val accountId: String, val botId: String, val sessionId: String)

val ShareSelectionSaver = androidx.compose.runtime.saveable.Saver<Pair<ChatTarget, SharedContent>?, ArrayList<String>>(
    save = { selection -> selection?.let { (target, content) ->
        arrayListOf(target.accountId, target.botId, target.sessionId, content.id, content.text).apply { addAll(content.uris.map(Uri::toString)) }
    } },
    restore = { saved -> if (saved.size < 5) null else ChatTarget(saved[0], saved[1], saved[2]) to
        SharedContent(saved[3], saved[4], saved.drop(5).map(Uri::parse)) })
