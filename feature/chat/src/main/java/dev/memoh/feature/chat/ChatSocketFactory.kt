package dev.memoh.feature.chat

import dev.memoh.core.model.UIStreamEvent
import dev.memoh.core.network.ChatSocket
import dev.memoh.core.network.CloudAuth
import dev.memoh.core.network.MemohApi
import dev.memoh.core.network.ServerEndpoint
import dev.memoh.core.network.SocketStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Builds chat sockets.
 *
 * Exists so the ViewModel need not know how a socket authenticates, which
 * differs per deployment: a self-hosted server accepts the bearer token on the
 * handshake, while the platform requires a fresh one-shot ticket before *every*
 * handshake — reconnects included, since a ticket is single-use.
 *
 * The token is sent as a header, never a query parameter: a URL ends up in logs
 * and crash reports, and the official client's `?token=` form exists only
 * because browsers cannot set WebSocket headers.
 */
class ChatSocketFactory @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val cloudAuth: CloudAuth,
) {
    fun create(
        botId: String,
        endpoint: ServerEndpoint?,
        api: MemohApi?,
        scope: CoroutineScope,
        onEvent: (UIStreamEvent) -> Unit,
        onStatus: (SocketStatus) -> Unit,
        onResubscribeNeeded: () -> Unit,
    ): ChatSocket {
        val resolved = endpoint ?: ServerEndpoint.OfficialCloud
        val url = resolved.memoh("/bots/$botId/web/ws")
        return ChatSocket(
            client = client,
            json = json,
            scope = scope,
            buildRequest = {
                if (resolved.isCloud) {
                    try {
                        cloudAuth.authorize(Request.Builder().url(cloudAuth.webSocketUrl(botId))).build()
                    } catch (e: dev.memoh.core.network.ApiException) {
                        if (e.status == 401) {
                            api?.invalidateSession()
                            throw dev.memoh.core.network.SessionExpiredException()
                        }
                        throw e
                    }
                } else {
                    val token = api?.freshBearerToken()
                    Request.Builder()
                        .url(url)
                        .apply { if (token != null) header("Authorization", "Bearer $token") }
                        .build()
                }
            },
            onEvent = onEvent,
            onStatus = onStatus,
            onResubscribeNeeded = onResubscribeNeeded,
        )
    }
}
