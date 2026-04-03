package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Resident Evil 1 Classic(Steam)
 */
val STEAM_Fix_4249100: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "4249100",
    registryKey = "Software\\CAPCOM\\STEAM_R EVIL1",
    defaultValues = mapOf(
        "Install Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
