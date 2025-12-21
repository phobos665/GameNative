package app.gamenative.gogdl.library

import android.content.Context
import app.gamenative.gogdl.api.GOGApiClient
import app.gamenative.gogdl.models.GOGGameDetails
import app.gamenative.gogdl.models.GOGLibraryGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

class GOGLibraryManager(
    private val context: Context,
    private val apiClient: GOGApiClient
) {

    private val tag = "GOGLibraryManager"

    private val _library = MutableStateFlow<List<GOGLibraryGame>>(emptyList())
    val library: Flow<List<GOGLibraryGame>> = _library


    suspend fun syncLibrary(): Result<List<GOGLibraryGame>> {
        // TODO: Fetch from API, update database, emit to flow
        Timber.tag(tag).d("syncLibrary() - stub")
        return apiClient.fetchOwnedGames()
    }


    suspend fun getGameDetails(gameId: String): Result<GOGGameDetails> {
        // TODO: Check cache first, then fetch if needed
        Timber.tag(tag).d("getGameDetails($gameId) - stub")
        return apiClient.getGameDetails(gameId)
    }

    suspend fun searchLibrary(query: String): List<GOGLibraryGame> {
        // TODO: Filter cached library by search query
        Timber.tag(tag).d("searchLibrary($query) - stub")
        return emptyList()
    }


    suspend fun getInstalledGames(): List<GOGLibraryGame> {
        // TODO: Filter games marked as installed
        Timber.tag(tag).d("getInstalledGames() - stub")
        return emptyList()
    }


    suspend fun markAsInstalled(gameId: String, installPath: String) {
        // TODO: Update database
        Timber.tag(tag).d("markAsInstalled($gameId, $installPath) - stub")
    }


    suspend fun markAsUninstalled(gameId: String) {
        // TODO: Update database
        Timber.tag(tag).d("markAsUninstalled($gameId) - stub")
    }
}
