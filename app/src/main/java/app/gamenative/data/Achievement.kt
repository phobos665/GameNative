package app.gamenative.data

data class Achievement(
    val name: String,
    val iconUrl: String,
    val timestampUnlocked: Long? = null,
    val id: Int? = null
)

data class AchievementList(
    val locked: List<Achievement>,
    val unlocked: List<Achievement>
)
