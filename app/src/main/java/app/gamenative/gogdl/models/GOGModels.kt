package app.gamenative.gogdl.models

import kotlinx.serialization.Serializable

@Serializable
data class GOGCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long, // Unix timestamp
    val userId: String
)

@Serializable
data class GOGLibraryGame(
    val id: String,
    val title: String,
    val slug: String,
    val imageUrl: String? = null,
    val iconUrl: String? = null,
    val isInstalled: Boolean = false,
    val installPath: String? = null
)

@Serializable
data class GOGGameDetails(
    val id: String,
    val title: String,
    val description: String,
    val developer: String,
    val publisher: String,
    val releaseDate: String,
    val genres: List<String>,
    val languages: List<String>,
    val downloadSize: Long,
    val installSize: Long,
    val builds: List<GOGBuild>,
    val images: GOGImages,
    val cloudSaves: Boolean = false
)

@Serializable
data class GOGBuild(
    val buildId: String,
    val version: String,
    val os: String, // "windows", "linux", "mac"
    val architecture: String, // "x86_64", "arm64"
    val manifestId: String
)

@Serializable
data class GOGImages(
    val icon: String? = null,
    val logo: String? = null,
    val hero: String? = null,
    val screenshot: List<String> = emptyList()
)

@Serializable
data class GOGDownloadManifest(
    val gameId: String,
    val buildId: String,
    val version: String,
    val files: List<GOGFileEntry>,
    val totalSize: Long
)

@Serializable
data class GOGFileEntry(
    val path: String,
    val size: Long,
    val md5: String,
    val downloadUrl: String? = null // Populated when starting download
)

@Serializable
data class GOGCloudSave(
    val gameId: String,
    val timestamp: Long,
    val saveName: String,
    val size: Long,
    val path: String
)
