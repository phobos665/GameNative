package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import timber.log.Timber
import java.io.File

/**
 * Installs the EOS achievement spy (version.dll) next to the game's .exe and writes
 * the achievement server port to C:\windows\temp\eos_ach_port.txt so the DLL can
 * connect back to EpicAchievementServer at startup.
 *
 * The DLL is a proxy version.dll that:
 *   1. Forwards all real version.dll exports to the system DLL.
 *   2. IAT-patches EOS_Achievements_UnlockAchievements and POSTs {"name":"..."} to
 *      http://127.0.0.1:<port>/unlock when an achievement is successfully unlocked.
 *   3. Also polls the SDK's own player-achievement cache in the background to catch
 *      stat-threshold achievements the game never calls UnlockAchievements for.
 */
object EpicAchievementSpyDependency : LaunchDependency {
    private const val TAG = "EpicAchievementSpy"
    private const val ASSET_PATH = "epic/version.dll"

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.EPIC

    /**
     * Always reinstall so the port file is refreshed every session.
     */
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        false

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Installing epic achievement service"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        val relativeExePath = EpicService.getInstalledExe(gameId)
        if (relativeExePath.isEmpty()) {
            Timber.tag(TAG).w("No exe path for gameId=$gameId — skipping DLL install")
            return
        }
        val installPath = EpicService.getInstallPath(gameId)
        if (installPath.isNullOrEmpty()) {
            Timber.tag(TAG).w("No install path for gameId=$gameId — skipping DLL install")
            return
        }
        val exeDir = File(installPath, relativeExePath).parentFile
        if (exeDir == null || !exeDir.exists()) {
            Timber.tag(TAG).w("Exe directory does not exist: $exeDir — skipping DLL install")
            return
        }

        val dllDest = File(exeDir, "version.dll")
        try {
            context.assets.open(ASSET_PATH).use { input ->
                dllDest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Timber.tag(TAG).i("Installed version.dll to ${dllDest.absolutePath}")
        } catch (e: Exception) {
            // Non-fatal: game launches without achievement notifications.
            Timber.tag(TAG).w(e, "Failed to install version.dll — achievements won't be notified")
        }
    }
}
