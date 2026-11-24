package com.vigil.android.feature_chat.data.remote

import com.vigil.android.core.di.api.ApiNames
import com.vigil.android.core.di.api.ChatApiService
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatWebSocketManager @Inject constructor(private val api: ChatApiService) {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Synchronized fun connect(onEvent: () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    val ticket = api.getWsTicket().body()
                    val path = ticket?.wsPath
                    if (path != null && path.startsWith("/ws/chat/?")) {
                        val origin = ApiNames.BASE_URL.toHttpUrl()
                        val url = requireNotNull(origin.resolve(path))
                        require(url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme)
                        val done = CompletableDeferred<Unit>()
                        val allowedOrigin = ticket?.wsOrigin ?: origin.toString().trimEnd('/')
                        val request = Request.Builder().url(url).header("Origin", allowedOrigin).build()
                        val socket = client.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) = onEvent()
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (runCatching { JSONObject(text).optString("type") }.getOrNull() == "chat.event") onEvent()
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(Unit) }
                        })
                        try { done.await() } finally { socket.cancel() }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                delay(10_000)
            }
        }
    }
    @Synchronized fun disconnect() { job?.cancel(); job = null }
}
