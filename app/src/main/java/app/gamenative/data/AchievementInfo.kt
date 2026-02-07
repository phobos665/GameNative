package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class AchievementInfo(
    val apiName: String = "",
    val displayName: String = "",
    val description: String = "",
    val hidden: Boolean = false,
    val icon: String = "",
    val iconGray: String = "",
    val defaultValue: Int = 0,
    val statName: String = "",
)

@Serializable
data class StatInfo(
    val name: String = "",
    val type: StatType = StatType.INT,
    val defaultValue: String = "0",
    val displayName: String = "",
    val incrementOnly: Boolean = false,
    val maxChange: Float = 0f,
    val minValue: Float = 0f,
    val maxValue: Float = 0f,
    val aggregated: Boolean = false,
)

@Serializable
enum class StatType {
    INT,
    FLOAT,
    AVGRATE
}

@Serializable
data class AchievementSchema(
    val achievements: List<AchievementInfo> = emptyList(),
    val stats: List<StatInfo> = emptyList(),
)
