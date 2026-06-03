package app.gamenative.service.epic

import android.content.Context
import app.gamenative.ui.data.Achievement
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val GQL_GAME_ACHIEVEMENTS = """
query Achievement(${'$'}sandboxId: String!, ${'$'}locale: String!) {
  Achievement {
    productAchievementsRecordBySandbox(sandboxId: ${'$'}sandboxId, locale: ${'$'}locale) {
      achievements {
        achievement {
          name
          hidden
          unlockedDisplayName
          lockedDisplayName
          unlockedDescription
          lockedDescription
          unlockedIconLink
          lockedIconLink
          XP
        }
      }
    }
  }
}
""".trimIndent()

private val GQL_PLAYER_ACHIEVEMENTS = """
query PlayerAchievement(${'$'}epicAccountId: String!, ${'$'}sandboxId: String!) {
  PlayerAchievement {
    playerAchievementGameRecordsBySandbox(epicAccountId: ${'$'}epicAccountId, sandboxId: ${'$'}sandboxId) {
      records {
        playerAchievements {
          playerAchievement {
            achievementName
            unlocked
            unlockDate
            XP
          }
        }
      }
    }
  }
}
""".trimIndent()

@Singleton
class EpicAchievementsManager @Inject constructor() {

    // ── API endpoints ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "EpicAchievements"
        private val httpClient: OkHttpClient get() = Net.http

        private const val GQL_URL = "https://launcher.store.epicgames.com/graphql"

        private const val STORE_USER_AGENT = "EpicGamesLauncher/14.0.8-22004686+++Portal+Release-Live"

        fun eosAchievementSaveDir(context: Context, gameId: Int): File {
            val container = app.gamenative.utils.ContainerUtils.getContainer(context, "EPIC_$gameId")
            return File(
                container.rootDir,
                ".wine/drive_c/users/user/AppData/Local/EpicGamesLauncher/Saved",
            )
        }

        fun eosAchievementSaveDirs(context: Context, gameId: Int): List<File> =
            listOf(eosAchievementSaveDir(context, gameId))
    }

    data class EpicAchievementInfo(
        val name: String,
        val displayName: String,
        val description: String,
        val iconUrl: String?,
        val iconGrayUrl: String?,
        val hidden: Boolean,
        val xp: Int,
    )

    suspend fun fetchAchievementsForDisplay(
        context: Context,
        namespace: String,
    ): List<Achievement>? = withContext(Dispatchers.IO) {
        try {
            val credentials = EpicAuthManager.getStoredCredentials(context).getOrNull()
                ?: return@withContext null
            val accessToken = credentials.accessToken.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val accountId = credentials.accountId.takeIf { it.isNotEmpty() }
                ?: return@withContext null

            val definitions = fetchDefinitions(accessToken, namespace)
                ?: return@withContext null
            val playerState = fetchPlayerState(accessToken, accountId, namespace)
                ?: emptyMap()

            definitions.map { def ->
                val state = playerState[def.name]
                val isUnlocked = state?.optBoolean("unlocked", false) ?: false
                val unlockTimestamp = state?.optLong("unlockDateMs", 0L)
                    ?.let { epochMs -> (epochMs / 1000L).toInt() } ?: 0

                Achievement(
                    displayName = def.displayName,
                    name = def.name,
                    isUnlocked = isUnlocked,
                    description = def.description,
                    unlockTimestamp = unlockTimestamp,
                    hidden = def.hidden,
                    icon = def.iconUrl ?: "",
                    iconGray = def.iconGrayUrl,
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "fetchAchievementsForDisplay failed for namespace=$namespace")
            null
        }
    }

    suspend fun generateAchievementsFile(
        context: Context,
        namespace: String,
        configDirectory: String,
    ): List<EpicAchievementInfo>? = withContext(Dispatchers.IO) {
        try {
            val credentials = EpicAuthManager.getStoredCredentials(context).getOrNull()
                ?: return@withContext null
            val accessToken = credentials.accessToken.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val accountId = credentials.accountId.takeIf { it.isNotEmpty() }
                ?: return@withContext null

            val definitions = fetchDefinitions(accessToken, namespace)
                ?: return@withContext null
            val playerState = fetchPlayerState(accessToken, accountId, namespace)
                ?: emptyMap()

            val achievementsJson = buildAchievementsJson(definitions, playerState)

            val outputDir = File(configDirectory).also { it.mkdirs() }
            File(outputDir, "achievements.json").writeText(
                achievementsJson.toString(2),
                Charsets.UTF_8,
            )
            Timber.tag(TAG).i("Generated achievements.json for namespace=$namespace at $configDirectory")

            definitions
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "generateAchievementsFile failed for namespace=$namespace")
            null
        }
    }

    suspend fun uploadUnlocks(
        @Suppress("UNUSED_PARAMETER") context: Context,
        namespace: String,
        @Suppress("UNUSED_PARAMETER") accountId: String,
        unlockedNames: Set<String>,
    ): Result<Unit> {
        // Epic achievements are server-authoritative — the EOS SDK reports them to Epic's
        // backend directly via the game's own EOS credentials.
        Timber.tag(TAG).i(
            "Epic achievement sync skipped: " +
                "${unlockedNames.size} unlocked for namespace=$namespace",
        )
        return Result.success(Unit)
    }


    private fun fetchDefinitions(
        accessToken: String,
        namespace: String,
    ): List<EpicAchievementInfo>? {
        val body = JSONObject().apply {
            put("query", GQL_GAME_ACHIEVEMENTS)
            put("variables", JSONObject().apply {
                put("sandboxId", namespace)
                put("locale", "en")
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(GQL_URL)
            .header("User-Agent", STORE_USER_AGENT)
            // Auth header included for consistency; the store GQL endpoint accepts it
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("GQL definitions fetch failed: HTTP ${response.code} for namespace=$namespace")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            Timber.tag(TAG).d("GQL definitions raw response (first 500 chars): ${bodyStr.take(500)}")
            parseDefinitions(JSONObject(bodyStr))
        }
    }

    private fun fetchPlayerState(
        accessToken: String,
        accountId: String,
        namespace: String,
    ): Map<String, JSONObject>? {
        val body = JSONObject().apply {
            put("query", GQL_PLAYER_ACHIEVEMENTS)
            put("variables", JSONObject().apply {
                put("epicAccountId", accountId)
                put("sandboxId", namespace)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(GQL_URL)
            .header("User-Agent", STORE_USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("GQL player state fetch failed: HTTP ${response.code} for namespace=$namespace")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            Timber.tag(TAG).d("GQL player state raw response (first 500 chars): ${bodyStr.take(500)}")
            parsePlayerState(JSONObject(bodyStr))
        }
    }

    private fun parseDefinitions(json: JSONObject): List<EpicAchievementInfo> {
        val list = mutableListOf<EpicAchievementInfo>()
        val record = json
            .optJSONObject("data")
            ?.optJSONObject("Achievement")
            ?.optJSONObject("productAchievementsRecordBySandbox")
            ?: return list

        val achievements = record.optJSONArray("achievements") ?: return list
        for (i in 0 until achievements.length()) {
            val ach = achievements.optJSONObject(i)?.optJSONObject("achievement") ?: continue
            val name = ach.optString("name").takeIf { it.isNotEmpty() } ?: continue
            list.add(
                EpicAchievementInfo(
                    name = name,
                    displayName = ach.optString("unlockedDisplayName")
                        .ifEmpty { ach.optString("lockedDisplayName") }
                        .ifEmpty { name },
                    description = ach.optString("unlockedDescription")
                        .ifEmpty { ach.optString("lockedDescription") },
                    iconUrl = ach.optString("unlockedIconLink").takeIf { it.isNotEmpty() },
                    iconGrayUrl = ach.optString("lockedIconLink").takeIf { it.isNotEmpty() },
                    hidden = ach.optBoolean("hidden", false),
                    xp = ach.optInt("XP", 0),
                ),
            )
        }
        Timber.tag(TAG).d("Parsed ${list.size} achievement definitions")
        return list
    }

    private fun parsePlayerState(json: JSONObject): Map<String, JSONObject> {
        val map = mutableMapOf<String, JSONObject>()
        val records = json
            .optJSONObject("data")
            ?.optJSONObject("PlayerAchievement")
            ?.optJSONObject("playerAchievementGameRecordsBySandbox")
            ?.optJSONArray("records")
            ?: return map

        for (r in 0 until records.length()) {
            val record = records.optJSONObject(r) ?: continue
            val playerAchievements = record.optJSONArray("playerAchievements") ?: continue
            for (i in 0 until playerAchievements.length()) {
                val pa = playerAchievements.optJSONObject(i)
                    ?.optJSONObject("playerAchievement") ?: continue
                val name = pa.optString("achievementName").takeIf { it.isNotEmpty() } ?: continue
                // unlockDate is an ISO-8601 string e.g. "2024-01-15T12:34:56.000Z"
                val isoDate = pa.optString("unlockDate").takeIf { it.isNotEmpty() }
                val epochMs: Long = isoDate?.let {
                    runCatching {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                            .parse(it)?.time
                    }.getOrNull()
                } ?: 0L
                map[name] = JSONObject().apply {
                    put("unlocked", pa.optBoolean("unlocked", false))
                    put("unlockDateMs", epochMs)
                }
            }
        }
        Timber.tag(TAG).d("Parsed player state for ${map.size} achievements")
        return map
    }

    private fun buildAchievementsJson(
        definitions: List<EpicAchievementInfo>,
        playerState: Map<String, JSONObject>,
    ): JSONObject {
        val root = JSONObject()
        for (def in definitions) {
            val state = playerState[def.name]
            val earned = state?.optBoolean("unlocked", false) ?: false
            val earnTimeMs = state?.optLong("unlockDateMs", 0L) ?: 0L
            val earnTimeSec = (earnTimeMs / 1000L).toInt()

            root.put(
                def.name,
                JSONObject().apply {
                    put("hidden", if (def.hidden) 1 else 0)
                    put("display_name", def.displayName)
                    put("description", def.description)
                    put("icon", def.iconUrl ?: "")
                    put("icon_gray", def.iconGrayUrl ?: "")
                    put("xp", def.xp)
                    put("earned", earned)
                    put("earn_time", earnTimeSec)
                },
            )
        }
        return root
    }
}
