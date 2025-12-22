package app.gamenative.utils

import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object SteamAchievements {

    private const val PUBLIC_SCHEMA_URL = "https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/"
    private const val WEB_SCHEMA_URL = "https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/"

    data class Definition(
        val name: String,
        val displayName: String? = null,
        val description: String? = null,
        val hidden: Int? = null,
        val icon: String? = null,
        val iconGray: String? = null,
    )

    fun ensureSchemaIfMissing(appId: Int, settingsDir: Path) {
        val schemaFile = settingsDir.resolve("achievements.json").toFile()
        if (schemaFile.exists() && schemaFile.length() > 0) return

        val apiKey = BuildConfig.STEAM_WEB_API_KEY.trim().ifEmpty { null }
        runCatching { generateSchema(appId, settingsDir, apiKey) }
            .onFailure { Timber.w(it, "Failed to generate achievements schema for app $appId") }
    }

    fun generateSchema(appId: Int, settingsDir: Path, apiKey: String?) {
        val definitions = if (!apiKey.isNullOrBlank()) {
            fetchSchemaFromWebApi(appId, apiKey, settingsDir)
        } else {
            fetchSchemaFromPublic(appId)
        }

        if (definitions.isEmpty()) {
            Timber.w("No achievements found for app $appId")
            return
        }

        val array = JSONArray()
        for (def in definitions) {
            val obj = JSONObject().apply {
                put("name", def.name)
                def.displayName?.let { put("displayName", it) }
                def.description?.let { put("description", it) }
                def.hidden?.let { put("hidden", it.toString()) }
                def.icon?.let { put("icon", it) }
                def.iconGray?.let { put("icon_gray", it) }
            }
            array.put(obj)
        }

        Files.createDirectories(settingsDir)
        val schemaFile = settingsDir.resolve("achievements.json")
        Files.write(
            schemaFile,
            array.toString(2).toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        Timber.i("Generated achievements schema for app $appId (${definitions.size} entries)")
    }

    fun findUserAchievementsFile(appId: Int, container: Container): File {
        val accountId = SteamService.userSteamId?.accountID ?: 0L
        val root = File(container.getRootDir(), ".wine/drive_c")
        val user = ImageFs.USER

        val primary = File(root, "Program Files (x86)/Steam/userdata/$accountId/$appId/achievements.json")
        val fallbackGse = File(root, "users/$user/AppData/Roaming/GSE Saves/$appId/achievements.json")
        val fallbackGoldberg = File(root, "users/$user/AppData/Roaming/Goldberg SteamEmu Saves/$appId/achievements.json")

        return listOf(primary, fallbackGse, fallbackGoldberg).firstOrNull { it.exists() } ?: primary
    }

    fun readEarnedAchievements(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        val content = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (content.isEmpty()) return emptySet()

        val earned = mutableSetOf<String>()
        val parsed = runCatching { JSONTokener(content).nextValue() }.getOrNull()

        when (parsed) {
            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val obj = parsed.optJSONObject(i) ?: continue
                    val name = obj.optString("name", obj.optString("apiName", "")).trim()
                    if (name.isNotEmpty() && isEarned(obj)) {
                        earned.add(name)
                    }
                }
            }
            is JSONObject -> {
                val keys = parsed.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = parsed.opt(name)
                    when (value) {
                        is JSONObject -> if (isEarned(value)) earned.add(name)
                        is Boolean -> if (value) earned.add(name)
                        is Number -> if (value.toLong() > 0) earned.add(name)
                    }
                }
            }
        }

        return earned
    }

    private fun fetchSchemaFromPublic(appId: Int): List<Definition> {
        val url = PUBLIC_SCHEMA_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("gameid", appId.toString())
            ?.build()
            ?: return emptyList()

        val request = Request.Builder().url(url).build()
        val response = SteamUtils.http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return emptyList()
            val body = it.body?.string().orEmpty()
            val root = JSONObject(body)
            val achievements = root.optJSONObject("achievementpercentages")
                ?.optJSONArray("achievements")
                ?: return emptyList()

            val definitions = mutableListOf<Definition>()
            for (i in 0 until achievements.length()) {
                val obj = achievements.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isNotEmpty()) {
                    definitions.add(Definition(name = name))
                }
            }
            return definitions
        }
    }

    private fun fetchSchemaFromWebApi(appId: Int, apiKey: String, settingsDir: Path): List<Definition> {
        val url = WEB_SCHEMA_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("key", apiKey)
            ?.addQueryParameter("appid", appId.toString())
            ?.build()
            ?: return emptyList()

        val request = Request.Builder().url(url).build()
        val response = SteamUtils.http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return emptyList()
            val body = it.body?.string().orEmpty()
            val root = JSONObject(body)
            val achievements = root.optJSONObject("game")
                ?.optJSONObject("availableGameStats")
                ?.optJSONArray("achievements")
                ?: return emptyList()

            val imagesDir = settingsDir.resolve("achievement_images").toFile()
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val definitions = mutableListOf<Definition>()
            for (i in 0 until achievements.length()) {
                val obj = achievements.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue

                val displayName = obj.optString("displayName").takeIf { it.isNotBlank() }
                val description = obj.optString("description").takeIf { it.isNotBlank() }
                val hidden = obj.optInt("hidden", 0)

                val iconUrl = obj.optString("icon").takeIf { it.isNotBlank() }
                val iconGrayUrl = obj.optString("icongray").takeIf { it.isNotBlank() }
                val iconPath = iconUrl?.let { downloadIcon(it, imagesDir, name, "icon") }
                val iconGrayPath = iconGrayUrl?.let { downloadIcon(it, imagesDir, name, "gray") }

                definitions.add(
                    Definition(
                        name = name,
                        displayName = displayName,
                        description = description,
                        hidden = hidden,
                        icon = iconPath,
                        iconGray = iconGrayPath,
                    ),
                )
            }
            return definitions
        }
    }

    private fun downloadIcon(url: String, imagesDir: File, baseName: String, suffix: String): String? {
        val safeBase = baseName.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        val cleanUrl = url.substringBefore("?")
        val ext = cleanUrl.substringAfterLast('.', "png")
        val fileName = "${safeBase}_$suffix.$ext"
        val outFile = File(imagesDir, fileName)

        val request = Request.Builder().url(url).build()
        val response = SteamUtils.http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body ?: return null
            outFile.outputStream().use { output ->
                body.byteStream().copyTo(output)
            }
        }

        return "achievement_images/$fileName"
    }

    private fun isEarned(obj: JSONObject): Boolean {
        if (obj.has("earned")) {
            return obj.optBoolean("earned", obj.optInt("earned", 0) != 0)
        }
        if (obj.has("achieved")) {
            return obj.optBoolean("achieved", obj.optInt("achieved", 0) != 0)
        }
        if (obj.has("unlocked")) {
            return obj.optBoolean("unlocked", obj.optInt("unlocked", 0) != 0)
        }
        if (obj.has("unlock_time")) {
            return obj.optLong("unlock_time", 0L) > 0L
        }
        if (obj.has("progress")) {
            val progress = obj.optDouble("progress", 0.0)
            return progress >= 1.0 || progress >= 100.0
        }
        return false
    }
}
