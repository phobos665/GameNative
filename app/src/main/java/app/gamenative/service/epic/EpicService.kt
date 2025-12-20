package app.gamenative.service.epic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Epic Games Service - thin coordinator that delegates to specialized managers.
 *
 * Architecture:
 * - EpicPythonBridge: Low-level Legendary CLI command execution
 * - EpicAuthManager: Authentication and account management
 * - EpicManager: Game library, downloads, and installation
 *
 * This service maintains backward compatibility through static accessors
 * while delegating all operations to the appropriate managers.
 */
@AndroidEntryPoint
class EpicService : Service() {

    companion object {
        private var instance: EpicService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null


        fun start(context: Context) {
            Timber.tag("Epic").i("[EpicService.start] Called. isRunning=$isRunning")
            if (!isRunning) {
                Timber.tag("Epic").i("[EpicService.start] Starting foreground service...")
                val intent = Intent(context, EpicService::class.java)
                context.startForegroundService(intent)
                Timber.tag("Epic").i("[EpicService.start] startForegroundService called")
            } else {
                Timber.tag("Epic").i("[EpicService.start] Service already running, skipping")
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        fun initialize(context: Context): Boolean {
            return EpicPythonBridge.initialize(context)
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to EpicAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
            return EpicAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return EpicAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
            return EpicAuthManager.getStoredCredentials(context)
        }

        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return EpicAuthManager.validateCredentials(context)
        }

        suspend fun logout(context: Context): Result<Unit> {
            return EpicAuthManager.logout(context)
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): EpicService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance EpicManager
        // ==========================================================================


        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }


        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }


        fun getDownloadInfo(appName: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(appName)
        }


        fun cleanupDownload(appName: String) {
            getInstance()?.activeDownloads?.remove(appName)
        }


        fun cancelDownload(appName: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(appName)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for Epic game: $appName")
                downloadInfo.cancel()
                instance.activeDownloads.remove(appName)
                Timber.d("Download cancelled for Epic game: $appName")
                true
            } else {
                Timber.w("No active download found for Epic game: $appName")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance EpicManager (Stubs)
        // ==========================================================================


        fun getEpicGameOf(appName: String): EpicGame? {
            return runBlocking {
                getInstance()?.epicManager?.getGameByAppName(appName)
            }
        }


        suspend fun updateEpicGame(game: EpicGame) {
            getInstance()?.epicManager?.updateGame(game)
        }


        fun isGameInstalled(appName: String): Boolean {
            val game = getEpicGameOf(appName)
            return game?.isInstalled == true
        }


        fun getInstallPath(appName: String): String? {
            val game = getEpicGameOf(appName)
            return if (game?.isInstalled == true && game.installPath.isNotEmpty()) {
                game.installPath
            } else {
                null
            }
        }


        fun verifyInstallation(appName: String): Pair<Boolean, String?> {
            // TODO: Implement when EpicManager is ready
            return Pair(false, "Not implemented")
        }


        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            // Strip EPIC_ prefix to get the raw Epic app name
            val epicAppName = libraryItem.appId.removePrefix("EPIC_")
            val game = getInstance()?.epicManager?.getGameByAppName(epicAppName)
            if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
                Timber.tag("Epic").e("Game not installed: ${libraryItem.appId}")
                return ""
            }

            // For now, return the install path - actual executable detection would require
            // parsing the game's launch manifest or config files
            // Most Epic games have a .exe in the root or Binaries folder
            val installDir = File(game.installPath)
            if (!installDir.exists()) {
                Timber.tag("Epic").e("Install directory does not exist: ${game.installPath}")
                return ""
            }

            // Try to find the main executable
            // Common patterns: Game.exe, GameName.exe, or in Binaries/Win64/
            val exeFiles = installDir.walk()
                .filter { it.extension.equals("exe", ignoreCase = true) }
                .filter { !it.name.contains("UnityCrashHandler", ignoreCase = true) }
                .filter { !it.name.contains("UnrealCEFSubProcess", ignoreCase = true) }
                .sortedBy { it.absolutePath.length } // Prefer shorter paths (usually main exe)
                .toList()

            val mainExe = exeFiles.firstOrNull()
            if (mainExe != null) {
                Timber.tag("Epic").i("Found executable: ${mainExe.absolutePath}")
                return mainExe.absolutePath
            }

            Timber.tag("Epic").w("No executable found in ${game.installPath}")
            return ""
        }


        fun getWineStartCommand(
            context: Context,
            libraryItem: LibraryItem,
            container: com.winlator.container.Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: com.winlator.core.envvars.EnvVars,
            guestProgramLauncherComponent: com.winlator.xenvironment.components.GuestProgramLauncherComponent
        ): String {
            // Strip EPIC_ prefix to get the raw Epic app name
            val epicAppName = libraryItem.appId.removePrefix("EPIC_")

            val game = runBlocking {
                getInstance()?.epicManager?.getGameByAppName(epicAppName)
            }

            if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
                Timber.tag("Epic").e("Cannot launch: game not installed")
                return "\"explorer.exe\""
            }

            // Get the executable path
            val exePath = runBlocking {
                getInstalledExe(context, libraryItem)
            }

            if (exePath.isEmpty()) {
                Timber.tag("Epic").e("Cannot launch: executable not found")
                return "\"explorer.exe\""
            }

            // Find the drive letter that's mapped to this game's install path
            var epicDriveLetter: String? = null
            for (drive in com.winlator.container.Container.drivesIterator(container.drives)) {
                if (drive[1] == game.installPath) {
                    epicDriveLetter = drive[0]
                    Timber.tag("Epic").d("Found Epic game mapped to ${drive[0]}: drive")
                    break
                }
            }

            if (epicDriveLetter == null) {
                Timber.tag("Epic").e("Epic game directory not mapped to any drive: ${game.installPath}")
                return "\"explorer.exe\""
            }

            // Convert to relative path from install directory
            val gameInstallDir = java.io.File(game.installPath)
            val execFile = java.io.File(exePath)
            val relativePath = execFile.relativeTo(gameInstallDir).path.replace('/', '\\')
            val windowsPath = "$epicDriveLetter:\\$relativePath"

            // Set working directory
            val execWorkingDir = execFile.parentFile
            if (execWorkingDir != null) {
                guestProgramLauncherComponent.workingDir = execWorkingDir
                envVars.put("WINEPATH", "$epicDriveLetter:\\")
            } else {
                guestProgramLauncherComponent.workingDir = gameInstallDir
            }

            Timber.tag("Epic").i("Launching Epic game with exe: $windowsPath")

            // Build Wine command with proper escaping
            return "\"$windowsPath\""
        }


        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.epicManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        suspend fun fetchInstallSize(context: Context, appName: String): Long {
            return getInstance()?.epicManager?.fetchInstallSize(context, appName) ?: 0L
        }

        fun downloadGame(context: Context, appName: String, installPath: String): Result<DownloadInfo> {
            val instance = getInstance()
            if (instance == null) {
                Timber.tag("Epic").e("Service not running")
                return Result.failure(Exception("Service not running"))
            }

            // Check if already downloading
            if (instance.activeDownloads.containsKey(appName)) {
                Timber.tag("Epic").w("Download already in progress for $appName")
                return Result.success(instance.activeDownloads[appName]!!)
            }

            // Start download in background
            instance.scope.launch {
                try {
                    val game = instance.epicManager.getGameByAppName(appName)
                    if (game == null) {
                        Timber.tag("Epic").e("Game not found: $appName")
                        return@launch
                    }

                    val downloadInfo = DownloadInfo()
                    downloadInfo.setActive(true)
                    instance.activeDownloads[appName] = downloadInfo

                    Timber.tag("Epic").i("Starting download for ${game.title}")

                    // Emit event for UI to start tracking progress
                    val gameId = game.id.toIntOrNull() ?: 0
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, true)
                    )

                    // Download the game
                    val result = instance.epicDownloadManager.downloadGame(
                        context = context,
                        game = game,
                        installPath = installPath,
                        downloadInfo = downloadInfo
                    )

                    if (result.isSuccess) {
                        Timber.tag("Epic").i("Download completed successfully for ${game.title}")

                        // Update game as installed
                        val updatedGame = game.copy(
                            isInstalled = true,
                            installPath = installPath
                        )
                        instance.epicManager.updateGame(updatedGame)

                        // Emit events for UI update
                        val gameId = game.id.toIntOrNull() ?: 0
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false)
                        )
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId)
                        )
                    } else {
                        Timber.tag("Epic").e("Download failed: ${result.exceptionOrNull()?.message}")

                        // Emit event for UI update on failure
                        val gameId = game.id.toIntOrNull() ?: 0
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false)
                        )
                    }

                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Download exception for $appName")

                    // Emit event for UI update on exception
                    val game = instance.epicManager.getGameByAppName(appName)
                    if (game != null) {
                        val gameId = game.id.toIntOrNull() ?: 0
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false)
                        )
                    }
                } finally {
                    instance.activeDownloads.remove(appName)
                }
            }

            // Return the DownloadInfo immediately so caller can track progress
            return Result.success(instance.activeDownloads[appName]!!)
        }


        suspend fun refreshSingleGame(appName: String, context: Context): Result<EpicGame?> {
            // For now, just get from database
            val game = getInstance()?.epicManager?.getGameByAppName(appName)
            return if (game != null) {
                Result.success(game)
            } else {
                Result.failure(Exception("Game not found: $appName"))
            }
        }


        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            // TODO: Implement when EpicManager is ready
            return Result.failure(Exception("Not implemented"))
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var epicManager: EpicManager

    @Inject
    lateinit var epicDownloadManager: EpicDownloadManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by Epic app name
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.tag("Epic").i("[EpicService] Service created")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("Epic").i("[EpicService] onStartCommand() called")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("Epic Games Service running...")
        startForeground(3, notification) // Use different ID than Steam (1) and GOG (2)
        Timber.tag("Epic").i("[EpicService] Started as foreground service")

        // Start background library sync automatically when service starts
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.tag("Epic").i("[EpicService] Starting background library sync...")

                val syncResult = epicManager.startBackgroundSync(applicationContext)
                if (syncResult.isFailure) {
                    Timber.tag("Epic").w("[EpicService] Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                } else {
                    Timber.tag("Epic").i("[EpicService] Background library sync completed successfully")
                }
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[EpicService] Exception starting background sync")
            } finally {
                setSyncInProgress(false)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Epic").i("[EpicService] Service destroyed")

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
