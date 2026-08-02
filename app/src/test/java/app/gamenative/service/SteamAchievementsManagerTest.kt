package app.gamenative.service

import app.gamenative.service.SteamAchievementsManager.ServerAchievementBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure achievement-reconciliation logic in [SteamAchievementsManager]:
 * decoding server-reported bitmasks, resolving them to achievement names, and diffing
 * against locally-earned names to decide whether a cloud sync is actually needed.
 */
class SteamAchievementsManagerTest {

    private fun decode(blocks: List<ServerAchievementBlock>) =
        SteamAchievementsManager.decodeAchievementBitmasks(blocks)

    private fun unlockedNames(bitmasks: Map<Int, Int>, nameToBlockBit: Map<String, Pair<Int, Int>>) =
        SteamAchievementsManager.unlockedNamesFromBitmasks(bitmasks, nameToBlockBit)

    private fun diff(local: Set<String>, server: Set<String>) =
        SteamAchievementsManager.diffNewlyUnlocked(local, server)

    // -------------------------------------------------------------------------
    // decodeAchievementBitmasks
    // -------------------------------------------------------------------------

    @Test
    fun `decodeAchievementBitmasks sets a bit for each non-zero unlock time`() {
        val bitmasks = decode(listOf(ServerAchievementBlock(blockId = 1, unlockTimes = listOf(0L, 100L, 0L, 200L))))
        assertEquals(mapOf(1 to 0b1010), bitmasks)
    }

    @Test
    fun `decodeAchievementBitmasks yields zero bitmask when all unlock times are zero`() {
        val bitmasks = decode(listOf(ServerAchievementBlock(blockId = 5, unlockTimes = listOf(0L, 0L, 0L))))
        assertEquals(mapOf(5 to 0), bitmasks)
    }

    @Test
    fun `decodeAchievementBitmasks handles an empty unlockTimes list`() {
        val bitmasks = decode(listOf(ServerAchievementBlock(blockId = 2, unlockTimes = emptyList())))
        assertEquals(mapOf(2 to 0), bitmasks)
    }

    @Test
    fun `decodeAchievementBitmasks decodes multiple blocks independently`() {
        val bitmasks = decode(
            listOf(
                ServerAchievementBlock(blockId = 0, unlockTimes = listOf(1L, 0L)),
                ServerAchievementBlock(blockId = 1, unlockTimes = listOf(0L, 1L, 1L)),
            ),
        )
        assertEquals(mapOf(0 to 0b01, 1 to 0b110), bitmasks)
    }

    @Test
    fun `decodeAchievementBitmasks of an empty block list is an empty map`() {
        assertEquals(emptyMap<Int, Int>(), decode(emptyList()))
    }

    // -------------------------------------------------------------------------
    // unlockedNamesFromBitmasks
    // -------------------------------------------------------------------------

    @Test
    fun `unlockedNamesFromBitmasks resolves names whose bit is set`() {
        val bitmasks = mapOf(0 to 0b101) // bits 0 and 2 set
        val nameToBlockBit = mapOf(
            "ACH_FIRST" to (0 to 0),
            "ACH_SECOND" to (0 to 1),
            "ACH_THIRD" to (0 to 2),
        )
        assertEquals(setOf("ACH_FIRST", "ACH_THIRD"), unlockedNames(bitmasks, nameToBlockBit))
    }

    @Test
    fun `unlockedNamesFromBitmasks ignores names whose block is missing from bitmasks`() {
        val bitmasks = emptyMap<Int, Int>()
        val nameToBlockBit = mapOf("ACH_ORPHAN" to (7 to 0))
        assertTrue(unlockedNames(bitmasks, nameToBlockBit).isEmpty())
    }

    @Test
    fun `unlockedNamesFromBitmasks returns empty set for empty mapping`() {
        assertTrue(unlockedNames(mapOf(0 to 0xFF), emptyMap()).isEmpty())
    }

    // -------------------------------------------------------------------------
    // diffNewlyUnlocked — the three user-facing scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `diffNewlyUnlocked is empty when local matches server exactly`() {
        val local = setOf("ACH_A", "ACH_B")
        val server = setOf("ACH_A", "ACH_B")
        assertTrue(diff(local, server).isEmpty())
    }

    @Test
    fun `diffNewlyUnlocked is empty when local is a subset of server`() {
        val local = setOf("ACH_A")
        val server = setOf("ACH_A", "ACH_B")
        assertTrue(diff(local, server).isEmpty())
    }

    @Test
    fun `diffNewlyUnlocked returns achievements unlocked locally but not on the server`() {
        val local = setOf("ACH_A", "ACH_B", "ACH_C")
        val server = setOf("ACH_A")
        assertEquals(setOf("ACH_B", "ACH_C"), diff(local, server))
    }

    @Test
    fun `diffNewlyUnlocked with empty local and non-empty server is empty`() {
        assertTrue(diff(emptySet(), setOf("ACH_A")).isEmpty())
    }

    @Test
    fun `diffNewlyUnlocked with empty server returns all locally unlocked names`() {
        val local = setOf("ACH_A", "ACH_B")
        assertEquals(local, diff(local, emptySet()))
    }
}
