
/**
 * Dumps expanded achievements to a JSON file for inspection and unit testing.
 * Each entry captures all fields of [AchievementBlocks].
 */
internal fun dumpExpandedAchievementsToFile(
    achievements: List<AchievementBlocks>,
    filePath: String,
) {
    val array = JSONArray()
    for (ach in achievements) {
        val obj = JSONObject()
        obj.put("achievementId", ach.achievementId)
        obj.put("name", ach.name ?: JSONObject.NULL)
        obj.put("displayName", ach.displayName ?: JSONObject.NULL)
        obj.put("description", ach.description ?: JSONObject.NULL)
        obj.put("icon", ach.icon ?: JSONObject.NULL)
        obj.put("iconGray", ach.iconGray ?: JSONObject.NULL)
        obj.put("hidden", ach.hidden)
        obj.put("isUnlocked", ach.isUnlocked)
        obj.put("unlockTimestamp", ach.unlockTimestamp)
        obj.put("formattedUnlockTime", ach.getFormattedUnlockTime() ?: JSONObject.NULL)
        val times = JSONArray()
        for (t in ach.unlockTime) times.put(t)
        obj.put("unlockTime", times)
        array.put(obj)
    }
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeText(array.toString(2), Charsets.UTF_8)
    Timber.d("dumpExpandedAchievements: wrote ${achievements.size} entries to $filePath")
}

/**
 * Dumps [ParsedSchemaData] to a JSON file for inspection and unit testing.
 * Captures all achievements, stats, and nameToBlockBit mappings.
 */
internal fun dumpParsedSchemaDataToFile(
    parsedData: ParsedSchemaData,
    filePath: String,
) {
    val root = JSONObject()

    // achievements
    val achArray = JSONArray()
    for (ach in parsedData.achievements) {
        val obj = JSONObject()
        obj.put("name", ach.name)
        obj.put("hidden", ach.hidden)
        obj.put("displayName", ach.displayName?.let { JSONObject(it as Map<*, *>) } ?: JSONObject.NULL)
        obj.put("description", ach.description?.let { JSONObject(it as Map<*, *>) } ?: JSONObject.NULL)
        obj.put("icon", ach.icon ?: JSONObject.NULL)
        obj.put("iconGray", ach.iconGray ?: JSONObject.NULL)
        obj.put("icongray", ach.icongray ?: JSONObject.NULL)
        obj.put("progress", ach.progress?.let { JSONObject(it as Map<*, *>) } ?: JSONObject.NULL)
        obj.put("unlocked", ach.unlocked ?: JSONObject.NULL)
        obj.put("unlockTimestamp", ach.unlockTimestamp ?: JSONObject.NULL)
        obj.put("formattedUnlockTime", ach.formattedUnlockTime ?: JSONObject.NULL)
        achArray.put(obj)
    }
    root.put("achievements", achArray)

    // stats
    val statsArray = JSONArray()
    for (stat in parsedData.stats) {
        val obj = JSONObject()
        obj.put("id", stat.id)
        obj.put("name", stat.name)
        obj.put("type", stat.type)
        obj.put("default", stat.default)
        obj.put("global", stat.global)
        obj.put("min", stat.min ?: JSONObject.NULL)
        statsArray.put(obj)
    }
    root.put("stats", statsArray)

    // nameToBlockBit
    val blockBitObj = JSONObject()
    for ((name, pair) in parsedData.nameToBlockBit) {
        val entry = JSONArray()
        entry.put(pair.first)
        entry.put(pair.second)
        blockBitObj.put(name, entry)
    }
    root.put("nameToBlockBit", blockBitObj)

    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeText(root.toString(2), Charsets.UTF_8)
    Timber.d("dumpParsedSchemaData: wrote ${parsedData.achievements.size} achievements, ${parsedData.stats.size} stats to $filePath")
}
