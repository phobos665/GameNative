package app.gamenative

import android.content.Context
import android.system.OsConstants
import app.gamenative.service.AchievementPushServer
import timber.log.Timber
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs comet (https://github.com/imLinguin/comet), a reimplementation of GOG Galaxy's
 * Communication Service, as a host-side process for the duration of a GOG game session.
 *
 * Comet listens on 127.0.0.1:9977. Games run under proot inside the Wine prefix share the
 * app's network namespace, so the Galaxy SDK injected into the game (Galaxy64.dll) can reach
 * this port with no bridging needed for achievements/stats/leaderboards.
 */
object CometBootstrap {

    private const val PORT = 9977

    @Volatile
    private var process: Process? = null

    @Volatile
    private var pushServer: AchievementPushServer? = null

    fun start(
        context: Context,
        accessToken: String,
        refreshToken: String,
        galaxyUserId: String,
        username: String,
    ) {
        if (process?.isAlive == true) return

        val bin = File(context.applicationInfo.nativeLibraryDir, "libcomet.so")
        if (!bin.exists()) {
            Timber.tag("comet").w("libcomet.so not found, skipping GOG achievement support")
            return
        }

        val server = AchievementPushServer(context)
        val notifyPort = if (server.start()) {
            pushServer = server
            server.port
        } else {
            null
        }

        val logFile = File(context.cacheDir, "comet.log")
        val args = mutableListOf(
            bin.absolutePath,
            "--access-token", accessToken,
            "--refresh-token", refreshToken,
            "--user-id", galaxyUserId,
            "--username", username,
        )
        if (notifyPort != null) {
            args += listOf("--achievement-notify-port", notifyPort.toString())
        }
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("XDG_DATA_HOME", context.filesDir.resolve("comet/data").absolutePath)
            put("XDG_CONFIG_HOME", context.filesDir.resolve("comet/config").absolutePath)
        }

        process = try {
            pb.start()
        } catch (e: Exception) {
            Timber.tag("comet").w(e, "Failed to start comet")
            pushServer?.stop()
            pushServer = null
            return
        }

        waitUntilListening(timeoutMs = 5_000)
        Timber.tag("comet").i("comet started for GOG user $galaxyUserId")
    }

    fun stop() {
        pushServer?.stop()
        pushServer = null

        val p = process ?: return
        process = null
        val pid = runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
        if (pid != null) {
            runCatching { android.os.Process.sendSignal(pid, OsConstants.SIGTERM) }
            waitExit(p, 3_000)
        }
        if (p.isAlive) {
            runCatching { p.destroy() }
            waitExit(p, 1_000)
        }
        Timber.tag("comet").i("comet stopped")
    }

    private fun waitExit(p: Process, timeoutMs: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    private fun waitUntilListening(timeoutMs: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", PORT), 200)
                    return
                }
            } catch (_: Exception) {
                // not up yet
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
        Timber.tag("comet").w("Timed out waiting for comet to listen on $PORT")
    }
}
