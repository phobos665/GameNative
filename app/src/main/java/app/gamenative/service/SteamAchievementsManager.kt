package app.gamenative.service

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Owns the Steam/Goldberg achievement *definitions* file setup, independent of DLL replacement.
 *
 * - If `steam_settings/achievements.json` doesn't exist, generate it fresh.
 * - If it already exists, check whether the local GSE save-dir state has unlocks the server
 *   doesn't know about yet, and only push a sync when something is actually new — instead of
 *   unconditionally regenerating/syncing on every launch.
 */
object SteamAchievementsManager {

    /** A server-reported achievement stat block: which bits are set encodes which achievements are unlocked. */
    data class ServerAchievementBlock(val blockId: Int, val unlockTimes: List<Long>)

    /** blockId -> bitmask, one bit per index in [ServerAchievementBlock.unlockTimes] where the time is non-zero. */
    internal fun decodeAchievementBitmasks(blocks: List<ServerAchievementBlock>): Map<Int, Int> {
        return blocks.associate { block ->
            var bitmask = 0
            block.unlockTimes.forEachIndexed { index, unlockTime ->
                if (unlockTime != 0L) bitmask = bitmask or (1 shl index)
            }
            block.blockId to bitmask
        }
    }

    /** Which achievement names (per the name -> [blockId, bitIndex] mapping) have their bit set. */
    internal fun unlockedNamesFromBitmasks(
        bitmasks: Map<Int, Int>,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Set<String> {
        return nameToBlockBit.filter { (_, blockBit) ->
            val (blockId, bitIndex) = blockBit
            (bitmasks[blockId] ?: 0) and (1 shl bitIndex) != 0
        }.keys
    }

    /** Locally-earned achievement names not reflected in the server's unlocked set. */
    internal fun diffNewlyUnlocked(localEarned: Set<String>, serverUnlocked: Set<String>): Set<String> {
        return localEarned - serverUnlocked
    }

    suspend fun ensureAchievementsReady(context: Context, appId: Int, configDirectory: String) {
        val definitionsFile = File(configDirectory, "achievements.json")
        if (!definitionsFile.exists()) {
            if (!SteamService.isLoggedIn) {
                Timber.tag("achievements").w("Skipping achievements generation for appId=$appId — Steam not logged in")
                return
            }
            try {
                File(configDirectory).mkdirs()
                SteamService.generateAchievements(appId, configDirectory)
            } catch (e: Exception) {
                Timber.tag("achievements").e(e, "Failed to generate achievements for appId=$appId")
            }
            return
        }

        val gseSaveDirs = SteamService.getGseSaveDirs(context, appId).filter { it.isDirectory }
        if (gseSaveDirs.isEmpty()) return

        val (localUnlocked, _) = SteamService.collectGseUnlocksAndStats(gseSaveDirs)
        if (localUnlocked.isEmpty()) return

        val serverUnlocked = SteamService.fetchServerUnlockedAchievementNames(appId, configDirectory) ?: return
        val newlyUnlocked = diffNewlyUnlocked(localUnlocked, serverUnlocked)
        if (newlyUnlocked.isEmpty()) {
            Timber.tag("achievements").d("Local unlocks already in sync with server for appId=$appId")
            return
        }

        Timber.tag("achievements").i(
            "Found ${newlyUnlocked.size} locally-unlocked achievement(s) not yet on server for appId=$appId, syncing",
        )
        SteamService.syncAchievementsFromGoldberg(context, appId)
    }
}
