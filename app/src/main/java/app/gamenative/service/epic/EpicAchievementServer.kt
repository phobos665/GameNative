package app.gamenative.service.epic

import app.gamenative.PrefManager
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Reports which hooks version.dll actually managed to install for the
 * running game, POSTed once from [dllmain.c]'s install_hooks() right after
 * it finishes trying every IAT patch. Lets GameNative tell "this game
 * doesn't use EOS achievements" apart from "version.dll never loaded" apart
 * from "it loaded but IAT patching failed" — see [EpicAchievementServer.health].
 */
data class HookHealth(
    val unlockHook: Boolean,
    val queryHook: Boolean,
    val statsHook: Boolean,
    val pollReady: Boolean,
)

/**
 * Minimal HTTP server bound to 127.0.0.1 that receives achievement unlock
 * events from the in-process EOS hook DLL (version.dll).
 *
 * The hook DLL calls:
 *   POST http://127.0.0.1:[port]/unlock
 *   Content-Type: application/json
 *   {"name": "ACHIEVEMENT_API_NAME"}
 *
 *   POST http://127.0.0.1:[port]/health
 *   Content-Type: application/json
 *   {"unlock_hook": true, "query_hook": true, "stats_hook": true, "poll_ready": true}
 *
 * The server looks up the display name and icon from [displayNameMap] /
 * [iconUrlMap] (populated from the catalog fetched by [EpicAchievementsManager]
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

    /** Last health report from version.dll, or null if none has arrived yet. */
    @Volatile
    var health: HookHealth? = null
        private set

    suspend fun start() {
        val portReady = CompletableDeferred<Int>()
        scope.launch {
            try {
                // Bind to loopback only, auto-assign port.
                val ss = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
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
                    handleClient(client)
                }
            } catch (e: Exception) {
                portReady.completeExceptionally(e)
                Timber.tag(TAG).e(e, "EpicAchievementServer error")
            }
        }
        portReady.await()

        // version.dll only gets a chance to POST /health once it's actually loaded and
        // install_hooks() has run — if the game never loads it (or loads a different,
        // real version.dll first), we'd otherwise have no way to know achievements
        // notifications simply won't work for this session.
        scope.launch {
            delay(HEALTH_CHECK_TIMEOUT_MS)
            if (health == null) {
                Timber.tag(TAG).w(
                    "No health-check received from version.dll within ${HEALTH_CHECK_TIMEOUT_MS}ms — " +
                        "achievement notifications likely won't work this session (game may not have loaded version.dll)",
                )
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing server socket")
        }
        serverSocket = null
        port = -1
        health = null
        scope.cancel()
        Timber.tag(TAG).d("EpicAchievementServer stopped")
    }

    private fun handleClient(client: Socket) {
        client.use {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

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

                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    sendResponse(client, 400, "Bad Request")
                    return
                }

                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read < 0) break
                    totalRead += read
                }
                val body = String(bodyChars, 0, totalRead)

                when {
                    requestLine.startsWith("POST /unlock") -> {
                        val json = JSONObject(body)
                        val apiName = json.optString("name").takeIf { it.isNotEmpty() }
                        if (apiName == null) {
                            sendResponse(client, 400, "Missing name")
                            return
                        }
                        onUnlock(apiName)
                        sendResponse(client, 200, "OK")
                    }
                    requestLine.startsWith("POST /health") -> {
                        onHealth(JSONObject(body))
                        sendResponse(client, 200, "OK")
                    }
                    else -> sendResponse(client, 404, "Not Found")
                }
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

    private fun onHealth(json: JSONObject) {
        val report = HookHealth(
            unlockHook = json.optBoolean("unlock_hook", false),
            queryHook = json.optBoolean("query_hook", false),
            statsHook = json.optBoolean("stats_hook", false),
            pollReady = json.optBoolean("poll_ready", false),
        )
        health = report
        Timber.tag(TAG).i(
            "version.dll health check: unlock_hook=%s query_hook=%s stats_hook=%s poll_ready=%s",
            report.unlockHook,
            report.queryHook,
            report.statsHook,
            report.pollReady,
        )
        if (!report.unlockHook && !report.queryHook && !report.statsHook) {
            // Not necessarily a bug — plenty of Epic games don't use EOS achievements at
            // all, in which case there's nothing in their import table to hook. Worth a
            // warning either way since it also covers the "hooking the wrong DLL name/
            // architecture" failure case.
            Timber.tag(TAG).w(
                "version.dll loaded but found no EOS achievement/stat imports to hook — " +
                    "this game may not use EOS achievements directly, or hooks a different import table",
            )
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

        /** How long to wait for version.dll's first /health POST before warning. */
        private const val HEALTH_CHECK_TIMEOUT_MS = 15_000L
    }
}
