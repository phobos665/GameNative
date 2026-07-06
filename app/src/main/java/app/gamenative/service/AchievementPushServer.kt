package app.gamenative.service

import android.content.Context
import android.media.MediaPlayer
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.util.AchievementNotificationManager
import org.json.JSONObject
import timber.log.Timber
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local one-shot TCP listener that comet (the GOG Galaxy Communication Service
 * reimplementation) pushes achievement-unlock events to, so GameNative can show a
 * notification immediately instead of polling. Bound to an OS-assigned ephemeral
 * port; the port is handed to comet via `--achievement-notify-port`.
 *
 * Each connection carries a single line of JSON: {"achievement_key","name","description","icon_url"}.
 */
class AchievementPushServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** Bound port, valid only after [start] returns successfully. */
    var port: Int = 0
        private set

    fun start(): Boolean {
        val socket = try {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            Timber.tag("achievement").w(e, "Failed to bind achievement push server")
            return false
        }
        serverSocket = socket
        port = socket.localPort
        running = true

        Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                handleClient(client)
            }
        }.apply { isDaemon = true }.start()

        Timber.tag("achievement").i("GOG Achievement server listening on 127.0.0.1:$port")
        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun handleClient(client: Socket) {
        Thread {
            client.use {
                try {
                    val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                    handleEvent(JSONObject(line))
                } catch (e: Exception) {
                    Timber.tag("achievement").w(e, "Failed to parse achievement push event")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handleEvent(json: JSONObject) {
        val name = json.optString("name").ifEmpty { json.optString("achievement_key") }
        val iconUrl = json.optString("icon_url").ifEmpty { null }

        Timber.tag("achievement").i("Achievement unlocked: $name")

        if (PrefManager.achievementShowNotification) {
            AchievementNotificationManager.show(name, iconUrl)
        }
        if (PrefManager.achievementPlaySound) {
            playUnlockSound()
        }
    }

    private fun playUnlockSound() {
        try {
            MediaPlayer.create(context.applicationContext, R.raw.achievement_pop)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (e: Exception) {
            Timber.tag("achievement").w(e, "Failed to play achievement unlock sound")
        }
    }
}
