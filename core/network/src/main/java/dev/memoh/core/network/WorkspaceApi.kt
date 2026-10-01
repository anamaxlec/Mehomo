package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.serialization.serializer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

private fun workspacePath(botId: String) = "/bots/$botId/container"
private suspend inline fun <reified T> MemohApi.workspaceCall(path: String, method: String = "GET", body: String? = null): T =
    call(path, method, body, deserializer = serializer<T>())

suspend fun MemohApi.terminalInfo(botId: String): TerminalInfo = workspaceCall("${workspacePath(botId)}/terminal")
suspend fun MemohApi.createBrowserSession(botId: String, port: Int, path: String): BrowserSession =
    workspaceCall("${workspacePath(botId)}/browser/sessions", "POST", apiBody("port" to port, "path" to path).toString())
suspend fun MemohApi.keepBrowserSession(botId: String, id: String): Unit =
    workspaceCall("${workspacePath(botId)}/browser/sessions/$id/keepalive", "POST", "{}")
suspend fun MemohApi.closeBrowserSession(botId: String, id: String): Unit =
    workspaceCall("${workspacePath(botId)}/browser/sessions/$id", "DELETE")
suspend fun MemohApi.displayInfo(botId: String): DisplayInfo = workspaceCall("${workspacePath(botId)}/display")
suspend fun MemohApi.displayAnswer(botId: String, sdp: String): DisplayAnswer =
    workspaceCall("${workspacePath(botId)}/display/webrtc/offer", "POST",
        apiBody("type" to "offer", "sdp" to sdp, "candidate_host" to workspaceCandidateHost).toString())
suspend fun MemohApi.closeDisplaySession(botId: String, id: String): Unit =
    workspaceCall("${workspacePath(botId)}/display/sessions/$id", "DELETE")
fun MemohApi.prepareDisplay(botId: String) = sse("${workspacePath(botId)}/display/prepare", DisplayPrepareEvent.serializer(), apiBody(), "POST")
val MemohApi.usesRuntimeDisplay: Boolean get() = runtimeDisplay
internal suspend fun MemohApi.runtimeDisplaySession(botId: String): RuntimeDisplaySession =
    workspaceCall("${workspacePath(botId)}/display/runtime-session", "POST", "{}")

/** Official Cloud uses the runtime gateway's RFB stream, not the self-hosted WebRTC offer. */
class RuntimeDisplayConnection(private val api: MemohApi, private val botId: String,
    private val onOpened: () -> Unit, private val onOutput: (ByteArray) -> Unit,
    private val onClosed: (String?) -> Unit) {
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var opened = false
    suspend fun connect() {
        val session = api.runtimeDisplaySession(botId)
        if (closed.get()) return
        val request = api.runtimeDisplayRequest(session)
        if (closed.get()) return
        socket = api.networkClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed.get()) { webSocket.close(1000, "Leaving display"); return }
                    opened = true; onOpened()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!closed.get()) onOutput(bytes.toByteArray())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed.compareAndSet(false, true)) onClosed("桌面连接已关闭")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) api.invalidateSession()
                    if (closed.compareAndSet(false, true)) onClosed(response?.code?.let { "桌面连接失败（HTTP $it）" } ?: "桌面连接中断，请重新连接")
                }
            })
        if (closed.get()) socket?.cancel()
    }
    fun input(bytes: ByteArray): Boolean = !closed.get() && opened && socket?.send(bytes.toByteString()) == true
    fun close() {
        closed.set(true)
        if (opened) socket?.close(1000, "Leaving display") else socket?.cancel()
        socket = null
    }
}

data class BrowserAddress(val port: Int, val path: String) {
    val display: String get() = "localhost:$port$path"
}
fun parseBrowserAddress(raw: String): BrowserAddress {
    var address = raw.trim().ifBlank { "localhost:5173/" }
    if (Regex("^:\\d+(?:$|[/?#])").containsMatchIn(address)) address = "localhost$address"
    else if (Regex("^\\d+(?:$|[/?#])").containsMatchIn(address)) address = "localhost:$address"
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(address)) address = "http://$address"
    val uri = try { URI(address) } catch (_: Exception) { throw IllegalArgumentException("请输入 localhost:端口/路径") }
    require(uri.scheme.lowercase() in listOf("http", "https") && uri.userInfo == null &&
        uri.host?.lowercase() in listOf("localhost", "127.0.0.1", "[::1]", "::1")) { "地址需使用 localhost、127.0.0.1 或 [::1]" }
    require(uri.port in 1..65535) { "请填写 1–65535 之间的端口" }
    return BrowserAddress(uri.port, (uri.rawPath?.ifBlank { "/" } ?: "/") +
        (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: ""))
}

/** A PTY connection. Input is binary UTF-8; resize is the server's JSON control frame. */
class TerminalConnection(
    private val api: MemohApi, private val botId: String,
    private val onConnected: () -> Unit,
    private val onOutput: (ByteArray) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    suspend fun connect(cols: Int, rows: Int) {
        val request = api.socketRequest("${workspacePath(botId)}/terminal/ws?cols=${cols.coerceIn(1, 500)}&rows=${rows.coerceIn(1, 500)}")
        if (closed.get()) return
        socket = api.networkClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) { webSocket.close(1000, "Leaving terminal"); return }
                connected = true; onConnected()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (!closed.get()) onOutput(bytes.toByteArray()) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (!closed.get()) onOutput(text.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.compareAndSet(false, true)) onClosed(if (code == 1000) null else "终端连接已关闭（$code）")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) api.invalidateSession()
                if (closed.compareAndSet(false, true)) onClosed(response?.code?.let { "终端连接失败（HTTP $it）" } ?: "终端连接中断，请重新连接")
            }
        })
        if (closed.get()) socket?.cancel()
    }
    fun input(data: ByteArray): Boolean = !closed.get() && connected && socket?.send(data.toByteString()) == true
    fun resize(cols: Int, rows: Int): Boolean = !closed.get() && socket?.send(apiBody("type" to "resize", "cols" to cols.coerceIn(1, 500), "rows" to rows.coerceIn(1, 500)).toString()) == true
    fun close() {
        closed.set(true)
        if (connected) socket?.close(1000, "Leaving terminal") else socket?.cancel()
        socket = null
    }
}
