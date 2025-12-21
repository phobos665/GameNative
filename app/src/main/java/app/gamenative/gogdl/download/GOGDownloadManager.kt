package app.gamenative.gogdl.download

import android.content.Context
import app.gamenative.gogdl.api.GOGApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber


class GOGDownloadManager(
    private val context: Context,
    private val apiClient: GOGApiClient
) {

    private val tag = "GOGDownloadManager"

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: Flow<Map<String, DownloadProgress>> = _activeDownloads

    suspend fun downloadGame(
        gameId: String,
        installPath: String,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> {
        // TODO: Fetch manifest, download files, verify integrity
        Timber.tag(tag).d("downloadGame($gameId, $installPath) - stub")
        return Result.failure(NotImplementedError("downloadGame not implemented"))
    }

    suspend fun pauseDownload(gameId: String) {
        // TODO: Pause download, save state
        Timber.tag(tag).d("pauseDownload($gameId) - stub")
    }

    suspend fun resumeDownload(gameId: String): Result<Unit> {
        // TODO: Resume from saved state
        Timber.tag(tag).d("resumeDownload($gameId) - stub")
        return Result.failure(NotImplementedError("resumeDownload not implemented"))
    }

    suspend fun cancelDownload(gameId: String) {
        // TODO: Stop download, remove partial files
        Timber.tag(tag).d("cancelDownload($gameId) - stub")
    }


    suspend fun verifyInstallation(gameId: String, installPath: String): Result<Boolean> {
        // TODO: Check file hashes against manifest
        Timber.tag(tag).d("verifyInstallation($gameId, $installPath) - stub")
        return Result.success(false)
    }

    fun getDownloadProgress(gameId: String): DownloadProgress? {
        return _activeDownloads.value[gameId]
    }

    data class DownloadProgress(
        val gameId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val downloadSpeed: Long, // bytes per second
        val status: DownloadStatus
    ) {
        val progress: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }

    enum class DownloadStatus {
        QUEUED,
        DOWNLOADING,
        PAUSED,
        VERIFYING,
        COMPLETED,
        FAILED
    }
}
