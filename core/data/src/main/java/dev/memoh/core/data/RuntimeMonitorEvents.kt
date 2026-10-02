package dev.memoh.core.data

import dev.memoh.core.model.Session
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class LocalRunAccepted(val accountId: String, val botId: String, val session: Session, val runId: String)

/** Server admission from the chat socket lets the monitor catch very short new runs. */
object RuntimeMonitorEvents {
    private val mutable = MutableSharedFlow<LocalRunAccepted>(extraBufferCapacity = 32)
    val accepted = mutable.asSharedFlow()
    fun accepted(event: LocalRunAccepted) { mutable.tryEmit(event) }
}
