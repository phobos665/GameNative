package app.gamenative.service.gog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * GOG Service - thin coordinator that delegates to specialized managers.
 *
 * Architecture:
 * - GOGPythonBridge: Low-level Python/GOGDL command execution
 * - GOGAuthManager: Authentication and account management
 * - GOGManager: Game library, downloads, and installation
 *
 * This service maintains backward compatibility through static accessors
 * while delegating all operations to the appropriate managers.
 */
@AndroidEntryPoint
class GOGService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.GOG_SYNC_LIBRARY"

        private var instance: GOGService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, GOGService::class.java)
            if (!isRunning) {
                Timber.d("[GOGService] Starting service for first time")
                context.startForegroundService(intent)
            } else {
                Timber.d("[GOGService] Service already running, triggering sync")
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }


        fun initialize(context: Context): Boolean {
            return GOGPythonBridge.initialize(context)
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to GOGAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
            return GOGAuthManager.authenticateWithCode(context, authorizationCode)
        }


        fun hasStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.hasStoredCredentials(context)
        }


        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return GOGAuthManager.getStoredCredentials(context)
        }


        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return GOGAuthManager.validateCredentials(context)
        }


        fun clearStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.clearStoredCredentials(context)
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

        /**
         * Trigger a background library sync
         * Can be called even if service is already running
         */
        fun triggerLibrarySync(context: Context) {
            val intent = Intent(context, GOGService::class.java)
            intent.action = ACTION_SYNC_LIBRARY
            context.startForegroundService(intent)
        }

        fun getInstance(): GOGService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }


        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }


        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }


        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }


        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(gameId)
                Timber.d("Download cancelled for game: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameById(gameId)
            }
        }

        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameById(gameId)
                if (game?.isInstalled != true) {
                    return@runBlocking false
                }

                // Verify the installation is actually valid
                val (isValid, errorMessage) = getInstance()?.gogManager?.verifyInstallation(gameId)
                    ?: Pair(false, "Service not available")
                if (!isValid) {
                    Timber.w("Game $gameId marked as installed but verification failed: $errorMessage")
                }
                isValid
            }
        }


        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }


        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            return getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }


        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            return getInstance()?.gogManager?.getInstalledExe(context, libraryItem)
                ?: ""
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
            return getInstance()?.gogManager?.getWineStartCommand(
                context, libraryItem, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent
            ) ?: "\"explorer.exe\""
        }


        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }


        fun downloadGame(context: Context, gameId: String, installPath: String): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            // Create DownloadInfo for progress tracking
            val downloadInfo = DownloadInfo(jobCount = 1)

            // Track in activeDownloads first
            instance.activeDownloads[gameId] = downloadInfo

            // Launch download in service scope so it runs independently
            instance.scope.launch {
                try {
                    Timber.d("[Download] Starting download for game $gameId")
                    val result = instance.gogManager.downloadGame(context, gameId, installPath, downloadInfo)

                    if (result.isFailure) {
                        Timber.e(result.exceptionOrNull(), "[Download] Failed for game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                    } else {
                        Timber.i("[Download] Completed successfully for game $gameId")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                } finally {
                    // Remove from activeDownloads for both success and failure
                    // so UI knows download is complete and to prevent stale entries
                    instance.activeDownloads.remove(gameId)
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }

            return Result.success(downloadInfo)
        }


        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Delete/uninstall a GOG game
         * Delegates to GOGManager.deleteGame
         */
        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            return getInstance()?.gogManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var gogManager: GOGManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    // GOGManager is injected by Hilt
    override fun onCreate() {
        super.onCreate()
        instance = this


        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[GOGService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("GOG Service running...")
        startForeground(2, notification) // Use different ID than SteamService (which uses 1)

        // Start background library sync if service is starting or sync action requested
        if (intent?.action == ACTION_SYNC_LIBRARY || backgroundSyncJob == null || !backgroundSyncJob!!.isActive) {
            Timber.i("[GOGService] Triggering background library sync")
            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.d("[GOGService]: Starting background library sync")

                    val syncResult = gogManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.i("[GOGService]: Background library sync started successfully")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService]: Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else {
            Timber.d("[GOGService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

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
