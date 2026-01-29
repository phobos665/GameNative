package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {
    @Test
    fun normalizeProcessName() {
        assertEquals("game", FileUtils.normalizeProcessName("game.exe"))
        assertEquals("game", FileUtils.normalizeProcessName("GAME.EXE"))
        assertEquals("game", FileUtils.normalizeProcessName("game"))
        assertEquals("a\\ really\\ cool\\ game", FileUtils.normalizeProcessName("A Really Cool Game"))
    }

    @Test
    fun testNormalizeProcessName_withPathSeparators() {
        assertEquals("game", FileUtils.normalizeProcessName("C:/Games/game.exe"))
        assertEquals("game", FileUtils.normalizeProcessName("C:\\Games\\game.exe"))
        assertEquals("a\\ cool\\ game", FileUtils.normalizeProcessName("C:\\Cool Games\\A Cool Game.exe"))
        assertEquals("game", FileUtils.normalizeProcessName("/usr/bin/game.exe"))
    }
}
