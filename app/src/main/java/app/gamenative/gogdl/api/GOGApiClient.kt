package app.gamenative.gogdl.api

import android.content.Context
import app.gamenative.gogdl.auth.GOGAuthenticator
import app.gamenative.gogdl.models.GOGGameDetails
import app.gamenative.gogdl.models.GOGLibraryGame
import timber.log.Timber

class GOGApiClient(
    private val context: Context,
    private val authenticator: GOGAuthenticator
) {
    private val httpClient = new OkHttpClient()
    private val tag = "GOGApiClient"

    suspend fun fetchOwnedGames(): Result<List<GOGLibraryGame>> {
        // TODO: GET /user/data/games
        Timber.tag(tag).d("fetchOwnedGames() - stub")
        return Result.success(emptyList())
    }

    suspend fun getGameDetails(gameId: String): Result<GOGGameDetails> {
        // TODO: GET /products/{gameId}
        Timber.tag(tag).d("getGameDetails($gameId) - stub")
        return Result.failure(NotImplementedError("getGameDetails not implemented"))
    }

    suspend fun getDownloadManifest(gameId: String, buildId: String): Result<String> {
        // TODO: Fetch game manifest JSON
        Timber.tag(tag).d("getDownloadManifest($gameId, $buildId) - stub")
        return Result.failure(NotImplementedError("getDownloadManifest not implemented"))
    }

    suspend fun getSecureDownloadUrl(gameId: String, fileId: String): Result<String> {
        // TODO: Generate secure download URL with token
        Timber.tag(tag).d("getSecureDownloadUrl($gameId, $fileId) - stub")
        return Result.failure(NotImplementedError("getSecureDownloadUrl not implemented"))
    }

    suspend fun uploadCloudSave(gameId: String, saveData: ByteArray): Result<Unit> {
        // TODO: POST to cloud storage endpoint
        Timber.tag(tag).d("uploadCloudSave($gameId) - stub")
        return Result.failure(NotImplementedError("uploadCloudSave not implemented"))
    }

    suspend fun downloadCloudSave(gameId: String): Result<ByteArray> {
        // TODO: GET from cloud storage endpoint
        Timber.tag(tag).d("downloadCloudSave($gameId) - stub")
        return Result.failure(NotImplementedError("downloadCloudSave not implemented"))
    }

    private suspend fun <T> authenticatedGet(endpoint: String, parser: (String) -> T): Result<T> {
        // TODO: Make authenticated HTTP GET request
        Timber.tag(tag).d("authenticatedGet($endpoint) - stub")
        return Result.failure(NotImplementedError("authenticatedGet not implemented"))
    }

    private suspend fun <T> authenticatedPost(
        endpoint: String,
        body: String,
        parser: (String) -> T
    ): Result<T> {
        // TODO: Make authenticated HTTP POST request
        Timber.tag(tag).d("authenticatedPost($endpoint) - stub")
        return Result.failure(NotImplementedError("authenticatedPost not implemented"))
    }
}
