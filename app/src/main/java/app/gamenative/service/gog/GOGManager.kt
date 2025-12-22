package app.gamenative.service.gog

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.GameSource
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SyncResult
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Data class to hold size information from gogdl info command
 */
data class GameSizeInfo(
    val downloadSize: Long,
    val diskSize: Long
)

/**
 * Unified manager for GOG game and library operations.
 *
 * Responsibilities:
 * - Database CRUD for GOG games
 * - Library syncing from GOG API
 * - Game downloads and installation
 * - Installation verification
 * - Executable discovery
 * - Wine launch commands
 * - File system operations
 *
 * Uses GOGPythonBridge for all GOGDL command execution.
 * Uses GOGAuthManager for authentication checks.
 */
@Singleton
class GOGManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
) {

    // Thread-safe cache for download sizes
    private val downloadSizeCache = ConcurrentHashMap<String, String>()
    private val REFRESH_BATCH_SIZE = 10

    suspend fun getGameById(gameId: String): GOGGame? {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get GOG game by ID: $gameId")
                null
            }
        }
    }

    suspend fun insertGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.insert(game)
        }
    }

    suspend fun updateGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.update(game)
        }
    }


    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("GOG").i("Starting GOG library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("GOG").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync GOG library in background")
            Result.failure(e)
        }
    }

    /**
     * Refresh the entire library (called manually by user)
     * Fetches all games from GOG API and updates the database
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with GOG")
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            Timber.tag("GOG").i("Refreshing GOG library from GOG API...")

            // Fetch games from GOG via GOGDL Python backend

            var gameIdList = listGameIds(context)

            if(!gameIdList.isSuccess){
                val error = gameIdList.exceptionOrNull()
                Timber.e(error, "Failed to fetch GOG game IDs: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch GOG game IDs"))
            }

            val gameIds = gameIdList.getOrNull() ?: emptyList()
            Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs from GOG")

            if (gameIds.isEmpty()) {
                Timber.w("No games found in GOG library")
                return@withContext Result.success(0)
            }

            var totalProcessed = 0

            Timber.tag("GOG").d("Getting Game Details for GOG Games...")

            val games = mutableListOf<GOGGame>()
            val authConfigPath = GOGAuthManager.getAuthConfigPath(context)

            for ((index, id) in gameIds.withIndex()) {
                try {
                    val result = GOGPythonBridge.executeCommand(
                        "--auth-config-path", authConfigPath,
                        "game-details",
                        "--game_id", id,
                        "--pretty"
                    )

                    if (result.isSuccess) {
                        val output = result.getOrNull() ?: ""
                        Timber.tag("GOG").d("Got Game Details for ID: $id")
                        val gameDetails = JSONObject(output.trim())
                        val game = parseGameObject(gameDetails)
                        games.add(game)
                        Timber.tag("GOG").d("Refreshed GOG game ID $id: ${game.title}")
                        totalProcessed++
                    } else {
                        Timber.w("GOG game ID $id not found in library after refresh")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse game details for ID: $id")
                }

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == gameIds.size - 1) {
                    if (games.isNotEmpty()) {
                        gogGameDao.upsertPreservingInstallStatus(games)
                        Timber.tag("GOG").d("Batch inserted ${games.size} games (processed ${index + 1}/${gameIds.size})")
                        games.clear()
                    }
                }
            }
            val detectedCount = detectAndUpdateExistingInstallations()
            if (detectedCount > 0) {
                Timber.d("Detected and updated $detectedCount existing installations")
            }
            Timber.tag("GOG").i("Successfully refreshed GOG library with $totalProcessed games")
            return@withContext Result.success(totalProcessed)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh GOG library")
            return@withContext Result.failure(e)
        }
    }

    private suspend fun listGameIds(context: Context): Result<List<String>> {

            Timber.tag("GOG").i("Fetching GOG Game Ids via GOGDL...")
            val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
            if (!GOGAuthManager.hasStoredCredentials(context)) {
              Timber.e("Cannot list games: not authenticated")
              return Result.failure(Exception("Not authenticated. Please log in first."))
          }

            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "game-ids")

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.e(error, "Failed to fetch GOG game IDs")
                return Result.failure(error ?: Exception("Failed to fetch GOG game IDs"))
            }

            val output = result.getOrNull() ?: ""

            if (output.isBlank()) {
                Timber.w("Empty response when fetching GOG game IDs")
                return Result.failure(Exception("Empty response from GOGDL"))
            }

            val gamesArray = org.json.JSONArray(output.trim())
            val gameIds = List(gamesArray.length()) { gamesArray.getString(it) }
            Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs")

            return Result.success(gameIds)
    }

    private fun parseGameObject(gameObj: JSONObject): GOGGame {
        val genresList = parseJsonArray(gameObj.optJSONArray("genres"))
        val languagesList = parseJsonArray(gameObj.optJSONArray("languages"))

        return GOGGame(
            id = gameObj.optString("id", ""),
            title = gameObj.optString("title", "Unknown Game"),
            slug = gameObj.optString("slug", ""),
            imageUrl = gameObj.optString("imageUrl", ""),
            iconUrl = gameObj.optString("iconUrl", ""),
            description = gameObj.optString("description", ""),
            releaseDate = gameObj.optString("releaseDate", ""),
            developer = gameObj.optString("developer", ""),
            publisher = gameObj.optString("publisher", ""),
            genres = genresList,
            languages = languagesList,
            downloadSize = gameObj.optLong("downloadSize", 0L),
            installSize = 0L,
            isInstalled = false,
            installPath = "",
            lastPlayed = 0L,
            playTime = 0L,
        )
    }

    private fun parseJsonArray(jsonArray: org.json.JSONArray?): List<String> {
        val result = mutableListOf<String>()
        if (jsonArray != null) {
            for (j in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(j))
            }
        }
        return result
    }

    /**
     * Scan the GOG games directories for existing installations
     * and update the database with installation info
     *
     * @return Number of installations detected and updated
     */
    private suspend fun detectAndUpdateExistingInstallations(): Int = withContext(Dispatchers.IO) {
        var detectedCount = 0

        try {
            // Check both internal and external storage paths
            val pathsToCheck = listOf(
                GOGConstants.internalGOGGamesPath,
                GOGConstants.externalGOGGamesPath
            )

            for (basePath in pathsToCheck) {
                val baseDir = File(basePath)
                if (!baseDir.exists() || !baseDir.isDirectory) {
                    Timber.d("Skipping non-existent path: $basePath")
                    continue
                }

                Timber.d("Scanning for installations in: $basePath")
                val installDirs = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()

                for (installDir in installDirs) {
                    try {
                        val detectedGame = detectGameFromDirectory(installDir)
                        if (detectedGame != null) {
                            // Update database with installation info
                            val existingGame = getGameById(detectedGame.id)
                            if (existingGame != null && !existingGame.isInstalled) {
                                val updatedGame = existingGame.copy(
                                    isInstalled = true,
                                    installPath = detectedGame.installPath,
                                    installSize = detectedGame.installSize
                                )
                                updateGame(updatedGame)
                                detectedCount++
                                Timber.i("Detected existing installation: ${existingGame.title} at ${installDir.absolutePath}")
                            } else if (existingGame != null) {
                                Timber.d("Game ${existingGame.title} already marked as installed")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Error detecting game in ${installDir.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during installation detection")
        }

        detectedCount
    }

    /**
     * Try to detect which game is installed in the given directory
     * by looking for GOG-specific files and matching against the database
     *
     * @param installDir The directory to check
     * @return GOGGame with installation info, or null if no game detected
     */
    private suspend fun detectGameFromDirectory(installDir: File): GOGGame? {
        if (!installDir.exists() || !installDir.isDirectory) {
            return null
        }

        val dirName = installDir.name
        Timber.d("Checking directory: $dirName")

        // Look for .info files which contain game metadata
        val infoFiles = installDir.listFiles { file ->
            file.isFile && file.extension == "info"
        } ?: emptyArray()

        if (infoFiles.isNotEmpty()) {
            // Try to parse game ID from .info file
            val infoFile = infoFiles.first()
            try {
                val infoContent = infoFile.readText()
                val infoJson = JSONObject(infoContent)
                val gameId = infoJson.optString("gameId", "")
                if (gameId.isNotEmpty()) {
                    val game = getGameById(gameId)
                    if (game != null) {
                        val installSize = calculateDirectorySize(installDir)
                        return game.copy(
                            isInstalled = true,
                            installPath = installDir.absolutePath,
                            installSize = installSize
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing .info file: ${infoFile.name}")
            }
        }

        // Fallback: Try to match by directory name with game titles in database
        val allGames = gogGameDao.getAllAsList()
        for (game in allGames) {
            // Sanitize game title to match directory naming convention
            val sanitizedTitle = game.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()

            if (dirName.equals(sanitizedTitle, ignoreCase = true)) {
                // Verify it's actually a game directory (has executables or subdirectories)
                val hasContent = installDir.listFiles()?.any {
                    it.isDirectory || it.extension in listOf("exe", "dll", "bat")
                } == true

                if (hasContent) {
                    val installSize = calculateDirectorySize(installDir)
                    Timber.d("Matched directory '$dirName' to game '${game.title}'")
                    return game.copy(
                        isInstalled = true,
                        installPath = installDir.absolutePath,
                        installSize = installSize
                    )
                }
            }
        }

        return null
    }

    /**
     * Calculate the total size of a directory recursively
     *
     * @param directory The directory to calculate size for
     * @return Total size in bytes
     */
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return 0L
            }

            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }

    suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
        return try {
            Timber.d("Fetching single game data for gameId: $gameId")
            val authConfigPath = GOGAuthManager.getAuthConfigPath(context)

            if (!GOGAuthManager.hasStoredCredentials(context)) {
                return Result.failure(Exception("Not authenticated"))
            }

            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "game-details", "--game_id", gameId, "--pretty")

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Failed to fetch game data"))
            }

            val output = result.getOrNull() ?: ""

            if(result != null){
                val gameDetails = org.json.JSONObject(output.trim())
                var game = parseGameObject(gameDetails)
                insertGame(game)
                return Result.success(game)
            }

            Timber.w("Game $gameId not found in GOG library")
            Result.success(null)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching single game data for $gameId")
            Result.failure(e)
        }
    }

    suspend fun downloadGame(context: Context, gameId: String, installPath: String, downloadInfo: DownloadInfo): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Check authentication first
                if (!GOGAuthManager.hasStoredCredentials(context)) {
                    return@withContext Result.failure(Exception("Not authenticated. Please login to GOG first."))
                }

                Timber.i("[Download] Starting GOGDL download for game $gameId to $installPath")

                val installDir = File(installPath)
                if (!installDir.exists()) {
                    Timber.d("[Download] Creating install directory: $installPath")
                    installDir.mkdirs()
                }

                // Create support directory for redistributables
                val parentDir = installDir.parentFile
                val supportDir = if (parentDir != null) {
                    File(parentDir, "gog-support")
                } else {
                    Timber.w("[Download] installDir.parentFile is null for $installPath, using installDir as fallback parent")
                    File(installDir, "gog-support")
                }
                if (!supportDir.exists()) {
                    Timber.d("[Download] Creating support directory: ${supportDir.absolutePath}")
                    supportDir.mkdirs()
                }

                // Get expected download size from database for accurate progress tracking
                val game = getGameById(gameId)
                if (game != null && game.downloadSize > 0L) {
                    downloadInfo.setTotalExpectedBytes(game.downloadSize)
                    Timber.d("[Download] Set total expected bytes: ${game.downloadSize} (${game.downloadSize / 1_000_000} MB)")
                } else {
                    Timber.w("[Download] Could not determine download size for game $gameId")
                }

                val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                val numericGameId = ContainerUtils.extractGameIdFromContainerId(gameId).toString()

                Timber.d("[Download] Calling GOGPythonBridge with gameId=$numericGameId, authConfig=$authConfigPath")

                // Initialize progress and emit download started event
                downloadInfo.setProgress(0.0f)
                downloadInfo.setActive(true)
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true)
                )

                val result = GOGPythonBridge.executeCommandWithCallback(
                    downloadInfo,
                    "--auth-config-path", authConfigPath,
                    "download", numericGameId,
                    "--platform", "windows",
                    "--path", installPath,
                    "--support", supportDir.absolutePath,
                    "--skip-dlcs",
                    "--lang", "en-US",
                    "--max-workers", "1",
                )

                if (result.isSuccess) {
                    downloadInfo.setProgress(1.0f)
                    Timber.i("[Download] GOGDL download completed successfully for game $gameId")

                    // Calculate actual disk size
                    val diskSize = calculateDirectorySize(File(installPath))
                    Timber.d("[Download] Calculated install size: $diskSize bytes (${diskSize / 1_000_000} MB)")

                    // Update or create database entry
                    var game = getGameById(gameId)
                    if (game != null) {
                        // Game exists - update install status
                        Timber.d("Updating existing game install status: isInstalled=true, installPath=$installPath, installSize=$diskSize")
                        val updatedGame = game.copy(
                            isInstalled = true,
                            installPath = installPath,
                            installSize = diskSize
                        )
                        updateGame(updatedGame)
                        Timber.i("Updated GOG game install status in database for ${game.title}")
                    } else {
                        // Game not in database - fetch from API and insert
                        Timber.w("Game not found in database, fetching from GOG API for gameId: $gameId")
                        try {
                            val refreshResult = refreshSingleGame(gameId, context)
                            if (refreshResult.isSuccess) {
                                game = refreshResult.getOrNull()
                                if (game != null) {
                                    val updatedGame = game.copy(
                                        isInstalled = true,
                                        installPath = installPath,
                                        installSize = diskSize
                                    )
                                    insertGame(updatedGame)
                                    Timber.i("Fetched and inserted GOG game ${game.title} with install status")
                                } else {
                                    Timber.w("Failed to fetch game data from GOG API for gameId: $gameId")
                                }
                            } else {
                                Timber.e(refreshResult.exceptionOrNull(), "Error fetching game from GOG API: $gameId")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Exception fetching game from GOG API: $gameId")
                        }
                    }

                    // Verify installation
                    val (isValid, errorMessage) = verifyInstallation(gameId)
                    if (!isValid) {
                        Timber.w("Installation verification failed for game $gameId: $errorMessage")
                    } else {
                        Timber.i("Installation verified successfully for game: $gameId")
                    }

                    // Emit completion events
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false)
                    )
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0)
                    )

                    Result.success(Unit)
                } else {
                    downloadInfo.setProgress(-1.0f)
                    val error = result.exceptionOrNull()
                    Timber.e(error, "[Download] GOGDL download failed for game $gameId")

                    // Emit download stopped event on failure
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false)
                    )

                    Result.failure(error ?: Exception("Download failed"))
                }
            } catch (e: Exception) {
                Timber.e(e, "[Download] Exception during download for game $gameId")
                downloadInfo.setProgress(-1.0f)

                // Emit download stopped event on exception
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false)
                )

                Result.failure(e)
            }
        }
    }

    suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val gameId = libraryItem.gameId.toString()
                val installPath = getGameInstallPath(context, gameId, libraryItem.name)
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
                    Timber.w("GOG game directory doesn't exist: $installPath")
                }

                // Remove all markers
                val appDirPath = getAppDirPath(libraryItem.appId)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                // Update database - mark as not installed
                val game = getGameById(gameId)
                if (game != null) {
                    val updatedGame = game.copy(isInstalled = false, installPath = "")
                    gogGameDao.update(updatedGame)
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
                Timber.e(e, "Failed to delete GOG game ${libraryItem.gameId}")
                Result.failure(e)
            }
        }
    }

    fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        try {
            val appDirPath = getAppDirPath(libraryItem.appId)

            // Use marker-based approach
            val isDownloadComplete = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            val isInstalled = isDownloadComplete && !isDownloadInProgress

            // Update database if status changed
            val gameId = libraryItem.gameId.toString()
            val game = runBlocking { getGameById(gameId) }
            if (game != null && isInstalled != game.isInstalled) {
                val installPath = if (isInstalled) getGameInstallPath(context, gameId, libraryItem.name) else ""
                val updatedGame = game.copy(isInstalled = isInstalled, installPath = installPath)
                runBlocking { gogGameDao.update(updatedGame) }
            }

            return isInstalled
        } catch (e: Exception) {
            Timber.e(e, "Error checking if GOG game is installed")
            return false
        }
    }


    fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
        val game = runBlocking { getGameById(gameId) }
        val installPath = game?.installPath

        if (installPath == null || !game.isInstalled) {
            return Pair(false, "Game not marked as installed in database")
        }

        val installDir = File(installPath)
        if (!installDir.exists()) {
            return Pair(false, "Install directory not found: $installPath")
        }

        if (!installDir.isDirectory) {
            return Pair(false, "Install path is not a directory")
        }

        val contents = installDir.listFiles()
        if (contents == null || contents.isEmpty()) {
            return Pair(false, "Install directory is empty")
        }

        Timber.i("Installation verified for game $gameId at $installPath")
        return Pair(true, null)
    }

    // Get the exe. There is a v1 and v2 depending on the age of the game.
    suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId.toString()
        try {
            val game = getGameById(gameId) ?: return@withContext ""
            val installPath = getGameInstallPath(context, game.id, game.title)

            // Try V2 structure first (game_$gameId subdirectory)
            val v2GameDir = File(installPath, "game_$gameId")
            if (v2GameDir.exists()) {
                return@withContext getGameExecutable(installPath, v2GameDir)
            }

            // Try V1 structure
            val installDirFile = File(installPath)
            val subdirs = installDirFile.listFiles()?.filter {
                it.isDirectory && it.name != "saves"
            } ?: emptyList()

            if (subdirs.isNotEmpty()) {
                return@withContext getGameExecutable(installPath, subdirs.first())
            }

            ""
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for GOG game $gameId")
            ""
        }
    }

    private fun getGameExecutable(installPath: String, gameDir: File): String {
        val result = getMainExecutableFromGOGInfo(gameDir, installPath)
        if (result.isSuccess) {
            val exe = result.getOrNull() ?: ""
            Timber.d("Found GOG game executable from info file: $exe")
            return exe
        }
        Timber.e(result.exceptionOrNull(), "Failed to find executable from GOG info file in: ${gameDir.absolutePath}")
        return ""
    }

    private fun getMainExecutableFromGOGInfo(gameDir: File, installPath: String): Result<String> {
        return try {
            val infoFile = gameDir.listFiles()?.find {
                it.isFile && it.name.startsWith("goggame-") && it.name.endsWith(".info")
            } ?: return Result.failure(Exception("GOG info file not found in ${gameDir.absolutePath}"))

            val content = infoFile.readText()
            val jsonObject = JSONObject(content)

            if (!jsonObject.has("playTasks")) {
                return Result.failure(Exception("playTasks array not found in ${infoFile.name}"))
            }

            val playTasks = jsonObject.getJSONArray("playTasks")
            for (i in 0 until playTasks.length()) {
                val task = playTasks.getJSONObject(i)
                if (task.has("isPrimary") && task.getBoolean("isPrimary")) {
                    val executablePath = task.getString("path")
                    val actualExeFile = gameDir.listFiles()?.find {
                        it.name.equals(executablePath, ignoreCase = true)
                    }
                    if (actualExeFile != null && actualExeFile.exists()) {
                        return Result.success("${gameDir.name}/${actualExeFile.name}")
                    }
                    return Result.failure(Exception("Primary executable '$executablePath' not found in ${gameDir.absolutePath}"))
                }
            }
            Result.failure(Exception("No primary executable found in playTasks"))
        } catch (e: Exception) {
            Result.failure(Exception("Error parsing GOG info file in ${gameDir.absolutePath}: ${e.message}", e))
        }
    }

    fun getWineStartCommand(
        context: Context,
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId)

        // Verify installation
        val (isValid, errorMessage) = verifyInstallation(gameId.toString())
        if (!isValid) {
            Timber.e("Installation verification failed: $errorMessage")
            return "\"explorer.exe\""
        }

        val game = runBlocking { getGameById(gameId.toString()) }
        if (game == null) {
            Timber.e("Game not found for ID: $gameId")
            return "\"explorer.exe\""
        }

        val gameInstallPath = getGameInstallPath(context, gameId.toString(), game.title)
        val gameDir = File(gameInstallPath)

        if (!gameDir.exists()) {
            Timber.e("Game directory does not exist: $gameInstallPath")
            return "\"explorer.exe\""
        }

        val executablePath = runBlocking { getInstalledExe(context, libraryItem) }
        if (executablePath.isEmpty()) {
            Timber.w("No executable found, opening file manager")
            return "\"explorer.exe\""
        }

        // Find the drive letter that's mapped to this game's install path
        var gogDriveLetter: String? = null
        for (drive in com.winlator.container.Container.drivesIterator(container.drives)) {
            if (drive[1] == gameInstallPath) {
                gogDriveLetter = drive[0]
                Timber.d("Found GOG game mapped to ${drive[0]}: drive")
                break
            }
        }

        if (gogDriveLetter == null) {
            Timber.e("GOG game directory not mapped to any drive: $gameInstallPath")
            return "\"explorer.exe\""
        }

        val gameInstallDir = File(gameInstallPath)
        val execFile = File(gameInstallPath, executablePath)
        val relativePath = execFile.relativeTo(gameInstallDir).path.replace('/', '\\')
        val windowsPath = "$gogDriveLetter:\\$relativePath"

        // Set working directory
        val execWorkingDir = execFile.parentFile
        if (execWorkingDir != null) {
            guestProgramLauncherComponent.workingDir = execWorkingDir
            envVars.put("WINEPATH", "$gogDriveLetter:\\")
        } else {
            guestProgramLauncherComponent.workingDir = gameDir
        }

        Timber.d("GOG Wine command: \"$windowsPath\"")
        return "\"$windowsPath\""
    }

    // TODO: Implement Cloud Saves here

    // ==========================================================================
    // FILE SYSTEM & PATHS
    // ==========================================================================

    fun getAppDirPath(appId: String): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        val game = runBlocking { getGameById(gameId.toString()) }

        if (game != null) {
            return GOGConstants.getGameInstallPath(game.title)
        }

        Timber.w("Could not find game for appId $appId")
        return GOGConstants.defaultGOGGamesPath
    }

    fun getGameInstallPath(context: Context, gameId: String, gameTitle: String): String {
        return GOGConstants.getGameInstallPath(gameTitle)
    }

}
