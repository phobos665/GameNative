package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Native Kotlin client for Epic Games API
 *
 * Replaces Python legendary CLI calls with direct HTTP API calls.
 * This avoids Chaquopy frame errors and is more efficient.
 *
 * API Endpoints:
 * - Library: https://library-service.live.use1a.on.epicgames.com/library/api/public/items
 * - Game Info: https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/{namespace}/bulk/items
 * - Assets: https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/{platform}
 */

/**
 * * Checklist:
 * ! Create file with authorization token (Done with authenticateWithCode and saveCredentials)
 * ! Authenticate with token and get refresh token & access token - Refresh when close to expiry (Done with EpicAuthManager loadCredentials, getStoredCredentials)
 * ! Get Library -> Ids or just all the game details (fetchLibrary and fetchGameInfo)
 * ! Getting the Manifest and the Downoad -> DownloadManager... fetchManifestData ->  downloadGame( Composite of readEpicChunk, downloadChunk, verifyChunkHash, verifyChunkHashBytes
 * ! Downloading happens like this: Grab Manifest, store any manifest extra data. Get the chunk information from the manifest. From there, understand the chunks, download and store them. We still need Python for this due to the manifest binary format.
 * ? Cloud Saves
 * ? DLC Handling
 * ? Any updates that are required after pulling the manifest
 * ? Test uninstalling / Pausing / Delete
 * ? Removing the last parts of Python Code if possible
 * * Focus on the basics and refactor for readability then move onto the DLC & Cloud Saves
 */

object EpicApiClient {

    private const val OAUTH_HOST = "account-public-service-prod03.ol.epicgames.com"
    private const val LIBRARY_HOST = "library-service.live.use1a.on.epicgames.com"
    private const val CATALOG_HOST = "catalog-public-service-prod06.ol.epicgames.com"
    private const val LAUNCHER_HOST = "launcher-public-service-prod06.ol.epicgames.com"

    private const val USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"

    // OAuth credentials (from legendary - these are public and safe to include)
    private const val CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
    private const val CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch user's Epic library
     *
     * Calls: GET https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true
     *
     * Returns list of library items with app names, namespaces, and catalog IDs
     */
    suspend fun fetchLibrary(context: Context): Result<List<EpicGame>> = withContext(Dispatchers.IO) {
        try {

            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val games = mutableListOf<EpicGame>()
            var cursor: String? = null

            // Fetch all pages of library items
            do {
                val url = buildString {
                    append("https://$LIBRARY_HOST/library/api/public/items?includeMetadata=true")
                    if (cursor != null) {
                        append("&cursor=$cursor")
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                Timber.d("Fetching Epic library page: cursor=$cursor")

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "Unknown error"
                    Timber.e("Library fetch failed: ${response.code} - $error")
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $error"))
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Timber.e("Empty response body from library API")
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val json = JSONObject(body)
                val records = json.optJSONArray("records") ?: JSONArray()

                Timber.d("Received ${records.length()} library items in this page")

                // Process records and fetch game info for each
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)

                    // Skip items without app name
                    if (!record.has("appName")) {
                        continue
                    }

                    val appName = record.getString("appName")
                    val namespace = record.getString("namespace")
                    val catalogItemId = record.getString("catalogItemId")
                    val sandboxType = record.optString("sandboxType", "")

                    // Skip UE assets, private sandboxes, and broken entries
                    if (namespace == "ue" || sandboxType == "PRIVATE" || appName == "1") {
                        Timber.d("Skipping $appName (namespace=$namespace, sandbox=$sandboxType)")
                        continue
                    }

                    // Fetch detailed game info from catalog
                    val gameInfo = fetchGameInfo(accessToken, namespace, catalogItemId)
                    if (gameInfo != null) {
                        games.add(gameInfo)
                    }
                }

                // Get cursor for next page
                val metadata = json.optJSONObject("responseMetadata")
                cursor = metadata?.optString("nextCursor")?.takeIf { it.isNotEmpty() }

            } while (cursor != null)

            Timber.i("Successfully fetched ${games.size} games from Epic library")
            Result.success(games)

        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Epic library")
            Result.failure(e)
        }
    }

    /**
     * Fetch detailed game info from catalog
     *
     * Calls: GET https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/{namespace}/bulk/items?id={catalogItemId}
     */
    private suspend fun fetchGameInfo(
        accessToken: String,
        namespace: String,
        catalogItemId: String
    ): EpicGame? = withContext(Dispatchers.IO) {
        try {
            val url = "https://$CATALOG_HOST/catalog/api/shared/namespace/$namespace/bulk/items" +
                    "?id=$catalogItemId&includeDLCDetails=true&includeMainGameDetails=true" +
                    "&country=US&locale=en-US"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("Failed to fetch game info for $catalogItemId: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                return@withContext null
            }

            val json = JSONObject(body)
            val gameData = json.optJSONObject(catalogItemId)

            if (gameData != null) {
                parseGameFromCatalog(gameData)
            } else {
                null
            }

        } catch (e: Exception) {
            Timber.w(e, "Error fetching game info for $catalogItemId")
            null
        }
    }

    /**
     * Parse Epic catalog JSON into EpicGame object
     *
     * Catalog structure:
     * {
     *   "id": "catalogItemId",
     *   "namespace": "namespace",
     *   "title": "Game Title",
     *   "description": "Description...",
     *   "keyImages": [...],
     *   "categories": [...],
     *   "developer": "Developer",
     *   "developerDisplayName": "Developer Display Name",
     *   "publisher": "Publisher",
     *   "publisherDisplayName": "Publisher Display Name",
     *   "releaseInfo": [...],
     *   "mainGameItem": { ... },  // Present for DLC
     *   ...
     * }
     */
    private fun parseGameFromCatalog(data: JSONObject): EpicGame {
        val catalogItemId = data.getString("id")
        val namespace = data.getString("namespace")
        val title = data.getString("title")
        val description = data.optString("description", "")

        // Get app name from data (it may be in different fields)
        val appName = data.optString("appName", "") // Sometimes available
            .takeIf { it.isNotEmpty() }
            ?: catalogItemId // Fallback to catalog ID

        // Extract images - map to EpicGame's art fields
        val keyImages = data.optJSONArray("keyImages")
        var artCover = ""      // DieselGameBoxTall - Tall cover art
        var artSquare = ""     // DieselGameBox - Square box art
        var artLogo = ""       // DieselGameBoxLogo - Logo image
        var artPortrait = ""   // DieselStoreFrontWide - Wide banner

        if (keyImages != null) {
            for (i in 0 until keyImages.length()) {
                val img = keyImages.getJSONObject(i)
                val imgType = img.optString("type")
                val imgUrl = img.optString("url", "")

                when (imgType) {
                    "DieselGameBoxTall" -> artCover = imgUrl
                    "DieselGameBox" -> artSquare = imgUrl
                    "DieselGameBoxLogo" -> artLogo = imgUrl
                    "DieselStoreFrontWide" -> artPortrait = imgUrl
                    "Thumbnail" -> if (artSquare.isEmpty()) artSquare = imgUrl
                }
            }
        }

        // Check if this is DLC
        val isDLC = data.has("mainGameItem")
        val baseGameAppName = if (isDLC) {
            data.optJSONObject("mainGameItem")?.optString("id", "") ?: ""
        } else {
            ""
        }

        // Get developer/publisher
        val developer = data.optString("developerDisplayName", data.optString("developer", ""))
        val publisher = data.optString("publisherDisplayName", data.optString("publisher", ""))

        // Get categories to check for mods
        val categories = data.optJSONArray("categories")
        var isMod = false
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                if (cat.optString("path") == "mods") {
                    isMod = true
                    break
                }
            }
        }

        // Release date - convert to string format
        val releaseInfo = data.optJSONArray("releaseInfo")
        var releaseDate = ""
        if (releaseInfo != null && releaseInfo.length() > 0) {
            val release = releaseInfo.getJSONObject(0)
            releaseDate = release.optString("dateAdded", "")
        }

        // Parse genres/tags from categories
        val genresList = mutableListOf<String>()
        val tagsList = mutableListOf<String>()
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                val path = cat.optString("path", "")
                if (path.startsWith("games/")) {
                    genresList.add(path.removePrefix("games/"))
                } else if (path.isNotEmpty() && path != "mods") {
                    tagsList.add(path)
                }
            }
        }

        return EpicGame(
            id = catalogItemId,
            appName = appName,
            title = title,
            namespace = namespace,
            developer = developer,
            publisher = publisher,
            description = description,
            artCover = artCover,
            artSquare = artSquare,
            artLogo = artLogo,
            artPortrait = artPortrait,
            isDLC = isDLC,
            baseGameAppName = baseGameAppName,
            releaseDate = releaseDate,
            genres = genresList,
            tags = tagsList,
            isInstalled = false, // Will be updated from local database
            installPath = "",
            platform = "Windows",
            version = "",
            executable = "",
            installSize = 0,
            downloadSize = 0,
            canRunOffline = false, // Unknown from catalog API, will need manifest
            requiresOT = false,
            cloudSaveEnabled = false,
            saveFolder = "",
            thirdPartyManagedApp = "",
            isEAManaged = false,
            lastPlayed = 0,
            playTime = 0
        )
    }
}
