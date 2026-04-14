package app.gamenative.statsgen

import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StatsAchievementsGeneratorTest {

    private lateinit var generator: StatsAchievementsGenerator

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        generator = StatsAchievementsGenerator()
    }


    // ---- Test Utility Functions ----
    private fun loadResource(name: String): String =
        javaClass.classLoader!!
            .getResourceAsStream("statsgen/$name")!!
            .bufferedReader()
            .readText()

    // Parse the schema for grabbing truncated achievement dumps to help with testing.
    private fun parsedSchemaFromJson(filename: String = "brotatoParsedSchema.json"): ParsedSchemaData {
        val root = JSONObject(loadResource(filename))

        val achievements = buildList {
            val arr = root.getJSONArray("achievements")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    Achievement(
                        name = obj.getString("name"),
                        hidden = obj.getInt("hidden"),
                        displayName = obj.optJSONObject("displayName")?.toStringMap(),
                        description = obj.optJSONObject("description")?.toStringMap(),
                        icon = obj.optNullableString("icon"),
                        iconGray = obj.optNullableString("iconGray"),
                        icongray = obj.optNullableString("icongray"),
                    ),
                )
            }
        }

        val stats = buildList {
            val arr = root.getJSONArray("stats")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    Stat(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = obj.getString("type"),
                        default = obj.getString("default"),
                        global = obj.getString("global"),
                        min = obj.optNullableString("min"),
                    ),
                )
            }
        }

        val nameToBlockBit = buildMap<String, Pair<Int, Int>> {
            val obj = root.getJSONObject("nameToBlockBit")
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                put(key, Pair(arr.getInt(0), arr.getInt(1)))
            }
        }

        return ParsedSchemaData(achievements, stats, nameToBlockBit)
    }

    // Parse the expanded achievements from userStats dumps to help with testing.
    private fun expandedAchievementsFromJson(filename: String = "brotatoExpandedAchievements.json"): List<AchievementBlocks> {
        val arr = JSONArray(loadResource(filename))
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val times = obj.getJSONArray("unlockTime")
                add(
                    AchievementBlocks(
                        achievementId = obj.getInt("achievementId"),
                        unlockTime = (0 until times.length()).map { times.getInt(it) },
                        name = obj.optNullableString("name"),
                        displayName = obj.optNullableString("displayName"),
                        description = obj.optNullableString("description"),
                        icon = obj.optNullableString("icon"),
                        iconGray = obj.optNullableString("iconGray"),
                        hidden = obj.getBoolean("hidden"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.toStringMap(): Map<String, String> =
        buildMap { for (k in keys()) put(k, getString(k)) }





    // ---- Tests ----
    @Test
    fun `parseStatsAchievements with empty schema returns empty ParsedSchemaData`() {
        val result = generator.parseStatsAchievements(ByteArray(0))
        assertTrue("achievements should be empty", result.achievements.isEmpty())
        assertTrue("stats should be empty", result.stats.isEmpty())
        assertTrue("nameToBlockBit should be empty", result.nameToBlockBit.isEmpty())
    }

    @Test
    fun `setEarnedAchievements applies timestamps to all valid unlocked achievements`() {
        val parsedData = parsedSchemaFromJson()
        val expanded = expandedAchievementsFromJson()

        val mockUserStats = mockk<UserStatsCallback>(relaxed = true)
        every { mockUserStats.getExpandedAchievements() } returns expanded

        val result = generator.setEarnedAchievements(parsedData, mockUserStats)

        val survivor1 = result.achievements.first { it.name == "chal_survivor_1" }
        assertEquals(true, survivor1.unlocked)
        assertEquals(1775762756, survivor1.unlockTimestamp)
        assertEquals("2026-04-09 20:25:56", survivor1.formattedUnlockTime)

        val survivor2 = result.achievements.first { it.name == "chal_survivor_2" }
        assertEquals(true, survivor2.unlocked)
        assertEquals(1775762756, survivor2.unlockTimestamp)
        assertEquals("2026-04-09 20:25:56", survivor2.formattedUnlockTime)
    }

    @Test
    fun `setEarnedAchievements with empty expanded list returns original parsedData`() {
        val parsedData = parsedSchemaFromJson()

        val mockUserStats = mockk<UserStatsCallback>(relaxed = true)
        every { mockUserStats.getExpandedAchievements() } returns emptyList()

        val result = generator.setEarnedAchievements(parsedData, mockUserStats)

        assertEquals(parsedData, result)
        result.achievements.forEach { assertNull("unlocked should be null for ${it.name}", it.unlocked) }
    }

    @Test
    fun `setEarnedAchievements with no earned expanded achievements leaves unlocked null`() {
        val parsedData = parsedSchemaFromJson()

        // Same structure as fixture but with unlockTime = 0 (locked)
        val allLocked = expandedAchievementsFromJson().map { it.copy(unlockTime = listOf(0)) }

        val mockUserStats = mockk<UserStatsCallback>(relaxed = true)
        every { mockUserStats.getExpandedAchievements() } returns allLocked

        val result = generator.setEarnedAchievements(parsedData, mockUserStats)

        result.achievements.forEach {
            assertNull("${it.name} should not be marked unlocked", it.unlocked)
        }
    }

    @Test
    fun `setEarnedAchievements with partial unlock only marks matching achievements`() {
        val parsedData = parsedSchemaFromJson()

        // Only survivor_1 is unlocked
        val partial = expandedAchievementsFromJson().map { block ->
            if (block.name == "chal_survivor_2") block.copy(unlockTime = listOf(0)) else block
        }

        val mockUserStats = mockk<UserStatsCallback>(relaxed = true)
        every { mockUserStats.getExpandedAchievements() } returns partial

        val result = generator.setEarnedAchievements(parsedData, mockUserStats)

        assertEquals(true, result.achievements.first { it.name == "chal_survivor_1" }.unlocked)
        assertNull(result.achievements.first { it.name == "chal_survivor_2" }.unlocked)
    }

    // -------------------------------------------------------------------------
    // applyEarnedStateToAchievements
    // -------------------------------------------------------------------------

    @Test
    fun `applyEarnedStateToAchievements with empty map returns parsedData unchanged`() {
        val parsedData = parsedSchemaFromJson()

        val result = generator.applyEarnedStateToAchievements(parsedData, emptyMap())

        assertEquals(parsedData, result)
    }

    @Test
    fun `applyEarnedStateToAchievements marks matching achievement as unlocked`() {
        val parsedData = parsedSchemaFromJson()
        val unlocked = mapOf("chal_survivor_1" to Pair(1775762756, "2026-04-09 20:25:56"))

        val result = generator.applyEarnedStateToAchievements(parsedData, unlocked)

        val survivor1 = result.achievements.first { it.name == "chal_survivor_1" }
        assertEquals(true, survivor1.unlocked)
        assertEquals(1775762756, survivor1.unlockTimestamp)
        assertEquals("2026-04-09 20:25:56", survivor1.formattedUnlockTime)
    }

    @Test
    fun `applyEarnedStateToAchievements leaves non-matching achievements untouched`() {
        val parsedData = parsedSchemaFromJson()
        val unlocked = mapOf("chal_survivor_1" to Pair(1775762756, "2026-04-09 20:25:56"))

        val result = generator.applyEarnedStateToAchievements(parsedData, unlocked)

        val survivor2 = result.achievements.first { it.name == "chal_survivor_2" }
        assertNull(survivor2.unlocked)
        assertNull(survivor2.unlockTimestamp)
        assertNull(survivor2.formattedUnlockTime)
    }

    @Test
    fun `applyEarnedStateToAchievements applies timestamps for all entries in the map`() {
        val parsedData = parsedSchemaFromJson()
        val unlocked = mapOf(
            "chal_survivor_1" to Pair(1775762756, "2026-04-09 20:25:56"),
            "chal_survivor_2" to Pair(1775762756, "2026-04-09 20:25:56"),
        )

        val result = generator.applyEarnedStateToAchievements(parsedData, unlocked)

        result.achievements.forEach {
            assertEquals("${it.name} should be unlocked", true, it.unlocked)
            assertEquals(1775762756, it.unlockTimestamp)
        }
    }

    @Test
    fun `applyEarnedStateToAchievements does not mutate original parsedData`() {
        val parsedData = parsedSchemaFromJson()
        val unlocked = mapOf("chal_survivor_1" to Pair(1775762756, "2026-04-09 20:25:56"))

        generator.applyEarnedStateToAchievements(parsedData, unlocked)

        // Original must be unchanged (data class immutability check)
        assertNull(parsedData.achievements.first { it.name == "chal_survivor_1" }.unlocked)
    }

    // -------------------------------------------------------------------------
    // createStatsAchievementFiles
    // -------------------------------------------------------------------------

    @Test
    fun `createStatsAchievementFiles creates achievements json`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = tempFolder.newFolder("cfg1").absolutePath

        generator.createStatsAchievementFiles(parsedData, configDir)

        val file = File(configDir, "achievements.json")
        assertTrue("achievements.json must exist", file.exists())
        val json = JSONArray(file.readText())
        assertEquals(2, json.length())
    }

    @Test
    fun `createStatsAchievementFiles creates stats json`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = tempFolder.newFolder("cfg2").absolutePath

        generator.createStatsAchievementFiles(parsedData, configDir)

        val file = File(configDir, "stats.json")
        assertTrue("stats.json must exist", file.exists())
        val json = JSONArray(file.readText())
        assertEquals(2, json.length())
        val stat = json.getJSONObject(0)
        assertEquals("enemies_killed", stat.getString("name"))
        assertEquals("int", stat.getString("type"))
    }

    @Test
    fun `createStatsAchievementFiles uses default icons when icon fields are null`() {
        val noIconData = parsedSchemaFromJson().let { data ->
            data.copy(achievements = data.achievements.map { it.copy(icon = null, iconGray = null) })
        }
        val configDir = tempFolder.newFolder("cfg3").absolutePath

        val result = generator.createStatsAchievementFiles(noIconData, configDir)

        assertTrue(result.copyDefaultUnlockedImg)
        assertTrue(result.copyDefaultLockedImg)

        val json = JSONArray(File(configDir, "achievements.json").readText())
        val first = json.getJSONObject(0)
        assertEquals("img/steam_default_icon_unlocked.jpg", first.getString("icon"))
        assertEquals("img/steam_default_icon_locked.jpg", first.getString("icon_gray"))
    }

    @Test
    fun `createStatsAchievementFiles does not set copyDefaultImg flags when icons are present`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = tempFolder.newFolder("cfg4").absolutePath

        val result = generator.createStatsAchievementFiles(parsedData, configDir)

        assertFalse(result.copyDefaultUnlockedImg)
        assertFalse(result.copyDefaultLockedImg)
    }

    @Test
    fun `createStatsAchievementFiles includes unlock fields when achievement is unlocked`() {
        val parsedData = parsedSchemaFromJson().let { data ->
            data.copy(
                achievements = data.achievements.map {
                    if (it.name == "chal_survivor_1") {
                        it.copy(unlocked = true, unlockTimestamp = 1775762756, formattedUnlockTime = "2026-04-09 20:25:56")
                    } else {
                        it
                    }
                },
            )
        }
        val configDir = tempFolder.newFolder("cfg5").absolutePath

        generator.createStatsAchievementFiles(parsedData, configDir)

        val json = JSONArray(File(configDir, "achievements.json").readText())
        val survivor1 = (0 until json.length())
            .map { json.getJSONObject(it) }
            .first { it.getString("name") == "chal_survivor_1" }

        assertTrue(survivor1.getBoolean("unlocked"))
        assertEquals(1775762756, survivor1.getInt("unlockTimestamp"))
        assertEquals("2026-04-09 20:25:56", survivor1.getString("formattedUnlockTime"))
    }

    @Test
    fun `createStatsAchievementFiles omits unlock fields for locked achievements`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = tempFolder.newFolder("cfg6").absolutePath

        generator.createStatsAchievementFiles(parsedData, configDir)

        val json = JSONArray(File(configDir, "achievements.json").readText())
        val first = json.getJSONObject(0)
        assertFalse("locked achievement must not have unlocked field", first.has("unlocked"))
        assertFalse(first.has("unlockTimestamp"))
        assertFalse(first.has("formattedUnlockTime"))
    }

    @Test
    fun `createStatsAchievementFiles returns correct result`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = tempFolder.newFolder("cfg7").absolutePath

        val result = generator.createStatsAchievementFiles(parsedData, configDir)

        assertEquals(2, result.achievements.size)
        assertEquals(2, result.stats.size)
        assertEquals(parsedData.nameToBlockBit, result.nameToBlockBit)
    }

    @Test
    fun `createStatsAchievementFiles creates output directory if it does not exist`() {
        val parsedData = parsedSchemaFromJson()
        val configDir = File(tempFolder.root, "deep/nested/dir").absolutePath

        generator.createStatsAchievementFiles(parsedData, configDir)

        assertTrue(File(configDir).exists())
        assertTrue(File(configDir, "achievements.json").exists())
    }

    @Test
    fun `createStatsAchievementFiles writes empty achievements array when no achievements`() {
        val emptyData = ParsedSchemaData(emptyList(), emptyList(), emptyMap())
        val configDir = tempFolder.newFolder("cfg8").absolutePath

        generator.createStatsAchievementFiles(emptyData, configDir)

        val file = File(configDir, "achievements.json")
        assertTrue(file.exists())
        assertEquals("[]", file.readText().trim())
    }
}

