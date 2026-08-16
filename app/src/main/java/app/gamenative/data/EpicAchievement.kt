package app.gamenative.data

/** A single Epic Online Services achievement definition, as fetched from Epic's GraphQL catalog. */
data class EpicAchievement(
    val name: String,
    val displayName: String,
    val description: String,
    val iconUrl: String?,
    val iconGrayUrl: String?,
    val hidden: Boolean,
    val xp: Int,
)
