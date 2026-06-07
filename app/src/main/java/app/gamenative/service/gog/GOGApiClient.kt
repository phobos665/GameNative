package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGGame
import app.gamenative.ui.data.Achievement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Parsed/Formartted details returned by GOGApiClient.
 */
data class ParsedGogGame(
    val id: String,
    val title: String,
    val slug: String,
    val imageUrl: String,
    val iconUrl: String,
    val backgroundUrl: String,
    val developer: String,
    val publisher: String,
    val genres: List<String>,
    val languages: List<String>,
    val description: String,
    val releaseDate: String,
    val downloadSize: Long,
    val isSecret: Boolean,
    val isDlc: Boolean
)

data class GOGGameAuthData(
    val clientId: String,
    val clientSecret: String,
)

/**
 * Raw API Response details from gameDetails endpoint (Used for reference)
 */
data class RawGogApiResponse(
    val id: String?,
    val title: String?,
    val slug: String?,
    val images: Images?,
    val developers: List<Developer>?,
    val publisher: Any?, // Can be object with name or plain string
    val genres: List<Genre>?,
    val languages: Map<String, String>?, // Language code -> Language name
    val description: Description?,
    val release_date: String?,
    val downloads: Downloads?
) {
    data class Images(
        val background: String?,
        val logo2x: String?,
        val logo: String?,
        val icon: String?
    )

    data class Developer(
        val name: String?
    )

    data class Genre(
        val name: String?
    )

    data class Description(
        val lead: String?
    )

    data class Downloads(
        val installers: List<Installer>?
    )

    data class Installer(
        val id: String?,
        val name: String?,
        val os: String?,
        val language: String?,
        val total_size: Long?
    )
}

/**
 * Direct HTTP client for GOG API operations.
 * Uses GOGAuthManager for authentication tokens.
 */
object GOGApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch list of game IDs owned by the user
     *
     * - Gets credentials from AuthManager
     * - Calls GOG_EMBED/user/data/games endpoint to get Ids
     * - Returns list of owned game IDs
     *
     * @param context Application context for auth access
     * @return Result containing list of game IDs or error
     */
    suspend fun getGameIds(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching GOG game IDs...")

            // Get credentials from AuthManager
            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.tag("GOG").e(error, "Cannot list games: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.tag("GOG").e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }


            val url = "${GOGConstants.GOG_EMBED_URL}/user/data/games"
            val request = Request.Builder() // Returns an "owned" key with an array of ints.
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()

            Timber.tag("GOG").d("Requesting game IDs from: $url")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.e("Failed to fetch game IDs: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game IDs: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.w("Empty response when fetching game IDs")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                // Parse JSON response
                val userData = JSONObject(responseBody)
                val ownedGames = userData.optJSONArray("owned") ?: JSONArray()

                val gameIds = List(ownedGames.length()) {
                    ownedGames.get(it).toString()
                }

                Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs")
                Timber.tag("GOG").d("First 10 game IDs: ${gameIds.take(10).joinToString()}")
                return@withContext Result.success(gameIds)
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching game IDs: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Fetch detailed information for a specific game by ID
     *
     * - Gets credentials from AuthManager
     * - Calls GOG_API/products/{id} endpoint to get gameInfo
     * - Returns game details as ParsedGogGame
     *
     * @param context Application context for auth access
     * @param gameId The GOG game ID
     * @param expanded List of fields to expand (defaults to downloads, description, screenshots)
     * @return Result containing ParsedGogGame with transformed details or error
     */
    suspend fun getGameById(
        context: Context,
        gameId: String,
        expanded: List<String> = listOf("downloads", "description", "screenshots")
    ): Result<ParsedGogGame> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching game details for gameId: $gameId")

            // Get credentials from AuthManager
            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.e(error, "Cannot fetch game details: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            // Build URL with expanded fields
            val expandedParam = if (expanded.isNotEmpty()) {
                "?expand=${expanded.joinToString(",")}"
            } else {
                ""
            }
            val url = "${GOGConstants.GOG_BASE_API_URL}/products/$gameId$expandedParam"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()

            Timber.tag("GOG").d("Requesting game details from: $url")

            // Execute request
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to fetch game details for $gameId: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game details: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.tag("GOG").w("Empty response when fetching game details for $gameId")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                // Parse raw GOG API response
                val rawApiResponse = JSONObject(responseBody)

                // Transform to simplified, flattened structure
                val transformedResponse = transformGameDetails(rawApiResponse, gameId)

                return@withContext Result.success(transformedResponse)
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Exception fetching game details for $gameId: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    // Fetch game OAuth metadata from GOG build metadata API.
    suspend fun getGameAuthData(context: Context, gameId: String, installPath: String?): GOGGameAuthData? = withContext(Dispatchers.IO) {
        val manifestJson = fetchBuildManifest(context, gameId, installPath, "[Achievements]")
            ?: return@withContext null

        val clientId = manifestJson.optString("clientId", "")
        if (clientId.isEmpty()) {
            Timber.tag("GOGAchievements").w("No clientId in manifest for game $gameId")
            return@withContext null
        }

        val clientSecret = manifestJson.optString("clientSecret", "")
        if (clientSecret.isEmpty()) {
            Timber.tag("GOGAchievements").w("No clientSecret in manifest for game $gameId")
            return@withContext null
        }

        Timber.tag("GOGAchievements").d("Successfully retrieved game OAuth metadata for game $gameId")
        return@withContext GOGGameAuthData(clientId, clientSecret)
    }

    /**
     * Fetch client secret from GOG build metadata API.
     * @param gameId GOG game ID
     * @param installPath Game install path (for platform detection, defaults to "windows")
     * @return Client secret string, or null if not found
     */
    suspend fun getClientSecret(context: Context, gameId: String, installPath: String?): String? = withContext(Dispatchers.IO) {
        val manifestJson = fetchBuildManifest(context, gameId, installPath, "[Cloud Saves]")
            ?: return@withContext null

        val clientSecret = manifestJson.optString("clientSecret", "")
        if (clientSecret.isEmpty()) {
            Timber.tag("GOG").w("[Cloud Saves] No clientSecret in manifest for game $gameId")
            return@withContext null
        }

        Timber.tag("GOG").d("[Cloud Saves] Successfully retrieved clientSecret for game $gameId")
        return@withContext clientSecret
    }

    suspend fun getAchievements(
        context: Context,
        clientId: String,
        clientSecret: String,
    ): Result<List<Achievement>> = withContext(Dispatchers.IO) {
        try {
            val credentialsResult = GOGAuthManager.getGameCredentials(context, clientId, clientSecret)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.tag("GOGAchievements").e(error, "[Achievements] Cannot fetch achievements: game credentials unavailable")
                return@withContext Result.failure(error ?: Exception("Game credentials unavailable"))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty() || credentials.userId.isEmpty()) {
                Timber.tag("GOGAchievements").w("Missing game access token or user ID")
                return@withContext Result.failure(Exception("Invalid game credentials"))
            }

            val url = "https://gameplay.gog.com/clients/$clientId/users/${credentials.userId}/achievements"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "GameNative/1.0")
                .addHeader("X-Gog-Lc", Locale.getDefault().toLanguageTag())
                .get()
                .build()

            Timber.tag("GOGAchievements").d("Fetching achievements for clientId=$clientId userId=${credentials.userId}")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").w("Fetch failed: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(Exception("Failed to fetch GOG achievements: HTTP ${response.code}"))
                }

                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) {
                    Timber.tag("GOG").w("Empty achievements response")
                    return@withContext Result.success(emptyList())
                }

                val json = JSONObject(responseBody)
                val items = json.optJSONArray("items") ?: JSONArray()
                val achievementsMode = json.optString("achievements_mode", "")
                val achievements = mutableListOf<Achievement>()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val dateUnlocked = item.optString("date_unlocked", "")
                        .takeIf { it.isNotBlank() && it != "null" }
                    val unlockedIcon = item.optString("image_url_unlocked", "")
                    val lockedIcon = item.optString("image_url_locked", "")
                    val fallbackName = item.optString("achievement_key", item.optString("achievement_id", ""))

                    achievements.add(
                        Achievement(
                            displayName = item.optString("name", fallbackName),
                            name = fallbackName,
                            isUnlocked = dateUnlocked != null,
                            description = item.optString("description", ""),
                            unlockTimestamp = parseGogAchievementTimestamp(dateUnlocked),
                            hidden = !item.optBoolean("visible", true),
                            icon = unlockedIcon.ifEmpty { lockedIcon },
                            iconGray = lockedIcon.ifEmpty { null },
                        ),
                    )
                }

                Timber.tag("GOGAchievements").i(
                    "Fetched ${achievements.size} achievements for clientId=$clientId mode=$achievementsMode",
                )
                return@withContext Result.success(achievements)
            }
        } catch (e: Exception) {
            Timber.tag("GOGAchievements").e(e, "Failed to fetch achievements")
            return@withContext Result.failure(e)
        }
    }

    private suspend fun fetchBuildManifest(
        context: Context,
        gameId: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val platform = "windows" // For now, assume Windows (proton)
            val buildsUrl = "https://content-system.gog.com/products/$gameId/os/$platform/builds?generation=2"

            Timber.tag("GOG").d("Fetching build metadata from: $buildsUrl")

            // Get credentials for API authentication
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                Timber.tag("GOG").w(" No credentials available for build metadata fetch")
                return@withContext null
            }

            val request = Request.Builder()
                .url(buildsUrl)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            // Fetch the builds list and extract manifest link
            val manifestLink = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GOG").w("Build metadata fetch failed: ${response.code}")
                    return@withContext null
                }

                val jsonStr = response.body?.string() ?: ""
                val buildsJson = JSONObject(jsonStr)

                // Get first build
                val items = buildsJson.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    Timber.tag("GOG").w("No builds found for game $gameId")
                    return@withContext null
                }

                val firstBuild = items.getJSONObject(0)
                val link = firstBuild.optString("link", "")
                if (link.isEmpty()) {
                    Timber.tag("GOG").w("No manifest link in first build")
                    return@withContext null
                }

                Timber.tag("GOG").d("Fetching build manifest from: $link")
                link
            }

            // Fetch the build manifest
            val manifestRequest = Request.Builder()
                .url(manifestLink)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            httpClient.newCall(manifestRequest).execute().use { manifestResponse ->
                if (!manifestResponse.isSuccessful) {
                    Timber.tag("GOG").w("Manifest fetch failed: ${manifestResponse.code}")
                    return@withContext null
                }

                // Log response headers to debug compression
                val contentEncoding = manifestResponse.header("Content-Encoding")
                val contentType = manifestResponse.header("Content-Type")
                Timber.tag("GOG").d("Response headers - Content-Encoding: $contentEncoding, Content-Type: $contentType")

                // Read the response bytes (can only read body once)
                val manifestBytes = manifestResponse.body?.bytes() ?: return@withContext null

                // Check compression type by magic bytes
                val isGzipped = manifestBytes.size >= 2 &&
                                manifestBytes[0] == 0x1f.toByte() &&
                                manifestBytes[1] == 0x8b.toByte()

                val isZlib = manifestBytes.size >= 2 &&
                             manifestBytes[0] == 0x78.toByte() &&
                             (manifestBytes[1] == 0x9c.toByte() ||
                              manifestBytes[1] == 0xda.toByte() ||
                              manifestBytes[1] == 0x01.toByte())

                Timber.tag("GOG").d("Manifest bytes: ${manifestBytes.size}, isGzipped: $isGzipped, isZlib: $isZlib")

                // Decompress based on detected format
                val manifestStr = when {
                    isGzipped -> {
                        try {
                            Timber.tag("GOG").d("Decompressing gzip manifest")
                            val gzipStream = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(manifestBytes))
                            gzipStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "Gzip decompression failed")
                            return@withContext null
                        }
                    }
                    isZlib -> {
                        try {
                            Timber.tag("GOG").d("Decompressing zlib manifest")
                            val inflaterStream = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(manifestBytes))
                            inflaterStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "Zlib decompression failed")
                            return@withContext null
                        }
                    }
                    else -> {
                        // Not compressed, read as plain text
                        Timber.tag("GOG").d("Not compressed, reading as UTF-8")
                        String(manifestBytes, Charsets.UTF_8)
                    }
                }

                if (manifestStr.isEmpty()) {
                    Timber.tag("GOG").w("Empty manifest response")
                    return@withContext null
                }

                Timber.tag("GOG").d("Parsing manifest JSON (${manifestStr.take(100)}...)")
                return@withContext JSONObject(manifestStr)
            }

        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get build manifest for game $gameId")
            return@withContext null
        }
    }

    private fun parseGogAchievementTimestamp(dateUnlocked: String?): Int {
        if (dateUnlocked.isNullOrBlank()) return 0

        val normalized = dateUnlocked.replace(
            Regex("([+-]\\d{2})(\\d{2})$"),
            "${'$'}1:${'$'}2",
        )

        return runCatching {
            OffsetDateTime.parse(normalized).toInstant().epochSecond.toInt()
        }.recoverCatching {
            Instant.parse(normalized).epochSecond.toInt()
        }.getOrDefault(0)
    }

    /**
     * Transform raw GOG API response into better format. Based on GOGDL implementation
     *
     * @param rawResponse Raw JSON from GOG API
     * @param gameId The game ID
     * @return ParsedGogGame with simplified structure
     */
    private fun transformGameDetails(rawResponse: JSONObject, gameId: String): ParsedGogGame {
        // Extract image URLs and add https: protocol if missing
        val images = rawResponse.optJSONObject("images")
        var background = images?.optString("background", "") ?: ""
        var logo2x = images?.optString("logo2x", "") ?: ""
        var logo = images?.optString("logo", "") ?: ""
        var icon = images?.optString("icon", "") ?: ""

        if (background.startsWith("//")) background = "https:$background"
        if (logo2x.startsWith("//")) logo2x = "https:$logo2x"
        if (logo.startsWith("//")) logo = "https:$logo"
        if (icon.startsWith("//")) icon = "https:$icon"

        val imageUrl = logo2x.ifEmpty { logo }
        val backgroundUrl = background.ifEmpty { imageUrl }

        // Extract developer (first from array)
        val developers = rawResponse.optJSONArray("developers")
        val developer = if (developers != null && developers.length() > 0) {
            developers.optJSONObject(0)?.optString("name", "") ?: ""
        } else {
            ""
        }

        // Extract publisher (can be object or string)
        val publisherObj = rawResponse.opt("publisher")
        val publisher = when (publisherObj) {
            is JSONObject -> publisherObj.optString("name", "")
            is String -> publisherObj
            else -> ""
        }

        // Extract genres (array of objects with name field)
        val genresArray = rawResponse.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.opt(i)
                val genreName = when (genreObj) {
                    is JSONObject -> genreObj.optString("name", "")
                    is String -> genreObj
                    else -> ""
                }
                if (genreName.isNotEmpty()) {
                    genres.add(genreName)
                }
            }
        }

        // Extract language codes (keys from object)
        val languages = mutableListOf<String>()
        val langObj = rawResponse.optJSONObject("languages")
        if (langObj != null) {
            val keys = langObj.keys()
            while (keys.hasNext()) {
                languages.add(keys.next())
            }
        }

        // Extract description from nested structure
        val descriptionObj = rawResponse.opt("description")
        val description = when (descriptionObj) {
            is JSONObject -> descriptionObj.optString("lead", "")
            is String -> descriptionObj
            else -> ""
        }

        // Extract download size from first installer
        val downloads = rawResponse.optJSONObject("downloads")
        // Used in GOG Galaxy to hide specific entitlements
        val isSecret = rawResponse.optBoolean("is_secret", false)
        val gameType = rawResponse.optString("game_type", "dlc")
        val isDlc = gameType == "dlc"

        val installers = downloads?.optJSONArray("installers")
        val downloadSize = if (installers != null && installers.length() > 0) {
            installers.optJSONObject(0)?.optLong("total_size", 0L) ?: 0L
        } else {
            0L
        }

        // Return data class matching GOGDL format
        return ParsedGogGame(
            id = gameId,
            title = rawResponse.optString("title", "Unknown"),
            slug = rawResponse.optString("slug", ""),
            imageUrl = imageUrl,
            iconUrl = icon,
            backgroundUrl = backgroundUrl,
            developer = developer,
            publisher = publisher,
            genres = genres,
            languages = languages,
            description = description,
            releaseDate = rawResponse.optString("release_date", ""),
            downloadSize = downloadSize,
            isSecret = isSecret,
            isDlc = isDlc
        )
    }
}
