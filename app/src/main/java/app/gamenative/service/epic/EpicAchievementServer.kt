package app.gamenative.service.epic

import app.gamenative.PrefManager
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Minimal HTTP server bound to 127.0.0.1 that receives achievement unlock
 * events from the in-process EOS hook DLL (version.dll).
 *
 * The hook DLL calls:
 *   POST http://127.0.0.1:[port]/unlock
 *   Content-Type: application/json
 *   {"name": "ACHIEVEMENT_API_NAME"}
 *
 * The server looks up the display name and icon from [displayNameMap] /
 * [iconUrlMap] (populated from [EpicAchievementsManager.cachedAchievements]
 * before game launch) and fires [AchievementNotificationManager.show].
 *
 * Lifecycle: call [start] just before the game launches and [stop] when it
 * exits. Both are safe to call from any thread.
 */
class EpicAchievementServer(
    private val displayNameMap: Map<String, String>,
    private val iconUrlMap: Map<String, String?>,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    /** The port this server is listening on, or -1 if not started. */
    @Volatile
    var port: Int = -1
        private set

    suspend fun start() {
        val portReady = CompletableDeferred<Int>()
        scope.launch {
            try {
                // Bind to loopback only Auto-assign port
                val ss = ServerSocket(0,4, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                portReady.complete(port)
                Timber.tag(TAG).i("EpicAchievementServer listening on 127.0.0.1:$port")

                while (!ss.isClosed) {
                    val client: Socket = try {
                        ss.accept()
                    } catch (_: SocketException) {
                        // ServerSocket was closed via stop() — expected, not an error.
                        break
                    }
                    Timber.tag(TAG).i("Accepted achievement API socket from ${client.inetAddress.hostAddress}:${client.port}")
                    handleClient(client)
                }
            } catch (e: Exception) {
                portReady.completeExceptionally(e)
                Timber.tag(TAG).e(e, "EpicAchievementServer error")
            }
        }
        portReady.await()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing server socket")
        }
        serverSocket = null
        port = -1
        scope.cancel()
        Timber.tag(TAG).d("EpicAchievementServer stopped")
    }

    private fun handleClient(client: Socket) {
        client.use {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

                // Read request line and headers
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // Log every request we receive, regardless of route, to validate hook traffic.
                Timber.tag(TAG).i("Incoming achievement API request: requestLine='$requestLine', contentLength=$contentLength")

                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    sendResponse(client, 400, "Bad Request")
                    return
                }

                // Read body
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read < 0) break
                    totalRead += read
                }
                val body = String(bodyChars, 0, totalRead)

                // Keep logs safe/compact while still showing payload visibility for debugging.
                Timber.tag(TAG).d("Incoming achievement API body: ${body.take(MAX_LOG_BODY_CHARS)}")

                // Route: only POST /unlock is supported
                if (!requestLine.startsWith("POST /unlock")) {
                    sendResponse(client, 404, "Not Found")
                    return
                }

                val json = JSONObject(body)
                val apiName = json.optString("name").takeIf { it.isNotEmpty() }
                if (apiName == null) {
                    sendResponse(client, 400, "Missing name")
                    return
                }

                onUnlock(apiName)
                sendResponse(client, 200, "OK")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error handling achievement client")
            }
        }
    }

    private fun onUnlock(name: String) {
        val displayName = displayNameMap[name] ?: name
        val iconUrl = iconUrlMap[name]
        Timber.tag(TAG).i("Achievement unlocked: $name ($displayName)")
        if (PrefManager.achievementShowNotification) {
            AchievementNotificationManager.show(displayName, iconUrl)
        }
    }

    private fun sendResponse(client: Socket, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val TAG = "EpicAchievements"

        /** Hard cap on accepted body size to prevent runaway reads. */
        private const val MAX_BODY_BYTES = 4096
        private const val MAX_LOG_BODY_CHARS = 512
    }
}
