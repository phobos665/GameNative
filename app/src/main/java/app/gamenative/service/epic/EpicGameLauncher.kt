package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.data.EpicEpicEpicGameToken
import timber.log.Timber
import java.io.File

/**
 * Helper for launching Epic Games with proper authentication parameters
 * Based on Legendary's game launch implementation
 *
 * Handles:
 * - Getting authentication tokens before launch
 * - Building Epic Games Services command-line parameters
 * - Managing ownership token files for DRM-protected games
 */
object EpicGameLauncher {

    /**
     * Build launch parameters for an Epic game
     *
     * Returns a list of command-line arguments to pass to the game executable
     * for Epic Games Services authentication
     *
     * @param context Android context
     * @param game Epic game to launch
     * @param offline Whether to launch in offline mode (no authentication)
     * @param userDisplayName Override for user display name (optional)
     * @param languageCode Language code (e.g., "en-US")
     * @return Result containing list of launch parameters
     */
    suspend fun buildLaunchParameters(
        context: Context,
        game: EpicGame,
        offline: Boolean = false,
        userDisplayName: String? = null,
        languageCode: String = "en-US"
    ): Result<List<String>> {
        return try {
            val params = mutableListOf<String>()

            // Check if game can run offline
            if (offline && !game.canRunOffline) {
                Timber.w("Game ${game.appName} is not marked for offline use (may still work)")
            }

            // Get authentication tokens if online mode
            val gameToken: EpicEpicGameToken?
            val ownershipTokenPath: String?

            if (!offline) {
                Timber.d("Getting game launch token for ${game.appName}...")

                val tokenResult = EpicAuthManager.getGameLaunchToken(
                    context = context,
                    namespace = game.namespace,
                    catalogItemId = game.catalogId,
                    requiresOwnershipToken = game.requiresOT
                )

                if (tokenResult.isFailure) {
                    return Result.failure(tokenResult.exceptionOrNull() ?: Exception("Failed to get launch token"))
                }

                gameToken = tokenResult.getOrNull()!!

                // Save ownership token to temp file if present
                ownershipTokenPath = if (gameToken.ownershipToken != null) {
                    saveOwnershipTokenToFile(context, game.namespace, game.catalogId, gameToken.ownershipToken)
                } else {
                    null
                }

                Timber.i("Game launch token obtained for ${game.appName}")
            } else {
                // Offline mode - use dummy token
                gameToken = null
                ownershipTokenPath = null
                Timber.i("Launching ${game.appName} in offline mode")
            }

            // Build Epic Games Services parameters
            // Based on Legendary's implementation in get_launch_parameters()

            // Authentication parameters
            params.add("-AUTH_LOGIN=unused")
            params.add("-AUTH_PASSWORD=${gameToken?.authCode ?: "0"}")
            params.add("-AUTH_TYPE=exchangecode")
            params.add("-epicapp=${game.appName}")
            params.add("-epicenv=Prod")

            // Epic Portal flag
            params.add("-EpicPortal")

            // User information parameters
            val displayName = userDisplayName ?: gameToken?.accountId ?: "GameNativeUser"
            val accountId = gameToken?.accountId ?: "0"

            params.add("-epicusername=$displayName")
            params.add("-epicuserid=$accountId")
            params.add("-epiclocale=$languageCode")
            params.add("-epicsandboxid=${game.namespace}")

            // Ownership token for DRM-protected games
            if (ownershipTokenPath != null) {
                params.add("-epicovt=$ownershipTokenPath")
                Timber.d("Added ownership token path: $ownershipTokenPath")
            }

            // Additional command-line parameters from game metadata
            // This would come from game.metadata.customAttributes.AdditionalCommandLine
            // TODO: Parse and add additional parameters if available in metadata

            Timber.d("Built ${params.size} launch parameters for ${game.appName}")
            Result.success(params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to build launch parameters")
            Result.failure(e)
        }
    }

    /**
     * Save ownership token bytes to temporary file
     * File path format: {temp_dir}/{namespace}{catalogItemId}.ovt
     *
     * @return Absolute path to the saved token file
     */
    private fun saveOwnershipTokenToFile(
        context: Context,
        namespace: String,
        catalogItemId: String,
        ownershipTokenHex: String
    ): String {
        val tempDir = File(context.cacheDir, "epic_tokens")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val tokenFile = File(tempDir, "$namespace$catalogItemId.ovt")

        // Convert hex string back to bytes
        val tokenBytes = ownershipTokenHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        tokenFile.writeBytes(tokenBytes)

        Timber.d("Ownership token saved to: ${tokenFile.absolutePath}")
        return tokenFile.absolutePath
    }

    /**
     * Clean up temporary ownership token files after game exits
     * Call this after the game process terminates
     */
    fun cleanupOwnershipTokens(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "epic_tokens")
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.listFiles()?.forEach { file ->
                    if (file.extension == "ovt") {
                        file.delete()
                        Timber.d("Deleted ownership token file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup ownership token files")
        }
    }

    /**
     * Check if a game should be launched online or offline
     *
     * @param game Epic game to check
     * @param forceOffline User preference to force offline
     * @return true if should launch offline, false if should launch online
     */
    fun shouldLaunchOffline(game: EpicGame, forceOffline: Boolean): Boolean {
        // If user forces offline, respect that
        if (forceOffline) {
            return true
        }

        // If game requires ownership token, must be online
        if (game.requiresOT) {
            Timber.d("Game ${game.appName} requires ownership token - must launch online")
            return false
        }

        // Otherwise use game's offline capability setting
        return game.canRunOffline
    }

    /**
     * Get warning message if launching a game that may not work offline
     *
     * @param game Epic game to check
     * @return Warning message, or null if no warning needed
     */
    fun getOfflineLaunchWarning(game: EpicGame): String? {
        return when {
            game.requiresOT && !game.canRunOffline ->
                "This game requires an ownership verification token and cannot run offline. Online authentication is required."

            game.requiresOT ->
                "This game requires an ownership verification token and likely uses Denuvo DRM. It must be launched online."

            !game.canRunOffline ->
                "This game is not marked for offline use and may not work correctly without online authentication."

            else -> null
        }
    }

    /**
     * Build full launch command with game executable and parameters
     *
     * @param game Epic game to launch
     * @param launchParams List of launch parameters from buildLaunchParameters()
     * @param additionalArgs Additional user-provided arguments (optional)
     * @return Complete command as list of strings ready for ProcessBuilder
     */
    fun buildLaunchCommand(
        game: EpicGame,
        launchParams: List<String>,
        additionalArgs: List<String> = emptyList()
    ): List<String> {
        val command = mutableListOf<String>()

        // Game executable path
        val exePath = File(game.installPath, game.executable).absolutePath
        command.add(exePath)

        // Game's predefined launch parameters (from manifest)
        // TODO: Parse game.launchParameters if available

        // Epic Games Services parameters
        command.addAll(launchParams)

        // User-provided additional arguments
        command.addAll(additionalArgs)

        Timber.d("Launch command: ${command.joinToString(" ")}")
        return command
    }
}
