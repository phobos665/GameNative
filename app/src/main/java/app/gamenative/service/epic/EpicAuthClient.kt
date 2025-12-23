package app.gamenative.service.epic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Native Epic OAuth authentication client
 *
 * Handles authentication, token refresh, and token verification
 * without requiring Python/legendary
 */
object EpicAuthClient {

    private const val OAUTH_HOST = "account-public-service-prod03.ol.epicgames.com"
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
     * Authenticate with Epic using authorization code
     *
     * POST https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token
     * Body: grant_type=authorization_code&code=<code>&token_type=eg1
     * Auth: Basic (CLIENT_ID:CLIENT_SECRET)
     */
    suspend fun authenticateWithCode(authorizationCode: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://$OAUTH_HOST/account/api/oauth/token"

            val formBody = "grant_type=authorization_code&code=$authorizationCode&token_type=eg1"
            val requestBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

            val credentials = okhttp3.Credentials.basic(CLIENT_ID, CLIENT_SECRET)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("User-Agent", USER_AGENT)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.e("Authentication failed: ${response.code} - $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val json = JSONObject(body)

            if (json.has("errorCode")) {
                val errorCode = json.getString("errorCode")
                val errorMessage = json.optString("errorMessage", "Authentication failed")
                Timber.e("Epic auth error: $errorCode - $errorMessage")
                return@withContext Result.failure(Exception("$errorCode: $errorMessage"))
            }

            val authResponse = EpicAuthResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accountId = json.getString("account_id"),
                displayName = json.optString("displayName", ""),
                expiresAt = parseExpiresAt(json),
                expiresIn = json.getInt("expires_in")
            )

            Timber.i("Successfully authenticated with Epic: ${authResponse.displayName} (${authResponse.accountId})")
            Result.success(authResponse)

        } catch (e: Exception) {
            Timber.e(e, "Failed to authenticate with Epic")
            Result.failure(e)
        }
    }

    /**
     * Refresh access token using refresh token
     *
     * POST https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token
     * Body: grant_type=refresh_token&refresh_token=<token>&token_type=eg1
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://$OAUTH_HOST/account/api/oauth/token"

            val formBody = "grant_type=refresh_token&refresh_token=$refreshToken&token_type=eg1"
            val requestBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

            val credentials = okhttp3.Credentials.basic(CLIENT_ID, CLIENT_SECRET)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("User-Agent", USER_AGENT)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.e("Token refresh failed: ${response.code} - $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val json = JSONObject(body)

            if (json.has("errorCode")) {
                val errorCode = json.getString("errorCode")
                val errorMessage = json.optString("errorMessage", "Token refresh failed")
                Timber.e("Epic token refresh error: $errorCode - $errorMessage")
                return@withContext Result.failure(Exception("$errorCode: $errorMessage"))
            }

            val authResponse = EpicAuthResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accountId = json.getString("account_id"),
                displayName = json.optString("displayName", ""),
                expiresAt = parseExpiresAt(json),
                expiresIn = json.getInt("expires_in")
            )

            Timber.i("Successfully refreshed Epic token")
            Result.success(authResponse)

        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic token")
            Result.failure(e)
        }
    }

    /**
     * Verify current access token is valid
     *
     * GET https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/verify
     */
    suspend fun verifyToken(accessToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://$OAUTH_HOST/account/api/oauth/verify"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.code == 200) {
                Result.success(true)
            } else {
                Timber.w("Token verification failed: ${response.code}")
                Result.success(false)
            }

        } catch (e: Exception) {
            Timber.w(e, "Error verifying token")
            Result.success(false)
        }
    }

    /**
     * Parse expires_at from JSON - handles both string (ISO 8601) and long (epoch ms) formats
     */
    private fun parseExpiresAt(json: JSONObject): Long {
        return try {
            // Try to get as long first (epoch milliseconds)
            json.getLong("expires_at")
        } catch (e: Exception) {
            try {
                // If that fails, try parsing as ISO 8601 string
                val expiresAtString = json.getString("expires_at")
                val instant = Instant.parse(expiresAtString)
                instant.toEpochMilli()
            } catch (e2: Exception) {
                // Fallback: calculate from expires_in if available
                val expiresIn = json.optInt("expires_in", 7200) // default 2 hours
                System.currentTimeMillis() + (expiresIn * 1000L)
            }
        }
    }
}

/**
 * Epic OAuth response data
 */
data class EpicAuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val displayName: String,
    val expiresAt: Long,
    val expiresIn: Int
)
