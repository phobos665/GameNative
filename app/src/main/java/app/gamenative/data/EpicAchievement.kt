package app.gamenative.data

data class EpicAchievement(
    val name: String,
    val displayName: String,
    val description: String,
    val iconUrl: String?,
    val iconGrayUrl: String?,
    val hidden: Boolean,
    val xp: Int,
)
