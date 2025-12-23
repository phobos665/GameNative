package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.enums.Marker
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton


/**
 *
 * TODO: Download Game, Uninstall Game, Ensure we can track Progress via STDOUT parsing
 * TODO: Launching games using the different execution params that we store.
 *
 * | Install | `legendary install <APPNAME> --base-path <PATH> --platform Windows` | Progress output |
 * | Launch | `legendary launch <APPNAME> --offline --skip-version-check` | Launch output |
 * TODO: We should see if we need to put any disclaimers around online games not being supported and THEY BETTER NOT TRY FORTNITE.
 */


/**
 * EpicManager handles Epic Games library management
 *
 * Responsibilities:
 * - Fetch game library from Epic via native API client (no Python)
 * - Parse game metadata from Epic's catalog API
 * - Update Room database with game information
 * - Detect existing installations
 *
 * Uses direct HTTP API calls:
 * - Library sync: GET https://library-service.live.use1a.on.epicgames.com/library/api/public/items
 * - Game info: GET https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/{namespace}/bulk/items
 */
@Singleton
class EpicManager @Inject constructor(
    private val epicGameDao: EpicGameDao,
) {

    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with Epic")
                return@withContext Result.failure(Exception("Not authenticated with Epic"))
            }

            Timber.tag("Epic").i("Refreshing Epic library from Epic API...")

            // Fetch games from Epic via native API client (no Python)
            val listResult = EpicApiClient.fetchLibrary(context)

            if (listResult.isFailure) {
                val error = listResult.exceptionOrNull()
                Timber.e(error, "Failed to fetch games from Epic: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch Epic library"))
            }

            val games = listResult.getOrNull() ?: emptyList()
            Timber.tag("Epic").i("Successfully fetched ${games.size} games from Epic")

            if (games.isEmpty()) {
                Timber.w("No games found in Epic library")
                return@withContext Result.success(0)
            }

            // Update database using upsert to preserve install status
            Timber.d("Upserting ${games.size} games to database...")
            epicGameDao.upsertPreservingInstallStatus(games)

            Timber.tag("Epic").i("Successfully refreshed Epic library with ${games.size} games")
            Result.success(games.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic library")
            Result.failure(e)
        }
    }

    /**
     * Get a single game by ID
     */
    suspend fun getGameById(gameId: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by ID: $gameId")
                null
            }
        }
    }

    /**
     * Get a single game by app name (Legendary identifier)
     */
    suspend fun getGameByAppName(appName: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getByAppName(appName)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by app name: $appName")
                null
            }
        }
    }

    /**
     * Insert or update an Epic game in database
     */
    suspend fun insertGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.insert(game)
        }
    }

    /**
     * Update an Epic game in database
     */
    suspend fun updateGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.update(game)
        }
    }

    /**
     * Start background sync (called after login)
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("Epic").i("Starting Epic library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("Epic").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync Epic library in background")
            Result.failure(e)
        }
    }

    /**
     * Fetch install size for a game by downloading its manifest
     * Manifest is small (~500KB-1MB) and contains all file metadata
     * Returns size in bytes, or 0 if failed
     *
     * TODO: Implement native manifest fetching via Epic API
     */
    suspend fun fetchInstallSize(context: Context, appName: String): Long = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").w("Install size fetching not yet implemented in native client")
            // For now, return 0 - install size will need to be fetched from manifest API
            // This would require implementing Epic's manifest download protocol
            return@withContext 0L
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching install size for $appName")
            0L
        }
    }

    fun getGameInstallPath(gameTitle: String): String {
        return EpicConstants.getGameInstallPath(gameTitle)
    }

    suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val gameId = libraryItem.gameIdString
                val installPath = getGameInstallPath(libraryItem.name)
                val installDir = File(installPath)

                // Delete the manifest file
                val manifestPath = File(context.filesDir, "manifests/$gameId")
                if (manifestPath.exists()) {
                    manifestPath.delete()
                    Timber.i("Deleted manifest file for game $gameId")
                }

                // Delete game files
                if (installDir.exists()) {
                    val success = installDir.deleteRecursively()
                    if (success) {
                        Timber.i("Successfully deleted game directory: $installPath")
                    } else {
                        Timber.w("Failed to delete some game files")
                    }
                } else {
                    Timber.w("Epic game directory doesn't exist: $installPath")
                }

                // Remove all markers from container directory
                try {
                    val container = ContainerUtils.getContainer(context, libraryItem.appId)
                    val containerPath = container.rootDir.absolutePath
                    MarkerUtils.removeMarker(containerPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(containerPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                } catch (e: Exception) {
                    Timber.w(e, "Could not remove markers - container may not exist")
                }

                // Update database - mark as not installed
                val game = getGameById(gameId)
                if (game != null) {
                    val updatedGame = game.copy(isInstalled = false, installPath = "")
                    epicGameDao.update(updatedGame)
                    Timber.d("Updated database: game marked as not installed")
                }

                // Delete container (must run on Main thread)
                withContext(Dispatchers.Main) {
                    ContainerUtils.deleteContainer(context, libraryItem.appId)
                }

                // Trigger library refresh event
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId)
                )

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete epic game ${libraryItem.gameIdString}")
                Result.failure(e)
            }
        }
    }
}
