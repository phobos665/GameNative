package app.gamenative.gogdl

import android.content.Context
import app.gamenative.gogdl.api.GOGApiClient
import app.gamenative.gogdl.auth.GOGAuthenticator
import app.gamenative.gogdl.download.GOGDownloadManager
import app.gamenative.gogdl.library.GOGLibraryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main entry point for GOGDL Kotlin client.
 * Provides a clean API for interacting with GOG services without Python dependencies.
 *
 * Usage:
 * ```
 * val client = GOGDLClient(context)
 * client.authenticate(username, password)
 * val games = client.library.fetchOwnedGames()
 * client.download.downloadGame(gameId, installPath)
 * ```
 */
class GOGDLClient(private val context: Context) {

    private val tag = "GOGDLClient"


    val auth: GOGAuthenticator by lazy {
        GOGAuthenticator(context)
    }


    val api: GOGApiClient by lazy {
        GOGApiClient(context, auth)
    }


    val library: GOGLibraryManager by lazy {
        GOGLibraryManager(context, api)
    }

    val download: GOGDownloadManager by lazy {
        GOGDownloadManager(context, api)
    }


    suspend fun isAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        auth.isAuthenticated()
    }


    suspend fun logout() = withContext(Dispatchers.IO) {
        auth.clearCredentials()
        Timber.tag(tag).i("User logged out")
    }

    companion object {
        private var instance: GOGDLClient? = null

        fun getInstance(context: Context): GOGDLClient {
            return instance ?: synchronized(this) {
                instance ?: GOGDLClient(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
